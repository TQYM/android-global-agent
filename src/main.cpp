#include "global_agent/agent_loop.h"
#include "global_agent/state_store.h"

#include <chrono>
#include <cstdlib>
#include <iostream>
#include <memory>
#include <string>
#include <thread>

namespace global_agent {
std::unique_ptr<PerceptionBackend> CreateSyntheticPerception();
std::unique_ptr<DecisionEngine> CreateDemoDecision(bool enabled);
std::unique_ptr<InputInjector> CreateLoggingInput();
} // namespace global_agent

namespace {

struct Options {
  std::string state_path = "/tmp/global-agent-state.bin";
  int iterations = 5;
  int interval_ms = 25;
  bool demo_action = false;
};

bool ParseInt(const char *text, int *value) {
  char *end = nullptr;
  const long parsed = std::strtol(text, &end, 10);
  if (end == text || *end != '\0' || parsed < 0 || parsed > 1000000) {
    return false;
  }
  *value = static_cast<int>(parsed);
  return true;
}

bool ParseOptions(int argc, char **argv, Options *options) {
  for (int i = 1; i < argc; ++i) {
    const std::string argument = argv[i];
    if (argument == "--state" && i + 1 < argc) {
      options->state_path = argv[++i];
    } else if (argument == "--iterations" && i + 1 < argc) {
      if (!ParseInt(argv[++i], &options->iterations))
        return false;
    } else if (argument == "--interval-ms" && i + 1 < argc) {
      if (!ParseInt(argv[++i], &options->interval_ms))
        return false;
    } else if (argument == "--demo-action") {
      options->demo_action = true;
    } else {
      return false;
    }
  }
  return true;
}

} // namespace

int main(int argc, char **argv) {
  Options options;
  if (!ParseOptions(argc, argv, &options)) {
    std::cerr << "usage: global-agentd [--state PATH] [--iterations N] "
                 "[--interval-ms N] [--demo-action]\n";
    return 2;
  }

  global_agent::StateStore store;
  std::string error;
  if (!store.Open(options.state_path, &error)) {
    std::cerr << "state store error: " << error << '\n';
    return 1;
  }

  auto perception = global_agent::CreateSyntheticPerception();
  auto decision = global_agent::CreateDemoDecision(options.demo_action);
  auto input = global_agent::CreateLoggingInput();
  global_agent::AgentLoop loop(perception.get(), decision.get(), input.get(),
                               &store);
  if (!loop.Restore(&error)) {
    std::cerr << "restore error: " << error << '\n';
    return 1;
  }

  for (int iteration = 0; iteration < options.iterations; ++iteration) {
    const auto result = loop.Step(std::chrono::milliseconds(200));
    if (!result.ok) {
      std::cerr << "step error: " << result.error << '\n';
      return 1;
    }
    std::this_thread::sleep_for(std::chrono::milliseconds(options.interval_ms));
  }

  std::cout << "generation=" << store.generation()
            << " nodes=" << loop.graph().nodes().size()
            << " edges=" << loop.graph().edges().size() << '\n';
  return 0;
}
