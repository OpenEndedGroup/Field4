#include <IOSurface/IOSurface.h>
#include <mach/mach.h>
#include <servers/bootstrap.h>

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>

#if defined(FIELD_NODE_ADDON)
#include <node_api.h>
#endif

namespace {

struct SurfaceMessage {
  mach_msg_header_t header;
  mach_msg_body_t body;
  mach_msg_port_descriptor_t surface;
  std::uint64_t token;
};

struct SurfaceReceiveBuffer {
  SurfaceMessage message;
  mach_msg_max_trailer_t trailer;
};

struct SurfaceReceiver {
  mach_port_t port = MACH_PORT_NULL;
  std::string service;
};

void copy_error(char* destination, std::size_t capacity, const char* message) {
  if (!destination || capacity == 0) return;
  std::snprintf(destination, capacity, "%s", message ? message : "unknown error");
}

kern_return_t bootstrap_port_for_task(mach_port_t* port) {
  return task_get_bootstrap_port(mach_task_self(), port);
}

kern_return_t send_surface(const char* service, IOSurfaceRef io_surface,
                           std::uint64_t token) {
  if (!service || !*service || !io_surface) return KERN_INVALID_ARGUMENT;

  mach_port_t bootstrap = MACH_PORT_NULL;
  kern_return_t result = bootstrap_port_for_task(&bootstrap);
  if (result != KERN_SUCCESS) return result;

  mach_port_t destination = MACH_PORT_NULL;
  result = bootstrap_look_up(bootstrap, const_cast<char*>(service), &destination);
  mach_port_deallocate(mach_task_self(), bootstrap);
  if (result != KERN_SUCCESS) return result;

  mach_port_t surface_port = IOSurfaceCreateMachPort(io_surface);
  if (surface_port == MACH_PORT_NULL) {
    mach_port_deallocate(mach_task_self(), destination);
    return KERN_FAILURE;
  }

  SurfaceMessage message{};
  message.header.msgh_bits =
      MACH_MSGH_BITS_COMPLEX | MACH_MSGH_BITS(MACH_MSG_TYPE_COPY_SEND, 0);
  message.header.msgh_size = sizeof(message);
  message.header.msgh_remote_port = destination;
  message.header.msgh_local_port = MACH_PORT_NULL;
  message.header.msgh_id = 0x4649454c;  // "FIEL"
  message.body.msgh_descriptor_count = 1;
  message.surface.name = surface_port;
  message.surface.disposition = MACH_MSG_TYPE_COPY_SEND;
  message.surface.type = MACH_MSG_PORT_DESCRIPTOR;
  message.token = token;

  result = mach_msg(&message.header, MACH_SEND_MSG, sizeof(message), 0,
                    MACH_PORT_NULL, MACH_MSG_TIMEOUT_NONE, MACH_PORT_NULL);
  mach_port_deallocate(mach_task_self(), surface_port);
  mach_port_deallocate(mach_task_self(), destination);
  return result;
}

}  // namespace

extern "C" __attribute__((visibility("default"))) void*
field_browser_mach_receiver_create(const char* service, char* error,
                                   std::size_t error_capacity) {
  if (!service || !*service) {
    copy_error(error, error_capacity, "service name is empty");
    return nullptr;
  }

  auto* receiver = new SurfaceReceiver();
  receiver->service = service;
  kern_return_t result = mach_port_allocate(
      mach_task_self(), MACH_PORT_RIGHT_RECEIVE, &receiver->port);
  if (result == KERN_SUCCESS) {
    result = mach_port_insert_right(mach_task_self(), receiver->port,
                                    receiver->port, MACH_MSG_TYPE_MAKE_SEND);
  }

  mach_port_t bootstrap = MACH_PORT_NULL;
  if (result == KERN_SUCCESS) result = bootstrap_port_for_task(&bootstrap);
  if (result == KERN_SUCCESS) {
    result = bootstrap_register(bootstrap, const_cast<char*>(service),
                                receiver->port);
  }
  if (bootstrap != MACH_PORT_NULL) {
    mach_port_deallocate(mach_task_self(), bootstrap);
  }

  if (result != KERN_SUCCESS) {
    char buffer[160];
    std::snprintf(buffer, sizeof(buffer),
                  "could not register Mach service %s: %s (%d)", service,
                  mach_error_string(result), result);
    copy_error(error, error_capacity, buffer);
    if (receiver->port != MACH_PORT_NULL) {
      mach_port_destroy(mach_task_self(), receiver->port);
    }
    delete receiver;
    return nullptr;
  }
  return receiver;
}

extern "C" __attribute__((visibility("default"))) void*
field_browser_mach_receiver_open(const char* service) {
  return field_browser_mach_receiver_create(service, nullptr, 0);
}

