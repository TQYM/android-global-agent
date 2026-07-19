#include "aosp_surface_capture.h"

#include <algorithm>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <mutex>
#include <optional>
#include <string>

#include <android/gui/BnScreenCaptureListener.h>
#include <gui/SurfaceComposerClient.h>
#include <gui/ScreenCaptureResults.h>
#include <hardware/gralloc.h>
#include <ui/Fence.h>
#include <ui/GraphicBuffer.h>
#include <ui/PixelFormat.h>

#include "global_agent/hash.h"

namespace global_agent::aosp {
namespace {

using android::NO_ERROR;
using android::PixelFormat;
using android::ScreenshotClient;
using android::sp;
using android::status_t;
using android::gui::ScreenCaptureResults;
using android::SurfaceComposerClient;
using android::PIXEL_FORMAT_BGRA_8888;
using android::PIXEL_FORMAT_RGBX_8888;
using android::PIXEL_FORMAT_RGBA_8888;

// SyncScreenCaptureListener::waitForResults() waits forever on Android 14.
// The agent has a bounded step budget, so its listener must be cancellable by
// timeout even though SurfaceFlinger may deliver the callback later.
class TimedScreenCaptureListener final : public android::gui::BnScreenCaptureListener {
public:
  android::binder::Status onScreenCaptureCompleted(
      const ScreenCaptureResults &capture_results) override {
    {
      std::lock_guard lock(mutex_);
      if (!result_.has_value()) {
        result_ = capture_results;
      }
    }
    condition_.notify_all();
    return android::binder::Status::ok();
  }

  bool WaitForResults(std::chrono::milliseconds timeout,
                      ScreenCaptureResults *out) {
    if (out == nullptr) {
      return false;
    }
    std::unique_lock lock(mutex_);
    if (!condition_.wait_for(lock, timeout,
                             [this] { return result_.has_value(); })) {
      return false;
    }
    *out = *result_;
    return true;
  }

private:
  std::condition_variable condition_;
  std::mutex mutex_;
  std::optional<ScreenCaptureResults> result_;
};

std::uint64_t HashRgbaBuffer(const std::uint8_t *pixels, std::uint32_t width,
                             std::uint32_t height, std::uint32_t stride) {
  std::uint64_t hash = kFnvOffsetBasis;
  const std::uint32_t x_step = std::max<std::uint32_t>(1, width / 64);
  const std::uint32_t y_step = std::max<std::uint32_t>(1, height / 64);
  for (std::uint32_t y = 0; y < height; y += y_step) {
    const auto *row = pixels + static_cast<std::size_t>(y) * stride * 4;
    for (std::uint32_t x = 0; x < width; x += x_step) {
      hash = HashBytes(row + static_cast<std::size_t>(x) * 4, 4, hash);
    }
  }
  return hash;
}

} // namespace

bool AospSurfaceCapture::Capture(const Deadline &deadline,
                                 Perception *perception, std::string *error) {
  if (perception == nullptr || service_ == nullptr || deadline.Expired()) {
    if (error != nullptr) {
      *error = service_ == nullptr ? "capture backend is unavailable"
                                   : "capture deadline expired";
    }
    return false;
  }

  const auto display_ids = SurfaceComposerClient::getPhysicalDisplayIds();
  if (display_ids.empty()) {
    if (error != nullptr)
      *error = "no physical display available";
    return false;
  }
  const sp<TimedScreenCaptureListener> listener =
      sp<TimedScreenCaptureListener>::make();
  // The DisplayId overload reaches SurfaceFlinger::captureDisplayById(). On
  // Android 14 that path explicitly permits AID_ROOT and forces secure-layer
  // capture off; the argument-based overload instead requires
  // READ_FRAME_BUFFER (or a matching uid) and rejects a root daemon by default.
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
  const status_t requested =
      ScreenshotClient::captureDisplay(display_ids.front(), listener);
#pragma clang diagnostic pop
  if (requested != NO_ERROR) {
    if (error != nullptr)
      *error = "SurfaceFlinger rejected capture request";
    return false;
  }

  ScreenCaptureResults results;
  const auto callback_budget = deadline.Remaining();
  if (callback_budget.count() <= 0 ||
      !listener->WaitForResults(callback_budget, &results)) {
    if (error != nullptr)
      *error = "SurfaceFlinger capture callback timed out";
    return false;
  }
  if (!results.fenceResult.ok()) {
    if (error != nullptr) {
      *error = "SurfaceFlinger capture failed: fence status " +
               std::to_string(results.fenceResult.error());
    }
    return false;
  }
  if (results.capturedSecureLayers) {
    if (error != nullptr)
      *error = "SurfaceFlinger returned secure layers unexpectedly";
    return false;
  }
  if (results.buffer == nullptr) {
    if (error != nullptr)
      *error = "SurfaceFlinger capture failed";
    return false;
  }
  if (results.fenceResult.value() != nullptr) {
    const int wait_ms = static_cast<int>(
        std::min<std::int64_t>(deadline.Remaining().count(), 50));
    if (wait_ms <= 0 || results.fenceResult.value()->wait(wait_ms) != NO_ERROR) {
      if (error != nullptr)
        *error = "capture fence timed out";
      return false;
    }
  }

  const PixelFormat format = results.buffer->getPixelFormat();
  if (format != PIXEL_FORMAT_RGBA_8888 && format != PIXEL_FORMAT_RGBX_8888 &&
      format != PIXEL_FORMAT_BGRA_8888) {
    if (error != nullptr)
      *error = "unsupported capture pixel format";
    return false;
  }

  void *pixels = nullptr;
  const status_t locked =
      results.buffer->lock(GRALLOC_USAGE_SW_READ_OFTEN, &pixels);
  if (locked != NO_ERROR || pixels == nullptr) {
    if (error != nullptr)
      *error = "failed to map capture buffer";
    return false;
  }
  const std::uint32_t width = results.buffer->getWidth();
  const std::uint32_t height = results.buffer->getHeight();
  const std::uint32_t stride = results.buffer->getStride();
  constexpr std::uint32_t kMaxCaptureDimension = 16'384;
  if (width == 0 || height == 0 || stride < width ||
      width > kMaxCaptureDimension || height > kMaxCaptureDimension) {
    results.buffer->unlock();
    if (error != nullptr)
      *error = "invalid capture buffer dimensions";
    return false;
  }
  const std::uint64_t visual_hash = HashRgbaBuffer(
      static_cast<const std::uint8_t *>(pixels), width, height, stride);
  const status_t unlocked = results.buffer->unlock();
  if (unlocked != NO_ERROR) {
    if (error != nullptr)
      *error = "failed to release capture buffer";
    return false;
  }

  const auto now = std::chrono::steady_clock::now().time_since_epoch();
  perception->monotonic_ns = static_cast<std::uint64_t>(
      std::chrono::duration_cast<std::chrono::nanoseconds>(now).count());
  perception->visual_hash = visual_hash;
  perception->confidence_milli = 850;
  perception->window = service_->window_metadata();
  perception->window.view_hash =
      HashCombine(perception->window.view_hash, service_->setting_epoch());
  return true;
}

} // namespace global_agent::aosp
