const { contextBridge, ipcRenderer, sharedTexture } = require("electron");

const nowMs = () => performance.timeOrigin + performance.now();

// The renderer half of the RTT view (see rtt-view-host.js). Frames arrive here, in the isolated
// world, as imported shared textures; the DOM is shared with the page, so this draws each one into
// the view's canvas and tells the page (a `field-rtt-drawn` event on that canvas) so it can hand
// the slot back to Field.
if (sharedTexture && process.platform === "darwin") {
  sharedTexture.setSharedTextureReceiver(async ({ importedSharedTexture }, meta) => {
    const receivedAt = nowMs();
    const canvas = document.querySelector(`canvas[data-field-rtt-view="${CSS.escape(meta.viewId)}"]`);
    try {
      if (canvas) {
        const frame = importedSharedTexture.getVideoFrame();
        canvas.getContext("2d").drawImage(frame, 0, 0, canvas.width, canvas.height);
        frame.close();
      }
    } finally {
      importedSharedTexture.release();
    }
    if (canvas) {
      canvas.dispatchEvent(
        new CustomEvent("field-rtt-drawn", { detail: { ...meta, receivedAt, drawnAt: nowMs() } }),
      );
    }
  });
}

contextBridge.exposeInMainWorld("fieldElectron", {
  captureHistoryScreenshot() {
    return ipcRenderer.invoke("field4-history-capture-window");
  },
  diagnostic(event, details = {}) {
    ipcRenderer.send("field4-renderer-diagnostic", { event, details });
  },
  rttView: sharedTexture && process.platform === "darwin"
    ? {
        open(options) {
          return ipcRenderer.invoke("field4-rtt-open", options);
        },
        present(viewId, slot, seq, readyAt, generation) {
          ipcRenderer.send("field4-rtt-present", { viewId, slot, seq, readyAt, generation });
        },
        close(viewId) {
          ipcRenderer.send("field4-rtt-close", { viewId });
        },
      }
    : null,
});
