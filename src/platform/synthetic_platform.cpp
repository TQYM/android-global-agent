#include "global_agent/agent_loop.h"
#include "global_agent/bezier.h"
#include "global_agent/hash.h"

#include <chrono>
#include <iostream>
#include <optional>
#include <string>

namespace global_agent {

class SyntheticPerception final : public PerceptionBackend {
public:
  bool Capture(const Deadline &deadline, Perception *perception,
               std::string *error) override {
    if (deadline.Expired() || perception == nullptr) {
      if (error != nullptr)
        *error = "synthetic capture deadline expired";
      return false;
    }
    ++frame_;
    const auto now = std::chrono::steady_clock::now().time_since_epoch();
    perception->monotonic_ns = static_cast<std::uint64_t>(
        std::chrono::duration_cast<std::chrono::nanoseconds>(now).count());
    perception->visual_hash = frame_ < 3 ? 0x1001U : 0x2002U;
    perception->confidence_milli = 1000;
    perception->window.component_hash =
        HashString(frame_ < 3 ? "demo/.FirstActivity" : "demo/.SecondActivity");
    perception->window.focused_pid = 4242;
    return true;
  }

private:
  std::uint64_t frame_ = 0;
};

class DemoDecision final : public DecisionEngine {
public:
  explicit DemoDecision(bool enabled) : enabled_(enabled) {}

  std::optional<Gesture> Decide(const StateGraph &,
                                const Perception &perception) override {
    if (!enabled_ || emitted_)
      return std::nullopt;
    emitted_ = true;
    const CubicBezier curve{
        .p0 = {100.0F, 900.0F},
        .p1 = {110.0F, 700.0F},
        .p2 = {160.0F, 400.0F},
        .p3 = {180.0F, 220.0F},
    };
    return BuildSinglePointerGesture(1, perception.window.display_id,
                                     SampleBezierByArcLength(curve, 32, 8));
  }

private:
  bool enabled_ = false;
  bool emitted_ = false;
};

class LoggingInput final : public InputInjector {
public:
  bool Inject(const Gesture &gesture, const Deadline &deadline,
              std::string *error) override {
    if (deadline.Expired() || gesture.frames.size() < 2) {
      if (error != nullptr)
        *error = "invalid or expired demo gesture";
      return false;
    }
    std::cout << "validated demo gesture with " << gesture.frames.size()
              << " frames\n";
    return true;
  }

  void CancelActiveGesture() override {}
};

std::unique_ptr<PerceptionBackend> CreateSyntheticPerception() {
  return std::make_unique<SyntheticPerception>();
}

std::unique_ptr<DecisionEngine> CreateDemoDecision(bool enabled) {
  return std::make_unique<DemoDecision>(enabled);
}

std::unique_ptr<InputInjector> CreateLoggingInput() {
  return std::make_unique<LoggingInput>();
}

} // namespace global_agent
