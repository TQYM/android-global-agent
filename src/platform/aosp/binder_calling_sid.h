#pragma once

#include <android/binder_ibinder.h>

#if __has_include(<android/binder_ibinder_platform.h>)
#include <android/binder_ibinder_platform.h>
#define GLOBAL_AGENT_HAS_BINDER_CALLING_SID 1
#else
#define GLOBAL_AGENT_HAS_BINDER_CALLING_SID 0
#endif

namespace global_agent::aosp {

inline bool EnableCallingSid(AIBinder *binder) {
#if GLOBAL_AGENT_HAS_BINDER_CALLING_SID
  if (binder == nullptr || AIBinder_setRequestingSid == nullptr) {
    return false;
  }
  AIBinder_setRequestingSid(binder, true);
  return true;
#else
  (void)binder;
  return false;
#endif
}

inline const char *CallingSid() {
#if GLOBAL_AGENT_HAS_BINDER_CALLING_SID
  if (AIBinder_getCallingSid == nullptr) {
    return nullptr;
  }
  return AIBinder_getCallingSid();
#else
  return nullptr;
#endif
}

} // namespace global_agent::aosp
