#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace global_agent {

using NodeId = std::uint64_t;

struct Rect {
  std::int32_t left = 0;
  std::int32_t top = 0;
  std::int32_t right = 0;
  std::int32_t bottom = 0;
};

struct WindowMetadata {
  std::uint64_t component_hash = 0;
  std::uint64_t view_hash = 0;
  std::int32_t focused_pid = -1;
  std::uint32_t display_id = 0;
  std::uint16_t rotation = 0;
  Rect bounds;
};

struct Perception {
  std::uint64_t monotonic_ns = 0;
  // One bounded sample from the current step, not a video stream.
  std::uint64_t single_frame_visual_hash = 0;
  std::uint16_t confidence_milli = 0;
  WindowMetadata window;
};

struct PointF {
  float x = 0.0F;
  float y = 0.0F;
};

struct PointerSample {
  std::int32_t pointer_id = 0;
  PointF position;
};

enum class GestureAction : std::uint8_t {
  kDown = 0,
  kPointerDown = 1,
  kMove = 2,
  kPointerUp = 3,
  kUp = 4,
  kCancel = 5,
};

struct GestureFrame {
  GestureAction action = GestureAction::kMove;
  std::uint8_t action_index = 0;
  std::uint32_t elapsed_ms = 0;
  std::vector<PointerSample> pointers;
};

struct Gesture {
  std::uint64_t action_id = 0;
  std::uint32_t display_id = 0;
  std::vector<GestureFrame> frames;
};

enum class ActionOutcome : std::uint8_t {
  kUnknown = 0,
  kSucceeded = 1,
  kNoVisibleChange = 2,
  kRejected = 3,
  kTimedOut = 4,
};

struct StateNode {
  NodeId id = 0;
  std::uint64_t visual_hash = 0;
  std::uint64_t component_hash = 0;
  std::uint64_t view_hash = 0;
  std::uint64_t last_seen_ns = 0;
  std::int32_t focused_pid = -1;
  std::uint32_t display_id = 0;
  std::uint16_t rotation = 0;
  std::uint16_t confidence_milli = 0;
};

struct StateEdge {
  NodeId from = 0;
  NodeId to = 0;
  std::uint64_t action_id = 0;
  std::uint64_t timestamp_ns = 0;
  std::uint32_t latency_ms = 0;
  ActionOutcome outcome = ActionOutcome::kUnknown;
};

} // namespace global_agent
