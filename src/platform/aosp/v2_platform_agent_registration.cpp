#include "v2_platform_agent_service.h"

#include <android/binder_manager.h>
#include <log/log.h>

#include "binder_calling_sid.h"

namespace global_agent::aosp {
namespace {

constexpr const char *kV2ServiceName = "global_agent_v2";

} // namespace

std::shared_ptr<V2PlatformAgentService>
V2PlatformAgentService::Register(std::string *error) {
  auto service = ndk::SharedRefBase::make<V2PlatformAgentService>();
  const auto binder = service->asBinder();
  // Enable kernel-provided transaction SIDs before the local binder is
  // published; calling this after addService would violate the platform API.
  if (!EnableCallingSid(binder.get())) {
    if (error != nullptr) {
      *error = "platform Binder calling-SID support is unavailable for v2";
    }
    return nullptr;
  }
  const binder_status_t status =
      AServiceManager_addService(binder.get(), kV2ServiceName);
  if (status != STATUS_OK) {
    if (error != nullptr) {
      *error = "failed to register global_agent_v2 Binder service: " +
          std::to_string(status);
    }
    return nullptr;
  }
  ALOGI("registered fail-closed global_agent_v2 protocol boundary");
  return service;
}

} // namespace global_agent::aosp
