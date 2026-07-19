#pragma once

#include <cstddef>
#include <string>

#include "global_agent/types.h"

namespace global_agent {

bool ValidateGesture(const Gesture &gesture, std::string *error,
                     std::size_t max_frames = 256, std::size_t max_pointers = 5,
                     std::uint32_t max_duration_ms = 2000);

} // namespace global_agent
