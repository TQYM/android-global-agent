#include "global_agent/state_graph.h"

#include <algorithm>
#include <bit>
#include <cstring>
#include <limits>
#include <type_traits>

#include "global_agent/hash.h"

namespace global_agent {
namespace {

constexpr std::uint32_t kGraphMagic = 0x48505247U; // GRPH
constexpr std::uint32_t kGraphVersion = 1;

template <typename T, bool = std::is_enum_v<T>> struct SerializedType {
  using type = T;
};

template <typename T> struct SerializedType<T, true> {
  using type = std::underlying_type_t<T>;
};

template <typename T> using SerializedTypeT = typename SerializedType<T>::type;

class Writer {
public:
  template <typename T> void Put(T value) {
    static_assert(std::is_integral_v<T> || std::is_enum_v<T>);
    using Raw = SerializedTypeT<T>;
    using Unsigned = std::make_unsigned_t<Raw>;
    const Raw signed_or_unsigned = static_cast<Raw>(value);
    const Unsigned raw = std::bit_cast<Unsigned>(signed_or_unsigned);
    for (std::size_t i = 0; i < sizeof(Unsigned); ++i) {
      bytes_.push_back(static_cast<std::uint8_t>(raw >> (8U * i)));
    }
  }

  std::vector<std::uint8_t> Take() { return std::move(bytes_); }

private:
  std::vector<std::uint8_t> bytes_;
};

class Reader {
public:
  explicit Reader(std::span<const std::uint8_t> bytes) : bytes_(bytes) {}

  template <typename T> bool Get(T *value) {
    static_assert(std::is_integral_v<T> || std::is_enum_v<T>);
    using Raw = SerializedTypeT<T>;
    using Unsigned = std::make_unsigned_t<Raw>;
    if (remaining() < sizeof(Unsigned)) {
      return false;
    }
    Unsigned raw = 0;
    for (std::size_t i = 0; i < sizeof(Unsigned); ++i) {
      raw |= static_cast<Unsigned>(bytes_[offset_ + i]) << (8U * i);
    }
    offset_ += sizeof(Unsigned);
    const Raw signed_or_unsigned = std::bit_cast<Raw>(raw);
    *value = static_cast<T>(signed_or_unsigned);
    return true;
  }

