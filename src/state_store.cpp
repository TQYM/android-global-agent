#include "global_agent/state_store.h"

#include <cerrno>
#include <cstring>
#include <fcntl.h>
#include <span>
#include <string>
#include <sys/file.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include "global_agent/crc32.h"

namespace global_agent {
namespace {

constexpr char kStoreMagic[8] = {'A', 'G', 'S', 'T', 'A', 'T', 'E', '\0'};
constexpr std::uint32_t kCommittedMarker = 0xA61E7EDU;

struct alignas(64) StoreHeader {
  char magic[8];
  std::uint32_t version;
  std::uint32_t slot_capacity;
  std::uint8_t reserved[48];
};

struct alignas(64) SlotHeader {
  std::uint64_t generation;
  std::uint32_t payload_size;
  std::uint32_t payload_crc;
  std::uint32_t committed;
  std::uint8_t reserved[44];
};

static_assert(sizeof(StoreHeader) == 64);
static_assert(sizeof(SlotHeader) == 64);

std::string ErrorWithErrno(const char *operation) {
  return std::string(operation) + ": " + std::strerror(errno);
}

std::size_t MappingSize(std::size_t slot_capacity) {
  return sizeof(StoreHeader) + 2 * (sizeof(SlotHeader) + slot_capacity);
}

SlotHeader *SlotAt(void *mapping, std::size_t slot_capacity,
                   std::size_t index) {
  auto *base = static_cast<std::uint8_t *>(mapping) + sizeof(StoreHeader);
  return reinterpret_cast<SlotHeader *>(
      base + index * (sizeof(SlotHeader) + slot_capacity));
}

std::uint8_t *PayloadAt(SlotHeader *slot) {
  return reinterpret_cast<std::uint8_t *>(slot) + sizeof(SlotHeader);
}

const std::uint8_t *PayloadAt(const SlotHeader *slot) {
  return reinterpret_cast<const std::uint8_t *>(slot) + sizeof(SlotHeader);
}

bool IsValidSlot(const SlotHeader *slot, std::size_t capacity) {
  if (__atomic_load_n(&slot->committed, __ATOMIC_ACQUIRE) != kCommittedMarker ||
      slot->payload_size > capacity) {
    return false;
  }
  return Crc32(PayloadAt(slot), slot->payload_size) == slot->payload_crc;
}

} // namespace

StateStore::~StateStore() { Close(); }

bool StateStore::Open(const std::string &path, std::string *error,
                      std::size_t slot_capacity) {
  Close();
  if (slot_capacity == 0 ||
      slot_capacity > static_cast<std::size_t>(UINT32_MAX)) {
    if (error != nullptr)
      *error = "invalid slot capacity";
    return false;
  }

  fd_ = open(path.c_str(), O_RDWR | O_CREAT | O_CLOEXEC | O_NOFOLLOW, 0600);
  if (fd_ < 0) {
    if (error != nullptr)
      *error = ErrorWithErrno("open state store");
    return false;
  }
  if (flock(fd_, LOCK_EX | LOCK_NB) != 0) {
    if (error != nullptr)
      *error = ErrorWithErrno("lock state store");
    Close();
    return false;
  }
  if (fchmod(fd_, 0600) != 0) {
    if (error != nullptr)
      *error = ErrorWithErrno("set state store mode");
    Close();
    return false;
  }

  struct stat info {};
  if (fstat(fd_, &info) != 0) {
    if (error != nullptr)
      *error = ErrorWithErrno("stat state store");
    Close();
    return false;
  }
  if (!S_ISREG(info.st_mode)) {
    if (error != nullptr)
      *error = "state store is not a regular file";
    Close();
    return false;
  }

  slot_capacity_ = slot_capacity;
  mapping_size_ = MappingSize(slot_capacity_);
  const bool is_new = info.st_size == 0;
  if (is_new) {
    if (ftruncate(fd_, static_cast<off_t>(mapping_size_)) != 0) {
      if (error != nullptr)
        *error = ErrorWithErrno("resize state store");
      Close();
      return false;
    }
  } else if (static_cast<std::size_t>(info.st_size) != mapping_size_) {
    if (error != nullptr)
      *error = "state store size does not match configuration";
    Close();
    return false;
  }

  mapping_ =
      mmap(nullptr, mapping_size_, PROT_READ | PROT_WRITE, MAP_SHARED, fd_, 0);
  if (mapping_ == MAP_FAILED) {
    mapping_ = nullptr;
    if (error != nullptr)
      *error = ErrorWithErrno("map state store");
    Close();
    return false;
  }

  if (is_new && !InitializeNewFile(error)) {
    Close();
    return false;
  }

  const auto *header = static_cast<const StoreHeader *>(mapping_);
  if (std::memcmp(header->magic, kStoreMagic, sizeof(kStoreMagic)) != 0 ||
      header->version != kFormatVersion ||
      header->slot_capacity != slot_capacity_) {
    if (error != nullptr)
      *error = "invalid state store header";
    Close();
    return false;
  }

  std::vector<std::uint8_t> ignored;
  std::uint64_t generation = 0;
  if (!LoadLatest(&ignored, &generation, error)) {
    Close();
    return false;
  }
  generation_ = generation;
  return true;
}

bool StateStore::InitializeNewFile(std::string *error) {
  std::memset(mapping_, 0, mapping_size_);
  auto *header = static_cast<StoreHeader *>(mapping_);
  std::memcpy(header->magic, kStoreMagic, sizeof(kStoreMagic));
  header->version = kFormatVersion;
  header->slot_capacity = static_cast<std::uint32_t>(slot_capacity_);
  if (msync(mapping_, mapping_size_, MS_SYNC) != 0 || fsync(fd_) != 0) {
    if (error != nullptr)
      *error = ErrorWithErrno("initialize state store");
    return false;
  }
  return true;
}

bool StateStore::Commit(std::span<const std::uint8_t> payload,
                        std::string *error) {
  if (mapping_ == nullptr) {
    if (error != nullptr)
      *error = "state store is not open";
    return false;
  }
  if (payload.size() > slot_capacity_) {
    if (error != nullptr)
      *error = "state payload exceeds slot capacity";
    return false;
  }

  const std::uint64_t next_generation = generation_ + 1;
  const std::size_t slot_index = static_cast<std::size_t>(next_generation & 1U);
  SlotHeader *slot = SlotAt(mapping_, slot_capacity_, slot_index);
  __atomic_store_n(&slot->committed, 0U, __ATOMIC_RELEASE);

  std::memcpy(PayloadAt(slot), payload.data(), payload.size());
  if (payload.size() < slot_capacity_) {
    std::memset(PayloadAt(slot) + payload.size(), 0,
                slot_capacity_ - payload.size());
  }
  slot->generation = next_generation;
  slot->payload_size = static_cast<std::uint32_t>(payload.size());
  slot->payload_crc = Crc32(payload);

  if (msync(mapping_, mapping_size_, MS_SYNC) != 0) {
    if (error != nullptr)
      *error = ErrorWithErrno("flush state payload");
    return false;
  }
  __atomic_store_n(&slot->committed, kCommittedMarker, __ATOMIC_RELEASE);
  if (msync(mapping_, mapping_size_, MS_SYNC) != 0 || fsync(fd_) != 0) {
    __atomic_store_n(&slot->committed, 0U, __ATOMIC_RELEASE);
    if (error != nullptr)
      *error = ErrorWithErrno("commit state payload");
    return false;
  }
  generation_ = next_generation;
  return true;
}

bool StateStore::LoadLatest(std::vector<std::uint8_t> *payload,
                            std::uint64_t *generation,
                            std::string *error) const {
  if (mapping_ == nullptr || payload == nullptr || generation == nullptr) {
    if (error != nullptr)
      *error = "invalid state store load request";
    return false;
  }

  const SlotHeader *selected = nullptr;
  for (std::size_t index = 0; index < 2; ++index) {
    const SlotHeader *candidate = SlotAt(mapping_, slot_capacity_, index);
    if (!IsValidSlot(candidate, slot_capacity_)) {
      continue;
    }
    if (selected == nullptr || candidate->generation > selected->generation) {
      selected = candidate;
    }
  }

  if (selected == nullptr) {
    payload->clear();
    *generation = 0;
    return true;
  }
  payload->assign(PayloadAt(selected),
                  PayloadAt(selected) + selected->payload_size);
  *generation = selected->generation;
  return true;
}

void StateStore::Close() {
  if (mapping_ != nullptr) {
    munmap(mapping_, mapping_size_);
    mapping_ = nullptr;
  }
  if (fd_ >= 0) {
    close(fd_);
    fd_ = -1;
  }
  mapping_size_ = 0;
  slot_capacity_ = 0;
  generation_ = 0;
}

} // namespace global_agent
