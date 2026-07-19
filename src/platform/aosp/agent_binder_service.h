#pragma once

#include <atomic>
#include <memory>
#include <mutex>
#include <string>

#include <aidl/com/example/globalagent/BnAgentService.h>
#include <aidl/com/example/globalagent/IAgentBridge.h>
#include <aidl/com/example/globalagent/WindowSnapshot.h>

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

  std::shared_ptr<aidl::com::example::globalagent::IAgentBridge> bridge() const;
  [[nodiscard]] WindowMetadata window_metadata() const;
  [[nodiscard]] std::uint64_t setting_epoch() const {
    return setting_epoch_.load(std::memory_order_acquire);
  }

private:
  mutable std::mutex mutex_;
  std::shared_ptr<aidl::com::example::globalagent::IAgentBridge> bridge_;
  WindowMetadata window_metadata_;
  std::atomic<std::uint64_t> setting_epoch_{0};
};

} // namespace global_agent::aosp
