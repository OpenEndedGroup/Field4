# External browser surfaces

Status: the platform-neutral frame/lease contract, macOS/MoltenVK receiver, and Electron/macOS
producer transport are implemented and live-tested end to end. On Linux the receiver and the full
Electron/dma-buf producer transport are implemented and live-tested — descriptors cross, frames
import, and leases are acknowledged — but the picture is blank, because Chromium does not put the
rendered page into the dma-buf it exports (see "What Linux is still blocked on"). Windows/D3D11 is
a compile-reviewed first pass still needing both its bridge and native-machine validation.

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

### The Electron transport

`native/field-browser-bridge/linux_bridge.cc` is the twin of `macos_bridge.cc`, built twice from one
source for the same reason: a Node-API addon the producer loads to send, and a plain shared object
the JVM opens through LWJGL to receive. Keeping both halves in one translation unit is what stops
the wire struct between them drifting.

The descriptors move by `SCM_RIGHTS` over an abstract-namespace `AF_UNIX` `SOCK_SEQPACKET` socket:
abstract so a crash leaves nothing on the filesystem to clean up, seqpacket so one `sendmsg` is
always exactly one `recvmsg` and a frame's descriptors can never be split or coalesced. Only the
descriptors and the frame token travel out of band; each plane's stride, offset and size and the
allocation's DRM modifier stay on the JSON control channel, where metadata belongs. Planes name
their descriptor by index rather than by number, so that planes sharing one allocation stay one
allocation after transfer — `SCM_RIGHTS` would otherwise duplicate a shared fd into several
descriptors that look unrelated, and the receiver rejects multi-allocation pixmaps.

`SCM_RIGHTS` is not a stylistic choice. A descriptor number means nothing in another process, and
the cheaper trick of passing pid plus number and re-opening `/proc/<pid>/fd/<n>` does not work for
dma-bufs: they live on anonymous inodes and the open fails with ENXIO. (`UnixFdPass`'s note claimed
otherwise; it has been corrected.)

### What Linux is still blocked on

Two Chromium switches are load-bearing, and Field's Electron head has only half of them.
`--use-angle=vulkan` (with `--enable-features=Vulkan`) is required before Chromium will export a
paint as a native pixmap at all; on the GL backend the shared image cannot be created, Chromium
logs `Unable to initialize SkSurface`, and every `paint` silently arrives as a CPU bitmap with no
texture. `--ignore-gpu-blocklist` on its own was the worst of the three states: it enables Vulkan without
pointing ANGLE at it, which additionally makes Ozone report that Wayland and Vulkan are
incompatible. `electron/main.js` set exactly that, and now sets both switches, so Field's own head
runs coherently on ANGLE-over-Vulkan too (`ANGLE (NVIDIA, Vulkan 1.4.341, RTX 4090)`). `--ozone-platform=x11` is not an escape route: the GPU
process segfaults in a loop until Chromium gives up.

With those switches the whole chain runs. Chromium exports a single-plane `DRM_FORMAT_MOD_LINEAR`
dma-buf, the bridge moves it, the receiver imports it, the copy is recorded, and `PRESENTED` comes
back after the graphics fence. The pixels, however, are all zero, and the evidence says Chromium
never wrote them rather than that Field failed to read them. Against a page of solid
`rgb(255,64,32)`, three independent readers agree:

| reader | non-zero pixels |
| --- | --- |
| Field's receiver, imported and copied on the GPU | 0 (resident texture reads back black) |
| `mmap` of the received descriptor, outside Field entirely | 0 of 921600 |
| a separate C `VK_EXT_image_drm_format_modifier` import + GPU copy | 0 of 921600 |

The cleanest repro is an A/B against Chromium's own other output path. Same URL, same switches,
same Electron, same machine, differing only in `useSharedTexture`:

| offscreen mode | non-zero pixels |
| --- | --- |
| `useSharedTexture: false` — CPU bitmap | 921600 of 921600 |
| `useSharedTexture: true` — dma-buf | 0 of 921600 |

Chromium renders the page; only the shared-texture export is empty.

