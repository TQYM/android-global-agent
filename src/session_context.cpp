#include "global_agent/session_context.h"

#include <limits>

namespace global_agent {
namespace {

bool IsContinuation(std::uint8_t byte) {
  return (byte & 0xC0U) == 0x80U;
}

} // namespace

bool SessionContext::Begin(const TriggerEvent &event, TimePoint now,
                           std::string *error) {
  std::lock_guard lock(mutex_);
  if (session_.active) {
    if (error != nullptr)
      *error = "a session is already active";
    return false;
  }
  if (!event.user_confirmed || event.keyguard_locked) {
    if (error != nullptr)
      *error = "user confirmation and an unlocked keyguard are required";
    return false;
  }
  if (event.monotonic_ns == 0 || event.monotonic_ns <= last_trigger_ns_) {
    if (error != nullptr)
      *error = "trigger timestamp is not strictly increasing";
    return false;
  }
  if (event.display_id < 0 || event.display_id > kMaxDisplayId) {
    if (error != nullptr)
      *error = "invalid display id";
    return false;
  }
  if (event.source != TriggerSource::kPowerLongPress &&
      event.source != TriggerSource::kExplicitUi) {
    if (error != nullptr)
      *error = "unsupported trigger source";
    return false;
  }
  if (event.source == TriggerSource::kPowerLongPress &&
      (event.press_duration_ms < kMinPowerPressMs ||
       event.press_duration_ms > kMaxPowerPressMs)) {
    if (error != nullptr)
      *error = "power press duration is outside the allowed range";
    return false;
  }

  const std::uint64_t session_id = next_session_id_ == 0 ? 1 : next_session_id_;
  if (session_id == std::numeric_limits<std::uint64_t>::max()) {
    next_session_id_ = 1;
  } else {
    next_session_id_ = session_id + 1;
  }
  session_ = SessionSnapshot{
      .id = session_id,
      .source = event.source,
      .started_ns = event.monotonic_ns,
      .display_id = event.display_id,
      .state = VisualState::kListening,
      .user_confirmed = true,
      .transcript_sequence = 0,
      .transcript_final = false,
      .active = true,
      .transcript = {},
  };
  last_trigger_ns_ = event.monotonic_ns;
  deadline_ = now + kSessionTimeout;
  return true;
}

bool SessionContext::SubmitTranscript(const TranscriptChunk &chunk,
                                      std::string *error) {
  std::lock_guard lock(mutex_);
  if (!session_.active) {
    if (error != nullptr)
      *error = "no active session";
    return false;
  }
  if (chunk.session_id != session_.id || chunk.sequence <= session_.transcript_sequence) {
    if (error != nullptr)
      *error = "transcript session or sequence is invalid";
    return false;
  }
  if (chunk.text.empty() || chunk.text.size() > kMaxTranscriptBytes ||
      chunk.text.find('\0') != std::string::npos ||
      !IsValidUtf8(chunk.text)) {
    if (error != nullptr)
      *error = "transcript is empty or exceeds the UTF-8 limit";
    return false;
  }
  if (session_.transcript_final) {
    if (error != nullptr)
      *error = "a final transcript has already been accepted";
    return false;
  }
  session_.transcript_sequence = chunk.sequence;
  session_.transcript_final = chunk.is_final;
  WipeTranscriptLocked();
  session_.transcript = chunk.text;
  return true;
}

bool SessionContext::Transition(VisualState state, std::string *error) {
  std::lock_guard lock(mutex_);
  if (!session_.active) {
    if (state == VisualState::kIdle)
      return true;
    if (error != nullptr)
      *error = "no active session";
    return false;
  }
  if (!IsAllowedTransition(session_.state, state)) {
    if (error != nullptr)
      *error = "invalid visual-state transition";
    return false;
  }
  if (state == VisualState::kIdle) {
    ClearLocked();
    return true;
  }
  session_.state = state;
  return true;
}

bool SessionContext::Expire(TimePoint now) {
  std::lock_guard lock(mutex_);
  if (!session_.active || now < deadline_)
    return false;
  ClearLocked();
  return true;
}

void SessionContext::Cancel() {
  std::lock_guard lock(mutex_);
  ClearLocked();
}

SessionSnapshot SessionContext::Snapshot() const {
  std::lock_guard lock(mutex_);
  return session_;
}

bool SessionContext::IsValidUtf8(const std::string &text) {
  for (std::size_t i = 0; i < text.size();) {
    const std::uint8_t lead = static_cast<std::uint8_t>(text[i]);
    std::size_t width = 0;
    std::uint32_t code_point = 0;
    if (lead <= 0x7FU) {
      width = 1;
      code_point = lead;
    } else if (lead >= 0xC2U && lead <= 0xDFU) {
      width = 2;
      code_point = lead & 0x1FU;
    } else if (lead >= 0xE0U && lead <= 0xEFU) {
      width = 3;
      code_point = lead & 0x0FU;
    } else if (lead >= 0xF0U && lead <= 0xF4U) {
      width = 4;
      code_point = lead & 0x07U;
    } else {
      return false;
    }
    if (i + width > text.size())
      return false;
    for (std::size_t offset = 1; offset < width; ++offset) {
      const std::uint8_t byte = static_cast<std::uint8_t>(text[i + offset]);
      if (!IsContinuation(byte))
        return false;
      code_point = (code_point << 6U) | (byte & 0x3FU);
    }
    if ((width == 2 && code_point < 0x80U) ||
        (width == 3 && code_point < 0x800U) ||
        (width == 4 && code_point < 0x10000U) ||
        code_point > 0x10FFFFU ||
        (code_point >= 0xD800U && code_point <= 0xDFFFU)) {
      return false;
    }
    i += width;
  }
  return true;
}

bool SessionContext::IsAllowedTransition(VisualState from, VisualState to) {
  if (to == VisualState::kError) {
    return from != VisualState::kIdle && from != VisualState::kError;
  }
  switch (from) {
  case VisualState::kListening:
    return to == VisualState::kThinking || to == VisualState::kIdle;
  case VisualState::kThinking:
    return to == VisualState::kExecuting || to == VisualState::kFeedback ||
           to == VisualState::kIdle;
  case VisualState::kExecuting:
    return to == VisualState::kFeedback || to == VisualState::kIdle;
  case VisualState::kFeedback:
    return to == VisualState::kIdle;
  case VisualState::kError:
    return to == VisualState::kIdle;
  case VisualState::kIdle:
    return false;
  }
  return false;
}

void SessionContext::ClearLocked() {
  session_.state = VisualState::kIdle;
  session_.active = false;
  session_.user_confirmed = false;
  WipeTranscriptLocked();
  session_.transcript_sequence = 0;
  session_.transcript_final = false;
  deadline_ = TimePoint{};
}

void SessionContext::WipeTranscriptLocked() {
  volatile char *bytes = session_.transcript.empty()
                             ? nullptr
                             : session_.transcript.data();
  for (std::size_t index = 0; index < session_.transcript.size(); ++index) {
    bytes[index] = '\0';
  }
  session_.transcript.clear();
}

} // namespace global_agent
