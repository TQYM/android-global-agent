#include "global_agent/agent_loop.h"
#include "global_agent/bezier.h"
#include "global_agent/crc32.h"
#include "global_agent/dumpsys_parser.h"
#include "global_agent/gesture_validation.h"
#include "global_agent/hash.h"
#include "global_agent/state_graph.h"
#include "global_agent/state_store.h"
#include "global_agent/session_context.h"
#include "global_agent/shell_backend.h"
#include "global_agent/subprocess.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <fcntl.h>
#include <filesystem>
#include <iostream>
#include <optional>
#include <string>
#include <unistd.h>
#include <vector>

namespace ga = global_agent;

namespace {

int failures = 0;

#define CHECK(condition)                                                       \
  do {                                                                         \
    if (!(condition)) {                                                        \
      std::cerr << __FILE__ << ':' << __LINE__                                 \
                << " CHECK failed: " << #condition << '\n';                    \
      ++failures;                                                              \
    }                                                                          \
  } while (false)

std::string TempPath() {
  std::string pattern = "/tmp/global-agent-test-XXXXXX";
  std::vector<char> buffer(pattern.begin(), pattern.end());
  buffer.push_back('\0');
  const int fd = mkstemp(buffer.data());
  CHECK(fd >= 0);
  if (fd >= 0)
    close(fd);
  std::filesystem::remove(buffer.data());
  return buffer.data();
}

ga::Perception MakePerception(std::uint64_t visual, std::string_view component,
                              std::uint64_t timestamp) {
  ga::Perception perception;
  perception.monotonic_ns = timestamp;
  perception.visual_hash = visual;
  perception.confidence_milli = 900;
  perception.window.component_hash = ga::HashString(component);
  perception.window.focused_pid = 123;
  return perception;
}

void TestCrc32() {
  constexpr char text[] = "123456789";
  CHECK(ga::Crc32(text, sizeof(text) - 1) == 0xCBF43926U);
}

void TestBezier() {
  const ga::CubicBezier curve{{0, 0}, {0, 100}, {100, 100}, {100, 0}};
  const auto points = ga::SampleBezierByArcLength(curve, 160, 8);
  CHECK(points.size() == 21);
  CHECK(std::fabs(points.front().position.x) < 0.001F);
  CHECK(std::fabs(points.back().position.x - 100.0F) < 0.001F);
  CHECK(points.back().elapsed_ms == 160);
  for (std::size_t i = 1; i < points.size(); ++i) {
    CHECK(points[i].elapsed_ms >= points[i - 1].elapsed_ms);
  }
  const auto gesture = ga::BuildSinglePointerGesture(7, 0, points);
  CHECK(gesture.frames.front().action == ga::GestureAction::kDown);
  CHECK(gesture.frames.back().action == ga::GestureAction::kUp);
}

void TestGestureValidation() {
  ga::Gesture valid;
  valid.frames = {
      {.action = ga::GestureAction::kDown,
       .elapsed_ms = 0,
       .pointers = {{.pointer_id = 0, .position = {10, 10}}}},
      {.action = ga::GestureAction::kPointerDown,
       .action_index = 1,
       .elapsed_ms = 8,
       .pointers = {{.pointer_id = 0, .position = {10, 10}},
                    {.pointer_id = 1, .position = {20, 20}}}},
      {.action = ga::GestureAction::kMove,
       .elapsed_ms = 16,
       .pointers = {{.pointer_id = 0, .position = {11, 11}},
                    {.pointer_id = 1, .position = {21, 21}}}},
      {.action = ga::GestureAction::kPointerUp,
       .action_index = 1,
       .elapsed_ms = 24,
       .pointers = {{.pointer_id = 0, .position = {12, 12}},
                    {.pointer_id = 1, .position = {22, 22}}}},
      {.action = ga::GestureAction::kUp,
       .elapsed_ms = 32,
       .pointers = {{.pointer_id = 0, .position = {13, 13}}}},
  };
  std::string error;
  CHECK(ga::ValidateGesture(valid, &error));

  valid.frames[2].pointers.pop_back();
  CHECK(!ga::ValidateGesture(valid, &error));
}

void TestStateRoundTrip() {
  ga::StateGraph graph;
  const auto first = graph.Observe(MakePerception(1, "pkg/.A", 10));
  const auto second = graph.Observe(MakePerception(2, "pkg/.B", 20));
  graph.RecordTransition(first, second, 99, 20, 12,
                         ga::ActionOutcome::kSucceeded);
  const auto bytes = graph.Serialize();

  ga::StateGraph restored;
  std::string error;
  CHECK(restored.Deserialize(bytes, &error));
  CHECK(restored.current_node() == second);
  CHECK(restored.nodes().size() == 2);
  CHECK(restored.edges().size() == 1);
}

void TestMmapStore() {
  const std::string path = TempPath();
  std::string error;
  {
    ga::StateStore store;
    CHECK(store.Open(path, &error, 4096));
    const std::vector<std::uint8_t> first{1, 2, 3};
    const std::vector<std::uint8_t> second{4, 5, 6, 7};
    CHECK(store.Commit(first, &error));
    CHECK(store.Commit(second, &error));
    CHECK(store.generation() == 2);
  }
  {
    const int fd = open(path.c_str(), O_RDWR);
    CHECK(fd >= 0);
    if (fd >= 0) {
      std::uint8_t byte = 0;
      CHECK(pread(fd, &byte, 1, 128) == 1);
      byte ^= 0xFFU;
      CHECK(pwrite(fd, &byte, 1, 128) == 1);
      close(fd);
    }
  }
  {
    ga::StateStore store;
    CHECK(store.Open(path, &error, 4096));
    std::vector<std::uint8_t> payload;
    std::uint64_t generation = 0;
    CHECK(store.LoadLatest(&payload, &generation, &error));
    CHECK(generation == 1);
    CHECK((payload == std::vector<std::uint8_t>{1, 2, 3}));
  }
  std::filesystem::remove(path);
}

void TestStoreSingleWriterLock() {
  const std::string path = TempPath();
  std::string error;
  ga::StateStore first;
  CHECK(first.Open(path, &error, 4096));
  ga::StateStore second;
  CHECK(!second.Open(path, &error, 4096));
  std::filesystem::remove(path);
}

void TestGraphLimitKeepsCurrent() {
  ga::StateGraph graph;
  for (std::uint64_t index = 0; index < 160; ++index) {
    graph.Observe(MakePerception(index + 1, "pkg/.Activity", index + 1));
  }
  CHECK(graph.nodes().size() <= ga::StateGraph::kMaxNodes);
  CHECK(graph.current_node() != 0);
  CHECK(std::any_of(graph.nodes().begin(), graph.nodes().end(),
                    [&graph](const ga::StateNode &node) {
                      return node.id == graph.current_node();
                    }));
  std::string error;
  const auto bytes = graph.Serialize();
  ga::StateGraph restored;
  CHECK(restored.Deserialize(bytes, &error));
}

void TestBoundedSubprocess() {
  const auto success =
      ga::RunCommand({"/usr/bin/printf", "ok"}, std::chrono::milliseconds(100));
  CHECK(success.started);
  CHECK(!success.timed_out);
  CHECK(success.exit_code == 0);
  CHECK(success.output == "ok");

  const auto timed =
      ga::RunCommand({"/bin/sleep", "1"}, std::chrono::milliseconds(20));
  CHECK(timed.started);
  CHECK(timed.timed_out);
}

void TestDumpsysParser() {
  constexpr std::string_view activity = R"(
      topResumedActivity=ActivityRecord{123 u0 com.example/.MainActivity t4}
      ACTIVITY com.example/.MainActivity pid=4321
    )";
  constexpr std::string_view window = R"(
      mCurrentFocus=Window{abc u0 com.example/.MainActivity}
      mSession=Session{deadbeef 4321:u0a123}
    )";
  const auto metadata = ga::ParseActivityAndWindowDumps(activity, window);
  CHECK(metadata.has_component);
  CHECK(metadata.component == "com.example/.MainActivity");
  CHECK(metadata.window.focused_pid == 4321);

  constexpr std::string_view view_dump = R"(
      View Hierarchy:
        DecorView@0x7ffee123 id/content bounds=[0,0][100,100]
        TextView@abcdef text=Hello
    )";
  CHECK(ga::HashNormalizedViewDump(view_dump) != 0);
}

