// field_syphon: a flat C ABI over the Syphon framework (https://github.com/Syphon/Syphon-Framework),
// for afield.syphon. macOS only. Every call is safe from any thread: Syphon objects are created
// and discovered on one private thread that runs a CFRunLoop, because Syphon's server discovery
// rides on NSDistributedNotificationCenter, which needs a run loop the JVM does not provide.
//
// Pixels are 8-bit RGBA, row-major, top row first, unless a call says otherwise.
// A negative status or a NULL handle means failure; fs_last_error() says why (thread-local).
#ifndef FIELD_SYPHON_H
#define FIELD_SYPHON_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct fs_server fs_server;
typedef struct fs_client fs_client;

// ABI version; bumped on any incompatible change. Currently 1.
int32_t fs_version(void);
// The last error on this thread, or "" (never NULL; owned by the library).
const char *fs_last_error(void);
// Frees a string returned by fs_list_servers / fs_*_description.
void fs_free_string(char *s);

// Runs the calling thread's run loop for up to `ms` (0 = handle what is pending) if, and only if,
// the caller is the process's main thread; returns 1 if it ran, 0 if not. Syphon's discovery
// (NSDistributedNotificationCenter) is delivered on the main run loop, so a process whose main
// thread does not run one (a JVM started with -XstartOnFirstThread, like Field) must call this
// from its main thread regularly, or it neither sees other servers nor answers their clients.
int32_t fs_pump(int32_t ms);

// --- servers -----------------------------------------------------------------------------------
// A Metal-backed Syphon server named `name` (UTF-8, may be NULL), announced to every process.
fs_server *fs_server_new(const char *name);
// Publishes width x height RGBA8 pixels (stride bytes per row; 0 means width*4). Copies, so the
// caller may reuse `rgba` on return. Waits until the GPU has taken the copy. Returns 0.
int32_t fs_server_publish_rgba(fs_server *s, const uint8_t *rgba, int32_t width, int32_t height, int32_t stride);
// The same for BGRA8 bytes (a Vulkan B8G8R8A8 readback, as Field's RenderToTexture makes).
int32_t fs_server_publish_bgra(fs_server *s, const uint8_t *bgra, int32_t width, int32_t height, int32_t stride);
// Publishes a BGRA8 IOSurface (an IOSurfaceRef) without a CPU copy: the surface is wrapped as a
// Metal texture and Syphon blits it into its own shared surface. Returns 0.
int32_t fs_server_publish_iosurface(fs_server *s, void *ioSurfaceRef, int32_t flipped);
// 1 if any client is attached, 0 if not, negative on error.
int32_t fs_server_has_clients(fs_server *s);
// The server's description (JSON: name, appName, uuid). Free with fs_free_string.
char *fs_server_description(fs_server *s);
// Stops and frees the server. NULL is ignored.
void fs_server_free(fs_server *s);

// --- IOSurfaces ---------------------------------------------------------------------------------
// A new BGRA8 IOSurface (pixel format 'BGRA') of width x height, its allocation rounded up to whole
// 16 KB pages so it can be imported as Vulkan host memory (VK_EXT_external_memory_host). Returns the
// IOSurfaceRef (+1 retained; CFRelease it), or NULL.
void *fs_iosurface_create(int32_t width, int32_t height);

// --- discovery ---------------------------------------------------------------------------------
// Every server currently announced, as a JSON array of {"name","appName","uuid"}. The first call
// starts discovery and waits up to `waitMs` for announcements. Free with fs_free_string.
char *fs_list_servers(int32_t waitMs);

// --- clients -----------------------------------------------------------------------------------
// Connects to the first server whose uuid equals `match`, else whose name equals `match`, else
// whose appName equals `match` (NULL or "" means the first server). Waits up to `waitMs` for it to
// appear. NULL if none did.
fs_client *fs_client_new(const char *match, int32_t waitMs);
// Frames received so far (counts Syphon's new-frame callbacks). Negative on error.
int64_t fs_client_frame_count(fs_client *c);
// Copies the latest frame into `rgba` (capacity `capacity` bytes) as RGBA8, top row first, and
// writes its size: a GPU blit into a staging buffer the client keeps (so one caller at a time per
// client), then a CPU swizzle. Returns 1 if a frame was copied, 0 if there is none yet, negative on error
// (including a too-small buffer: then *width / *height are still written).
int32_t fs_client_copy_rgba(fs_client *c, uint8_t *rgba, int64_t capacity, int32_t *width, int32_t *height);
// The IOSurface the latest frame lives in (an IOSurfaceRef, +1 retained: CFRelease it), with no
// copy: Syphon's server surface itself, BGRA8 ('BGRA'), top row first. The server keeps drawing
// into the same surface frame after frame and replaces it only when its size changes, so compare
// IOSurfaceGetID to tell. NULL if there is no frame yet (then *width = *height = 0) or on error.
void *fs_client_frame_iosurface(fs_client *c, int32_t *width, int32_t *height);
// Waits up to `waitMs` for the frame count to exceed `after`. Returns the new count, or `after`.
int64_t fs_client_wait_frame(fs_client *c, int64_t after, int32_t waitMs);
// The connected server's description (JSON). Free with fs_free_string.
char *fs_client_description(fs_client *c);
// Disconnects and frees. NULL is ignored.
void fs_client_free(fs_client *c);

#ifdef __cplusplus
}
#endif
#endif