The third reader exists because the second alone is not conclusive: a CPU mapping of a device-local
allocation can read zeros spuriously. It imports the same descriptor with the same modifier and
stride and reports `reqs=3686400`, exactly the declared size, so the import is well formed — it
simply reads an empty buffer.

The result does not move across Electron 42.3.3 and 44.0.0, `argb` and `rgbaf16` shared-texture
formats, or the first paint, the fifth paint of a continuously animating page and the tenth paint of
a fully loaded `https://youtube.com` — so it is neither a first-frame race nor anything to do with
Field's own page. This is a known upstream problem, and it is **NVIDIA-plus-Wayland specific rather than Linux-wide**:
[electron#49247](https://github.com/electron/electron/issues/49247), "OSR paint events do not fire in
an NVIDIA GPU + Wayland environment", reports the same `Unable to initialize SkSurface` line, and was
closed as not planned. `reitowo`, who wrote the shared-texture implementation, dropped Linux from it
for lack of hardware ("Due to lack of development environment I choose drop Linux support for now",
[PR #46811](https://github.com/electron/electron/pull/46811)) and later
[reported it working on an AMD laptop](https://github.com/electron/electron/pull/46811#issuecomment-2859463799).
The reporter's own matrix in #49247 is the useful part:

| session | result |
| --- | --- |
| Ubuntu 24.04 Xorg, with `--enable-features=Vulkan` | works |
| Xorg without that switch | same `SkSurface` error |
| Wayland (Ubuntu and Fedora), with or without it | fails |
| XWayland | GPU process crashes, or paints arrive with `texture = null` |

The Wayland rows match this machine, and so does XWayland — every `--ozone-platform=x11` attempt
inside the Plasma session is XWayland, where the GPU process dies with 139 or compositing silently
drops to software. The Xorg row does not.

### Tested on a real Xorg session

Worth doing properly, because `supportsZeroCopyWebGpuImport` is the field that separates the working
Xorg capture in that thread (`true`) from every handle this machine produced under Wayland
(`false`). A bare Xorg session was started on a spare VT (`startx <test> -- :1`; the box needed
`xorg-server`, and an `AutoAddGPU off` snippet, because Xorg brings the NVIDIA screen up and then
auto-adds the AMD iGPU, which the live Wayland session still holds, and dies on `drmSetMaster`).

Under real Xorg the flag does flip to `true` — so this is genuinely the code path upstream reports
working. The buffer is still empty:

| variant under Xorg, `supportsZeroCopyWebGpuImport: true` | non-zero pixels |
| --- | --- |
| GPU import + copy, read immediately | 0 |
| GPU import + copy, read 3s after the paint | 0 |
| GPU import + copy of the twenty-fifth paint | 0 |
| CPU `mmap` + `DMA_BUF_IOCTL_SYNC`, read 2s after the paint | 0 |

Which rules out, in order: a Vulkan-import mistake of ours (the CPU mapping shares none of our
modifier, stride, memory-type or layout assumptions and agrees), an unflushed write, and a pool
warm-up. Zeros rather than garbage also argues the memory is genuinely zero rather than correct
bytes in a layout we are misreading.

So what `--use-angle=vulkan` buys on Wayland is getting *past* the `SkSurface` error to a real
dma-buf — further than #49247 got — but the emptiness is not the Wayland/Vulkan incoherence, since
it survives on Xorg where that incoherence is gone.

One caution before treating the upstream "Xorg works fine" report as contradicting this: the
evidence offered there is a `console.log` of `textureInfo`, which is metadata. It is the same
metadata this machine now prints under Xorg, `supportsZeroCopyWebGpuImport: true` included. Nothing
in that thread shows the *pixels* having been read back. So the two observations may not be in
conflict at all — the handle may well arrive correctly on Linux while the buffer behind it is never
filled, and only a consumer that samples it would notice.

An earlier revision of this page claimed "forcing Chromium onto the AMD iGPU gives the same empty
buffer". That was wrong and is withdrawn: the environment variables used never moved Chromium, which
stayed on the RTX 4090, and when the Vulkan ICD is genuinely restricted to RADV the GPU process will
not start against this NVIDIA-driven display. The AMD path remains untested here, and it is the one
configuration anybody has claimed a passing test on.

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
