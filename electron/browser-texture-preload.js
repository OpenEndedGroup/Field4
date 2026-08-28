const { contextBridge, ipcRenderer } = require("electron");

const cloneable = (value) => {
  if (value === undefined) return null;
  return JSON.parse(JSON.stringify(value));
};

contextBridge.exposeInMainWorld("fieldPuppet", Object.freeze({
  emit(name, value = null) {
    if (typeof name !== "string" || !name || name.length > 128) {
      throw new TypeError("fieldPuppet.emit needs a non-empty name of at most 128 characters");
    }
    ipcRenderer.send("field-puppet-event", { name, value: cloneable(value) });
  },

  request(name, value = null) {
    if (typeof name !== "string" || !name || name.length > 128) {
      return Promise.reject(new TypeError("fieldPuppet.request needs a non-empty name of at most 128 characters"));
    }
    return ipcRenderer.invoke("field-puppet-request", { name, value: cloneable(value) });
  },
}));
