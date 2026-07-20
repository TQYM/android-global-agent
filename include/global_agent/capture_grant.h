#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <optional>

namespace global_agent {

using CaptureTokenDigest = std::array<std::uint8_t, 32>;
using ServiceInstanceId = std::array<std::uint8_t, 16>;
using FocusDigest = std::array<std::uint8_t, 32>;

struct CaptureRect {
  std::int32_t left = 0;
  std::int32_t top = 0;
  std::int32_t right = 0;
  std::int32_t bottom = 0;

  bool operator==(const CaptureRect &) const = default;
};

struct CaptureGrantRecord {
  CaptureTokenDigest token_digest{};
  ServiceInstanceId service_instance_id{};
  std::uint64_t grant_id = 0;
  std::int32_t grantee_uid = -1;
  std::uint64_t capability_id = 0;
  std::uint64_t session_id = 0;
  std::uint64_t revision = 0;
  std::uint64_t focus_epoch = 0;
  FocusDigest focus_digest{};
  std::int32_t display_id = -1;
  CaptureRect crop{};
  std::uint64_t expires_at_elapsed_ns = 0;
  std::uint32_t max_image_bytes = 0;
  std::uint32_t redaction_policy_version = 0;
};

struct CaptureGrantContext {
  ServiceInstanceId service_instance_id{};
  std::int32_t caller_uid = -1;
  std::uint64_t capability_id = 0;
  std::uint64_t session_id = 0;
  std::uint64_t revision = 0;
  std::uint64_t focus_epoch = 0;
  FocusDigest focus_digest{};
  std::int32_t display_id = -1;
  std::uint64_t now_elapsed_ns = 0;
};

enum class CaptureGrantResult {
  kConsumed,
  kNotFound,
  kUnauthorized,
  kStale,
  kExpired,
};

// All public methods are thread-safe. mutex_ protects the outstanding grant
// and makes successful consumption an atomic ownership transfer before I/O.
class CaptureGrantStore {
public:
  static constexpr std::uint64_t kMaxTtlNs = 3'000'000'000ULL;
  static constexpr std::uint32_t kMaxImageBytes = 2U * 1024U * 1024U;

  bool Issue(const CaptureGrantRecord &record, std::uint64_t now_elapsed_ns);

  CaptureGrantResult ConsumeBeforeIo(
      const CaptureTokenDigest &presented_token_digest,
      const CaptureGrantContext &context, CaptureGrantRecord *consumed);

  void RevokeAll();
  bool HasOutstandingGrant() const;

private:
  static bool ConstantTimeEqual(const std::uint8_t *left,
                                const std::uint8_t *right,
                                std::size_t size);
  static void ClearTokenDigest(CaptureTokenDigest *digest);
  static bool IsValidRecord(const CaptureGrantRecord &record,
                            std::uint64_t now_elapsed_ns);

  mutable std::mutex mutex_;
  std::optional<CaptureGrantRecord> outstanding_;
};

} // namespace global_agent
