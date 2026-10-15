# Syphon bridge (afield.io.syphon, native/field-syphon)

User page: `public/docs/syphon.md`. Starting points: `syphon_bridge` (CPU), `syphon_gpu` (renderer).

## Shape
- `native/field-syphon/build.sh` (run by `./gradlew buildSyphonNative`) clones Syphon-Framework at
  the pinned revision into `build/field-syphon/src`, builds `Syphon.framework` with xcodebuild, then
  `libfield_syphon.dylib` (flat C ABI, `field_syphon.h`, rpath `@loader_path`) and
  `field_syphon_probe` (out-of-process test peer). Uses `xcrun --sdk macosx` with Xcode's
  DEVELOPER_DIR: the CommandLineTools SDK on this machine has no Foundation headers.
- `afield.io.syphon`: `SyphonNative` (FFM), `syphonServer`/`syphonClient`, `SyphonGpuPublisher`
  (`followOnGpu`), `SyphonTexture` (`SyphonClient.texture()`).

## Traps (measured)
- **Discovery needs the MAIN run loop.** Syphon announces/discovers via
  NSDistributedNotificationCenter, delivered only on the process main thread's run loop (a server
  made on a private run-loop thread with an idle main thread is invisible to a later
  `field_syphon_probe list`; verified with a test binary). Field runs with -XstartOnFirstThread, so
  its main thread is Field's Runloop: `Syphon.ensurePump` adds a `Runloop.preRun` hook calling
  `fs_pump(0)`. A plain JVM (jvmTest) needs nothing: the launcher parks the first thread in a
  CFRunLoop.
- **Box code runs ON the main thread.** A box that blocks (e.g. `Process.waitFor()` on a Syphon
  peer) stops the pump for that long; run such waits on a thread. `fs_client_new` / `fs_list_servers`
  pump while they wait when called on the main thread.
- A server's announcement at creation reaches listeners already running; later clients need the
  server process to answer an announce request (main run loop again).

## Verification
- `SyphonTest` (4 tests): JVM -> probe and probe -> JVM, exact RGBA at five points incl.
  orientation; skips without the library.
- Live in Field (port 9601, 2026-10-03): `syphon_bridge` round trip image correct; an external
  probe discovered Field's server and read (20,24,40); Field received the probe's pattern exactly.
- `followOnGpu`: clear colour (1,0.5,0) read by an external probe as (255,188,0) (sRGB of 0.5);
  gradient target read as R 137/225 at x=w/4,3w/4, G 224/137 at y=h/4,3h/4, B 149: exact sRGB.
  Green is high at the TOP of the published frame (Field's renderer draws +y up).
- `syphon_gpu` window: the receive quad used to map tc.y=0 to pos.y=-1, which in Field's window
  is the bottom, so the received picture was upside down. Fixed (top corners get v=0); boxes run one
  at a time on 2026-10-03, `native-screenshot` shows green-to-yellow along the TOP, dark blue at
  the bottom left, matching the published frame.
- Zero-copy receive: `syphon_zero_copy_receive_smoke.yaml`. `field_syphon_probe` (separate
  process) serves 64x48 then 96x64; a texelFetch shader samples the SyphonTexture into an RTT,
  read back exactly ((17,34,51), red/green over blue/white: colour order and orientation) at
  both sizes; imports=2, `Syphon.cpuCopies` and `SyphonTexture.cpuCopies` both 0 over ~420
  frames. PASS on KosmicKrisp (HOST_LINEAR_IMAGE), MoltenVK (IMPORTED_IMAGE) and, forced with
  `-Dafield.syphon.receive=host_buffer_copy`, HOST_BUFFER_COPY; 0 validation errors.
- Cost at 1920x1080, render-thread time per new frame (`SyphonTexture.recordNanos / frames`,
  ~230 frames each, same Field session, one probe serving): KosmicKrisp: copy path 4269 us,
  host buffer + GPU copy 219 us, linear image 50 us. MoltenVK: copy 4582 us, host buffer 170 us,
  imported image 51 us. The zero-copy figures include the one-off import amortised; steady state
  is one `newFrameImage` + an IOSurface id compare. The copy path also costs an 8 MB GPU upload,
  not counted here.

## Gaps
- Receive (`SyphonTexture`, `SyphonClient.texture()`) is zero-copy on both drivers: the client's
  IOSurface comes out through `fs_client_frame_iosurface` (+1 retained). MoltenVK: the IOSurface
  backs a VkImage (`VK_EXT_metal_objects`). KosmicKrisp: no IOSurface image import (see
  KOSMICKRISP.md), but its pages import as host memory (`VK_EXT_external_memory_host`) and bind to
  a LINEAR B8G8R8A8_SRGB VkImage whose row pitch equals the surface's; checked per import, and
  falling back to a host buffer + one GPU copy, then to the old CPU path (now reusing its
  MTLBuffer). BGRA is handled by the VkFormat. Re-imports on a new IOSurface id (size change);
  the old import is freed by `afterCurrentGraphicsFrameComplete`. No cross-process sync: like any
  Syphon client we sample the surface while the sender may be drawing the next frame (tearing
  possible in principle, not seen).
- `follow`/`publish(rtt)` read back; `followOnGpu` needs VK_EXT_external_memory_host.
- Not tested against a real third-party Syphon app (none installed).
