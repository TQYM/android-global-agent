#include "global_agent/shell_backend.h"

#include <algorithm>
#include <cmath>
#include <cstdint>

#include "global_agent/dumpsys_parser.h"
#include "global_agent/gesture_validation.h"
#include "global_agent/hash.h"

namespace global_agent::shell {
namespace {

// Gestures whose endpoints stay within this radius collapse to `input tap`.
constexpr float kTapRadiusPx = 8.0F;
constexpr std::size_t kMinScreencapBytes = 16;

std::string TrimForLog(std::string value, std::size_t limit = 200) {
  if (value.size() > limit) {
    value.resize(limit);
    value += "...";
  }
  std::replace(value.begin(), value.end(), '\n', ' ');
  std::replace(value.begin(), value.end(), '\r', ' ');
  return value;
}

std::chrono::milliseconds BoundedTimeout(const Config &config,
                                         const Deadline &deadline) {
  const auto remaining = deadline.Remaining();
  if (remaining <= std::chrono::milliseconds::zero()) {
    return std::chrono::milliseconds::zero();
  }
  return std::min(config.command_timeout, remaining);
}

bool DescribeFailure(const CommandResult &result,
                     const std::vector<std::string> &argv,
                     std::string *error) {
  if (error == nullptr) {
    return false;
  }
  std::string command;
  for (const std::string &argument : argv) {
    if (!command.empty()) {
      command += ' ';
    }
    command += argument;
  }
  if (result.timed_out) {
    *error = "command timed out: " + command;
  } else {
    *error = "command failed (exit " + std::to_string(result.exit_code) +
             "): " + command + " output: " + TrimForLog(result.output);
  }
  return false;
}

std::string FormatCoordinate(float value) {
  return std::to_string(std::lround(value));
}

} // namespace

CommandResult ProcessRunner::Run(const std::vector<std::string> &argv,
                                 std::chrono::milliseconds timeout,
                                 std::size_t max_output_bytes) {
  return RunCommand(argv, timeout, max_output_bytes);
}

std::vector<std::string> BuildArgv(const Config &config, AdbChannel channel,
                                   const std::vector<std::string> &device_argv) {
  if (device_argv.empty()) {
    return {};
  }
  if (config.transport == Transport::kAdb) {
    std::vector<std::string> argv{config.adb_path};
    if (!config.adb_serial.empty()) {
      argv.push_back("-s");
      argv.push_back(config.adb_serial);
    }
    argv.push_back(channel == AdbChannel::kExecOut ? "exec-out" : "shell");
    argv.insert(argv.end(), device_argv.begin(), device_argv.end());
    return argv;
  }
  if (device_argv.front().find('/') == std::string::npos) {
    std::vector<std::string> argv = device_argv;
    argv.front() = config.bin_dir + "/" + device_argv.front();
    return argv;
  }
  return device_argv;
}

std::optional<GestureCommandPlan> PlanGestureCommand(const Config &config,
                                                     const Gesture &gesture,
                                                     std::string *error) {
  if (!ValidateGesture(gesture, error)) {
    return std::nullopt;
  }
  bool single_pointer = true;
  for (const GestureFrame &frame : gesture.frames) {
    if (frame.pointers.size() != 1) {
      single_pointer = false;
      break;
    }
  }
  if (!single_pointer) {
    if (error != nullptr) {
      *error = "shell input requires single-pointer gestures";
    }
    return std::nullopt;
  }

  const PointF start = gesture.frames.front().pointers.front().position;
  const PointF end = gesture.frames.back().pointers.front().position;
  if (start.x < 0.0F || start.y < 0.0F || end.x < 0.0F || end.y < 0.0F) {
    if (error != nullptr) {
      *error = "shell input requires non-negative coordinates";
    }
    return std::nullopt;
  }

  GestureCommandPlan plan;
  const float dx = end.x - start.x;
  const float dy = end.y - start.y;
  if (std::hypot(dx, dy) <= kTapRadiusPx) {
    plan.is_tap = true;
    plan.device_argv = {"input", "tap", FormatCoordinate(start.x),
                        FormatCoordinate(start.y)};
  } else {
    const std::uint32_t duration_ms =
        std::max<std::uint32_t>(gesture.frames.back().elapsed_ms, 1U);
    plan.device_argv = {
        "input",
        "swipe",
        FormatCoordinate(start.x),
        FormatCoordinate(start.y),
        FormatCoordinate(end.x),
        FormatCoordinate(end.y),
        std::to_string(duration_ms),
    };
  }
  plan.argv = BuildArgv(config, AdbChannel::kShell, plan.device_argv);
  return plan;
}

ShellPerception::ShellPerception(std::shared_ptr<CommandRunner> runner,
                                 Config config)
    : runner_(std::move(runner)), config_(std::move(config)) {}

bool ShellPerception::RunDeviceCommand(
    AdbChannel channel, const std::vector<std::string> &device_argv,
    const Deadline &deadline, bool required, CommandResult *result,
    std::string *error) {
  if (deadline.Expired()) {
    if (required && error != nullptr) {
      *error = "perception deadline expired";
    }
    return false;
  }
  const std::vector<std::string> argv =
      BuildArgv(config_, channel, device_argv);
  *result = runner_->Run(argv, BoundedTimeout(config_, deadline),
                         config_.max_output_bytes);
  if (!result->started || result->exit_code != 0) {
    if (required) {
      DescribeFailure(*result, argv, error);
    }
    return false;
  }
  return true;
}

bool ShellPerception::Capture(const Deadline &deadline, Perception *perception,
                              std::string *error) {
  if (perception == nullptr) {
    if (error != nullptr) {
      *error = "perception output is null";
    }
    return false;
  }

  CommandResult screencap;
  if (!RunDeviceCommand(AdbChannel::kExecOut, {"screencap"}, deadline, true,
                        &screencap, error)) {
    return false;
  }
  if (screencap.output.size() < kMinScreencapBytes) {
    if (error != nullptr) {
      *error = "screencap returned an empty frame";
    }
    return false;
  }

  CommandResult activity_dump;
  const bool have_activity = RunDeviceCommand(
      AdbChannel::kShell, {"dumpsys", "activity", "top"}, deadline, false,
      &activity_dump, nullptr);

  CommandResult window_dump;
  const bool have_window = RunDeviceCommand(AdbChannel::kShell,
                                            {"dumpsys", "window"}, deadline,
                                            false, &window_dump, nullptr);

  const DumpsysMetadata metadata = ParseActivityAndWindowDumps(
      have_activity ? std::string_view(activity_dump.output) : std::string_view(),
      have_window ? std::string_view(window_dump.output) : std::string_view());

  const auto now_ns = std::chrono::steady_clock::now().time_since_epoch();
  perception->monotonic_ns = static_cast<std::uint64_t>(
      std::chrono::duration_cast<std::chrono::nanoseconds>(now_ns).count());
  perception->visual_hash =
      HashBytes(screencap.output.data(), screencap.output.size());
  // Shell diagnostics are lower-confidence than the AOSP capture path.
  perception->confidence_milli = 600;
  perception->window = metadata.window;
  perception->window.view_hash = HashNormalizedViewDump(
      have_activity ? std::string_view(activity_dump.output)
                    : std::string_view());
  return true;
}

ShellInputInjector::ShellInputInjector(std::shared_ptr<CommandRunner> runner,
                                       Config config)
    : runner_(std::move(runner)), config_(std::move(config)) {}

bool ShellInputInjector::RunPlan(const GestureCommandPlan &plan,
                                 const Deadline &deadline, std::string *error) {
  if (deadline.Expired()) {
    if (error != nullptr) {
      *error = "gesture deadline expired";
    }
    return false;
  }
  const CommandResult result =
      runner_->Run(plan.argv, BoundedTimeout(config_, deadline),
                   config_.max_output_bytes);
  if (!result.started || result.exit_code != 0) {
    DescribeFailure(result, plan.argv, error);
    return false;
  }
  return true;
}

bool ShellInputInjector::Inject(const Gesture &gesture,
                                const Deadline &deadline, std::string *error) {
  const std::optional<GestureCommandPlan> plan =
      PlanGestureCommand(config_, gesture, error);
  if (!plan.has_value()) {
    return false;
  }
  return RunPlan(*plan, deadline, error);
}

void ShellInputInjector::CancelActiveGesture() {
  // The shell transport cannot revoke a command that already executes; the
  // bounded subprocess runner enforces the timeout instead.
}

bool ShellInputInjector::InjectKeycode(int keycode, const Deadline &deadline,
                                       std::string *error) {
  if (keycode < 0 || keycode > 0xFFFF) {
    if (error != nullptr) {
      *error = "keycode is outside the Android range";
    }
    return false;
  }
  GestureCommandPlan plan;
  plan.device_argv = {"input", "keyevent", std::to_string(keycode)};
  plan.argv = BuildArgv(config_, AdbChannel::kShell, plan.device_argv);
  return RunPlan(plan, deadline, error);
}

bool ShellInputInjector::InjectText(const std::string &text,
                                    const Deadline &deadline,
                                    std::string *error) {
  std::string escaped;
  escaped.reserve(text.size());
  for (const char character : text) {
    const unsigned char byte = static_cast<unsigned char>(character);
    if (byte < 0x20 || byte > 0x7E) {
      if (error != nullptr) {
        *error = "shell input text supports printable ASCII only";
      }
      return false;
    }
    if (character == ' ') {
      escaped += "%s";
    } else {
      escaped += character;
    }
  }
  GestureCommandPlan plan;
  plan.device_argv = {"input", "text", escaped};
  plan.argv = BuildArgv(config_, AdbChannel::kShell, plan.device_argv);
  return RunPlan(plan, deadline, error);
}

std::unique_ptr<ShellPerception> CreateShellPerception(const Config &config) {
  return std::make_unique<ShellPerception>(
      std::make_shared<ProcessRunner>(), config);
}

std::unique_ptr<ShellInputInjector>
CreateShellInputInjector(const Config &config) {
  return std::make_unique<ShellInputInjector>(
      std::make_shared<ProcessRunner>(), config);
}

} // namespace global_agent::shell
