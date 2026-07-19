#pragma once

#include <chrono>
#include <cstddef>
#include <string>
#include <vector>

namespace global_agent {

struct CommandResult {
  bool started = false;
  bool timed_out = false;
  bool output_truncated = false;
  int exit_code = -1;
  std::string output;
  std::string error;
};

CommandResult RunCommand(const std::vector<std::string> &arguments,
                         std::chrono::milliseconds timeout,
                         std::size_t max_output_bytes = 256 * 1024);

} // namespace global_agent
