#include "global_agent/capture_grant.h"

#include <algorithm>

namespace global_agent {
namespace {

template <std::size_t Size>
bool IsAllZero(const std::array<std::uint8_t, Size> &value) {
  return std::all_of(value.begin(), value.end(),
                     [](std::uint8_t byte) { return byte == 0; });
}

} // namespace

bool CaptureGrantStore::Issue(const CaptureGrantRecord &record,
                              std::uint64_t now_elapsed_ns) {
  if (!IsValidRecord(record, now_elapsed_ns)) {
    return false;
  }
  std::lock_guard lock(mutex_);
  if (outstanding_.has_value()) {
    return false;
  }
  outstanding_ = record;
  return true;
}

CaptureGrantResult CaptureGrantStore::ConsumeBeforeIo(
    const CaptureTokenDigest &presented_token_digest,
    const CaptureGrantContext &context, CaptureGrantRecord *consumed) {
  std::lock_guard lock(mutex_);
  if (!outstanding_.has_value() ||
      !ConstantTimeEqual(presented_token_digest.data(),
                         outstanding_->token_digest.data(),
                         presented_token_digest.size())) {
    return CaptureGrantResult::kNotFound;
  }

  const CaptureGrantRecord record = *outstanding_;
  if (context.caller_uid != record.grantee_uid ||
      context.capability_id != record.capability_id) {
    return CaptureGrantResult::kUnauthorized;
  }
  if (context.now_elapsed_ns >= record.expires_at_elapsed_ns) {
    ClearTokenDigest(&outstanding_->token_digest);
    outstanding_.reset();
    return CaptureGrantResult::kExpired;
  }
  if (!ConstantTimeEqual(context.service_instance_id.data(),
                         record.service_instance_id.data(),
                         context.service_instance_id.size()) ||
      context.session_id != record.session_id ||
      context.revision != record.revision ||
      context.focus_epoch != record.focus_epoch ||
      !ConstantTimeEqual(context.focus_digest.data(),
                         record.focus_digest.data(),
                         context.focus_digest.size()) ||
      context.display_id != record.display_id) {
    ClearTokenDigest(&outstanding_->token_digest);
    outstanding_.reset();
    return CaptureGrantResult::kStale;
  }

  if (consumed != nullptr) {
    *consumed = record;
    consumed->token_digest.fill(0);
  }
  ClearTokenDigest(&outstanding_->token_digest);
  outstanding_.reset();
  return CaptureGrantResult::kConsumed;
}

void CaptureGrantStore::RevokeAll() {
  std::lock_guard lock(mutex_);
  if (outstanding_.has_value()) {
    ClearTokenDigest(&outstanding_->token_digest);
  }
  outstanding_.reset();
}

bool CaptureGrantStore::HasOutstandingGrant() const {
  std::lock_guard lock(mutex_);
  return outstanding_.has_value();
}

bool CaptureGrantStore::ConstantTimeEqual(const std::uint8_t *left,
                                          const std::uint8_t *right,
                                          std::size_t size) {
  const volatile std::uint8_t *volatile_left = left;
  const volatile std::uint8_t *volatile_right = right;
  std::uint8_t difference = 0;
  for (std::size_t index = 0; index < size; ++index) {
    difference = static_cast<std::uint8_t>(difference |
        (volatile_left[index] ^ volatile_right[index]));
  }
  return difference == 0;
}

void CaptureGrantStore::ClearTokenDigest(CaptureTokenDigest *digest) {
  volatile std::uint8_t *bytes = digest->data();
  for (std::size_t index = 0; index < digest->size(); ++index) {
    bytes[index] = 0;
  }
}

bool CaptureGrantStore::IsValidRecord(const CaptureGrantRecord &record,
                                      std::uint64_t now_elapsed_ns) {
  if (IsAllZero(record.token_digest) ||
      IsAllZero(record.service_instance_id) ||
      IsAllZero(record.focus_digest) || record.grant_id == 0 ||
      record.grantee_uid < 0 || record.capability_id == 0 ||
      record.session_id == 0 || record.display_id < 0 ||
      record.crop.left < 0 || record.crop.top < 0 ||
      record.crop.right <= record.crop.left ||
      record.crop.bottom <= record.crop.top || record.max_image_bytes == 0 ||
      record.max_image_bytes > kMaxImageBytes ||
      record.redaction_policy_version == 0 ||
      record.expires_at_elapsed_ns <= now_elapsed_ns) {
    return false;
  }
  return record.expires_at_elapsed_ns - now_elapsed_ns <= kMaxTtlNs;
}

} // namespace global_agent
