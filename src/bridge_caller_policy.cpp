#include "global_agent/bridge_caller_policy.h"

namespace global_agent {
namespace {

bool ParseCategory(std::string_view suffix, std::size_t *offset,
                   std::uint32_t *category) {
  if (suffix.empty() || *offset >= suffix.size() ||
      suffix[(*offset)++] != 'c') {
    return false;
  }
  const std::size_t digits = *offset;
  std::uint32_t value = 0;
  while (*offset < suffix.size() && suffix[*offset] >= '0' &&
         suffix[*offset] <= '9') {
    if (*offset > digits && suffix[digits] == '0') {
      return false;
    }
    value = value * 10 +
        static_cast<std::uint32_t>(suffix[*offset] - '0');
    if (value > kAndroidMaxMlsCategory) {
      return false;
    }
    ++*offset;
  }
  if (digits == *offset) {
    return false;
  }
  *category = value;
  return true;
}

bool IsBridgeSid(std::string_view sid) {
  if (!sid.starts_with(kBridgeSelinuxSid)) {
    return false;
  }
  std::string_view suffix = sid.substr(kBridgeSelinuxSid.size());
  if (suffix.empty()) {
    return true;
  }
  if (!suffix.starts_with(":c")) {
    return false;
  }
  suffix.remove_prefix(1);
  std::size_t offset = 0;
  std::optional<std::uint32_t> previous_end;
  while (offset < suffix.size()) {
    std::uint32_t start = 0;
    if (!ParseCategory(suffix, &offset, &start)) {
      return false;
    }
    std::uint32_t end = start;
    if (offset < suffix.size() && suffix[offset] == '.') {
      ++offset;
      if (!ParseCategory(suffix, &offset, &end) || end <= start) {
        return false;
      }
    }
    if (previous_end.has_value() && start <= *previous_end) {
      return false;
    }
    previous_end = end;
    if (offset == suffix.size()) {
      return true;
    }
    if (suffix[offset] != ',') {
      return false;
    }
    ++offset;
  }
  return false;
}

} // namespace

bool IsAuthorizedBridgeIdentity(
    std::int32_t calling_uid, std::string_view calling_sid,
    std::optional<std::int32_t> pinned_uid) {
  // Binder supplies the kernel-assigned SID. Android app MLS categories vary
  // by user/app, while the SELinux user, role, type and sensitivity stay fixed.
  if (calling_uid < 0) {
    return false;
  }
  const std::int32_t app_id = calling_uid % kAndroidPerUserRange;
  return app_id >= kAndroidAppUidStart && app_id <= kAndroidAppUidEnd &&
      IsBridgeSid(calling_sid) &&
      (!pinned_uid.has_value() || *pinned_uid == calling_uid);
}

} // namespace global_agent