class SequencePerception final : public ga::PerceptionBackend {
public:
  bool Capture(const ga::Deadline &, ga::Perception *perception,
               std::string *) override {
    *perception =
        frames.at(index < frames.size() ? index++ : frames.size() - 1);
    return true;
  }
  std::vector<ga::Perception> frames;
  std::size_t index = 0;
};

class OneActionDecision final : public ga::DecisionEngine {
public:
  std::optional<ga::Gesture> Decide(const ga::StateGraph &,
                                    const ga::Perception &) override {
    if (done)
      return std::nullopt;
    done = true;
    return ga::BuildSinglePointerGesture(
        10, 0,
        ga::SampleBezierByArcLength({{0, 0}, {0, 1}, {1, 1}, {1, 0}}, 16, 8));
  }
  bool done = false;
};

class AcceptingInput final : public ga::InputInjector {
public:
  bool Inject(const ga::Gesture &, const ga::Deadline &,
              std::string *) override {
    injected = true;
    return true;
  }
  void CancelActiveGesture() override { cancelled = true; }
  bool injected = false;
  bool cancelled = false;
};

void TestAgentLoop() {
  const std::string path = TempPath();
  std::string error;
  ga::StateStore store;
  CHECK(store.Open(path, &error, 8192));
  SequencePerception perception;
  perception.frames = {MakePerception(1, "pkg/.A", 10),
                       MakePerception(2, "pkg/.B", 20)};
  OneActionDecision decision;
  AcceptingInput input;
  ga::AgentLoop loop(&perception, &decision, &input, &store);
  CHECK(loop.Restore(&error));
  const auto dispatched = loop.Step(std::chrono::milliseconds(200));
  CHECK(dispatched.ok);
  CHECK(dispatched.outcome == ga::ActionOutcome::kUnknown);
  CHECK(input.injected);
  CHECK(loop.has_pending_action());
  usleep(60 * 1000);
  const auto result = loop.Step(std::chrono::milliseconds(200));
  CHECK(result.ok);
  CHECK(result.outcome == ga::ActionOutcome::kSucceeded);
  CHECK(!loop.has_pending_action());
  CHECK(loop.graph().edges().size() == 1);
  std::filesystem::remove(path);
}

