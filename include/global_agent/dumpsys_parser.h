#pragma once

#include <cstdint>
#include <string>
#include <string_view>

#include "global_agent/types.h"

namespace global_agent {

struct DumpsysMetadata {
  WindowMetadata window;
  std::string component;
  bool has_component = false;
};

DumpsysMetadata ParseActivityAndWindowDumps(std::string_view activity_dump,
                                            std::string_view window_dump);

std::uint64_t HashNormalizedViewDump(std::string_view activity_top_dump);

} // namespace global_agent
