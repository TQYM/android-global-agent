#include "agent_binder_service.h"

#include <android/binder_manager.h>
#include <android/binder_process.h>

#include "binder_calling_sid.h"

namespace global_agent::aosp {
namespace {

constexpr const char *kServiceName = "global_agent";

} // namespace

std::shared_ptr<AgentBinderService>
AgentBinderService::Register(std::string *error) {
  ABinderProcess_setThreadPoolMaxThreadCount(2);
  ABinderProcess_startThreadPool();
  auto service = ndk::SharedRefBase::make<AgentBinderService>();
  const auto binder = service->asBinder();
  // The platform API requires this while the binder is still local, before
  // AServiceManager_addService publishes it to other processes.
  if (!EnableCallingSid(binder.get())) {
    if (error != nullptr) {
      *error = "platform Binder calling-SID support is unavailable";
    }
    return nullptr;
  }
  const binder_status_t status =
      AServiceManager_addService(binder.get(), kServiceName);
  if (status != STATUS_OK) {
    if (error != nullptr) {
      *error = "failed to register global_agent binder service: " +
               std::to_string(status);
    }
    return nullptr;
  }
  return service;
}

} // namespace global_agent::aosp
