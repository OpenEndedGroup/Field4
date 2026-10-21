# Electron RTT view (spike)

A Field 4 `RenderToTexture` drawn straight into a box's output area in the Field Electron app,
with no readback and no encode. macOS (KosmicKrisp) and Linux native Wayland (verified on NVIDIA).

```kotlin
val view = ElectronRTTView(640, 360)
val module = moduleHere { +view }
module += "mesh" to m
view += "content" to module
display("main", view.html())
```

## How a frame travels

1. **Allocation (once per widget mount).** The widget calls `fieldElectron.rttView.open(...)`. Electron
   main (`electron/rtt-view-host.js`) creates a ring of 3 BGRA IOSurfaces (`createIOSurface` in
   `native/field-browser-bridge/macos_bridge.cc`), imports each once with
   `sharedTexture.importSharedTexture`, and sends each to Field over Mach with the existing
   `sendIOSurface`. Field (`ElectronRTTView`) receives them on its per-view Mach service and wraps each
   as an `IOSurfaceHostBuffer`.
2. **Each tick.** `ElectronRTTView.render` renders `rtt`. If a slot is free, it records one GPU copy
   image → that IOSurface on the same command buffer.
3. **After the fence.** Field pushes `{slot, seq}` to the page (a `sendWithReply` subscription). The
   page calls `present`, Electron main calls `sharedTexture.sendSharedTexture` for that slot's import,
   the preload's receiver (`electron/field-preload.js`) draws the `VideoFrame` into the canvas, and the
   page sends `electron-rtt-release`, which frees the slot.

## Measured (2026-10-03, M-series, KosmicKrisp, Electron 44.5.1, 640×360, ring of 3)