void TestSessionContext() {
  ga::SessionContext context;
  const ga::SessionContext::TimePoint start{};
  std::string error;

  ga::TriggerEvent unconfirmed{
      .source = ga::TriggerSource::kPowerLongPress,
      .monotonic_ns = 1,
      .press_duration_ms = 2000,
      .display_id = 0,
      .keyguard_locked = false,
      .user_confirmed = false,
  };
  CHECK(!context.Begin(unconfirmed, start, &error));

  ga::TriggerEvent locked = unconfirmed;
  locked.monotonic_ns = 2;
  locked.user_confirmed = true;
  locked.keyguard_locked = true;
  CHECK(!context.Begin(locked, start, &error));

  ga::TriggerEvent too_short = unconfirmed;
  too_short.monotonic_ns = 3;
  too_short.user_confirmed = true;
  too_short.press_duration_ms = 1999;
  CHECK(!context.Begin(too_short, start, &error));

  ga::TriggerEvent valid = too_short;
  valid.monotonic_ns = 4;
  valid.press_duration_ms = 2000;
  CHECK(context.Begin(valid, start, &error));
  const auto snapshot = context.Snapshot();
  CHECK(snapshot.active);
  CHECK(snapshot.state == ga::VisualState::kListening);
  CHECK(snapshot.id == 1);

  CHECK(!context.Begin(valid, start, &error));
  CHECK(context.SubmitTranscript(
      {.session_id = snapshot.id, .sequence = 1, .is_final = false,
       .text = "open settings"},
      &error));
  CHECK(context.Snapshot().transcript == "open settings");
  CHECK(!context.SubmitTranscript(
      {.session_id = snapshot.id, .sequence = 1, .is_final = true,
       .text = "duplicate"},
      &error));
  CHECK(!context.SubmitTranscript(
      {.session_id = snapshot.id, .sequence = 2, .is_final = true,
       .text = std::string("\xC0\x80")},
      &error));
  CHECK(!context.SubmitTranscript(
      {.session_id = snapshot.id, .sequence = 2, .is_final = true,
       .text = std::string(ga::SessionContext::kMaxTranscriptBytes + 1, 'x')},
      &error));
  CHECK(context.SubmitTranscript(
      {.session_id = snapshot.id, .sequence = 2, .is_final = true,
       .text = "open settings"},
      &error));
  CHECK(!context.SubmitTranscript(
      {.session_id = snapshot.id, .sequence = 3, .is_final = false,
       .text = "later"},
      &error));

  CHECK(context.Transition(ga::VisualState::kThinking, &error));
  CHECK(context.Transition(ga::VisualState::kExecuting, &error));
  CHECK(context.Transition(ga::VisualState::kFeedback, &error));
  CHECK(context.Transition(ga::VisualState::kIdle, &error));
  CHECK(!context.Snapshot().active);
  CHECK(context.Snapshot().transcript.empty());

  ga::TriggerEvent explicit_ui{
      .source = ga::TriggerSource::kExplicitUi,
      .monotonic_ns = 5,
      .press_duration_ms = 0,
      .display_id = 0,
      .keyguard_locked = false,
      .user_confirmed = true,
  };
  CHECK(context.Begin(explicit_ui, start, &error));
  CHECK(!context.Expire(start + ga::SessionContext::kSessionTimeout -
                        std::chrono::milliseconds(1)));
  CHECK(context.Expire(start + ga::SessionContext::kSessionTimeout));
  CHECK(!context.Snapshot().active);
}

