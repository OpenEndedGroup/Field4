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
