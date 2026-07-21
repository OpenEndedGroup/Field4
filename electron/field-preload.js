const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("fieldElectron", {
  captureHistoryScreenshot() {
    return ipcRenderer.invoke("field4-history-capture-window");
  },
  diagnostic(event, details = {}) {
    ipcRenderer.send("field4-renderer-diagnostic", { event, details });
  },
});
