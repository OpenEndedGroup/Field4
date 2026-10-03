// Run with Electron 44.5.1. FIELD_DMABUF_PROBE_DIR must name a fresh evidence directory.
const { app, BrowserWindow, ipcMain, sharedTexture } = require('electron');
const fs = require('node:fs');
const path = require('node:path');
const directory = process.env.FIELD_DMABUF_PROBE_DIR;
if (!directory) throw new Error('Set FIELD_DMABUF_PROBE_DIR to a fresh directory');
fs.mkdirSync(directory, { recursive: true });
app.setPath('userData', path.join(directory, 'profile'));
const bridge = require(path.resolve(__dirname, '../../build/field-browser-bridge/linux/field_browser_bridge.node'));
const listener = bridge.openDmaBufReceiver(path.join(directory, 'fds.sock'));
let ownedFds = [], imported, finished = false;
const finish = (result) => {
  if (finished) return;
  finished = true;
  fs.writeFileSync(path.join(directory, 'result.json'), JSON.stringify({
    electron: process.versions.electron, argv: process.argv,
    session: process.env.XDG_SESSION_TYPE, display: process.env.DISPLAY,
    waylandDisplay: process.env.WAYLAND_DISPLAY, ...result
  }, null, 2));
  console.log('PIXEL VERDICT', JSON.stringify(result));
  try { imported?.release(); } catch (_) {}
  ownedFds.forEach(fd => bridge.closeDmaBuf(fd));
  bridge.closeDmaBuf(listener);
  setTimeout(() => app.quit(), 100);
};
app.on('child-process-gone', (_, details) => console.error('CHILD GONE', details));
app.whenReady().then(async () => {
  console.log('GPU', JSON.stringify(await app.getGPUInfo('basic')));
  const win = new BrowserWindow({ width: 320, height: 240, show: true,
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, sandbox: false }
  });
  ipcMain.once('probe-result', (_, result) => finish(result));
  ipcMain.once('probe-ready', () => {
    const interval = setInterval(async () => {
      try {
        const fds = bridge.receiveDmaBuf(listener);
        if (!fds) return;
        clearInterval(interval);
        ownedFds = fds;
        const info = JSON.parse(fs.readFileSync(path.join(directory, 'texture.json'), 'utf8'));
        if (!info.fenceCompleted || fds.length !== 1) throw new Error('Invalid fence/FD metadata');
        const textureInfo = {
          pixelFormat: 'bgra', codedSize: { width: info.width, height: info.height },
          colorSpace: { primaries: 'bt709', transfer: 'srgb', matrix: 'rgb', range: 'full' },
          handle: { nativePixmap: {
            planes: [{ stride: info.stride, offset: info.offset, size: info.size, fd: fds[0] }],
            modifier: info.modifier,
            supportsZeroCopyWebGpuImport: process.env.FIELD_DMABUF_WEBGPU === '1'
          } }
        };
        fs.writeFileSync(path.join(directory, 'import.json'), JSON.stringify(textureInfo, null, 2));
        console.log('GPU COMPLETE', JSON.stringify(await app.getGPUInfo('complete')));
        console.log('IMPORT', JSON.stringify(textureInfo));
        imported = sharedTexture.importSharedTexture({ textureInfo });
        await sharedTexture.sendSharedTexture({ frame: win.webContents.mainFrame, importedSharedTexture: imported });
      } catch (error) { clearInterval(interval); finish({ ok: false, error: String(error.stack || error) }); }
    }, 25);
  });
  await win.loadURL('data:text/html,<canvas id="probe" width="64" height="64" style="width:256px;height:256px"></canvas>');
});
setTimeout(() => finish({ ok: false, error: 'Timed out waiting for Vulkan image or canvas verdict' }), 120000);
