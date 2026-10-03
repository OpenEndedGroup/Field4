# Electron RTT view (spike)

An afield.four `RenderToTexture` drawn straight into a box's output area in the Field Electron app,
with no readback and no encode. macOS, KosmicKrisp assumed. Branch `electron-shared-texture-spike`.

```kotlin
val view = ElectronRTTView(640, 360)
val module = moduleHere { view.rtt.apply() }
module += "mesh" to m
view.rtt += "content" to module
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

- **Release is not GPU-synchronised.** The slot is handed back after `drawImage` and
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
  `afield.docs.ElectronCaptureBrowser`). That needed two `electron/main.js` changes. With remote
  debugging on, the window's own unused `webContents` loads `about:blank`, because otherwise it has
  no renderer and Playwright's `connectOverCDP` waits forever on it. The capture Electron also gets
  its own profile (`FIELD_ELECTRON_USER_DATA`), because the user's per-host zoom had made the capture
  page render at a device pixel ratio of 1.67. It quits once the JVM that launched it is gone
  (`FIELD_ELECTRON_OWNER_PID`).
- **Public page.** `public/docs/four_in_output_areas.md`.
- **Colour.** The RTT is `B8G8R8A8_SRGB` and the import declares an sRGB transfer. This has been
  checked by eye only.
- **Installing Electron 44.5.1.** Its installer `require()`s an ES module, so `install.js` fails under
  Node < 22.12. The Gradle-managed Node is 22.12 and is fine.
