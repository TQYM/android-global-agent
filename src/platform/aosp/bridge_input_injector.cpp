#include "bridge_input_injector.h"

#include <aidl/com/example/globalagent/GestureFrame.h>
#include <aidl/com/example/globalagent/GestureSpec.h>
#include <aidl/com/example/globalagent/PointerSample.h>

#include "global_agent/gesture_validation.h"

namespace global_agent::aosp {
namespace {

constexpr std::size_t kMaxFrames = 256;
constexpr std::size_t kMaxPointers = 5;

} // namespace

bool BridgeInputInjector::Inject(const Gesture &gesture,
                                 const Deadline &deadline, std::string *error) {
  if (deadline.Expired()) {
    if (error != nullptr)
      *error = "gesture deadline expired";
    return false;
  }
  if (!ValidateGesture(gesture, error, kMaxFrames, kMaxPointers)) {
    return false;
  }
  const auto bridge = service_->bridge();
  if (bridge == nullptr) {
    if (error != nullptr)
      *error = "platform input bridge is not registered";
    return false;
  }

  aidl::com::example::globalagent::GestureSpec spec;
  spec.actionId = static_cast<std::int64_t>(gesture.action_id);
  spec.displayId = static_cast<std::int32_t>(gesture.display_id);
  spec.frames.reserve(gesture.frames.size());
  for (const auto &source_frame : gesture.frames) {
    if (source_frame.pointers.empty() ||
        source_frame.pointers.size() > kMaxPointers) {
      if (error != nullptr)
        *error = "invalid pointer count";
      return false;
    }
    aidl::com::example::globalagent::GestureFrame target_frame;
    target_frame.action = static_cast<std::int32_t>(source_frame.action);
    target_frame.actionIndex = source_frame.action_index;
    target_frame.elapsedMillis = source_frame.elapsed_ms;
    target_frame.pointers.reserve(source_frame.pointers.size());
    for (const auto &source_pointer : source_frame.pointers) {
      aidl::com::example::globalagent::PointerSample target_pointer;
      target_pointer.pointerId = source_pointer.pointer_id;
      target_pointer.x = source_pointer.position.x;
      target_pointer.y = source_pointer.position.y;
      target_frame.pointers.push_back(target_pointer);
    }
    spec.frames.push_back(std::move(target_frame));
  }

  bool accepted = false;
  const ndk::ScopedAStatus status = bridge->injectGesture(spec, &accepted);
  if (!status.isOk()) {
    if (error != nullptr)
      *error = status.getDescription();
    return false;
  }
  if (!accepted && error != nullptr) {
    *error = "platform bridge rejected gesture";
  }
  return accepted;
}

void BridgeInputInjector::CancelActiveGesture() {
  const auto bridge = service_->bridge();
  if (bridge != nullptr) {
    bridge->cancelActiveGesture();
  }
}

} // namespace global_agent::aosp
