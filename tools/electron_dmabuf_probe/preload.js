const { ipcRenderer, sharedTexture } = require('electron');
sharedTexture.setSharedTextureReceiver(async ({ importedSharedTexture }) => {
  let frame;
  try {
    frame = importedSharedTexture.getVideoFrame();
    const canvas = document.getElementById('probe');
    const ctx = canvas.getContext('2d', { willReadFrequently: true });
    ctx.drawImage(frame, 0, 0, 64, 64);
    const pixels = ctx.getImageData(0, 0, 64, 64).data;
    let nonBlack = 0, opaque = 0;
    const colours = new Set();
    for (let i = 0; i < pixels.length; i += 4) {
      if (pixels[i] || pixels[i+1] || pixels[i+2]) nonBlack++;
      if (pixels[i+3] === 255) opaque++;
      colours.add(Array.from(pixels.slice(i, i+4)).join(','));
    }
    ipcRenderer.send('probe-result', { ok: nonBlack === 4096, nonBlack, opaque,
      total: 4096, firstRgba: Array.from(pixels.slice(0,4)), colours: [...colours].slice(0,16),
      frameFormat: frame.format, codedWidth: frame.codedWidth, codedHeight: frame.codedHeight });
  } catch (error) {
    ipcRenderer.send('probe-result', { ok: false, error: String(error.stack || error) });
  } finally { frame?.close(); importedSharedTexture.release(); }
});
window.addEventListener('DOMContentLoaded', () => ipcRenderer.send('probe-ready'));