extern "C" __attribute__((visibility("default"))) int
field_browser_mach_receiver_receive(void* opaque, void* output,
                                    int timeout_ms) {
  if (!opaque || !output) return KERN_INVALID_ARGUMENT;
  auto* receiver = static_cast<SurfaceReceiver*>(opaque);

  SurfaceReceiveBuffer buffer{};
  SurfaceMessage& message = buffer.message;
  mach_msg_option_t options = MACH_RCV_MSG;
  mach_msg_timeout_t timeout = MACH_MSG_TIMEOUT_NONE;
  if (timeout_ms >= 0) {
    options |= MACH_RCV_TIMEOUT;
    timeout = static_cast<mach_msg_timeout_t>(timeout_ms);
  }
  kern_return_t result = mach_msg(
      &message.header, options, 0, sizeof(buffer), receiver->port, timeout,
      MACH_PORT_NULL);
  if (result != KERN_SUCCESS) return result;
  if (!(message.header.msgh_bits & MACH_MSGH_BITS_COMPLEX) ||
      message.body.msgh_descriptor_count != 1 ||
      message.surface.type != MACH_MSG_PORT_DESCRIPTOR) {
    return MACH_RCV_INVALID_DATA;
  }

  auto* words = static_cast<std::uint64_t*>(output);
  words[0] = message.token;
  words[1] = static_cast<std::uint64_t>(message.surface.name);
  return KERN_SUCCESS;
}

extern "C" __attribute__((visibility("default"))) void
field_browser_mach_receiver_destroy(void* opaque) {
  if (!opaque) return;
  auto* receiver = static_cast<SurfaceReceiver*>(opaque);
  mach_port_t bootstrap = MACH_PORT_NULL;
  if (bootstrap_port_for_task(&bootstrap) == KERN_SUCCESS) {
    bootstrap_register(bootstrap, const_cast<char*>(receiver->service.c_str()),
                       MACH_PORT_NULL);
    mach_port_deallocate(mach_task_self(), bootstrap);
  }
  if (receiver->port != MACH_PORT_NULL) {
    mach_port_destroy(mach_task_self(), receiver->port);
  }
  delete receiver;
}

#if defined(FIELD_NODE_ADDON)
namespace {

void throw_last(napi_env env, const char* context, kern_return_t result) {
  char message[256];
  std::snprintf(message, sizeof(message), "%s: %s (%d)", context,
                mach_error_string(result), result);
  napi_throw_error(env, nullptr, message);
}

napi_value node_send_surface(napi_env env, napi_callback_info info) {
  std::size_t argc = 3;
  napi_value argv[3];
  napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);
  if (argc != 3) {
    napi_throw_type_error(env, nullptr,
                          "sendIOSurface(service, handleBuffer, token) expected");
    return nullptr;
  }

  std::size_t service_size = 0;
  napi_get_value_string_utf8(env, argv[0], nullptr, 0, &service_size);
  std::string service(service_size, '\0');
  napi_get_value_string_utf8(env, argv[0], service.data(), service.size() + 1,
                             &service_size);

  void* data = nullptr;
  std::size_t size = 0;
  bool is_buffer = false;
  napi_is_buffer(env, argv[1], &is_buffer);
  if (!is_buffer || napi_get_buffer_info(env, argv[1], &data, &size) != napi_ok ||
      size != sizeof(std::uintptr_t)) {
    napi_throw_type_error(env, nullptr,
                          "Electron IOSurface handle must be a pointer-sized Buffer");
    return nullptr;
  }

  std::uint64_t token = 0;
  bool lossless = false;
  if (napi_get_value_bigint_uint64(env, argv[2], &token, &lossless) != napi_ok ||
      !lossless) {
    napi_throw_type_error(env, nullptr, "token must be a lossless BigInt");
    return nullptr;
  }

  std::uintptr_t pointer = 0;
  std::memcpy(&pointer, data, sizeof(pointer));
  auto io_surface = reinterpret_cast<IOSurfaceRef>(pointer);
  kern_return_t result = send_surface(service.c_str(), io_surface, token);
  if (result != KERN_SUCCESS) {
    throw_last(env, "could not transfer Electron IOSurface", result);
    return nullptr;
  }

  napi_value id;
  napi_create_uint32(env, IOSurfaceGetID(io_surface), &id);
  return id;
}

napi_value initialize(napi_env env, napi_value exports) {
  napi_value function;
  napi_create_function(env, "sendIOSurface", NAPI_AUTO_LENGTH,
                       node_send_surface, nullptr, &function);
  napi_set_named_property(env, exports, "sendIOSurface", function);
  return exports;
}

}  // namespace

NAPI_MODULE(NODE_GYP_MODULE_NAME, initialize)
#endif