6847 frames at ~93 fps (the renderer's rate). In ms after the frame's fence completed, over the
last 600 frames:

| hop | p50 | p90 | p99 | max |
| --- | --- | --- | --- | --- |
| fence → Electron main (JVM WebSocket → page → IPC) | 0.72 | 5.46 | 14.17 | 72.66 |
| fence → renderer receives the texture | 1.38 | 6.12 | 14.55 | 72.92 |
| fence → drawn into the canvas | 1.59 | 6.38 | 14.75 | 73.22 |

`sendSharedTexture` itself costs about 0.66 ms at the median and the draw about 0.2 ms. The tail
comes from the WebSocket and page hop, not from the shared texture. 139 ticks (2%) found all three
slots in flight and skipped the copy.

## Known gaps

- **macOS release is not GPU-synchronised.** The slot is handed back after `drawImage` and
  `importedSharedTexture.release()`, not after Chromium's GPU process has finished reading it. Use
  `subtle.release(callback)` or sync tokens before trusting this under load.
- **Lifecycle.** A view belongs to the box that made it. A re-run replaces it (keyed by box uid), and
  deleting the box or closing the document releases it (`RunScope`, `ReleasePolicy.KEYED`); a stop
  leaves it drawing. Nothing closes it if its renderer is disposed, which documents never do, but the
  test harness did, and device teardown reported the host buffers as leaked.
- **Caching.** A `cached<T>(renderer) { ... }` macro, keyed by its arguments and the type, would
  generalise the per-box keying. It is a separate ticket.
- **Signalling hop.** Frame-ready goes JVM → WebSocket → page → IPC → main. A direct JVM ↔ Electron-main
  channel would cut the dominant (and noisiest) hop.
- **Errors.** A failed `sendSharedTexture` never frees its slot.
- **Electron only.** The HTTP mirror and external browsers show a "needs the Field Electron app"
  label instead. Docs captures go through Electron itself (`capture-profile="macos-electron"`,
  `afield.tools.docs.ElectronCaptureBrowser`). That needed two `electron/main.js` changes. With remote
  debugging on, the window's own unused `webContents` loads `about:blank`, because otherwise it has
  no renderer and Playwright's `connectOverCDP` waits forever on it. The capture Electron also gets
  its own profile (`FIELD_ELECTRON_USER_DATA`), because the user's per-host zoom had made the capture
  page render at a device pixel ratio of 1.67. It quits once the JVM that launched it is gone
  (`FIELD_ELECTRON_OWNER_PID`).
- **Other platforms.** Linux native Wayland is implemented through dma-buf; see the verified results
  below. Xorg and XWayland are rejected with a panel error after their imports produced blank pixels.
  Windows/D3D11 is not implemented.
- **Public page.** `public/docs/four_in_output_areas.md`.
- **Colour.** The RTT is `B8G8R8A8_SRGB` and the import declares an sRGB transfer. This has been
  checked by eye only.
- **Installing Electron 44.5.1.** Its installer `require()`s an ES module, so `install.js` fails under
  Node < 22.12. The Gradle-managed Node is 22.12 and is fine.

## Linux import spike (2026-10-03, RTX 4090, NVIDIA 610.43.02, Electron 44.5.1)

The reverse direction was tested independently of the empty Chromium OSR export described in
`external_browser_surfaces.md`. The source is a real 64×64 `RenderToTexture` created with
`RTTParameters.exportDmaBuf`; `LinuxDmaBufExport` exports it, `UnixFdPass` transfers the descriptor
via SCM_RIGHTS, and the Linux browser-bridge Node addon receives it. Electron imports
`handle.nativePixmap`, sends the shared texture to its renderer, and the preload draws
`getVideoFrame()` into a canvas. The verdict counts RGB pixels with `getImageData`, excluding alpha.
The source GPU readback contains 4096/4096 coloured pixels, BGRA `[99,137,255,255]`.

The producer releases queue-family ownership to `VK_QUEUE_FAMILY_EXTERNAL` in `GENERAL` layout,
then waits for its submission fence before transferring the FD. Its immutable allocation stays
alive until the canvas verdict; no implicit dma-buf sync or subsequent producer write is involved.
The driver selected modifier `216172782120099859`, stride 256, offset 0, allocation size 16384.

### Real Xorg (`DISPLAY=:1`, NVIDIA Xorg server on VT3)

| Chromium switches | WebGPU import flag | Canvas RGB pixels | First RGBA |
| --- | --- | --- | --- |
| `--ozone-platform=x11` | false | 0/4096 | `[0,0,0,255]` |
| above + `--ignore-gpu-blocklist` | false | 0/4096 | `[0,0,0,255]` |
| above + `--use-angle=vulkan --enable-features=Vulkan` | true | 0/4096 | `[0,0,0,0]` |
| Vulkan + `--enable-gpu-rasterization --enable-zero-copy` | false | 0/4096 | `[0,0,0,0]` |

Without ANGLE/Vulkan, Chromium logs `Could not find SharedImageBackingFactory`,
`SharedImageStub: Unable to create shared image`, and `CopySharedImage: unknown source image mailbox`.
With ANGLE/Vulkan, import and transfer return without throwing, but the canvas is transparent black.
The same source check passes in every completed run. Earlier trials before the explicit ownership
release were also blank; they are not the basis of the table above.

### Native Wayland and XWayland (KDE active on VT1)

| Session / switches | WebGPU import flag | Canvas RGB pixels | First RGBA |
| --- | --- | --- | --- |
| Wayland, `--ozone-platform=wayland`, default GL | false | 4096/4096 | `[255,137,99,255]` |
| XWayland, `--ozone-platform=x11`, default GL | false | 0/4096 | `[0,0,0,255]` |
| XWayland + ANGLE/Vulkan and blocklist override | true | 0/4096 | `[0,0,0,0]` |

The positive result is native Wayland, with no ANGLE/Vulkan flags. XWayland GL reports the same
shared-image factory/mailbox errors as real Xorg; XWayland Vulkan returns transparent black.
No Vulkan-on-Wayland switch was needed, so the previously observed on-screen presentation failure
from forcing that backend is avoided.

### Linux view implementation and full-app verification

The JVM allocates three exportable `RenderToTexture` slots and sends their dma-buf descriptors
through `UnixFdPass` to the Node addon receiver. Rendering into the public `rtt` is followed by
one GPU image-to-image copy into a free slot, an external-ownership release in `GENERAL` layout,
and the graphics fence. Only then is the page told to present the slot. There is no image-to-buffer
copy, CPU upload/readback, or encode in the view's production path.

The Node host retains the ring FDs. Each presented frame obtains a fresh Chromium import lease;
main releases its reference after transfer, the preload closes the VideoFrame and releases its
reference after drawing, and `allReferencesReleased` acknowledges Chromium's GPU completion.
Only that acknowledgement frees the JVM slot. This intentionally trades import-once caching for
an explicit GPU completion boundary; the macOS import-once path is unchanged. Initial frame
notifications wait for the ring-open handshake, and removing the output DOM closes the Node ring.
The Linux format is BGRA8 sRGB, and ring size is limited to the transport's four FDs.

The actual Field Electron app displayed the public moving-square example at 640×360. Two
readbacks eight seconds apart contained 28224 coloured pixels each, with RGB sums 16956240 and
14224056: content changed while the ring continued to be reused. The panel reported 19.7–20.0 fps
and 0.82–0.83 ms average fence-to-draw latency in that run. This is a Linux measurement, not the
macOS 85–120 fps figure. Electron capture now resolves the Linux executable as well as the Mac app.
Evidence and the retained real screenshot are linked from the public page.

The reproducible probe is `tools/electron_dmabuf_probe/README.md` and
`afield.media.stream.LinuxElectronImportProbe`. Electron's versioned import implementation accepts
the decimal modifier string and duplicates the plane FDs:
[Electron 44.5.1 import source](https://github.com/electron/electron/blob/v44.5.1/shell/common/api/electron_api_shared_texture.cc).
