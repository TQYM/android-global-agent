#pragma once

#include <cstddef>
#include <cstdint>
#include <span>
#include <string>
#include <vector>

namespace global_agent {

class StateStore {
public:
  static constexpr std::uint32_t kFormatVersion = 1;
  static constexpr std::size_t kDefaultSlotCapacity = 64 * 1024;

  StateStore() = default;
  ~StateStore();

  StateStore(const StateStore &) = delete;
  StateStore &operator=(const StateStore &) = delete;

  bool Open(const std::string &path, std::string *error,
            std::size_t slot_capacity = kDefaultSlotCapacity);
  bool Commit(std::span<const std::uint8_t> payload, std::string *error);
  bool LoadLatest(std::vector<std::uint8_t> *payload, std::uint64_t *generation,
                  std::string *error) const;

  [[nodiscard]] std::uint64_t generation() const { return generation_; }
  [[nodiscard]] std::size_t slot_capacity() const { return slot_capacity_; }

private:
  void Close();
  bool InitializeNewFile(std::string *error);

  int fd_ = -1;
  void *mapping_ = nullptr;
  std::size_t mapping_size_ = 0;
  std::size_t slot_capacity_ = 0;
  std::uint64_t generation_ = 0;
};

} // namespace global_agent
