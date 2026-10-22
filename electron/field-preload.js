const { contextBridge, ipcRenderer, sharedTexture } = require("electron");

const linuxDrawTimes = new Map();
const frameKey = m => `${m.viewId}:${m.generation}:${m.slot}:${m.seq}`;

const nowMs = () => performance.timeOrigin + performance.now();

// The renderer half of the RTT view (see rtt-view-host.js). Frames arrive here, in the isolated
// world, as imported shared textures; the DOM is shared with the page, so this draws each one into
// the view's canvas and tells the page (a `field-rtt-drawn` event on that canvas) so it can hand
// the slot back to Field.
if (sharedTexture && ["darwin", "linux"].includes(process.platform)) {
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
      if (process.platform === "linux") linuxDrawTimes.set(frameKey(meta), { receivedAt, drawnAt: nowMs() });
      importedSharedTexture.release();
    }
    if (process.platform === "linux") return; // Main acknowledges only after Chromium's GPU release.
    if (canvas) {
      canvas.dispatchEvent(
        new CustomEvent("field-rtt-drawn", { detail: { ...meta, receivedAt, drawnAt: nowMs() } }),
      );
    }
  });
}

ipcRenderer.on("field4-rtt-released", (_, meta) => {
  const times = linuxDrawTimes.get(frameKey(meta)) || { receivedAt: nowMs(), drawnAt: nowMs() };
  linuxDrawTimes.delete(frameKey(meta));
  window.dispatchEvent(new CustomEvent("field-rtt-drawn", { detail: { ...meta, ...times } }));
});
ipcRenderer.on("field4-rtt-error", (_, meta) => {
  const label = document.querySelector(`[data-field-rtt-stats="${CSS.escape(meta.viewId)}"]`);
  if (label) label.textContent = "RTT view: " + meta.error;
});

ipcRenderer.on("field4-presentation-sources", (_, paths) => {
  if (!Array.isArray(paths) || paths.length > 64 || !paths.every(path => typeof path === "string")) return;
  window.dispatchEvent(new CustomEvent("field-presentation-sources", { detail: paths }));
});
ipcRenderer.on("field4-presentation-project", (_, paths) => {
  if (!Array.isArray(paths) || paths.length > 64 || !paths.every(path => typeof path === "string")) return;
  window.dispatchEvent(new CustomEvent("field-presentation-project", { detail: paths }));
});

contextBridge.exposeInMainWorld("fieldElectron", {
  presentationTypeScript(request) {
    return ipcRenderer.invoke("field-presentation-tsx", request);
  },
  identity() {
    return ipcRenderer.invoke("field4-tab-identity");
  },
  outputIframe(request) {
    return ipcRenderer.invoke("field4-live-output-iframe", request);
  },
  captureHistoryScreenshot() {
    return ipcRenderer.invoke("field4-history-capture-window");
  },
  // "granted" | "denied" | "restricted" | "not-determined" (macOS); always "granted" elsewhere.
  microphoneAccess() {
    return ipcRenderer.invoke("field4-microphone-access");
  },
  // Signs in to an aaaframe server in a Field-owned window and returns { ok, base, login, session }
  // (see aaaframe-sign-in.js). `force` discards a remembered login first.
  aaaframeSignIn(url, force = false) {
    return ipcRenderer.invoke("field4-aaaframe-sign-in", { url, force });
  },
  diagnostic(event, details = {}) {
    ipcRenderer.send("field4-renderer-diagnostic", { event, details });
  },
  rttView: sharedTexture && ["darwin", "linux"].includes(process.platform)
    ? {
        gpuRelease: process.platform === "linux",
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
