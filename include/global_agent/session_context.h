#pragma once

#include <chrono>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <string>

namespace global_agent {

enum class TriggerSource : std::uint8_t {
  kPowerLongPress = 0,
  kExplicitUi = 1,
};

enum class VisualState : std::uint8_t {
  kIdle = 0,
  kListening = 1,
  kThinking = 2,
  kExecuting = 3,
  kFeedback = 4,
  kError = 5,
};

struct TriggerEvent {
  TriggerSource source = TriggerSource::kExplicitUi;
  std::uint64_t monotonic_ns = 0;
  std::uint32_t press_duration_ms = 0;
  std::int32_t display_id = 0;
  bool keyguard_locked = false;
  bool user_confirmed = false;
};

struct TranscriptChunk {
  std::uint64_t session_id = 0;
  std::uint64_t sequence = 0;
  bool is_final = false;
  std::string text;
};

struct SessionSnapshot {
  std::uint64_t id = 0;
  TriggerSource source = TriggerSource::kExplicitUi;
  std::uint64_t started_ns = 0;
  std::int32_t display_id = 0;
  VisualState state = VisualState::kIdle;
  bool user_confirmed = false;
  std::uint64_t transcript_sequence = 0;
  bool transcript_final = false;
  bool active = false;
  std::string transcript;
};

// Ephemeral state shared by the platform bridge and the decision loop. It is
// intentionally independent of microphone, input, and SELinux APIs. Raw text
// is bounded and is never part of StateStore serialization.
class SessionContext final {
public:
  using Clock = std::chrono::steady_clock;
  using TimePoint = Clock::time_point;

  static constexpr std::size_t kMaxTranscriptBytes = 4096;
  static constexpr std::uint32_t kMinPowerPressMs = 2000;
  static constexpr std::uint32_t kMaxPowerPressMs = 10000;
  static constexpr std::int32_t kMaxDisplayId = 4095;
  static constexpr std::chrono::seconds kSessionTimeout{15};

  ~SessionContext();

  bool Begin(const TriggerEvent &event, TimePoint now, std::string *error);
  bool SubmitTranscript(const TranscriptChunk &chunk, std::string *error);
  bool Transition(VisualState state, std::string *error);

  // Returns true when an active session expired and was cleared.
  bool Expire(TimePoint now);
  void Cancel();

  [[nodiscard]] SessionSnapshot Snapshot() const;

private:
  static bool IsValidUtf8(const std::string &text);
  static bool IsAllowedTransition(VisualState from, VisualState to);
  void WipeTranscriptLocked();
  void ClearLocked();

  mutable std::mutex mutex_;
  std::uint64_t next_session_id_ = 1;
  std::uint64_t last_trigger_ns_ = 0;
  SessionSnapshot session_;
  TimePoint deadline_{};
};

} // namespace global_agent
