#include "agent_binder_service.h"

#include <android/binder_manager.h>
#include <android/binder_process.h>

#include "global_agent/hash.h"

namespace global_agent::aosp {
namespace {

constexpr const char *kServiceName = "global_agent";

} // namespace

std::shared_ptr<AgentBinderService>
AgentBinderService::Register(std::string *error) {
  ABinderProcess_setThreadPoolMaxThreadCount(2);
  ABinderProcess_startThreadPool();
  auto service = ndk::SharedRefBase::make<AgentBinderService>();
  const binder_status_t status =
      AServiceManager_addService(service->asBinder().get(), kServiceName);
  if (status != STATUS_OK) {
    if (error != nullptr) {
      *error = "failed to register global_agent binder service: " +
               std::to_string(status);
    }
    return nullptr;
  }
  return service;
}

ndk::ScopedAStatus AgentBinderService::registerBridge(
    const std::shared_ptr<aidl::com::example::globalagent::IAgentBridge>
        &bridge) {
  if (bridge == nullptr) {
    return ndk::ScopedAStatus::fromExceptionCodeWithMessage(
        EX_ILLEGAL_ARGUMENT, "bridge must not be null");
  }
  std::lock_guard lock(mutex_);
  bridge_ = bridge;
  return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus
AgentBinderService::notifySettingChanged(const std::string &key) {
  if (key.empty() || key.size() > 128) {
    return ndk::ScopedAStatus::fromExceptionCodeWithMessage(
        EX_ILLEGAL_ARGUMENT, "invalid setting key");
  }
  setting_epoch_.fetch_add(1, std::memory_order_acq_rel);
  return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus AgentBinderService::notifyWindowChanged(
    const aidl::com::example::globalagent::WindowSnapshot &snapshot) {
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

std::shared_ptr<aidl::com::example::globalagent::IAgentBridge>
AgentBinderService::bridge() const {
  std::lock_guard lock(mutex_);
  return bridge_;
}

WindowMetadata AgentBinderService::window_metadata() const {
  std::lock_guard lock(mutex_);
  return window_metadata_;
}

} // namespace global_agent::aosp
