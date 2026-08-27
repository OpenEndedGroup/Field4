# External browser surfaces

Status: the platform-neutral frame/lease contract and the macOS/MoltenVK receiver are implemented.
The Electron producer and its macOS Mach-message transport are not yet in this repository.

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

The eventual Windows backend belongs behind the same interface and should import duplicated
D3D11 NT handles using `VK_KHR_external_memory_win32`. Linux should receive dma-buf fds through
`SCM_RIGHTS`, preserve plane stride/offset/modifier metadata, and use Vulkan external-memory fd and
DRM-modifier support. Both need the same producer-lease acknowledgement discipline; neither should
pass a process-local integer handle over the JSON channel.

## Next integration slice

Build the small Electron native producer/transport first, not a browser abstraction in Kotlin.
Once it can deliver one `MacIOSurfaceMachPort` and receive one token, add the control channel and
forward pointer, scroll, keyboard, focus, navigation, and IME messages. The renderer API does not
need to change for those additions.
