#pragma once

#include <cstddef>
#include <cstdint>
#include <string>

#include "global_agent/types.h"

namespace global_agent {

inline constexpr std::size_t kMaxGestureFrames = 256;
inline constexpr std::size_t kMaxGesturePointers = 5;
inline constexpr std::uint32_t kMaxGestureDurationMs = 2000;

bool ValidateGesture(const Gesture &gesture, std::string *error,
                     std::size_t max_frames = kMaxGestureFrames,
                     std::size_t max_pointers = kMaxGesturePointers,
                     std::uint32_t max_duration_ms = kMaxGestureDurationMs);

} // namespace global_agent
