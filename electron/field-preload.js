const { contextBridge, ipcRenderer, sharedTexture, webUtils } = require("electron");

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

// Browser keys for a page tab (Documentation, Welcome, history, ...): Cmd/Ctrl+F opens the tab strip's
// find bar, Cmd/Ctrl+G steps through matches, Cmd+[ / Cmd+] (Alt+Left / Alt+Right elsewhere) go back
// and forward, as do the mouse side buttons. A Field document tab (`?file=`) is left alone: its
// canvas, Monaco and drawing layer own those keys, and documentSearch.kt opens the same find bar as a
// box search through `fieldElectron.canvasFind` when Cmd+F is not Monaco's. The listener is on window in the bubble phase and
// skips an event a page already handled, so an editor embedded in a page keeps its own Cmd+F. Main
// checks the tab again before acting.
const isFieldDocumentPage = () => {
  try {
    return !!new URLSearchParams(window.location.search).get("file")?.trim();
  } catch (_) {
    return true;
  }
};

const pageKeyCommand = (event) => {
  const mac = process.platform === "darwin";
  const mod = mac ? event.metaKey && !event.ctrlKey : event.ctrlKey && !event.metaKey;
  const key = String(event.key || "").toLowerCase();
  if (mod && !event.altKey && !event.shiftKey && (key === "f" || event.code === "KeyF")) return ["field4-tab-find-open"];
  if (mod && !event.altKey && (key === "g" || event.code === "KeyG")) return ["field4-tab-find-step", !event.shiftKey];
  if (mac && mod && !event.altKey && !event.shiftKey) {
    if (event.key === "[" || event.code === "BracketLeft") return ["field4-tab-navigate", -1];
    if (event.key === "]" || event.code === "BracketRight") return ["field4-tab-navigate", 1];
  }
  if (!mac && event.altKey && !event.ctrlKey && !event.metaKey && !event.shiftKey) {
    if (event.key === "ArrowLeft") return ["field4-tab-navigate", -1];
    if (event.key === "ArrowRight") return ["field4-tab-navigate", 1];
  }
  return null;
};

window.addEventListener("keydown", (event) => {
  if (event.defaultPrevented || event.isComposing || isFieldDocumentPage()) return;
  const command = pageKeyCommand(event);
  if (!command) return;
  event.preventDefault();
  ipcRenderer.send(...command);
});

window.addEventListener(
  "mouseup",
  (event) => {
    if ((event.button !== 3 && event.button !== 4) || isFieldDocumentPage()) return;
    event.preventDefault();
    ipcRenderer.send("field4-tab-navigate", event.button === 3 ? -1 : 1);
  },
  true,
);

contextBridge.exposeInMainWorld("fieldElectron", {
  chooseKitFolder() { return ipcRenderer.invoke("field4-kit-folder"); },
  filePath(file) { return webUtils.getPathForFile(file); },
  presentGuide(state) {
    ipcRenderer.send("field-presentation-native-state", state);
  },
  openPresentationLink(href) {
    ipcRenderer.send("field-presentation-open-link", String(href));
  },
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
  // Box search in the tab strip's find bar (documentSearch.kt): `open()` shows the bar, `onCommand`
  // receives { action: "query", query } | { action: "step", forward } | { action: "close" } from it,
  // and `report` sends back { matches, active (1-based, 0 = none), detail } for its counter.
  canvasFind: {
    open() {
      ipcRenderer.send("field4-canvas-find-open");
    },
    onCommand(callback) {
      if (typeof callback !== "function") return;
      ipcRenderer.on("field4-canvas-find", (_event, command) => callback(command));
    },
    report(result) {
      ipcRenderer.send("field4-canvas-find-result", {
        matches: Number(result?.matches) || 0,
        active: Number(result?.active) || 0,
        detail: String(result?.detail ?? ""),
      });
    },
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
