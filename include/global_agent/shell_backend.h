#pragma once

#include <chrono>
#include <cstddef>
#include <memory>
#include <optional>
#include <string>
#include <vector>

#include "global_agent/agent_loop.h"
#include "global_agent/subprocess.h"
#include "global_agent/types.h"

namespace global_agent::shell {

// Transport selects where Android shell commands execute.
enum class Transport {
  kOnDevice = 0, // daemon runs on Android itself; commands via /system/bin
  kAdb = 1,      // daemon runs on a host; commands forwarded through adb
};

// AdbChannel selects how adb forwards one command.
enum class AdbChannel {
  kShell = 0,   // text output; fine for dumpsys and input
  kExecOut = 1, // binary-safe stdout; required for raw screencap
};

struct Config {
  Transport transport = Transport::kOnDevice;
  std::string adb_path = "adb";
  std::string adb_serial; // optional; empty uses the single connected device
  std::string bin_dir = "/system/bin";
  std::chrono::milliseconds command_timeout{4000};
  std::size_t max_output_bytes = 1024 * 1024;
};

// CommandRunner abstracts process execution so host tests can verify command
// construction and parsing without an attached device.
class CommandRunner {
public:
  virtual ~CommandRunner() = default;
  virtual CommandResult Run(const std::vector<std::string> &argv,
                            std::chrono::milliseconds timeout,
                            std::size_t max_output_bytes) = 0;
};

// ProcessRunner executes commands through the bounded subprocess runner.
class ProcessRunner final : public CommandRunner {
public:
  CommandResult Run(const std::vector<std::string> &argv,
                    std::chrono::milliseconds timeout,
                    std::size_t max_output_bytes) override;
};

// BuildArgv prefixes device arguments with the selected transport. On-device
// arguments are made absolute against bin_dir because execv-family calls do
// not search PATH for names without a slash.
std::vector<std::string> BuildArgv(const Config &config, AdbChannel channel,
                                   const std::vector<std::string> &device_argv);

struct GestureCommandPlan {
  std::vector<std::string> argv;        // full argv including transport prefix
  std::vector<std::string> device_argv; // arguments as executed on the device
  bool is_tap = false;
};

// PlanGestureCommand maps one validated single-pointer gesture onto the
// Android `input` command: a near-stationary gesture becomes `input tap`, a
// moving gesture becomes `input swipe`. Multi-pointer gestures are rejected
// because the shell `input` command cannot express them.
std::optional<GestureCommandPlan> PlanGestureCommand(const Config &config,
                                                     const Gesture &gesture,
                                                     std::string *error);

// ShellPerception captures the screen with `screencap` (raw framebuffer dump
// on stdout, hashed with FNV-1a) and enriches WindowMetadata from
// `dumpsys activity top` plus `dumpsys window` through the existing parser.
// Failed or timed-out dumpsys calls degrade to empty metadata; a failed
// screencap fails the capture because the visual hash is essential.
class ShellPerception final : public PerceptionBackend {
public:
  ShellPerception(std::shared_ptr<CommandRunner> runner, Config config);

  bool Capture(const Deadline &deadline, Perception *perception,
               std::string *error) override;

private:
  bool RunDeviceCommand(AdbChannel channel,
                        const std::vector<std::string> &device_argv,
                        const Deadline &deadline, bool required,
                        CommandResult *result, std::string *error);

  std::shared_ptr<CommandRunner> runner_;
  Config config_;
};

// ShellInputInjector replays validated single-pointer gestures through
// `input tap` / `input swipe`. CancelActiveGesture is a documented no-op: the
// shell transport cannot revoke a command that already runs, and the bounded
// subprocess runner enforces the timeout instead.
class ShellInputInjector final : public InputInjector {
public:
  ShellInputInjector(std::shared_ptr<CommandRunner> runner, Config config);

  bool Inject(const Gesture &gesture, const Deadline &deadline,
              std::string *error) override;
  void CancelActiveGesture() override;

  // Convenience helpers outside the DecisionEngine gesture path, mainly for
  // operator scripts and tests. Spaces are escaped as %s because the adb
  // shell layer re-parses the joined command line.
  bool InjectKeycode(int keycode, const Deadline &deadline, std::string *error);
  bool InjectText(const std::string &text, const Deadline &deadline,
                  std::string *error);

private:
  bool RunPlan(const GestureCommandPlan &plan, const Deadline &deadline,
               std::string *error);

  std::shared_ptr<CommandRunner> runner_;
  Config config_;
};

std::unique_ptr<ShellPerception> CreateShellPerception(const Config &config);
std::unique_ptr<ShellInputInjector> CreateShellInputInjector(const Config &config);

} // namespace global_agent::shell
