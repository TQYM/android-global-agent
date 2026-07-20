#pragma once

#include <cstdint>
#include <memory>
#include <mutex>
#include <string>

#include <aidl/com/example/globalagent/v2/BnPlatformAgentV2.h>

namespace global_agent::aosp {

class V2PlatformAgentService final
    : public aidl::com::example::globalagent::v2::BnPlatformAgentV2 {
public:
  V2PlatformAgentService() = default;
  static std::shared_ptr<V2PlatformAgentService> Register(std::string *error);

  ndk::ScopedAStatus startSessionPrivileged(
      const aidl::com::example::globalagent::v2::SessionStartRequest &request,
      const std::shared_ptr<
          aidl::com::example::globalagent::v2::IAgentSessionCallback> &callback,
      aidl::com::example::globalagent::v2::SessionHandle *result) override;
  ndk::ScopedAStatus submitTranscriptPrivileged(
      std::int64_t session_id, std::int64_t expected_revision,
      std::int64_t sequence, bool is_final, const std::string &text,
      aidl::com::example::globalagent::v2::SessionStatusV2 *result) override;
  ndk::ScopedAStatus notifyFocusChangedPrivileged(
      const aidl::com::example::globalagent::v2::FocusIdentity &focus,
      aidl::com::example::globalagent::v2::SessionStatusV2 *result) override;
  ndk::ScopedAStatus issueCaptureGrantFor(
      std::int64_t session_id, std::int64_t expected_revision,
      std::int32_t grantee_uid, std::int64_t capability_id,
      const aidl::com::example::globalagent::v2::CaptureSpec &spec,
      aidl::com::example::globalagent::v2::CaptureGrant *result) override;
  ndk::ScopedAStatus captureOnceFor(
      const std::vector<std::uint8_t> &grant_token,
      std::int32_t grantee_uid, std::int64_t capability_id,
      aidl::com::example::globalagent::v2::PerceptionEnvelope *result) override;
  ndk::ScopedAStatus validatePlanFor(
      const aidl::com::example::globalagent::v2::ActionPlan &plan,
      std::int32_t grantee_uid, std::int64_t capability_id,
      aidl::com::example::globalagent::v2::PlanValidation *result) override;
  ndk::ScopedAStatus approvePlanPrivileged(
      std::int64_t session_id, std::int64_t expected_revision,
      std::int64_t server_plan_id, const std::vector<std::uint8_t> &plan_digest,
      aidl::com::example::globalagent::v2::ExecutionGrant *result) override;
  ndk::ScopedAStatus injectInputPrivileged(
      const aidl::com::example::globalagent::v2::ApprovedInput &approved,
      aidl::com::example::globalagent::v2::ActionReceipt *result) override;
  ndk::ScopedAStatus cancelSessionPrivileged(
      std::int64_t session_id, std::int64_t expected_revision,
      std::int32_t reason,
      aidl::com::example::globalagent::v2::SessionStatusV2 *result) override;
  ndk::ScopedAStatus cancelAllPrivileged(std::int32_t reason) override;
  ndk::ScopedAStatus getSessionStatusPrivileged(
      std::int64_t session_id,
      aidl::com::example::globalagent::v2::SessionStatusV2 *result) override;

private:
  bool IsAuthorizedBridgeCaller() const;
  ndk::ScopedAStatus Unsupported() const;

  mutable std::mutex identity_mutex_;
  mutable std::int32_t bridge_uid_ = -1;
};

} // namespace global_agent::aosp
