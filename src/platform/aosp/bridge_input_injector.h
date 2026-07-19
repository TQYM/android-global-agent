#pragma once

#include <memory>

#include "agent_binder_service.h"
#include "global_agent/agent_loop.h"

namespace global_agent::aosp {

class BridgeInputInjector final : public InputInjector {
public:
  explicit BridgeInputInjector(std::shared_ptr<AgentBinderService> service)
      : service_(std::move(service)) {}

  bool Inject(const Gesture &gesture, const Deadline &deadline,
              std::string *error) override;
  void CancelActiveGesture() override;

private:
  std::shared_ptr<AgentBinderService> service_;
};

} // namespace global_agent::aosp
