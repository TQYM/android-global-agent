#pragma once

#include <cstdint>
#include <optional>
#include <string_view>

namespace global_agent {

inline constexpr std::string_view kBridgeSelinuxSid =
    "u:r:global_agent_bridge:s0";
inline constexpr std::int32_t kAndroidAppUidStart = 10'000;
inline constexpr std::int32_t kAndroidAppUidEnd = 19'999;
inline constexpr std::int32_t kAndroidPerUserRange = 100'000;
inline constexpr std::uint32_t kAndroidMaxMlsCategory = 1023;

[[nodiscard]] bool IsAuthorizedBridgeIdentity(
    std::int32_t calling_uid, std::string_view calling_sid,
    std::optional<std::int32_t> pinned_uid);

} // namespace global_agent
