#pragma once

#include <cstddef>
#include <cstdint>
#include <span>

namespace global_agent {

std::uint32_t Crc32(std::span<const std::uint8_t> data);
std::uint32_t Crc32(const void *data, std::size_t size);

} // namespace global_agent
