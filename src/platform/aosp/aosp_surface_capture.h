#pragma once

#include <memory>

#include "agent_binder_service.h"
#include "global_agent/agent_loop.h"

namespace global_agent::aosp {

class AospSingleFrameCapture final : public PerceptionBackend {
public:
  explicit AospSingleFrameCapture(std::shared_ptr<AgentBinderService> service)
      : service_(std::move(service)) {}

  bool Capture(const Deadline &deadline, Perception *perception,
               std::string *error) override;

private:
  std::shared_ptr<AgentBinderService> service_;
};

} // namespace global_agent::aosp
