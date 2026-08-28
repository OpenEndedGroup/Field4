// The Linux half of the Electron/JVM browser-texture bridge. Its macOS twin transfers an
// IOSurface as a Mach send right; here the same job is a dma-buf file descriptor moved with
// SCM_RIGHTS over an abstract-namespace AF_UNIX socket.
//
// A descriptor number is meaningless in another process, so the fd cannot travel on the JSON
// control channel the way an IOSurfaceID could. SCM_RIGHTS installs the real kernel object in the
// receiver's descriptor table. It is also the only mechanism that works: a dma-buf lives on an
// anonymous inode and cannot be re-opened through /proc/<pid>/fd (ENXIO), so the cheaper
// pid-plus-number trick that the field-encode notes once suggested is not available.
//
// Built twice from this one source, exactly like macos_bridge.cc: as a Node-API addon that
// Electron's producer loads to send, and as a plain shared object the JVM opens through LWJGL to
// receive.

#include <errno.h>
#include <poll.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#if defined(FIELD_NODE_ADDON)
#include <node_api.h>
#endif

namespace {

// One accelerated paint's out-of-band payload. The descriptors themselves ride alongside in an
// SCM_RIGHTS control message; this only says how many to expect and which frame they belong to.
struct DmaBufMessage {
  std::uint64_t token;
  std::uint32_t fd_count;
  std::uint32_t reserved;
};

constexpr int kMaxFds = 4;              // a DRM modifier cannot have more than four memory planes
constexpr int kErrTimeout = -1000;      // distinct from any -errno
constexpr int kErrProtocol = -1001;
constexpr int kErrClosed = -1002;

// Abstract-namespace sockets (a leading NUL in sun_path) leave nothing on the filesystem to
// collide or to clean up after a crash, which matches the lifetime of the Mach service name the
// macOS side registers.
bool fill_abstract_address(const char* name, sockaddr_un* address, socklen_t* length) {
  if (!name || !*name) return false;
  const std::size_t name_length = std::strlen(name);
  if (name_length + 1 > sizeof(address->sun_path)) return false;
  std::memset(address, 0, sizeof(*address));
  address->sun_family = AF_UNIX;
  address->sun_path[0] = '\0';
  std::memcpy(address->sun_path + 1, name, name_length);
  *length = static_cast<socklen_t>(offsetof(sockaddr_un, sun_path) + 1 + name_length);
  return true;
}

struct DmaBufReceiver {
  int listener = -1;
  int connection = -1;
};

// SOCK_SEQPACKET keeps one sendmsg equal to one recvmsg, so a frame's descriptors can never be
// split across reads or coalesced with the next frame's the way SOCK_STREAM would allow.
int create_socket() { return ::socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0); }

int wait_readable(int fd, int timeout_ms) {
  pollfd descriptor{fd, POLLIN, 0};
  for (;;) {
    const int ready = ::poll(&descriptor, 1, timeout_ms);
    if (ready > 0) return 0;
    if (ready == 0) return kErrTimeout;
    if (errno != EINTR) return -errno;
  }
}

}  // namespace

extern "C" __attribute__((visibility("default"))) void*
field_browser_dmabuf_receiver_open(const char* name) {
  sockaddr_un address{};
  socklen_t length = 0;
  if (!fill_abstract_address(name, &address, &length)) return nullptr;

  const int listener = create_socket();
  if (listener < 0) return nullptr;
  if (::bind(listener, reinterpret_cast<sockaddr*>(&address), length) != 0 ||
      ::listen(listener, 2) != 0) {
    ::close(listener);
    return nullptr;
  }

  auto* receiver = new DmaBufReceiver();
  receiver->listener = listener;
  return receiver;
}

