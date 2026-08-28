### Preflight Checklist

- [x] I have read the Contributing Guidelines for this project.
- [x] I agree to follow the Code of Conduct that this project adheres to.
- [x] I have searched the issue tracker for a bug report that matches the one I want to file, without success.

### Electron Version

44.0.0 (also reproduced on 42.3.3)

### What operating system(s) are you using?

Other Linux

### Operating System Version

CachyOS (Arch), kernel 7.1.3-1-cachyos, x86_64. KDE Plasma 6 / kwin_wayland, and separately a bare Xorg session on a spare VT.

### What arch are you using?

x64

### Last Known Working Electron version

None known on Linux.

### Expected Behavior

With `webPreferences.offscreen.useSharedTexture: true`, the dma-buf referenced by
`textureInfo.handle.nativePixmap` should contain the rendered page.

### Actual Behavior

The `paint` event fires, `textureInfo` is fully populated and plausible, and the dma-buf is a real,
importable allocation of exactly the expected size — but every byte of it is zero.

This is **not** #49247. There, `paint` never fires with a texture. Here the handle arrives and is
structurally correct; only its contents are missing. It also reproduces where
`supportsZeroCopyWebGpuImport` is `true`, which is the configuration #49247 treats as working.

The clearest way to see it uses only Electron's own APIs — `sharedTexture.importSharedTexture`,
`sendSharedTexture`, and `VideoFrame.copyTo`. No native addon, no external consumer:

Under KDE Plasma / Wayland:

```
paint#5  supportsZeroCopyWebGpuImport=false  modifier=0  stride=5120
imported textureId=578d48a9-...
VideoFrame 1280x720 format=BGRA bytes=3686400
nonzeroPixels=0/921600  firstPixel=[0,0,0,0]
```

And on a bare Xorg session on a spare VT, where the flag is `true` — the configuration #49247
treats as working — the same script, unchanged:

```
session: tty  DISPLAY=:1  WAYLAND_DISPLAY=unset
paint#5  supportsZeroCopyWebGpuImport=true  modifier=0  stride=5120
imported textureId=14f9f92c-186a-4e2d-ac3a-07d73971ff53
VideoFrame 1280x720 format=BGRA bytes=3686400
nonzeroPixels=0/921600  firstPixel=[0,0,0,0]
```

The page under test is a solid, continuously animating background, so a correct buffer cannot be
zero. The A/B against Chromium's other output path, same URL, same switches, same machine, differing
only in `useSharedTexture`:

| offscreen mode | non-zero pixels |
| --- | --- |
| `useSharedTexture: false` (CPU bitmap) | 921600 / 921600 |
| `useSharedTexture: true` (dma-buf) | 0 / 921600 |

Chromium renders the page; only the shared-texture export is empty.

#### What has been ruled out

Four independent readers agree the memory is zero — Electron's own import + WebCodecs readback; a
`VK_EXT_image_drm_format_modifier` import plus GPU copy; a CPU `mmap` with `DMA_BUF_IOCTL_SYNC`; and
a third-party Vulkan consumer. The CPU mapping shares none of the Vulkan importers' assumptions
about modifier, stride, memory type or layout, and still reads zero. Zeros rather than garbage
argues the memory is genuinely zero rather than correct bytes read through a wrong layout.

Also ruled out:

- **Timing.** Reading 3 seconds after the paint, while still holding the lease, is unchanged.
- **Warm-up.** The 25th paint of a continuously animating page is unchanged.
- **First-frame effects.** Same at paint 1, 5, 10 and 25.
- **Content.** Same for a `data:` URL, a local app, and a fully loaded `https://youtube.com`.
- **Format.** Same for `argb` (stride 5120) and `rgbaf16` (stride 10240).
- **Electron version.** Same on 42.3.3 and 44.0.0.
- **Session type.** Same under Wayland and under a real Xorg session (not XWayland).
- **Vulkan/ANGLE coherence.** See below.

#### Interaction with #49247, and one caution

On Wayland, Chromium logs `Unable to initialize SkSurface` and delivers `paint` with no texture,
exactly as #49247 reports. Adding **`--use-angle=vulkan`** alongside `--enable-features=Vulkan`
clears that: ANGLE moves onto Vulkan (`ANGLE (NVIDIA, Vulkan 1.4.341, RTX 4090)`), the error stops,
and a real dma-buf is delivered. `--enable-features=Vulkan` on its own is not enough, and
`--ignore-gpu-blocklist` on its own is worse than neither, because it enables Vulkan while leaving
ANGLE on GL — which is also when Ozone reports Wayland and Vulkan to be incompatible.

