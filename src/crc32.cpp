#include "global_agent/crc32.h"

namespace global_agent {

std::uint32_t Crc32(std::span<const std::uint8_t> data) {
  std::uint32_t crc = 0xFFFFFFFFU;
  for (const std::uint8_t byte : data) {
    crc ^= byte;
    for (int bit = 0; bit < 8; ++bit) {
      const std::uint32_t mask = 0U - (crc & 1U);
      crc = (crc >> 1U) ^ (0xEDB88320U & mask);
    }
  }
  return ~crc;
}

std::uint32_t Crc32(const void *data, std::size_t size) {
  return Crc32(std::span(static_cast<const std::uint8_t *>(data), size));
}

} // namespace global_agent
