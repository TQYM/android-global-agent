#include "agent_binder_service.h"

#include <algorithm>
#include <chrono>
#include <cstdint>
#include <limits>

#include <android/binder_ibinder.h>
#include "global_agent/hash.h"

namespace global_agent::aosp {
namespace {

constexpr std::int32_t kRootUid = 0;
constexpr std::int32_t kSystemUid = 1000;

ndk::ScopedAStatus SecurityError() {
  return ndk::ScopedAStatus::fromExceptionCodeWithMessage(
      EX_SECURITY, "caller is not authorized for the agent service");
}

ndk::ScopedAStatus InvalidArgument(const std::string &message) {
  return ndk::ScopedAStatus::fromExceptionCodeWithMessage(EX_ILLEGAL_ARGUMENT,
                                                           message.c_str());
}

std::int32_t CallingUid() {
  return static_cast<std::int32_t>(AIBinder_getCallingUid());
}

} // namespace

ndk::ScopedAStatus AgentBinderService::registerBridge(
    const std::shared_ptr<aidl::com::example::globalagent::IAgentBridge>
        &bridge) {
  if (bridge == nullptr) {
    return ndk::ScopedAStatus::fromExceptionCodeWithMessage(
        EX_ILLEGAL_ARGUMENT, "bridge must not be null");
  }
  const std::int32_t caller = CallingUid();
  {
    std::lock_guard lock(mutex_);
    if (registered_bridge_uid_ >= 0 && caller != registered_bridge_uid_ &&
        caller != kRootUid && caller != kSystemUid) {
      return SecurityError();
    }
    registered_bridge_uid_ = caller;
    bridge_ = bridge;
  }
  aidl::com::example::globalagent::SessionStatus session_status;
  {
    std::lock_guard lock(session_mutex_);
    session_status = ToAidlStatus(
        session_context_.Snapshot(),
        session_revision_.load(std::memory_order_acquire));
  }
  bridge->onSessionStateChanged(session_status);
  return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus
AgentBinderService::notifySettingChanged(const std::string &key) {
  if (!IsAuthorizedCaller()) {
    return SecurityError();
  }
  if (key.empty() || key.size() > 128) {
    return ndk::ScopedAStatus::fromExceptionCodeWithMessage(
        EX_ILLEGAL_ARGUMENT, "invalid setting key");
  }
  setting_epoch_.fetch_add(1, std::memory_order_acq_rel);
  return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus AgentBinderService::notifyWindowChanged(
    const aidl::com::example::globalagent::WindowSnapshot &snapshot) {
  if (!IsAuthorizedCaller()) {
    return SecurityError();
  }
  if (snapshot.componentName.size() > 256 || snapshot.displayId < 0 ||
      snapshot.rotation < 0 || snapshot.rotation > 3 ||
      snapshot.right < snapshot.left || snapshot.bottom < snapshot.top) {
    return ndk::ScopedAStatus::fromExceptionCodeWithMessage(
        EX_ILLEGAL_ARGUMENT, "invalid window snapshot");
  }
  WindowMetadata metadata;
  metadata.component_hash = HashString(snapshot.componentName);
  metadata.focused_pid = snapshot.focusedPid;
  metadata.display_id = static_cast<std::uint32_t>(snapshot.displayId);
  metadata.rotation = static_cast<std::uint16_t>(snapshot.rotation);
  metadata.bounds = {
      .left = snapshot.left,
      .top = snapshot.top,
      .right = snapshot.right,
      .bottom = snapshot.bottom,
  };
  std::lock_guard lock(mutex_);
  window_metadata_ = metadata;
  return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus AgentBinderService::beginSession(
    const aidl::com::example::globalagent::SessionTrigger &trigger,
    aidl::com::example::globalagent::SessionStatus *status) {
  if (!IsAuthorizedCaller()) {
    return SecurityError();
  }
  if (status == nullptr || trigger.source < 0 || trigger.source > 1 ||
      trigger.monotonicNanos <= 0 || trigger.pressDurationMillis < 0) {
    return InvalidArgument("invalid session trigger");
  }
  std::lock_guard session_lock(session_mutex_);

  const TriggerEvent event{
      .source = static_cast<TriggerSource>(trigger.source),
      .monotonic_ns = static_cast<std::uint64_t>(trigger.monotonicNanos),
      .press_duration_ms =
          static_cast<std::uint32_t>(trigger.pressDurationMillis),
      .display_id = trigger.displayId,
      .keyguard_locked = trigger.keyguardLocked,
      .user_confirmed = trigger.userConfirmed,
  };
  std::string error;
  if (!session_context_.Begin(event, SessionContext::Clock::now(), &error)) {
    return InvalidArgument(error);
  }
  const SessionSnapshot snapshot = session_context_.Snapshot();
  const std::uint64_t revision =
      session_revision_.fetch_add(1, std::memory_order_acq_rel) + 1;
  *status = ToAidlStatus(snapshot, revision);
  PublishSessionStatus(*status);
  return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus AgentBinderService::submitTranscript(
    const aidl::com::example::globalagent::TranscriptUpdate &update,
    aidl::com::example::globalagent::SessionStatus *status) {
  if (!IsAuthorizedCaller()) {
    return SecurityError();
  }
  if (status == nullptr || update.sessionId <= 0 || update.sequence <= 0) {
    return InvalidArgument("invalid transcript metadata");
  }
  std::lock_guard session_lock(session_mutex_);

  const TranscriptChunk chunk{
      .session_id = static_cast<std::uint64_t>(update.sessionId),
      .sequence = static_cast<std::uint64_t>(update.sequence),
      .is_final = update.isFinal,
      .text = update.text,
  };
  std::string error;
  if (!session_context_.SubmitTranscript(chunk, &error)) {
    return InvalidArgument(error);
  }
  if (update.isFinal &&
      !session_context_.Transition(VisualState::kThinking, &error)) {
    session_context_.Cancel();
    return InvalidArgument(error);
  }
  const SessionSnapshot snapshot = session_context_.Snapshot();
  const std::uint64_t revision =
      session_revision_.fetch_add(1, std::memory_order_acq_rel) + 1;
  *status = ToAidlStatus(snapshot, revision);
  PublishSessionStatus(*status);
  return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus AgentBinderService::transitionSession(
    std::int64_t session_id, std::int32_t state,
    aidl::com::example::globalagent::SessionStatus *status) {
  if (!IsAuthorizedCaller()) {
    return SecurityError();
  }
  if (status == nullptr || session_id <= 0 || state < 0 || state > 5) {
    return InvalidArgument("invalid session transition");
  }
  std::lock_guard session_lock(session_mutex_);
  const SessionSnapshot before = session_context_.Snapshot();
  if (!before.active ||
      before.id != static_cast<std::uint64_t>(session_id)) {
    return InvalidArgument("session id is stale or inactive");
  }

  std::string error;
  if (!session_context_.Transition(static_cast<VisualState>(state), &error)) {
    return InvalidArgument(error);
  }
  const SessionSnapshot snapshot = session_context_.Snapshot();
  const std::uint64_t revision =
      session_revision_.fetch_add(1, std::memory_order_acq_rel) + 1;
  *status = ToAidlStatus(snapshot, revision);
  PublishSessionStatus(*status);
  return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus AgentBinderService::cancelSession(
    std::int64_t session_id,
    aidl::com::example::globalagent::SessionStatus *status) {
  if (!IsAuthorizedCaller()) {
    return SecurityError();
  }
  if (status == nullptr || session_id <= 0) {
    return InvalidArgument("invalid session id");
  }
  std::lock_guard session_lock(session_mutex_);
  const SessionSnapshot before = session_context_.Snapshot();
  if (!before.active ||
      before.id != static_cast<std::uint64_t>(session_id)) {
    return InvalidArgument("session id is stale or inactive");
  }

  session_context_.Cancel();
  const SessionSnapshot snapshot = session_context_.Snapshot();
  const std::uint64_t revision =
      session_revision_.fetch_add(1, std::memory_order_acq_rel) + 1;
  *status = ToAidlStatus(snapshot, revision);
  PublishSessionStatus(*status);
  return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus AgentBinderService::getSessionStatus(
    aidl::com::example::globalagent::SessionStatus *status) {
  if (!IsAuthorizedCaller()) {
    return SecurityError();
  }
  if (status == nullptr) {
    return InvalidArgument("session status output is null");
  }
  std::lock_guard session_lock(session_mutex_);
  *status = ToAidlStatus(
      session_context_.Snapshot(),
      session_revision_.load(std::memory_order_acquire));
  return ndk::ScopedAStatus::ok();
}

std::shared_ptr<aidl::com::example::globalagent::IAgentBridge>
AgentBinderService::bridge() const {
  std::lock_guard lock(mutex_);
  return bridge_;
}

WindowMetadata AgentBinderService::window_metadata() const {
  std::lock_guard lock(mutex_);
  return window_metadata_;
}

bool AgentBinderService::ExpireSession() {
  std::lock_guard session_lock(session_mutex_);
  if (!session_context_.Expire(SessionContext::Clock::now())) {
    return false;
  }
  const std::uint64_t revision =
      session_revision_.fetch_add(1, std::memory_order_acq_rel) + 1;
  PublishSessionStatus(ToAidlStatus(session_context_.Snapshot(), revision));
  return true;
}

void AgentBinderService::ResetSession() {
  std::lock_guard session_lock(session_mutex_);
  const bool was_active = session_context_.Snapshot().active;
  session_context_.Cancel();
  if (was_active) {
    const std::uint64_t revision =
        session_revision_.fetch_add(1, std::memory_order_acq_rel) + 1;
    PublishSessionStatus(ToAidlStatus(session_context_.Snapshot(), revision));
  }
}

bool AgentBinderService::IsAuthorizedCaller() const {
  const std::int32_t caller = CallingUid();
  if (caller == kRootUid || caller == kSystemUid) {
    return true;
  }
  std::lock_guard lock(mutex_);
  return registered_bridge_uid_ >= 0 && caller == registered_bridge_uid_;
}

aidl::com::example::globalagent::SessionStatus
AgentBinderService::ToAidlStatus(const SessionSnapshot &snapshot,
                                 std::uint64_t revision) {
  constexpr std::uint64_t kMaxAidlLong =
      static_cast<std::uint64_t>(std::numeric_limits<std::int64_t>::max());
  aidl::com::example::globalagent::SessionStatus status;
  status.protocolVersion =
      aidl::com::example::globalagent::IAgentService::PROTOCOL_VERSION;
  status.revision =
      static_cast<std::int64_t>(std::min(revision, kMaxAidlLong));
  status.sessionId =
      static_cast<std::int64_t>(std::min(snapshot.id, kMaxAidlLong));
  status.source = static_cast<std::int32_t>(snapshot.source);
  status.startedNanos =
      static_cast<std::int64_t>(std::min(snapshot.started_ns, kMaxAidlLong));
  status.displayId = snapshot.display_id;
  status.state = static_cast<std::int32_t>(snapshot.state);
  status.userConfirmed = snapshot.user_confirmed;
  status.transcriptSequence = static_cast<std::int64_t>(
      std::min(snapshot.transcript_sequence, kMaxAidlLong));
  status.transcriptFinal = snapshot.transcript_final;
  status.active = snapshot.active;
  return status;
}

void AgentBinderService::PublishSessionStatus(
    const aidl::com::example::globalagent::SessionStatus &status) const {
  const auto current_bridge = bridge();
  if (current_bridge != nullptr) {
    current_bridge->onSessionStateChanged(status);
  }
}

} // namespace global_agent::aosp
