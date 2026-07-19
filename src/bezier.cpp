#include "global_agent/bezier.h"

#include <algorithm>
#include <cmath>
#include <limits>

namespace global_agent {
namespace {

float Distance(const PointF &left, const PointF &right) {
  return std::hypot(right.x - left.x, right.y - left.y);
}

float ClampUnit(float value) { return std::clamp(value, 0.0F, 1.0F); }

} // namespace

PointF EvaluateBezier(const CubicBezier &curve, float t) {
  t = ClampUnit(t);
  const float u = 1.0F - t;
  const float uu = u * u;
  const float tt = t * t;
  const float uuu = uu * u;
  const float ttt = tt * t;
  return {
      .x = uuu * curve.p0.x + 3.0F * uu * t * curve.p1.x +
           3.0F * u * tt * curve.p2.x + ttt * curve.p3.x,
      .y = uuu * curve.p0.y + 3.0F * uu * t * curve.p1.y +
           3.0F * u * tt * curve.p2.y + ttt * curve.p3.y,
  };
}

std::vector<TimedPoint> SampleBezierByArcLength(const CubicBezier &curve,
                                                std::uint32_t duration_ms,
                                                std::uint32_t sample_period_ms,
                                                std::size_t lookup_segments) {
  lookup_segments = std::max<std::size_t>(lookup_segments, 8);
  sample_period_ms = std::max<std::uint32_t>(sample_period_ms, 1);

  std::vector<float> cumulative(lookup_segments + 1, 0.0F);
  PointF previous = curve.p0;
  for (std::size_t i = 1; i <= lookup_segments; ++i) {
    const float t = static_cast<float>(i) / static_cast<float>(lookup_segments);
    const PointF current = EvaluateBezier(curve, t);
    cumulative[i] = cumulative[i - 1] + Distance(previous, current);
    previous = current;
  }

  const float total_length = cumulative.back();
  const std::uint32_t sample_count = std::max<std::uint32_t>(
      1, (duration_ms + sample_period_ms - 1) / sample_period_ms);

  std::vector<TimedPoint> result;
  result.reserve(static_cast<std::size_t>(sample_count) + 1);
  for (std::uint32_t i = 0; i <= sample_count; ++i) {
    const float fraction =
        static_cast<float>(i) / static_cast<float>(sample_count);
    float t = fraction;
    if (total_length > std::numeric_limits<float>::epsilon()) {
      const float target = total_length * fraction;
      const auto upper =
          std::lower_bound(cumulative.begin(), cumulative.end(), target);
      const std::size_t upper_index =
          static_cast<std::size_t>(std::distance(cumulative.begin(), upper));
      if (upper_index == 0) {
        t = 0.0F;
      } else if (upper_index >= cumulative.size()) {
        t = 1.0F;
      } else {
        const float before = cumulative[upper_index - 1];
        const float segment_length = cumulative[upper_index] - before;
        const float local =
            segment_length > 0.0F ? (target - before) / segment_length : 0.0F;
        t = (static_cast<float>(upper_index - 1) + local) /
            static_cast<float>(lookup_segments);
      }
    }
    result.push_back({
        .elapsed_ms = static_cast<std::uint32_t>(
            (static_cast<std::uint64_t>(duration_ms) * i) / sample_count),
        .position = EvaluateBezier(curve, t),
    });
  }
  return result;
}

Gesture BuildSinglePointerGesture(std::uint64_t action_id,
                                  std::uint32_t display_id,
                                  const std::vector<TimedPoint> &path,
                                  std::int32_t pointer_id) {
  Gesture gesture{.action_id = action_id, .display_id = display_id};
  if (path.empty()) {
    return gesture;
  }

  gesture.frames.reserve(path.size());
  for (std::size_t i = 0; i < path.size(); ++i) {
    GestureAction action = GestureAction::kMove;
    if (i == 0) {
      action = GestureAction::kDown;
    } else if (i + 1 == path.size()) {
      action = GestureAction::kUp;
    }
    gesture.frames.push_back({
        .action = action,
        .action_index = 0,
        .elapsed_ms = path[i].elapsed_ms,
        .pointers = {{.pointer_id = pointer_id, .position = path[i].position}},
    });
  }
  return gesture;
}

} // namespace global_agent