// Blocks for up to timeout_ms for one frame. [output] receives token, fd count, then the
// receiver-local descriptors, which the caller then owns.
extern "C" __attribute__((visibility("default"))) int
field_browser_dmabuf_receiver_receive(void* opaque, void* output, int timeout_ms) {
  if (!opaque || !output) return -EINVAL;
  auto* receiver = static_cast<DmaBufReceiver*>(opaque);

  if (receiver->connection < 0) {
    const int ready = wait_readable(receiver->listener, timeout_ms);
    if (ready != 0) return ready;
    const int accepted = ::accept4(receiver->listener, nullptr, nullptr, SOCK_CLOEXEC);
    if (accepted < 0) return -errno;
    receiver->connection = accepted;
  }

  const int ready = wait_readable(receiver->connection, timeout_ms);
  if (ready != 0) return ready;

  DmaBufMessage message{};
  iovec payload{&message, sizeof(message)};
  // CMSG_SPACE, not CMSG_LEN: the kernel needs room for the header plus alignment padding.
  char control[CMSG_SPACE(sizeof(int) * kMaxFds)];
  std::memset(control, 0, sizeof(control));

  msghdr header{};
  header.msg_iov = &payload;
  header.msg_iovlen = 1;
  header.msg_control = control;
  header.msg_controllen = sizeof(control);

  ssize_t received = 0;
  do {
    received = ::recvmsg(receiver->connection, &header, MSG_CMSG_CLOEXEC);
  } while (received < 0 && errno == EINTR);
  if (received < 0) return -errno;
  if (received == 0) return kErrClosed;
  if (received != static_cast<ssize_t>(sizeof(message))) return kErrProtocol;

  int fds[kMaxFds];
  int fd_count = 0;
  for (cmsghdr* cmsg = CMSG_FIRSTHDR(&header); cmsg; cmsg = CMSG_NXTHDR(&header, cmsg)) {
    if (cmsg->cmsg_level != SOL_SOCKET || cmsg->cmsg_type != SCM_RIGHTS) continue;
    const int in_message =
        static_cast<int>((cmsg->cmsg_len - CMSG_LEN(0)) / sizeof(int));
    for (int index = 0; index < in_message && fd_count < kMaxFds; index++) {
      int value = -1;
      std::memcpy(&value, CMSG_DATA(cmsg) + index * sizeof(int), sizeof(value));
      fds[fd_count++] = value;
    }
  }

  // A truncated control message means descriptors the kernel could not deliver; closing what did
  // arrive keeps a protocol error from also being a descriptor leak.
  if ((header.msg_flags & MSG_CTRUNC) != 0 ||
      fd_count != static_cast<int>(message.fd_count)) {
    for (int index = 0; index < fd_count; index++) ::close(fds[index]);
    return kErrProtocol;
  }

  auto* words = static_cast<std::uint64_t*>(output);
  words[0] = message.token;
  words[1] = static_cast<std::uint64_t>(fd_count);
  for (int index = 0; index < fd_count; index++) {
    words[2 + index] = static_cast<std::uint64_t>(static_cast<std::uint32_t>(fds[index]));
  }
  return 0;
}

extern "C" __attribute__((visibility("default"))) void
field_browser_dmabuf_receiver_destroy(void* opaque) {
  if (!opaque) return;
  auto* receiver = static_cast<DmaBufReceiver*>(opaque);
  if (receiver->connection >= 0) ::close(receiver->connection);
  if (receiver->listener >= 0) ::close(receiver->listener);
  delete receiver;
}

#if defined(FIELD_NODE_ADDON)
namespace {

// One connection reused for the producer's whole session: reconnecting per paint would put a
// connect/accept round trip on the frame path for no benefit.
int cached_connection = -1;
std::string cached_name;

int connect_to(const char* name) {
  sockaddr_un address{};
  socklen_t length = 0;
  if (!fill_abstract_address(name, &address, &length)) return -EINVAL;
  const int socket_fd = create_socket();
  if (socket_fd < 0) return -errno;
  if (::connect(socket_fd, reinterpret_cast<sockaddr*>(&address), length) != 0) {
    const int failure = -errno;
    ::close(socket_fd);
    return failure;
  }
  return socket_fd;
}

int send_fds(int socket_fd, const std::vector<int>& fds, std::uint64_t token) {
  DmaBufMessage message{};
  message.token = token;
  message.fd_count = static_cast<std::uint32_t>(fds.size());
  iovec payload{&message, sizeof(message)};

  char control[CMSG_SPACE(sizeof(int) * kMaxFds)];
  std::memset(control, 0, sizeof(control));

  msghdr header{};
  header.msg_iov = &payload;
  header.msg_iovlen = 1;
  header.msg_control = control;
  header.msg_controllen = CMSG_SPACE(sizeof(int) * fds.size());

  cmsghdr* cmsg = CMSG_FIRSTHDR(&header);
  cmsg->cmsg_level = SOL_SOCKET;
  cmsg->cmsg_type = SCM_RIGHTS;
  cmsg->cmsg_len = CMSG_LEN(sizeof(int) * fds.size());
  std::memcpy(CMSG_DATA(cmsg), fds.data(), sizeof(int) * fds.size());

  ssize_t sent = 0;
  do {
    sent = ::sendmsg(socket_fd, &header, MSG_NOSIGNAL);
  } while (sent < 0 && errno == EINTR);
  if (sent < 0) return -errno;
  // The descriptors stay open here on purpose: SCM_RIGHTS duplicated them into the receiver, and
  // Electron still owns these ones until the paint lease is released.
  return 0;
}

napi_value node_send_dma_buf(napi_env env, napi_callback_info info) {
  std::size_t argc = 3;
  napi_value argv[3];
  napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);
  if (argc != 3) {
    napi_throw_type_error(env, nullptr, "sendDmaBuf(socketName, fds, token) expected");
    return nullptr;
  }

