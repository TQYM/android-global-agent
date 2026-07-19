#include "global_agent/gesture_validation.h"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <set>
#include <string>

namespace global_agent {
namespace {

bool Fail(std::string *error, const char *message) {
  if (error != nullptr) {
    *error = message;
  }
  return false;
}

std::set<std::int32_t> PointerIds(const GestureFrame &frame) {
  std::set<std::int32_t> ids;
  for (const PointerSample &pointer : frame.pointers) {
    ids.insert(pointer.pointer_id);
  }
  return ids;
}

} // namespace

bool ValidateGesture(const Gesture &gesture, std::string *error,
                     std::size_t max_frames, std::size_t max_pointers,
                     std::uint32_t max_duration_ms) {
  if (gesture.frames.size() < 2 || gesture.frames.size() > max_frames) {
    return Fail(error, "gesture frame count is outside limits");
  }
  if (gesture.frames.front().action != GestureAction::kDown ||
      gesture.frames.back().action != GestureAction::kUp) {
    return Fail(error, "gesture must start with DOWN and end with UP");
  }

  std::set<std::int32_t> active;
  std::uint32_t previous_time = 0;
  for (std::size_t frame_index = 0; frame_index < gesture.frames.size();
       ++frame_index) {
    const GestureFrame &frame = gesture.frames[frame_index];
    if (frame.pointers.empty() || frame.pointers.size() > max_pointers) {
      return Fail(error, "gesture pointer count is outside limits");
    }
    if ((frame_index != 0 && frame.elapsed_ms < previous_time) ||
        frame.elapsed_ms > max_duration_ms) {
      return Fail(error, "gesture times are not monotonic or exceed limits");
    }
    if (frame.action_index >= frame.pointers.size()) {
      return Fail(error, "gesture action index is invalid");
    }

    const std::set<std::int32_t> ids = PointerIds(frame);
    if (ids.size() != frame.pointers.size()) {
      return Fail(error, "gesture contains duplicate pointer ids");
    }
    for (const PointerSample &pointer : frame.pointers) {
      if (pointer.pointer_id < 0 || !std::isfinite(pointer.position.x) ||
          !std::isfinite(pointer.position.y) ||
          std::fabs(pointer.position.x) > 100000.0F ||
          std::fabs(pointer.position.y) > 100000.0F) {
        return Fail(error, "gesture contains invalid pointer coordinates");
      }
    }

    switch (frame.action) {
    case GestureAction::kDown:
      if (frame_index != 0 || frame.pointers.size() != 1 || !active.empty()) {
        return Fail(error, "DOWN must initialize one pointer");
      }
      active.insert(frame.pointers[0].pointer_id);
      break;
    case GestureAction::kPointerDown: {
      if (frame.pointers.size() != active.size() + 1 ||
          !std::includes(ids.begin(), ids.end(), active.begin(),
                         active.end())) {
        return Fail(error, "POINTER_DOWN has an invalid pointer set");
      }
      const std::int32_t added = frame.pointers[frame.action_index].pointer_id;
      if (active.contains(added)) {
        return Fail(error, "POINTER_DOWN index is not the new pointer");
      }
      active.insert(added);
      break;
    }
    case GestureAction::kMove:
      if (ids != active) {
        return Fail(error, "MOVE changed the active pointer set");
      }
      break;
    case GestureAction::kPointerUp:
      if (ids != active || active.size() < 2) {
        return Fail(error, "POINTER_UP has an invalid pointer set");
      }
      active.erase(frame.pointers[frame.action_index].pointer_id);
      break;
    case GestureAction::kUp:
      if (frame_index + 1 != gesture.frames.size() || active.size() != 1 ||
          ids != active) {
        return Fail(error, "UP must terminate the final pointer");
      }
      active.clear();
      break;
    case GestureAction::kCancel:
      return Fail(error, "CANCEL is reserved for the injector recovery path");
    }
    previous_time = frame.elapsed_ms;
  }
  return active.empty() ? true : Fail(error, "gesture left active pointers");
}

} // namespace global_agent
