#include "global_agent/dumpsys_parser.h"

#include <algorithm>
#include <cctype>
#include <charconv>
#include <optional>
#include <string>
#include <vector>

#include "global_agent/hash.h"

namespace global_agent {
namespace {

std::vector<std::string_view> Lines(std::string_view text) {
  std::vector<std::string_view> lines;
  std::size_t start = 0;
  while (start <= text.size()) {
    const std::size_t end = text.find('\n', start);
    lines.push_back(text.substr(start, end == std::string_view::npos
                                           ? text.size() - start
                                           : end - start));
    if (end == std::string_view::npos)
      break;
    start = end + 1;
  }
  return lines;
}

std::string_view TrimToken(std::string_view token) {
  while (!token.empty() && (token.front() == '{' || token.front() == '[' ||
                            token.front() == '(' || token.front() == ':')) {
    token.remove_prefix(1);
  }
  while (!token.empty() &&
         (token.back() == '}' || token.back() == ']' || token.back() == ')' ||
          token.back() == ',' || token.back() == ':')) {
    token.remove_suffix(1);
  }
  return token;
}

std::optional<std::string> FindComponent(std::string_view line) {
  std::size_t offset = 0;
  while (offset < line.size()) {
    while (offset < line.size() &&
           std::isspace(static_cast<unsigned char>(line[offset]))) {
      ++offset;
    }
    const std::size_t end = line.find_first_of(" \t\r\n", offset);
    const std::string_view raw =
        line.substr(offset, end == std::string_view::npos ? line.size() - offset
                                                          : end - offset);
    const std::string_view token = TrimToken(raw);
    if (token.find('/') != std::string_view::npos &&
        token.find('=') == std::string_view::npos) {
      return std::string(token);
    }
    if (end == std::string_view::npos)
      break;
    offset = end + 1;
  }
  return std::nullopt;
}

std::optional<std::int32_t> ParsePidAfter(std::string_view line,
                                          std::string_view marker) {
  const std::size_t position = line.find(marker);
  if (position == std::string_view::npos)
    return std::nullopt;
  std::size_t begin = position + marker.size();
  while (begin < line.size() &&
         !std::isdigit(static_cast<unsigned char>(line[begin]))) {
    ++begin;
  }
  std::size_t end = begin;
  while (end < line.size() &&
         std::isdigit(static_cast<unsigned char>(line[end]))) {
    ++end;
  }
  if (begin == end)
    return std::nullopt;
  std::int32_t pid = -1;
  const auto parsed =
      std::from_chars(line.data() + begin, line.data() + end, pid);
  if (parsed.ec != std::errc())
    return std::nullopt;
  return pid;
}

std::optional<std::int32_t> ParseSessionPid(std::string_view line) {
  constexpr std::string_view marker = "Session{";
  const std::size_t position = line.find(marker);
  if (position == std::string_view::npos)
    return std::nullopt;
  std::size_t cursor = position + marker.size();
  cursor = line.find_first_of(" \t", cursor);
  if (cursor == std::string_view::npos)
    return std::nullopt;
  while (cursor < line.size() &&
         std::isspace(static_cast<unsigned char>(line[cursor]))) {
    ++cursor;
  }
  const std::size_t end = line.find(':', cursor);
  if (end == std::string_view::npos || end == cursor)
    return std::nullopt;
  std::int32_t pid = -1;
  const auto parsed =
      std::from_chars(line.data() + cursor, line.data() + end, pid);
  if (parsed.ec != std::errc() || parsed.ptr != line.data() + end) {
    return std::nullopt;
  }
  return pid;
}

bool IsHexIdentity(std::string_view token) {
  if (token.size() < 6)
    return false;
  std::size_t start = token.starts_with("0x") ? 2 : 0;
  if (token.size() - start < 6)
    return false;
  return std::all_of(token.begin() + static_cast<std::ptrdiff_t>(start),
                     token.end(), [](char value) {
                       return std::isxdigit(static_cast<unsigned char>(value));
                     });
}

} // namespace

DumpsysMetadata ParseActivityAndWindowDumps(std::string_view activity_dump,
                                            std::string_view window_dump) {
  DumpsysMetadata result;
  const auto activity_lines = Lines(activity_dump);
  for (const std::string_view line : activity_lines) {
    if (line.find("topResumedActivity") == std::string_view::npos &&
        line.find("mResumedActivity") == std::string_view::npos &&
        line.find("ACTIVITY ") == std::string_view::npos) {
      continue;
    }
    if (const auto component = FindComponent(line); component.has_value()) {
      result.component = *component;
      result.has_component = true;
      result.window.component_hash = HashString(*component);
    }
    if (const auto pid = ParsePidAfter(line, "pid="); pid.has_value()) {
      result.window.focused_pid = *pid;
    }
    if (result.has_component)
      break;
  }

  const auto window_lines = Lines(window_dump);
  for (std::size_t index = 0; index < window_lines.size(); ++index) {
    const std::string_view line = window_lines[index];
    if (line.find("mCurrentFocus=") == std::string_view::npos &&
        line.find("mFocusedApp=") == std::string_view::npos) {
      continue;
    }
    if (const auto component = FindComponent(line); component.has_value()) {
      result.component = *component;
      result.has_component = true;
      result.window.component_hash = HashString(*component);
    }
    const std::size_t limit = std::min(window_lines.size(), index + 48);
    for (std::size_t cursor = index; cursor < limit; ++cursor) {
      if (const auto pid = ParsePidAfter(window_lines[cursor], "pid=");
          pid.has_value()) {
        result.window.focused_pid = *pid;
        break;
      }
      if (const auto pid = ParseSessionPid(window_lines[cursor]);
          pid.has_value()) {
        result.window.focused_pid = *pid;
        break;
      }
    }
    break;
  }
  return result;
}

std::uint64_t HashNormalizedViewDump(std::string_view activity_top_dump) {
  const auto lines = Lines(activity_top_dump);
  bool in_view_section = false;
  std::uint64_t hash = kFnvOffsetBasis;
  std::size_t hashed_tokens = 0;
  for (const std::string_view line : lines) {
    if (!in_view_section &&
        (line.find("View Hierarchy") != std::string_view::npos ||
         line.find("ViewRoot") != std::string_view::npos)) {
      in_view_section = true;
    }
    if (!in_view_section)
      continue;

    std::size_t offset = 0;
    while (offset < line.size()) {
      while (offset < line.size() &&
             std::isspace(static_cast<unsigned char>(line[offset]))) {
        ++offset;
      }
      const std::size_t end = line.find_first_of(" \t\r\n", offset);
      const std::string_view token = TrimToken(line.substr(
          offset,
          end == std::string_view::npos ? line.size() - offset : end - offset));
      if (!token.empty() && !IsHexIdentity(token) &&
          token.find("mLast") == std::string_view::npos &&
          token.find("time=") == std::string_view::npos) {
        hash = HashBytes(token.data(), token.size(), hash);
        ++hashed_tokens;
      }
      if (end == std::string_view::npos)
        break;
      offset = end + 1;
    }
  }
  return hashed_tokens == 0 ? 0 : hash;
}

} // namespace global_agent
