#pragma once

#include <cstddef>
#include <cstdint>
#include <string_view>

namespace global_agent {

constexpr std::uint64_t kFnvOffsetBasis = 14695981039346656037ULL;
constexpr std::uint64_t kFnvPrime = 1099511628211ULL;

inline std::uint64_t HashBytes(const void *data, std::size_t size,
                               std::uint64_t seed = kFnvOffsetBasis) {
  const auto *bytes = static_cast<const std::uint8_t *>(data);
  std::uint64_t hash = seed;
  for (std::size_t i = 0; i < size; ++i) {
    hash ^= bytes[i];
    hash *= kFnvPrime;
  }
  return hash;
}

inline std::uint64_t HashString(std::string_view value) {
  return HashBytes(value.data(), value.size());
}

inline std::uint64_t HashCombine(std::uint64_t left, std::uint64_t right) {
  return HashBytes(&right, sizeof(right), left ^ 0x9e3779b97f4a7c15ULL);
}

} // namespace global_agent
