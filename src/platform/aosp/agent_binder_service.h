#pragma once

#include <atomic>
#include <memory>
#include <mutex>
#include <string>

#include <aidl/com/example/globalagent/BnAgentService.h>
#include <aidl/com/example/globalagent/IAgentBridge.h>
#include <aidl/com/example/globalagent/SessionStatus.h>
#include <aidl/com/example/globalagent/SessionTrigger.h>
#include <aidl/com/example/globalagent/TranscriptUpdate.h>
#include <aidl/com/example/globalagent/WindowSnapshot.h>

#include "global_agent/session_context.h"
#include "global_agent/types.h"

namespace global_agent::aosp {

class AgentBinderService final
    : public aidl::com::example::globalagent::BnAgentService {
public:
  static std::shared_ptr<AgentBinderService> Register(std::string *error);

  ndk::ScopedAStatus registerBridge(
      const std::shared_ptr<aidl::com::example::globalagent::IAgentBridge>
          &bridge) override;
  ndk::ScopedAStatus notifySettingChanged(const std::string &key) override;
  ndk::ScopedAStatus notifyWindowChanged(
      const aidl::com::example::globalagent::WindowSnapshot &snapshot) override;
  ndk::ScopedAStatus beginSession(
      const aidl::com::example::globalagent::SessionTrigger &trigger,
      aidl::com::example::globalagent::SessionStatus *status) override;
  ndk::ScopedAStatus submitTranscript(
      const aidl::com::example::globalagent::TranscriptUpdate &update,
      aidl::com::example::globalagent::SessionStatus *status) override;
  ndk::ScopedAStatus transitionSession(
      std::int64_t session_id, std::int32_t state,
      aidl::com::example::globalagent::SessionStatus *status) override;
  ndk::ScopedAStatus cancelSession(
      std::int64_t session_id,
      aidl::com::example::globalagent::SessionStatus *status) override;
  ndk::ScopedAStatus getSessionStatus(
      aidl::com::example::globalagent::SessionStatus *status) override;

  std::shared_ptr<aidl::com::example::globalagent::IAgentBridge> bridge() const;
  [[nodiscard]] WindowMetadata window_metadata() const;
  [[nodiscard]] std::uint64_t setting_epoch() const {
    return setting_epoch_.load(std::memory_order_acquire);
  }
  bool ExpireSession();
  void ResetSession();

private:
  [[nodiscard]] bool IsAuthorizedCaller() const;
  static aidl::com::example::globalagent::SessionStatus
  ToAidlStatus(const SessionSnapshot &snapshot, std::uint64_t revision);
  void PublishSessionStatus(
      const aidl::com::example::globalagent::SessionStatus &status) const;

  mutable std::mutex mutex_;
  mutable std::mutex session_mutex_;
  std::shared_ptr<aidl::com::example::globalagent::IAgentBridge> bridge_;
  std::int32_t registered_bridge_uid_ = -1;
  WindowMetadata window_metadata_;
  std::atomic<std::uint64_t> setting_epoch_{0};
  std::atomic<std::uint64_t> session_revision_{0};
  SessionContext session_context_;
};

} // namespace global_agent::aosp
