# Syphon bridge (afield.syphon, native/field-syphon)

User page: `public/docs/syphon.md`. Starting points: `syphon_bridge` (CPU), `syphon_gpu` (renderer).

## Shape
- `native/field-syphon/build.sh` (run by `./gradlew buildSyphonNative`) clones Syphon-Framework at
  the pinned revision into `build/field-syphon/src`, builds `Syphon.framework` with xcodebuild, then
  `libfield_syphon.dylib` (flat C ABI, `field_syphon.h`, rpath `@loader_path`) and
  `field_syphon_probe` (out-of-process test peer). Uses `xcrun --sdk macosx` with Xcode's
  DEVELOPER_DIR: the CommandLineTools SDK on this machine has no Foundation headers.
- `afield.syphon`: `SyphonNative` (FFM), `syphonServer`/`syphonClient`, `SyphonGpuPublisher`
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
- `syphon_gpu` window `native-screenshot` shows green at the BOTTOM. The texture uploads top row
  first and the quad maps tc.y=0 to pos.y=-1, so the window and published frame agree in Field's
  convention; whether native-screenshot itself flips is the open "capture Y-flip" question. Not
  settled tonight: check by eye against Resolume/OBS before trusting either.

## Gaps
- Receiving is CPU (Metal blit -> shared buffer -> swizzle -> upload). Zero-copy receive would
  import Syphon's IOSurface via IOSurfaceHostBuffer.copyToImage.
- `follow`/`publish(rtt)` read back; `followOnGpu` needs VK_EXT_external_memory_host.
- Not tested against a real third-party Syphon app (none installed).
