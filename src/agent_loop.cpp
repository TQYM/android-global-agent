#include "global_agent/agent_loop.h"
#include "global_agent/gesture_validation.h"

#include <algorithm>
#include <chrono>
#include <cstdint>
#include <vector>

namespace global_agent {

Deadline::Deadline(std::chrono::milliseconds budget)
    : end_(std::chrono::steady_clock::now() + budget) {}

bool Deadline::Expired() const {
  return std::chrono::steady_clock::now() >= end_;
}

std::chrono::milliseconds Deadline::Remaining() const {
  const auto now = std::chrono::steady_clock::now();
  if (now >= end_) {
    return std::chrono::milliseconds::zero();
  }
  return std::chrono::duration_cast<std::chrono::milliseconds>(end_ - now);
}

AgentLoop::AgentLoop(PerceptionBackend *perception, DecisionEngine *decision,
                     InputInjector *input, StateStore *store,
                     std::chrono::milliseconds persist_interval,
                     std::chrono::milliseconds feedback_grace)
    : perception_(perception), decision_(decision), input_(input),
      store_(store), persist_interval_(persist_interval),
      feedback_grace_(feedback_grace) {}

bool AgentLoop::Restore(std::string *error) {
  std::vector<std::uint8_t> payload;
  std::uint64_t generation = 0;
  if (!store_->LoadLatest(&payload, &generation, error)) {
    return false;
  }
  if (payload.empty()) {
    return true;
  }
  if (!graph_.Deserialize(payload, error)) {
    return false;
  }
  last_persist_ = std::chrono::steady_clock::now();
  return true;
}

StepResult AgentLoop::Step(std::chrono::milliseconds budget) {
  Deadline deadline(budget);
  Perception current;
  std::string error;
  if (!perception_->Capture(deadline, &current, &error)) {
    return {.ok = false, .error = std::move(error)};
  }
  const NodeId current_node = graph_.Observe(current);

  const auto now = std::chrono::steady_clock::now();
  if (pending_action_.has_value()) {
    if (now < pending_action_->feedback_after) {
      if (!PersistIfDue(false, &error)) {
        return {.ok = false, .error = std::move(error)};
      }
      return {.ok = true, .outcome = ActionOutcome::kUnknown};
    }

    const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
        now - pending_action_->started);
    const auto bounded_latency = std::min<std::int64_t>(
        elapsed.count(), static_cast<std::int64_t>(UINT32_MAX));
    const ActionOutcome outcome = current_node == pending_action_->from
                                      ? ActionOutcome::kNoVisibleChange
                                      : ActionOutcome::kSucceeded;
    graph_.RecordTransition(pending_action_->from, current_node,
                            pending_action_->action_id, current.monotonic_ns,
                            static_cast<std::uint32_t>(bounded_latency),
                            outcome);
    pending_action_.reset();
    if (!PersistIfDue(true, &error)) {
      return {.ok = false,
              .action_attempted = true,
              .outcome = outcome,
              .error = std::move(error)};
    }
    return {.ok = true, .action_attempted = true, .outcome = outcome};
  }

  const std::optional<Gesture> gesture = decision_->Decide(graph_, current);
  if (!gesture.has_value()) {
    if (!PersistIfDue(false, &error)) {
      return {.ok = false, .error = std::move(error)};
    }
    return {.ok = true, .outcome = ActionOutcome::kUnknown};
  }

  if (!ValidateGesture(*gesture, &error)) {
    graph_.RecordTransition(current_node, current_node, gesture->action_id,
                            current.monotonic_ns, 0, ActionOutcome::kRejected);
    PersistIfDue(true, nullptr);
    return {.ok = false,
            .action_attempted = true,
            .outcome = ActionOutcome::kRejected,
            .error = std::move(error)};
  }

  if (deadline.Expired()) {
    return {.ok = false,
            .action_attempted = false,
            .outcome = ActionOutcome::kTimedOut,
            .error = "step deadline expired before input injection"};
  }
  if (!input_->Inject(*gesture, deadline, &error)) {
    input_->CancelActiveGesture();
    const ActionOutcome outcome = deadline.Expired() ? ActionOutcome::kTimedOut
                                                     : ActionOutcome::kRejected;
    graph_.RecordTransition(current_node, current_node, gesture->action_id,
                            current.monotonic_ns, 0, outcome);
    PersistIfDue(true, nullptr);
    return {.ok = false,
            .action_attempted = true,
            .outcome = outcome,
            .error = std::move(error)};
  }

  std::uint32_t duration_ms = 0;
  for (const GestureFrame &frame : gesture->frames) {
    duration_ms = std::max(duration_ms, frame.elapsed_ms);
  }
  const auto started = std::chrono::steady_clock::now();
  pending_action_ = PendingAction{
      .from = current_node,
      .action_id = gesture->action_id,
      .started = started,
      .feedback_after =
          started + std::chrono::milliseconds(duration_ms) + feedback_grace_,
  };
  if (!PersistIfDue(false, &error)) {
    return {.ok = false,
            .action_attempted = true,
            .outcome = ActionOutcome::kUnknown,
            .error = std::move(error)};
  }
  return {
      .ok = true, .action_attempted = true, .outcome = ActionOutcome::kUnknown};
}

bool AgentLoop::PersistIfDue(bool force, std::string *error) {
  const auto now = std::chrono::steady_clock::now();
  if (!force && last_persist_.time_since_epoch().count() != 0 &&
      now - last_persist_ < persist_interval_) {
    return true;
  }
  const std::vector<std::uint8_t> payload = graph_.Serialize();
  if (!store_->Commit(payload, error)) {
    return false;
  }
  last_persist_ = now;
  return true;
}

} // namespace global_agent
