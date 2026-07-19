#include <atomic>
#include <chrono>
#include <csignal>
#include <iostream>
#include <optional>
#include <string>
#include <thread>

#include "agent_binder_service.h"
#include "aosp_surface_capture.h"
#include "bridge_input_injector.h"
#include "global_agent/agent_loop.h"
#include "global_agent/state_store.h"

namespace ga = global_agent;
namespace platform = global_agent::aosp;

namespace {

std::atomic<bool> running{true};

void StopHandler(int) { running.store(false, std::memory_order_release); }

class NoopDecision final : public ga::DecisionEngine {
public:
  std::optional<ga::Gesture> Decide(const ga::StateGraph &,
                                    const ga::Perception &) override {
    return std::nullopt;
  }
};

} // namespace

int main() {
  std::signal(SIGTERM, StopHandler);
  std::signal(SIGINT, StopHandler);

  std::string error;
  const auto binder_service = platform::AgentBinderService::Register(&error);
  if (binder_service == nullptr) {
    std::cerr << error << '\n';
    return 1;
  }

  ga::StateStore store;
  if (!store.Open("/data/misc/global_agent/state.bin", &error)) {
    std::cerr << error << '\n';
    return 1;
  }

  platform::AospSingleFrameCapture capture(binder_service);
  platform::BridgeInputInjector input(binder_service);
  NoopDecision decision;
  ga::AgentLoop loop(&capture, &decision, &input, &store);
  if (!loop.Restore(&error)) {
    std::cerr << error << '\n';
    return 1;
  }

  while (running.load(std::memory_order_acquire)) {
    binder_service->ExpireSession();
    const ga::StepResult result = loop.Step(std::chrono::milliseconds(200));
    if (!result.ok) {
      std::cerr << "agent step failed: " << result.error << '\n';
      std::this_thread::sleep_for(std::chrono::milliseconds(100));
      continue;
    }
    std::this_thread::sleep_for(std::chrono::milliseconds(16));
  }
  input.CancelActiveGesture();
  binder_service->ResetSession();
  return 0;
}