class FakeShellRunner final : public ga::shell::CommandRunner {
public:
  struct Call {
    std::vector<std::string> argv;
  };

  CommandResult Run(const std::vector<std::string> &argv,
                    std::chrono::milliseconds /*timeout*/,
                    std::size_t /*max_output_bytes*/) override {
    calls.push_back({argv});
    CommandResult result;
    result.started = true;
    const std::string joined = Join(argv);
    if (joined.find("screencap") != std::string::npos) {
      result.exit_code = fail_screencap_ ? 1 : 0;
      result.output = std::string(64, '\xAB');
      return result;
    }
    if (joined.find("dumpsys activity top") != std::string::npos) {
      result.exit_code = 0;
      result.output =
          "TASK com.example pid=4321\n"
          "  ACTIVITY com.example/.MainActivity 22a71e8 pid=4321\n"
          "  View Hierarchy:\n"
          "    android.widget.LinearLayout{abc}\n";
      return result;
    }
    if (joined.find("dumpsys window") != std::string::npos) {
      result.exit_code = 0;
      result.output =
          "  mCurrentFocus=Window{7f1a001 u0 com.example/.MainActivity}\n";
      return result;
    }
    result.exit_code = fail_input_ ? 1 : 0;
    result.output = "device rejected the gesture";
    return result;
  }

  std::vector<Call> calls;
  bool fail_screencap_ = false;
  bool fail_input_ = false;

private:
  static std::string Join(const std::vector<std::string> &argv) {
    std::string joined;
    for (const std::string &argument : argv) {
      if (!joined.empty()) {
        joined += ' ';
      }
      joined += argument;
    }
    return joined;
  }
};