  std::size_t name_size = 0;
  napi_get_value_string_utf8(env, argv[0], nullptr, 0, &name_size);
  std::string name(name_size, '\0');
  napi_get_value_string_utf8(env, argv[0], name.data(), name.size() + 1, &name_size);

  bool is_array = false;
  napi_is_array(env, argv[1], &is_array);
  std::uint32_t length = 0;
  if (!is_array || napi_get_array_length(env, argv[1], &length) != napi_ok || length == 0 ||
      length > kMaxFds) {
    napi_throw_type_error(env, nullptr, "fds must be an array of 1..4 descriptors");
    return nullptr;
  }
  std::vector<int> fds;
  fds.reserve(length);
  for (std::uint32_t index = 0; index < length; index++) {
    napi_value element;
    napi_get_element(env, argv[1], index, &element);
    std::int32_t value = -1;
    if (napi_get_value_int32(env, element, &value) != napi_ok || value < 0) {
      napi_throw_type_error(env, nullptr, "every dma-buf descriptor must be a non-negative number");
      return nullptr;
    }
    fds.push_back(value);
  }

  std::uint64_t token = 0;
  bool lossless = false;
  if (napi_get_value_bigint_uint64(env, argv[2], &token, &lossless) != napi_ok || !lossless) {
    napi_throw_type_error(env, nullptr, "token must be a lossless BigInt");
    return nullptr;
  }

  if (cached_connection >= 0 && cached_name != name) {
    ::close(cached_connection);
    cached_connection = -1;
  }
  if (cached_connection < 0) {
    const int opened = connect_to(name.c_str());
    if (opened < 0) {
      char buffer[256];
      std::snprintf(buffer, sizeof(buffer), "could not connect to browser texture socket %s: %s",
                    name.c_str(), std::strerror(-opened));
      napi_throw_error(env, nullptr, buffer);
      return nullptr;
    }
    cached_connection = opened;
    cached_name = name;
  }

  int result = send_fds(cached_connection, fds, token);
  if (result < 0) {
    // A receiver restart shows up as EPIPE/ECONNRESET on a connection that used to work; one
    // reconnect distinguishes that from a genuinely broken transport.
    ::close(cached_connection);
    cached_connection = -1;
    const int reopened = connect_to(name.c_str());
    if (reopened >= 0) {
      cached_connection = reopened;
      result = send_fds(cached_connection, fds, token);
    }
  }
  if (result < 0) {
    char buffer[256];
    std::snprintf(buffer, sizeof(buffer), "could not transfer Electron dma-buf: %s",
                  std::strerror(-result));
    napi_throw_error(env, nullptr, buffer);
    return nullptr;
  }

  napi_value sent;
  napi_create_uint32(env, static_cast<std::uint32_t>(fds.size()), &sent);
  return sent;
}

napi_value initialize(napi_env env, napi_value exports) {
  napi_value function;
  napi_create_function(env, "sendDmaBuf", NAPI_AUTO_LENGTH, node_send_dma_buf, nullptr, &function);
  napi_set_named_property(env, exports, "sendDmaBuf", function);
  return exports;
}

}  // namespace

NAPI_MODULE(NODE_GYP_MODULE_NAME, initialize)
#endif
