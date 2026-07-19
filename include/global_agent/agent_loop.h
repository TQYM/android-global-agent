#pragma once

#include <chrono>
#include <optional>
#include <string>

#include "global_agent/state_graph.h"
#include "global_agent/state_store.h"
#include "global_agent/types.h"

namespace global_agent {

class Deadline {
public:
  explicit Deadline(std::chrono::milliseconds budget);

  [[nodiscard]] bool Expired() const;
  [[nodiscard]] std::chrono::milliseconds Remaining() const;

private:
  std::chrono::steady_clock::time_point end_;
};

class PerceptionBackend {
public:
  virtual ~PerceptionBackend() = default;
  virtual bool Capture(const Deadline &deadline, Perception *perception,
                       std::string *error) = 0;
};

class DecisionEngine {
public:
  virtual ~DecisionEngine() = default;
  virtual std::optional<Gesture> Decide(const StateGraph &graph,
                                        const Perception &perception) = 0;
};

class InputInjector {
public:
  virtual ~InputInjector() = default;
  virtual bool Inject(const Gesture &gesture, const Deadline &deadline,
                      std::string *error) = 0;
  virtual void CancelActiveGesture() = 0;
};

struct StepResult {
  bool ok = false;
  bool action_attempted = false;
  ActionOutcome outcome = ActionOutcome::kUnknown;
  std::string error;
};

class AgentLoop {
public:
  AgentLoop(
      PerceptionBackend *perception, DecisionEngine *decision,
      InputInjector *input, StateStore *store,
      std::chrono::milliseconds persist_interval =
          std::chrono::milliseconds(1000),
      std::chrono::milliseconds feedback_grace = std::chrono::milliseconds(16));

  bool Restore(std::string *error);
  StepResult Step(std::chrono::milliseconds budget);

  [[nodiscard]] const StateGraph &graph() const { return graph_; }
  [[nodiscard]] bool has_pending_action() const {
    return pending_action_.has_value();
  }

private:
  struct PendingAction {
    NodeId from = 0;
    std::uint64_t action_id = 0;
    std::chrono::steady_clock::time_point started;
    std::chrono::steady_clock::time_point feedback_after;
  };

  bool PersistIfDue(bool force, std::string *error);

  PerceptionBackend *perception_;
  DecisionEngine *decision_;
  InputInjector *input_;
  StateStore *store_;
  StateGraph graph_;
  std::chrono::milliseconds persist_interval_;
  std::chrono::milliseconds feedback_grace_;
  std::chrono::steady_clock::time_point last_persist_{};
  std::optional<PendingAction> pending_action_;
};

} // namespace global_agent
