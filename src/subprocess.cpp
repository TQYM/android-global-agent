#include "global_agent/subprocess.h"

#include <algorithm>
#include <cerrno>
#include <chrono>
#include <csignal>
#include <cstring>
#include <fcntl.h>
#include <poll.h>
#include <string>
#include <sys/wait.h>
#include <unistd.h>
#include <vector>

namespace global_agent {
namespace {

std::string ErrnoMessage(const char *operation) {
  return std::string(operation) + ": " + std::strerror(errno);
}

void Reap(pid_t pid) {
  int ignored = 0;
  while (waitpid(pid, &ignored, 0) < 0 && errno == EINTR) {
  }
}

} // namespace

CommandResult RunCommand(const std::vector<std::string> &arguments,
                         std::chrono::milliseconds timeout,
                         std::size_t max_output_bytes) {
  CommandResult result;
  if (arguments.empty() || arguments.front().empty()) {
    result.error = "command is empty";
    return result;
  }

  int pipe_fds[2];
  if (pipe(pipe_fds) != 0) {
    result.error = ErrnoMessage("pipe");
    return result;
  }
  fcntl(pipe_fds[0], F_SETFD, FD_CLOEXEC);
  fcntl(pipe_fds[1], F_SETFD, FD_CLOEXEC);

  const pid_t pid = fork();
  if (pid < 0) {
    result.error = ErrnoMessage("fork");
    close(pipe_fds[0]);
    close(pipe_fds[1]);
    return result;
  }
  if (pid == 0) {
    close(pipe_fds[0]);
    if (dup2(pipe_fds[1], STDOUT_FILENO) < 0 ||
        dup2(pipe_fds[1], STDERR_FILENO) < 0) {
      _exit(126);
    }
    close(pipe_fds[1]);

    std::vector<char *> argv;
    argv.reserve(arguments.size() + 1);
    for (const std::string &argument : arguments) {
      argv.push_back(const_cast<char *>(argument.c_str()));
    }
    argv.push_back(nullptr);
    execv(argv.front(), argv.data());
    _exit(127);
  }

  result.started = true;
  close(pipe_fds[1]);
  const int old_flags = fcntl(pipe_fds[0], F_GETFL, 0);
  if (old_flags >= 0) {
    fcntl(pipe_fds[0], F_SETFL, old_flags | O_NONBLOCK);
  }

  const auto deadline = std::chrono::steady_clock::now() + timeout;
  bool pipe_closed = false;
  bool child_exited = false;
  int child_status = 0;
  char buffer[4096];

  while (!pipe_closed || !child_exited) {
    const auto now = std::chrono::steady_clock::now();
    if (now >= deadline) {
      result.timed_out = true;
      kill(pid, SIGKILL);
      Reap(pid);
      child_exited = true;
      break;
    }

    const auto remaining =
        std::chrono::duration_cast<std::chrono::milliseconds>(deadline - now);
    pollfd descriptor{.fd = pipe_fds[0], .events = POLLIN | POLLHUP};
    const int poll_timeout =
        static_cast<int>(std::clamp<std::int64_t>(remaining.count(), 0, 50));
    const int polled = poll(&descriptor, 1, poll_timeout);
    if (polled < 0 && errno != EINTR) {
      result.error = ErrnoMessage("poll");
      kill(pid, SIGKILL);
      Reap(pid);
      child_exited = true;
      break;
    }

    if (polled > 0 && (descriptor.revents & (POLLIN | POLLHUP)) != 0) {
      for (;;) {
        const ssize_t count = read(pipe_fds[0], buffer, sizeof(buffer));
        if (count > 0) {
          const std::size_t available =
              max_output_bytes > result.output.size()
                  ? max_output_bytes - result.output.size()
                  : 0;
          const std::size_t accepted =
              std::min<std::size_t>(static_cast<std::size_t>(count), available);
          result.output.append(buffer, accepted);
          if (accepted < static_cast<std::size_t>(count)) {
            result.output_truncated = true;
          }
          continue;
        }
        if (count == 0) {
          pipe_closed = true;
        } else if (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) {
          result.error = ErrnoMessage("read command output");
          pipe_closed = true;
        }
        break;
      }
    }

    if (!child_exited) {
      const pid_t waited = waitpid(pid, &child_status, WNOHANG);
      if (waited == pid) {
        child_exited = true;
      } else if (waited < 0 && errno != EINTR) {
        result.error = ErrnoMessage("waitpid");
        child_exited = true;
      }
    }
  }

  close(pipe_fds[0]);
  if (!child_exited) {
    Reap(pid);
  }
  if (!result.timed_out && WIFEXITED(child_status)) {
    result.exit_code = WEXITSTATUS(child_status);
  } else if (!result.timed_out && WIFSIGNALED(child_status)) {
    result.exit_code = 128 + WTERMSIG(child_status);
  }
  return result;
}

} // namespace global_agent