  [[nodiscard]] std::size_t remaining() const {
    return bytes_.size() - offset_;
  }

private:
  std::span<const std::uint8_t> bytes_;
  std::size_t offset_ = 0;
};

void WriteNode(Writer *writer, const StateNode &node) {
  writer->Put(node.id);
  writer->Put(node.visual_hash);
  writer->Put(node.component_hash);
  writer->Put(node.view_hash);
  writer->Put(node.last_seen_ns);
  writer->Put(node.focused_pid);
  writer->Put(node.display_id);
  writer->Put(node.rotation);
  writer->Put(node.confidence_milli);
}

bool ReadNode(Reader *reader, StateNode *node) {
  return reader->Get(&node->id) && reader->Get(&node->visual_hash) &&
         reader->Get(&node->component_hash) && reader->Get(&node->view_hash) &&
         reader->Get(&node->last_seen_ns) && reader->Get(&node->focused_pid) &&
         reader->Get(&node->display_id) && reader->Get(&node->rotation) &&
         reader->Get(&node->confidence_milli);
}

void WriteEdge(Writer *writer, const StateEdge &edge) {
  writer->Put(edge.from);
  writer->Put(edge.to);
  writer->Put(edge.action_id);
  writer->Put(edge.timestamp_ns);
  writer->Put(edge.latency_ms);
  writer->Put(edge.outcome);
}

bool ReadEdge(Reader *reader, StateEdge *edge) {
  return reader->Get(&edge->from) && reader->Get(&edge->to) &&
         reader->Get(&edge->action_id) && reader->Get(&edge->timestamp_ns) &&
         reader->Get(&edge->latency_ms) && reader->Get(&edge->outcome);
}

} // namespace

NodeId StateGraph::ComputeNodeId(const Perception &perception) {
  std::uint64_t hash =
      HashCombine(perception.window.component_hash,
                  perception.single_frame_visual_hash);
  hash = HashCombine(hash, perception.window.view_hash);
  hash = HashCombine(hash, perception.window.display_id);
  hash = HashCombine(hash, perception.window.rotation);
  return hash == 0 ? 1 : hash;
}

NodeId StateGraph::Observe(const Perception &perception) {
  const NodeId id = ComputeNodeId(perception);
  const auto existing =
      std::find_if(nodes_.begin(), nodes_.end(),
                   [id](const StateNode &node) { return node.id == id; });
  if (existing == nodes_.end()) {
    nodes_.push_back({
        .id = id,
        .visual_hash = perception.single_frame_visual_hash,
        .component_hash = perception.window.component_hash,
        .view_hash = perception.window.view_hash,
        .last_seen_ns = perception.monotonic_ns,
        .focused_pid = perception.window.focused_pid,
        .display_id = perception.window.display_id,
        .rotation = perception.window.rotation,
        .confidence_milli = perception.confidence_milli,
    });
  } else {
    existing->last_seen_ns = perception.monotonic_ns;
    existing->focused_pid = perception.window.focused_pid;
    existing->confidence_milli = perception.confidence_milli;
  }
  current_node_ = id;
  TrimIfNeeded();
  return id;
}

void StateGraph::RecordTransition(NodeId from, NodeId to,
                                  std::uint64_t action_id,
                                  std::uint64_t timestamp_ns,
                                  std::uint32_t latency_ms,
                                  ActionOutcome outcome) {
  edges_.push_back({
      .from = from,
      .to = to,
      .action_id = action_id,
      .timestamp_ns = timestamp_ns,
      .latency_ms = latency_ms,
      .outcome = outcome,
  });
  TrimIfNeeded();
}

void StateGraph::TrimIfNeeded() {
  if (edges_.size() > kMaxEdges) {
    edges_.erase(edges_.begin(),
                 edges_.begin() +
                     static_cast<std::ptrdiff_t>(edges_.size() - kMaxEdges));
  }
  while (nodes_.size() > kMaxNodes) {
    const auto victim =
        std::min_element(nodes_.begin(), nodes_.end(),
                         [this](const StateNode &left, const StateNode &right) {
                           if (left.id == current_node_)
                             return false;
                           if (right.id == current_node_)
                             return true;
                           return left.last_seen_ns < right.last_seen_ns;
                         });
    if (victim == nodes_.end() || victim->id == current_node_) {
      break;
    }
    const NodeId victim_id = victim->id;
    edges_.erase(std::remove_if(edges_.begin(), edges_.end(),
                                [victim_id](const StateEdge &edge) {
                                  return edge.from == victim_id ||
                                         edge.to == victim_id;
                                }),
                 edges_.end());
    nodes_.erase(victim);
  }
}

std::vector<std::uint8_t> StateGraph::Serialize() const {
  Writer writer;
  writer.Put(kGraphMagic);
  writer.Put(kGraphVersion);
  writer.Put(current_node_);
  writer.Put(static_cast<std::uint32_t>(nodes_.size()));
  writer.Put(static_cast<std::uint32_t>(edges_.size()));
  for (const auto &node : nodes_) {
    WriteNode(&writer, node);
  }
  for (const auto &edge : edges_) {
    WriteEdge(&writer, edge);
  }
  return writer.Take();
}

bool StateGraph::Deserialize(std::span<const std::uint8_t> bytes,
                             std::string *error) {
  Reader reader(bytes);
  std::uint32_t magic = 0;
  std::uint32_t version = 0;
  NodeId current_node = 0;
  std::uint32_t node_count = 0;
  std::uint32_t edge_count = 0;
  if (!reader.Get(&magic) || !reader.Get(&version) ||
      !reader.Get(&current_node) || !reader.Get(&node_count) ||
      !reader.Get(&edge_count)) {
    if (error != nullptr)
      *error = "truncated graph header";
    return false;
  }
  if (magic != kGraphMagic || version != kGraphVersion) {
    if (error != nullptr)
      *error = "unsupported graph format";
    return false;
  }
  if (node_count > kMaxNodes || edge_count > kMaxEdges) {
    if (error != nullptr)
      *error = "graph exceeds configured limits";
    return false;
  }

  std::vector<StateNode> nodes(node_count);
  std::vector<StateEdge> edges(edge_count);
  for (auto &node : nodes) {
    if (!ReadNode(&reader, &node)) {
      if (error != nullptr)
        *error = "truncated node record";
      return false;
    }
  }
  for (auto &edge : edges) {
    if (!ReadEdge(&reader, &edge)) {
      if (error != nullptr)
        *error = "truncated edge record";
      return false;
    }
  }
  if (reader.remaining() != 0) {
    if (error != nullptr)
      *error = "trailing graph data";
    return false;
  }
  if (current_node != 0 &&
      std::none_of(nodes.begin(), nodes.end(), [current_node](const auto &n) {
        return n.id == current_node;
      })) {
    if (error != nullptr)
      *error = "current node is missing";
    return false;
  }

  current_node_ = current_node;
  nodes_ = std::move(nodes);
  edges_ = std::move(edges);
  return true;
}

} // namespace global_agent
