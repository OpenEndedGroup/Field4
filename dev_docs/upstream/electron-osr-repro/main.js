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
