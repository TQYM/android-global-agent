#include "global_agent/agent_loop.h"
#include "global_agent/shell_backend.h"
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

enum class Backend { kSynthetic, kShellOnDevice, kShellAdb };

struct Options {
  std::string state_path = "/tmp/global-agent-state.bin";
  int iterations = 5;
  int interval_ms = 25;
  bool demo_action = false;
  Backend backend = Backend::kSynthetic;
  std::string adb_serial;
  int budget_ms = 0; // 0 selects the backend default
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
    } else if (argument == "--budget-ms" && i + 1 < argc) {
      if (!ParseInt(argv[++i], &options->budget_ms))
        return false;
    } else if (argument == "--demo-action") {
      options->demo_action = true;
    } else if (argument == "--backend" && i + 1 < argc) {
      const std::string backend = argv[++i];
      if (backend == "synthetic") {
        options->backend = Backend::kSynthetic;
      } else if (backend == "shell") {
        options->backend = Backend::kShellOnDevice;
      } else if (backend == "shell-adb") {
        options->backend = Backend::kShellAdb;
      } else {
        return false;
      }
    } else if (argument == "--adb-serial" && i + 1 < argc) {
      options->adb_serial = argv[++i];
    } else {
      return false;
    }
  }
  return true;
}

int DefaultBudgetMs(Backend backend) {
  switch (backend) {
  case Backend::kSynthetic:
    return 200;
  case Backend::kShellOnDevice:
  case Backend::kShellAdb:
    // Each `input`/`screencap` invocation spawns a process, so the shell
    // backends cannot honor the 200 ms AOSP-era single-step budget.
    return 8000;
  }
  return 200;
}

} // namespace

int main(int argc, char **argv) {
  Options options;
  if (!ParseOptions(argc, argv, &options)) {
    std::cerr << "usage: global-agentd [--backend synthetic|shell|shell-adb] "
                 "[--adb-serial SERIAL] [--budget-ms N] [--state PATH] "
                 "[--iterations N] [--interval-ms N] [--demo-action]\n";
    return 2;
  }

  global_agent::StateStore store;
  std::string error;
  if (!store.Open(options.state_path, &error)) {
    std::cerr << "state store error: " << error << '\n';
    return 1;
  }

  std::unique_ptr<global_agent::PerceptionBackend> perception;
  std::unique_ptr<global_agent::DecisionEngine> decision;
  std::unique_ptr<global_agent::InputInjector> input;
  if (options.backend == Backend::kSynthetic) {
    perception = global_agent::CreateSyntheticPerception();
    decision = global_agent::CreateDemoDecision(options.demo_action);
    input = global_agent::CreateLoggingInput();
  } else {
    global_agent::shell::Config config;
    if (options.backend == Backend::kShellAdb) {
      config.transport = global_agent::shell::Transport::kAdb;
      config.adb_serial = options.adb_serial;
    }
    perception = global_agent::shell::CreateShellPerception(config);
    decision = global_agent::CreateDemoDecision(options.demo_action);
    input = global_agent::shell::CreateShellInputInjector(config);
  }
  global_agent::AgentLoop loop(perception.get(), decision.get(), input.get(),
                               &store);
  if (!loop.Restore(&error)) {
    std::cerr << "restore error: " << error << '\n';
    return 1;
  }

  const int budget_ms = options.budget_ms > 0 ? options.budget_ms
                                              : DefaultBudgetMs(options.backend);
  for (int iteration = 0; iteration < options.iterations; iteration++) {
    const auto result = loop.Step(std::chrono::milliseconds(budget_ms));
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