That may be useful to #49247 on its own. But it does not fix the buffer, and neither does Xorg. On a
bare Xorg session `supportsZeroCopyWebGpuImport` flips to `true` and all four readers still see
zero.

The caution: the "Ubuntu 24.04 Xorg — works fine" result in #49247 is evidenced by a `console.log`
of `textureInfo`, which is metadata. That is the same metadata this machine prints under Xorg,
`supportsZeroCopyWebGpuImport: true` included. I could not find anywhere in that thread, or in the
shared-texture docs, where the *pixels* are read back and checked. So the two observations may not
conflict: the handle can be correct while the buffer behind it is never filled, and only a consumer
that samples it would notice. If someone with a known-good Linux setup can run the script below and
report a non-zero count, that would localise this to NVIDIA immediately.

### Testcase Gist URL

Two files, no dependencies beyond Electron.

`main.js`:

```js
const { app, BrowserWindow, sharedTexture, ipcMain } = require("electron");
const path = require("node:path");
app.commandLine.appendSwitch("enable-features", "Vulkan");
app.commandLine.appendSwitch("use-angle", "vulkan"); // needed on Wayland; harmless on Xorg
const ANIM = "data:text/html,<style>@keyframes s{from{background:%23ff4020}to{background:%2320ff80}}body{margin:0;animation:s 1s infinite alternate}</style><body><h1 style='color:white;font-size:100px'>HELLO</h1></body>";
let done = false, paints = 0;

ipcMain.on("rt-result", (_e, r) => {
  if (r.error) console.log("readback failed: " + r.error);
  else console.log(`VideoFrame ${r.w}x${r.h} ${r.format} nonzeroPixels=${r.nonzero}/${r.total}`,
                   r.nonzero > 0 ? "=> CONTENT" : "=> BLANK");
  app.quit();
});

app.whenReady().then(async () => {
  const sink = new BrowserWindow({ show: false, webPreferences: {
    preload: path.join(__dirname, "preload.js"), contextIsolation: false, sandbox: false } });
  await sink.loadURL("data:text/html,<body>sink</body>");

  const win = new BrowserWindow({ show: false, width: 1280, height: 720, webPreferences: {
    offscreen: { useSharedTexture: true, sharedTexturePixelFormat: "argb", deviceScaleFactor: 1.0 } } });
  win.webContents.setFrameRate(20);
  win.webContents.on("paint", async (e) => {
    const t = e.texture; if (!t) return;
    if (done || ++paints < 5) { t.release(); return; }
    done = true;
    const info = t.textureInfo;
    console.log("paint#" + paints, JSON.stringify(info.handle));
    const imported = sharedTexture.importSharedTexture({ textureInfo: info });
    await sharedTexture.sendSharedTexture({ frame: sink.webContents.mainFrame, importedSharedTexture: imported });
    t.release();
  });
  win.loadURL(ANIM);
  setTimeout(() => { console.log("gave up, paints=" + paints); app.quit(); }, 40000);
});
app.on("window-all-closed", () => {});
```

`preload.js`:

```js
const { sharedTexture, ipcRenderer } = require("electron");
sharedTexture.setSharedTextureReceiver(async (data) => {
  try {
    const frame = data.importedSharedTexture.getVideoFrame();
    const buf = new Uint8Array(frame.allocationSize());
    await frame.copyTo(buf);
    let nz = 0;
    for (let i = 0; i < buf.length; i += 4) if (buf[i] | buf[i + 1] | buf[i + 2]) nz++;
    ipcRenderer.send("rt-result", { w: frame.codedWidth, h: frame.codedHeight,
      format: frame.format, nonzero: nz, total: buf.length / 4 });
    frame.close();
  } catch (err) { ipcRenderer.send("rt-result", { error: String(err && err.stack || err) }); }
});
```

Expected on a working platform: `=> CONTENT`. Observed here: `=> BLANK`, on both session types.

### Additional Information

GPU: NVIDIA GeForce RTX 4090, proprietary driver 610.43.02. The machine also has an AMD Raphael
iGPU, but Chromium runs on the NVIDIA card throughout (confirmed via
`WEBGL_debug_renderer_info`); the AMD path could not be tested, because restricting the Vulkan ICD
to RADV prevents the GPU process from starting against this NVIDIA-driven display.

`--ozone-platform=x11` inside a Wayland session is XWayland and is not a substitute: the GPU process
dies with exit code 139, matching #49247. The Xorg results above are from a genuine X server started
on a spare VT.
