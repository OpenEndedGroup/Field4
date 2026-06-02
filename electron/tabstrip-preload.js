const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('fieldTabs', {
  ready() {
    ipcRenderer.send('field4-tabs-ready');
  },
  onState(callback) {
    if (typeof callback !== 'function') return;
    ipcRenderer.on('tabs-state', (_event, state) => callback(state));
  },
  activateTab(tabId) {
    ipcRenderer.send('field4-tabs-activate', tabId);
  },
  closeTab(tabId) {
    ipcRenderer.send('field4-tabs-close', tabId);
  },
  openWelcomeTab() {
    ipcRenderer.send('field4-tabs-open-welcome');
  },
});
