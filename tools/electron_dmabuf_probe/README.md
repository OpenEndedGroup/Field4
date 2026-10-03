# Vulkan → Electron nativePixmap import probe

This measures pixels from a real 64×64 `RenderToTexture` exported through
`RTTParameters.exportDmaBuf` and `LinuxDmaBufExport`, transferred through `UnixFdPass`
and the browser bridge's receive addon. It renders a solid colour, reads the Vulkan
image back as a source check, waits for the submission fence before the FD handoff,
imports the native pixmap, transfers it to the Electron renderer, and draws
`getVideoFrame()` into a canvas. `getImageData()` counts RGB pixels, excluding alpha
so an opaque black image cannot pass. There is no CPU upload or encoded image in
this path. The JVM image remains alive until the canvas verdict.

Prerequisites: Electron 44.5.1 (`npm ci --prefix electron`),
`./gradlew buildFieldBrowserBridge`, and a built `native/field-fdpass` helper
(`cmake -S native/field-fdpass -B native/field-fdpass/build` followed by
`cmake --build native/field-fdpass/build`).

Run from a real Xorg session first. The runner uses the managed JVM launcher,
which applies Field's agent JVM arguments. Use a fresh directory for every run:

```sh
uv run --group backdoor python tools/electron_dmabuf_probe/run.py /tmp/rtt-xorg-default --ozone-platform=x11
uv run --group backdoor python tools/electron_dmabuf_probe/run.py /tmp/rtt-xorg-vulkan --ozone-platform=x11 --use-angle=vulkan --enable-features=Vulkan --ignore-gpu-blocklist
```

Then test a Wayland session with `--ozone-platform=wayland`, and XWayland with
`--ozone-platform=x11`. Set `FIELD_DMABUF_WEBGPU=1` to test the native pixmap's
`supportsZeroCopyWebGpuImport` flag (default false); this flag is recorded in
`import.json`. Vulkan-on-Wayland can break on-screen window presentation even when
canvas readback works; the pixel verdict alone does not certify a visible window.

Evidence: `texture.json` has the exact Vulkan modifier/stride/offset/allocation
size and completed-fence marker, `vulkan.bgra` contains the source GPU readback,
`import.json` the actual import arguments, `result.json` the canvas verdict and
session/command information, and `electron.log` / `jvm.log` retain diagnostics.
Verify the active session separately with `loginctl`; an x11 Ozone window under a
Wayland desktop is XWayland, not real Xorg.
