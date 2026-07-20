#include "v2_platform_agent_service.h"

#include <android/binder_ibinder.h>

#include <optional>
#include <string_view>

#include "binder_calling_sid.h"
#include "global_agent/bridge_caller_policy.h"

namespace global_agent::aosp {
namespace {

ndk::ScopedAStatus SecurityError() {
  return ndk::ScopedAStatus::fromExceptionCodeWithMessage(
      EX_SECURITY, "v2 caller is not the pinned bridge UID");
}

} // namespace

bool V2PlatformAgentService::IsAuthorizedBridgeCaller() const {
  const std::int32_t caller =
      static_cast<std::int32_t>(AIBinder_getCallingUid());
  const char *const sid = CallingSid();
  if (sid == nullptr) {
    return false;
  }
  std::lock_guard lock(identity_mutex_);
  const std::optional<std::int32_t> pinned_uid = bridge_uid_ >= 0
      ? std::optional<std::int32_t>(bridge_uid_)
      : std::nullopt;
  if (!IsAuthorizedBridgeIdentity(
          caller, std::string_view(sid), pinned_uid)) {
    return false;
  }
  if (bridge_uid_ < 0) {
    bridge_uid_ = caller;
  }
  return true;
}

ndk::ScopedAStatus V2PlatformAgentService::Unsupported() const {
  if (!IsAuthorizedBridgeCaller()) {
    return SecurityError();
  }
  return ndk::ScopedAStatus::fromExceptionCodeWithMessage(
      EX_UNSUPPORTED_OPERATION,
      "protocol v2 execution remains disabled in the P1 build");
}

ndk::ScopedAStatus V2PlatformAgentService::startSessionPrivileged(
    const aidl::com::example::globalagent::v2::SessionStartRequest &,
    const std::shared_ptr<
        aidl::com::example::globalagent::v2::IAgentSessionCallback> &,
    aidl::com::example::globalagent::v2::SessionHandle *) {
  return Unsupported();
}

ndk::ScopedAStatus V2PlatformAgentService::submitTranscriptPrivileged(
    std::int64_t, std::int64_t, std::int64_t, bool, const std::string &,
    aidl::com::example::globalagent::v2::SessionStatusV2 *) {
  return Unsupported();
}

ndk::ScopedAStatus V2PlatformAgentService::notifyFocusChangedPrivileged(
    const aidl::com::example::globalagent::v2::FocusIdentity &,
    aidl::com::example::globalagent::v2::SessionStatusV2 *) {
  return Unsupported();
}

ndk::ScopedAStatus V2PlatformAgentService::issueCaptureGrantFor(
    std::int64_t, std::int64_t, std::int32_t, std::int64_t,
    const aidl::com::example::globalagent::v2::CaptureSpec &,
    aidl::com::example::globalagent::v2::CaptureGrant *) {
  return Unsupported();
}

ndk::ScopedAStatus V2PlatformAgentService::captureOnceFor(
    const std::vector<std::uint8_t> &, std::int32_t, std::int64_t,
    aidl::com::example::globalagent::v2::PerceptionEnvelope *) {
  return Unsupported();
}

ndk::ScopedAStatus V2PlatformAgentService::validatePlanFor(
    const aidl::com::example::globalagent::v2::ActionPlan &, std::int32_t,
    std::int64_t, aidl::com::example::globalagent::v2::PlanValidation *) {
  return Unsupported();
}

ndk::ScopedAStatus V2PlatformAgentService::approvePlanPrivileged(
    std::int64_t, std::int64_t, std::int64_t, const std::vector<std::uint8_t> &,
    aidl::com::example::globalagent::v2::ExecutionGrant *) {
  return Unsupported();
}

ndk::ScopedAStatus V2PlatformAgentService::injectInputPrivileged(
    const aidl::com::example::globalagent::v2::ApprovedInput &,
    aidl::com::example::globalagent::v2::ActionReceipt *) {
  return Unsupported();
}

ndk::ScopedAStatus V2PlatformAgentService::cancelSessionPrivileged(
    std::int64_t, std::int64_t, std::int32_t,
    aidl::com::example::globalagent::v2::SessionStatusV2 *) {
  return Unsupported();
}

ndk::ScopedAStatus V2PlatformAgentService::cancelAllPrivileged(std::int32_t) {
  return Unsupported();
}

ndk::ScopedAStatus V2PlatformAgentService::getSessionStatusPrivileged(
    std::int64_t,
    aidl::com::example::globalagent::v2::SessionStatusV2 *) {
  return Unsupported();
}

} // namespace global_agent::aosp
