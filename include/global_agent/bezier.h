#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

#include "global_agent/types.h"

namespace global_agent {

struct CubicBezier {
  PointF p0;
  PointF p1;
  PointF p2;
  PointF p3;
};

struct TimedPoint {
  std::uint32_t elapsed_ms = 0;
  PointF position;
};

PointF EvaluateBezier(const CubicBezier &curve, float t);

std::vector<TimedPoint>
SampleBezierByArcLength(const CubicBezier &curve, std::uint32_t duration_ms,
                        std::uint32_t sample_period_ms = 8,
                        std::size_t lookup_segments = 128);

Gesture BuildSinglePointerGesture(std::uint64_t action_id,
                                  std::uint32_t display_id,
                                  const std::vector<TimedPoint> &path,
                                  std::int32_t pointer_id = 0);

} // namespace global_agent
