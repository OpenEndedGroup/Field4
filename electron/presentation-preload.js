const {contextBridge, ipcRenderer} = require('electron');

contextBridge.exposeInMainWorld('fieldPresentation', {
  action(action, id) { ipcRenderer.send('field-presentation-action', {action, id}); },
  state(info) { ipcRenderer.send('field-presentation-state', info); },
  error(message) { ipcRenderer.send('field-presentation-error', String(message)); },
  onAction(callback) {
    const listener = (_, action, id) => callback(action, id);
    ipcRenderer.on('field-presentation-action-to-audience', listener);
    return () => ipcRenderer.removeListener('field-presentation-action-to-audience', listener);
  },
  onState(callback) {
    const listener = (_, info) => callback(info);
    ipcRenderer.on('field-presentation-state-to-control', listener);
    return () => ipcRenderer.removeListener('field-presentation-state-to-control', listener);
  },
  onError(callback) {
    const listener = (_, message) => callback(message);
    ipcRenderer.on('field-presentation-error-to-control', listener);
    return () => ipcRenderer.removeListener('field-presentation-error-to-control', listener);
  },
});