ga::Gesture TapGesture(std::uint64_t action_id) {
  const std::vector<ga::TimedPoint> path{{0, {100.0F, 200.0F}},
                                         {40, {101.0F, 200.0F}},
                                         {80, {100.0F, 201.0F}}};
  return ga::BuildSinglePointerGesture(action_id, 0, path);
}

ga::Gesture MultiPointerGesture() {
  ga::Gesture gesture;
  gesture.action_id = 9;
  gesture.display_id = 0;
  gesture.frames.resize(5);
  gesture.frames[0].action = ga::GestureAction::kDown;
  gesture.frames[0].elapsed_ms = 0;
  gesture.frames[0].pointers = {{0, {10.0F, 10.0F}}};
  gesture.frames[1].action = ga::GestureAction::kPointerDown;
  gesture.frames[1].action_index = 1;
  gesture.frames[1].elapsed_ms = 50;
  gesture.frames[1].pointers = {{0, {10.0F, 10.0F}}, {1, {20.0F, 20.0F}}};
  gesture.frames[2].action = ga::GestureAction::kMove;
  gesture.frames[2].elapsed_ms = 100;
  gesture.frames[2].pointers = {{0, {11.0F, 11.0F}}, {1, {21.0F, 21.0F}}};
  gesture.frames[3].action = ga::GestureAction::kPointerUp;
  gesture.frames[3].action_index = 1;
  gesture.frames[3].elapsed_ms = 150;
  gesture.frames[3].pointers = {{0, {12.0F, 12.0F}}, {1, {22.0F, 22.0F}}};
  gesture.frames[4].action = ga::GestureAction::kUp;
  gesture.frames[4].elapsed_ms = 200;
  gesture.frames[4].pointers = {{0, {12.0F, 12.0F}}};
  return gesture;
}

void TestShellBuildArgv() {
  ga::shell::Config on_device;
  CHECK(ga::shell::BuildArgv(on_device, ga::shell::AdbChannel::kShell,
                             {"input", "tap", "5", "6"}) ==
        (std::vector<std::string>{"/system/bin/input", "tap", "5", "6"}));
  CHECK(ga::shell::BuildArgv(on_device, ga::shell::AdbChannel::kExecOut,
                             {"/system/bin/dumpsys", "window"}) ==
        (std::vector<std::string>{"/system/bin/dumpsys", "window"}));

  ga::shell::Config adb;
  adb.transport = ga::shell::Transport::kAdb;
  CHECK(ga::shell::BuildArgv(adb, ga::shell::AdbChannel::kShell, {"input"}) ==
        (std::vector<std::string>{"adb", "shell", "input"}));
  CHECK(ga::shell::BuildArgv(adb, ga::shell::AdbChannel::kExecOut,
                             {"screencap"}) ==
        (std::vector<std::string>{"adb", "exec-out", "screencap"}));
  adb.adb_serial = "ZX1G22";
  CHECK(ga::shell::BuildArgv(adb, ga::shell::AdbChannel::kShell, {"input"}) ==
        (std::vector<std::string>{"adb", "-s", "ZX1G22", "shell", "input"}));
}

