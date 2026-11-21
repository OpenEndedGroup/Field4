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
  revealTabs() {
    ipcRenderer.send('field4-tabs-reveal');
  },
  hideTabs() {
    ipcRenderer.send('field4-tabs-hide');
  },
  // Back (-1) / forward (+1) in the active page tab's history.
  navigate(direction) {
    ipcRenderer.send('field4-tabs-navigate', direction);
  },
  findQuery(text) {
    ipcRenderer.send('field4-tabs-find-query', String(text ?? ''));
  },
  findStep(forward) {
    ipcRenderer.send('field4-tabs-find-step', !!forward);
  },
  findClose() {
    ipcRenderer.send('field4-tabs-find-close');
  },
});
