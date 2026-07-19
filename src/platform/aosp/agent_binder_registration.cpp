#include "agent_binder_service.h"

#include <android/binder_manager.h>
#include <android/binder_process.h>

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

} // namespace global_agent::aosp
