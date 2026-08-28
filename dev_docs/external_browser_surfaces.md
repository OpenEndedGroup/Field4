# External browser surfaces

Status: the platform-neutral frame/lease contract, macOS/MoltenVK receiver, and Electron/macOS
producer transport are implemented and live-tested end to end. The Linux/dma-buf receiver is
live-tested against real dma-bufs, but against a Field-side producer: its Electron handle-transfer
bridge does not exist yet. Windows/D3D11 is a compile-reviewed first pass still needing both its
bridge and native-machine validation.

This is the texture seam for web UI that should appear on geometry in Field/OpenXR. It does not
choose the browser. Electron is the immediate producer because its accelerated offscreen `paint`
event exposes a [`SharedTexture`](https://www.electronjs.org/docs/latest/api/structures/offscreen-shared-texture)
and an explicit `release()` lease. Servo or another producer can feed the same receiver contract if
it can export native images and observe release acknowledgements.

## Contract

`ExternalBrowserSurface` is a stable, fixed-size Field texture and a thread-safe frame inbox:

```text
browser GPU image + lease
        |
        | native-handle transfer + ExternalBrowserFrame metadata
        v
platform import --GPU copy--> stable resident VkImage --> Field material / VR panel
        |                              |
        +-------- transport close      +-- fence --> BrowserFrameRelease --> producer release()
```

The copy is deliberate. Chromium owns a short-lived image and may recycle it after `release()`;
Field materials need a texture that remains valid between paints and across descriptor-set use.
One on-GPU copy buys a simple lifetime boundary and avoids sampling a producer allocation after its
lease ends.

The negotiated `ExternalBrowserSurfaceSpec` fixes width, height, pixel format, scale, and slot
count. Resize means creating a replacement surface; it does not mutate a `VkImageView` that might
already be in flight. `streamId` increases when the producer is replaced, `frameId` increases
within a stream, and `releaseToken` is opaque producer state echoed by `BrowserFrameRelease`.
The inbox conflates pending paints and immediately releases stale, invalid, superseded, and closed
leases. A handle is announced on first use of each slot in a stream and can then be omitted while
the receiver's import remains cached. Electron's primaries, transfer, matrix, and range strings are
preserved in `BrowserSurfaceColorSpace`; the GPU copy does not silently reinterpret them.

The texture path is separate from navigation and input. URL/load/reload and Electron
`webContents.sendInputEvent` messages are ordinary request/reply IPC and can evolve without being
coupled to GPU-handle ownership.

## macOS implementation

`MacExternalBrowserSurface` accepts either:

- `MacIOSurfaceMachPort`, the real cross-process path; or
- `MacIOSurfaceId`, only for same-process tests and explicitly global IOSurfaces such as the
  existing `field-encode` ring.

Electron documents its macOS shared-texture handle as an `IOSurfaceRef`. That pointer is local to
Electron's process; putting its bytes, or a Mach port *name*, into JSON does not transfer anything.
The producer-side native bridge must:

1. read the `IOSurfaceRef` from Electron's shared-texture handle;
2. call `IOSurfaceCreateMachPort`;
3. transfer the send right to Field in a Mach message;
4. send the frame metadata and token on the control channel; and
5. retain Electron's `SharedTexture` until Field returns that token.

The received port name is local to Field. The implementation calls `IOSurfaceLookupFromMachPort`,
deallocates the received send right, imports the retained `IOSurfaceRef` into a Vulkan image through
`VK_EXT_metal_objects`, and caches that import per stream slot. It records the import-to-resident
copy before sampling and returns `PRESENTED` only from `Renderer.afterCurrentGraphicsFrameComplete`.
The IOSurface retain is released after the imported Vulkan image is destroyed.

This exact receiver path has a live macOS smoke: a MoltenVK render target exported an IOSurface,
the browser surface resolved it through a Mach send right and copied it, the resident image read
back the expected BGRA pixel, and the release callback arrived after the graphics fence. The
replayable graph is `dev_docs/agents/transcripts/external_browser_surface_macos_smoke.yaml`.

The producer is `electron/browser-texture-producer.js`, launched by `FieldUiBrowserTexture`. It
opens a hidden Electron window with `offscreen.useSharedTexture`, points it at the current Field
document, and keeps every `OffscreenSharedTexture` lease until Field returns the frame token. The
small Node-API bridge in `native/field-browser-bridge/macos_bridge.cc` reads Electron's documented
pointer-sized IOSurface buffer, creates a Mach send right, and sends it to a uniquely named bootstrap
service owned by the JVM. Frame metadata and fence-delayed release tokens use a loopback control
socket; the GPU allocation itself never enters that socket and is never read back through the CPU.

`dev_docs/agents/transcripts/field_ui_browser_texture.yaml` is the end-to-end proof and a materialized
starting point. Its hidden browser waits until the panel box exists in the embedded Field DOM, then
the native window samples the following paint on a mesh. The tracked replay reached that condition
on frame 15 and captured the two live Field boxes, their connection, running stripes, and Field's
own notifications from the imported texture.

## Windows implementation

`WindowsExternalBrowserSurface` imports `WindowsD3D11NtHandle` with
`VK_EXTERNAL_MEMORY_HANDLE_TYPE_D3D11_TEXTURE_BIT`. The producer cannot send Electron's handle
integer directly because the documented `ntHandle` is local to Electron. A native producer must
use `DuplicateHandle` to create a receiver-local NT handle in Field, then send its numeric value on
the control channel. Field queries the handle's compatible memory types, makes a dedicated import,
binds it to a matching Vulkan image, acquires ownership from `VK_QUEUE_FAMILY_EXTERNAL`, and closes
its duplicate because Vulkan has retained the underlying allocation. After the copy it returns the
image to `GENERAL` layout and external ownership before acknowledging the lease. Handles rejected
or superseded before import are also closed.

The Windows backend follows the working personal prototype but shares the bounded slot cache,
stable resident copy, stream reset, and fence acknowledgement with macOS. It has compiled on macOS;
the required next check is a Windows replay that enables Vulkan validation, submits a genuinely
duplicated Electron/D3D11 handle, reads back the resident pixel, and observes `PRESENTED`.

Electron documents that RGBA/BGRA/RGBAF16 output textures do not use a keyed mutex. If Electron
adds NV12 to the negotiated surface formats, keyed-mutex acquisition and multi-plane handling must
be designed before accepting it; this interface intentionally rejects NV12 today.

## Linux implementation

`LinuxExternalBrowserSurface` receives Electron `nativePixmap` fds via `SCM_RIGHTS`, never as bare
JSON integers. It creates the image with `VK_EXT_image_drm_format_modifier`, preserving every
plane's byte offset and row pitch, and imports the allocation with
`VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT`. Vulkan consumes fd ownership only after a
successful memory import; before that point, rejected or failed frames close their receiver-local
fds. Each copy acquires the image from `VK_QUEUE_FAMILY_FOREIGN_EXT` and releases it back after the
copy, before the producer lease is acknowledged.

This supports one unique dma-buf allocation with one or more modifier layout planes. That is the
intended accelerated RGB/BGRA/RGBAF16 browser path. A nativePixmap containing distinct fds fails
with an actionable error: supporting it correctly requires disjoint image creation, per-memory-plane
requirement queries and `vkBindImageMemory2`, which should be added against a real Electron/driver
combination rather than guessed at.

Before building anything from a received handle, the importer asks
`vkGetPhysicalDeviceImageFormatProperties2` whether this device can import that
format/modifier/usage tuple at all. Without that question an unsupported combination arrives as a
bare `vkCreateImage` failure naming neither the modifier nor the reason. The Linux device path
enables and checks `VK_KHR_external_memory_fd`, `VK_EXT_external_memory_dma_buf`,
`VK_EXT_image_drm_format_modifier`, and `VK_EXT_queue_family_foreign`.

This receiver path has a live Linux smoke, and unlike the macOS one it also needed a producer.
`LinuxDmaBufExport` (with `Image.exportDmaBuf`/`RTTParameters.exportDmaBuf`) is that producer: a
Field render target whose backing allocation carries a driver-chosen DRM format modifier and exports
as a dma-buf fd. It exists because a dma-buf is only interpretable by another driver if a modifier
describes its tiling, which is precisely what the existing `exportOpaqueFd` NVENC path does *not*
carry — opaque fd is a same-vendor handshake and cannot stand in for a browser handle. Rather than
name a modifier, the producer offers every modifier the device can both build the image from and
share, and reads back the one the driver chose; a producer that hard-codes a modifier is guessing on
the consumer's behalf.

The smoke renders that target, releases it to `VK_QUEUE_FAMILY_FOREIGN_EXT`, imports the fd through
the receiver, and reads the expected BGRA pixel back out of the resident image after a post-fence
`PRESENTED`. It then covers the two paths a single-frame test would miss: a second paint that omits
its handle, which must copy from the cached slot import and re-acquire the same allocation from the
foreign queue family without corrupting the resident image; and a frame the mailbox must reject,
whose receiver-local fd must be closed rather than leaked. Verified on NVIDIA proprietary
610.43.02 (RTX 4090, modifier `0x0300000000606013`) with Vulkan validation enabled and silent. The
same recipe was checked against Mesa RADV 26.1.4 outside Field, which chooses a different modifier
— the negotiation is not NVIDIA-shaped. The replayable graph is
`dev_docs/agents/transcripts/external_browser_surface_linux_smoke.yaml`.

What this does *not* cover is the transport: the smoke exports and imports inside one process, so
the fd never crosses a process boundary. `UnixFdPass` can only send descriptors today, so the
Electron-side bridge that the macOS Mach-port path already has still has to be written and, with
it, an `SCM_RIGHTS` receive path.

## What came from the Windows prototype

`src/personal/kotlin/organic/textureStreaming.kt` established the useful core: import the D3D11
allocation, copy into a resident sampled image, restore layouts, and close the received NT handle
after Vulkan has retained the memory. The general contract keeps that shape while tightening the
parts that matter for a browser process:

- a finite slot ring rather than an unbounded set of seen handles;
- latest-frame conflation before command recording;
- fixed texture identity and explicit resize replacement;
- stream generations so a restarted producer cannot reuse stale imports; and
- one release acknowledgement for every offered producer lease, with `PRESENTED` delayed until the
  GPU fence.

The Windows and Linux backends live behind the same construction point. Linux has since passed its
native validation/readback smoke; Windows intentionally stops short of claiming runtime support
until it passes its own.

## Next integration slice

Carry the now-working Electron session protocol across the remaining handle transfers:
`DuplicateHandle` into Field on Windows and Unix-domain `SCM_RIGHTS` on Linux. Then forward pointer,
scroll, keyboard, focus, navigation, and IME messages over the existing control channel. The
renderer API does not need to change for those additions.
