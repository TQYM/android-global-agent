#pragma once

#include <cstddef>
#include <cstdint>
#include <span>
#include <string>
#include <vector>

#include "global_agent/types.h"

namespace global_agent {

class StateGraph {
public:
  static constexpr std::size_t kMaxNodes = 128;
  static constexpr std::size_t kMaxEdges = 512;

  NodeId Observe(const Perception &perception);
  void RecordTransition(NodeId from, NodeId to, std::uint64_t action_id,
                        std::uint64_t timestamp_ns, std::uint32_t latency_ms,
                        ActionOutcome outcome);

  [[nodiscard]] NodeId current_node() const { return current_node_; }
  [[nodiscard]] const std::vector<StateNode> &nodes() const { return nodes_; }
  [[nodiscard]] const std::vector<StateEdge> &edges() const { return edges_; }

  [[nodiscard]] std::vector<std::uint8_t> Serialize() const;
  bool Deserialize(std::span<const std::uint8_t> bytes, std::string *error);

private:
  static NodeId ComputeNodeId(const Perception &perception);
  void TrimIfNeeded();

  NodeId current_node_ = 0;
  std::vector<StateNode> nodes_;
  std::vector<StateEdge> edges_;
};

} // namespace global_agent