void TestShellGesturePlanning() {
  ga::shell::Config config;
  const auto tap = ga::shell::PlanGestureCommand(config, TapGesture(3), nullptr);
  CHECK(tap.has_value());
  CHECK(tap->is_tap);
  CHECK(tap->device_argv ==
        (std::vector<std::string>{"input", "tap", "100", "200"}));

  const ga::CubicBezier curve{{0, 0}, {0, 100}, {100, 100}, {100, 0}};
  const auto swipe = ga::shell::PlanGestureCommand(
      config, ga::BuildSinglePointerGesture(4, 0,
                                            ga::SampleBezierByArcLength(curve, 160, 8)),
      nullptr);
  CHECK(swipe.has_value());
  CHECK(!swipe->is_tap);
  CHECK(swipe->device_argv ==
        (std::vector<std::string>{"input", "swipe", "0", "0", "100", "0", "160"}));

  ga::shell::Config adb;
  adb.transport = ga::shell::Transport::kAdb;
  adb.adb_serial = "S1";
  const auto forwarded = ga::shell::PlanGestureCommand(adb, TapGesture(5), nullptr);
  CHECK(forwarded.has_value());
  CHECK((forwarded->argv ==
        std::vector<std::string>{"adb", "-s", "S1", "shell", "input", "tap",
                                 "100", "200"}));

  std::string error;
  CHECK(!ga::shell::PlanGestureCommand(config, MultiPointerGesture(), &error)
            .has_value());
  CHECK(error.find("single-pointer") != std::string::npos);

  ga::Gesture negative = TapGesture(6);
  for (ga::GestureFrame &frame : negative.frames) {
    frame.pointers[0].position.x = -5.0F;
  }
  error.clear();
  CHECK(!ga::shell::PlanGestureCommand(config, negative, &error).has_value());
  CHECK(error.find("non-negative") != std::string::npos);
}

void TestShellPerception() {
  auto runner = std::make_shared<FakeShellRunner>();
  ga::shell::Config config;
  ga::shell::ShellPerception perception(runner, config);
  ga::Deadline deadline(std::chrono::milliseconds(4000));
  ga::Perception out;
  std::string error;
  CHECK(perception.Capture(deadline, &out, &error));
  CHECK(error.empty());
  CHECK(runner->calls.size() == 3);
  CHECK(runner->calls[0].argv ==
        (std::vector<std::string>{"/system/bin/screencap"}));
  const std::string frame(64, '\xAB');
  CHECK(out.visual_hash == ga::HashBytes(frame.data(), frame.size()));
  CHECK(out.window.component_hash ==
        ga::HashString("com.example/.MainActivity"));
  CHECK(out.window.focused_pid == 4321);
  CHECK(out.window.view_hash != 0);
  CHECK(out.confidence_milli == 600);

  runner->fail_screencap_ = true;
  error.clear();
  CHECK(!perception.Capture(deadline, &out, &error));
  CHECK(error.find("screencap") != std::string::npos);
}

void TestShellInjector() {
  auto runner = std::make_shared<FakeShellRunner>();
  ga::shell::Config config;
  ga::shell::ShellInputInjector injector(runner, config);
  ga::Deadline deadline(std::chrono::milliseconds(4000));
  std::string error;

  CHECK(injector.Inject(TapGesture(11), deadline, &error));
  CHECK(error.empty());
  CHECK(runner->calls.back().argv ==
        (std::vector<std::string>{"/system/bin/input", "tap", "100", "200"}));

  CHECK(injector.InjectKeycode(4, deadline, &error));
  CHECK(runner->calls.back().argv ==
        (std::vector<std::string>{"/system/bin/input", "keyevent", "4"}));

  CHECK(injector.InjectText("hello world", deadline, &error));
  CHECK(runner->calls.back().argv ==
        (std::vector<std::string>{"/system/bin/input", "text", "hello%sworld"}));
  CHECK(!injector.InjectText("bad\ntext", deadline, &error));

  error.clear();
  runner->fail_input_ = true;
  CHECK(!injector.Inject(TapGesture(12), deadline, &error));
  CHECK(error.find("exit 1") != std::string::npos);
}

} // namespace

int main() {
  TestCrc32();
  TestBezier();
  TestGestureValidation();
  TestStateRoundTrip();
  TestMmapStore();
  TestStoreSingleWriterLock();
  TestGraphLimitKeepsCurrent();
  TestDumpsysParser();
  TestBoundedSubprocess();
  TestAgentLoop();
  TestSessionContext();
  TestShellBuildArgv();
  TestShellGesturePlanning();
  TestShellPerception();
  TestShellInjector();
  if (failures != 0) {
    std::cerr << failures << " test assertion(s) failed\n";
    return 1;
  }
  std::cout << "all tests passed\n";
  return 0;
}
