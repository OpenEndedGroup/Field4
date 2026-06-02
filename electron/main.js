const fs = require('node:fs');
const path = require('node:path');
const { app, BrowserWindow, Menu, WebContentsView, ipcMain } = require('electron');

const APP_NAME = 'Field4';
const APP_ICON_1024 = path.join(__dirname, 'icon-1024.png');
const TAB_STRIP_PRELOAD = path.join(__dirname, 'tabstrip-preload.js');
const TAB_STRIP_HEIGHT = 44;
const TAB_TRAFFIC_LIGHT_INSET = process.platform === 'darwin' ? 88 : 12;
const WITH_DEVTOOLS = ['1', 'true', 'yes', 'on'].includes(String(process.env.WITH_DEVTOOLS || '').toLowerCase());

app.setName(APP_NAME);
process.title = APP_NAME;
app.commandLine.appendSwitch('ignore-certificate-errors');

function normalizeUrl(target, base) {
  try {
    return new URL(String(target), base).toString();
  } catch (_) {
    return String(target || '');
  }
}

function filenameFromUrl(rawUrl) {
  try {
    const parsed = new URL(rawUrl);
    const file = parsed.searchParams.get('file');
    if (file && file.trim()) return file.trim();

    const pathname = parsed.pathname.replace(/\/+$/, '');
    if (pathname.endsWith('/welcome')) return 'welcome';
    if (pathname.endsWith('/dir')) return '/dir';
    if (pathname.endsWith('/index.html')) return 'document';
    if (parsed.hostname) return parsed.hostname;
  } catch (_) {
    // Ignore invalid URLs and fall back below.
  }
  return 'welcome';
}

function isWelcomeUrl(rawUrl) {
  try {
    return new URL(rawUrl).pathname.replace(/\/+$/, '').endsWith('/welcome');
  } catch (_) {
    return false;
  }
}

function titleForUrl(rawUrl) {
  return `${APP_NAME} — ${filenameFromUrl(rawUrl)}`;
}

function createTabStripMarkup() {
  return `<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="color-scheme" content="dark">
  <style>
    :root {
      --traffic-inset: ${TAB_TRAFFIC_LIGHT_INSET}px;
      --bg: rgba(16, 16, 18, 0.96);
      --tab-bg: rgba(255, 255, 255, 0.06);
      --tab-border: rgba(255, 255, 255, 0.12);
      --tab-active-bg: rgba(72, 201, 255, 0.16);
      --tab-active-border: rgba(72, 201, 255, 0.38);
      --text: rgba(221, 235, 244, 0.95);
      --text-soft: rgba(173, 198, 214, 0.82);
      --cyan: rgb(72, 201, 255);
    }

    * {
      box-sizing: border-box;
      font-family: system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
    }

    html, body {
      margin: 0;
      width: 100%;
      height: 100%;
      overflow: hidden;
      background: transparent;
      color: var(--text);
      user-select: none;
    }

    #tabstrip {
      height: 100%;
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 7px 10px 7px var(--traffic-inset);
      background: var(--bg);
      -webkit-app-region: drag;
    }

    #tabs {
      min-width: 0;
      display: flex;
      align-items: center;
      gap: 6px;
      overflow-x: auto;
      scrollbar-width: none;
      -webkit-app-region: no-drag;
    }

    #tabs::-webkit-scrollbar {
      display: none;
    }

    .tab {
      display: inline-flex;
      align-items: center;
      gap: 8px;
      min-width: 110px;
      max-width: 240px;
      padding: 6px 8px 6px 10px;
      border-radius: 9px;
      border: 1px solid var(--tab-border);
      background: var(--tab-bg);
      color: var(--text-soft);
      cursor: pointer;
    }

    .tab.active {
      background: var(--tab-active-bg);
      border-color: var(--tab-active-border);
      color: var(--text);
    }

    .tab:hover {
      border-color: rgba(255, 255, 255, 0.22);
      color: var(--text);
    }

    .tab-label {
      flex: 1 1 auto;
      min-width: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      font-size: 12px;
    }

    .tab-close,
    .tab-add {
      width: 22px;
      height: 22px;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      border-radius: 999px;
      border: 1px solid transparent;
      background: transparent;
      color: inherit;
      cursor: pointer;
      font-size: 14px;
      line-height: 1;
    }

    .tab-close:hover,
    .tab-add:hover {
      border-color: rgba(255, 255, 255, 0.16);
      background: rgba(255, 255, 255, 0.08);
    }

    .tab-add {
      flex: 0 0 auto;
      border-color: rgba(72, 201, 255, 0.18);
      color: var(--cyan);
      background: rgba(72, 201, 255, 0.08);
      -webkit-app-region: no-drag;
    }

    .tab-spacer {
      flex: 1 1 auto;
      min-width: 12px;
    }
  </style>
</head>
<body>
  <div id="tabstrip">
    <div id="tabs"></div>
    <div class="tab-spacer"></div>
    <button id="tab-add" class="tab-add" type="button" aria-label="Open welcome tab">+</button>
  </div>
  <script>
    const tabsNode = document.getElementById('tabs');
    const addButton = document.getElementById('tab-add');

    function render(state) {
      const tabs = Array.isArray(state?.tabs) ? state.tabs : [];
      tabsNode.innerHTML = tabs.map((tab) => {
        const title = String(tab.title || 'welcome')
          .replace(/&/g, '&amp;')
          .replace(/</g, '&lt;')
          .replace(/>/g, '&gt;')
          .replace(/"/g, '&quot;')
          .replace(/'/g, '&#39;');
        const activeClass = tab.active ? ' active' : '';
        return \`
          <button type="button" class="tab\${activeClass}" data-tab-id="\${tab.id}" aria-pressed="\${tab.active ? 'true' : 'false'}">
            <span class="tab-label">\${title}</span>
            <span class="tab-close" data-tab-close="\${tab.id}" aria-label="Close \${title}">&times;</span>
          </button>
        \`;
      }).join('');

      tabsNode.querySelectorAll('[data-tab-id]').forEach((node) => {
        node.addEventListener('click', (event) => {
          const closeNode = event.target.closest('[data-tab-close]');
          if (closeNode) return;
          window.fieldTabs.activateTab(node.getAttribute('data-tab-id'));
        });
      });

      tabsNode.querySelectorAll('[data-tab-close]').forEach((node) => {
        node.addEventListener('click', (event) => {
          event.preventDefault();
          event.stopPropagation();
          window.fieldTabs.closeTab(node.getAttribute('data-tab-close'));
        });
      });
    }

    addButton.addEventListener('click', () => window.fieldTabs.openWelcomeTab());
    window.fieldTabs.onState(render);
    window.fieldTabs.ready();
  </script>
</body>
</html>`;
}

function installAppMenu() {
  if (process.platform !== 'darwin') return;

  const template = [
    {
      label: APP_NAME,
      submenu: [
        { role: 'about' },
        { type: 'separator' },
        { role: 'services' },
        { type: 'separator' },
        { role: 'hide' },
        { role: 'hideOthers' },
        { role: 'unhide' },
        { type: 'separator' },
        { role: 'quit' },
      ],
    },
    {
      label: 'Edit',
      submenu: [
        { role: 'undo' },
        { role: 'redo' },
        { type: 'separator' },
        { role: 'cut' },
        { role: 'copy' },
        { role: 'paste' },
        { role: 'selectAll' },
      ],
    },
    {
      label: 'View',
      submenu: [
        { role: 'reload' },
        { role: 'forceReload' },
        { role: 'toggleDevTools' },
        { type: 'separator' },
        { role: 'resetZoom' },
        { role: 'zoomIn' },
        { role: 'zoomOut' },
        { type: 'separator' },
        { role: 'togglefullscreen' },
      ],
    },
    {
      label: 'Window',
      submenu: [
        { role: 'minimize' },
        { role: 'zoom' },
        { type: 'separator' },
        { role: 'front' },
      ],
    },
  ];

  Menu.setApplicationMenu(Menu.buildFromTemplate(template));
}

function installDockIcon() {
  if (process.platform !== 'darwin') return;
  if (!app.dock || typeof app.dock.setIcon !== 'function') return;
  if (!fs.existsSync(APP_ICON_1024)) {
    console.warn(`[electron] Dock icon not found at ${APP_ICON_1024}. Add a 1024x1024 PNG there.`);
    return;
  }

  app.dock.setIcon(APP_ICON_1024);
}

function createMainWindow() {
  const initialUrl = normalizeUrl(process.env.APP_URL || 'https://localhost:9092/welcome');
  const welcomeUrl = normalizeUrl('/welcome', initialUrl);
  const win = new BrowserWindow({
    width: 1400,
    height: 900,
    title: titleForUrl(initialUrl),
    backgroundColor: '#111111',
    autoHideMenuBar: true,
    titleBarStyle: 'hidden',
    titleBarOverlay: {
      color: '#111111',
      symbolColor: '#dcedf4',
      height: TAB_STRIP_HEIGHT,
    },
    trafficLightPosition: process.platform === 'darwin' ? { x: 16, y: 14 } : undefined,
  });

  if (process.platform !== 'darwin') {
    win.removeMenu();
  }

  const tabStripView = new WebContentsView({
    webPreferences: {
      preload: TAB_STRIP_PRELOAD,
      contextIsolation: true,
      sandbox: false,
    },
  });
  const tabStripUrl = `data:text/html;charset=UTF-8,${encodeURIComponent(createTabStripMarkup())}`;
  win.contentView.addChildView(tabStripView);
  tabStripView.webContents.loadURL(tabStripUrl);

  let nextTabId = 0;
  const tabs = [];
  let activeTabId = null;

  const currentBounds = () => {
    const [width, height] = win.getContentSize();
    return { width, height };
  };

  const activeTab = () => tabs.find((tab) => tab.id === activeTabId) || null;

  const sendTabState = () => {
    if (tabStripView.webContents.isDestroyed()) return;
    tabStripView.webContents.send('tabs-state', {
      tabs: tabs.map((tab) => ({
        id: tab.id,
        title: tab.title,
        active: tab.id === activeTabId,
        welcome: isWelcomeUrl(tab.url),
      })),
      activeTabId,
    });
  };

  const syncWindowTitle = () => {
    const tab = activeTab();
    win.setTitle(titleForUrl(tab ? tab.url : welcomeUrl));
  };

  const ensureStripOnTop = () => {
    try {
      win.contentView.removeChildView(tabStripView);
    } catch (_) {
      // View is either not attached yet or already on top.
    }
    win.contentView.addChildView(tabStripView);
  };

  const applyLayout = () => {
    const { width, height } = currentBounds();
    tabStripView.setBounds({ x: 0, y: 0, width, height: TAB_STRIP_HEIGHT });
    const tab = activeTab();
    if (!tab) return;
    tab.view.setBounds({
      x: 0,
      y: TAB_STRIP_HEIGHT,
      width,
      height: Math.max(0, height - TAB_STRIP_HEIGHT),
    });
  };

  const maybeOpenDevTools = (tab) => {
    if (!WITH_DEVTOOLS || !tab || tab.devtoolsOpened) return;
    tab.view.webContents.openDevTools({ mode: 'detach' });
    tab.devtoolsOpened = true;
  };

  const activateTab = (tabId) => {
    const next = tabs.find((tab) => tab.id === tabId);
    if (!next) return;

    const previous = activeTab();
    if (previous && previous !== next) {
      try {
        win.contentView.removeChildView(previous.view);
      } catch (_) {
        // The previous view may already be detached.
      }
    }

    activeTabId = next.id;

    try {
      win.contentView.removeChildView(next.view);
    } catch (_) {
      // The next view is not attached yet.
    }
    win.contentView.addChildView(next.view);
    ensureStripOnTop();
    applyLayout();
    syncWindowTitle();
    sendTabState();
    maybeOpenDevTools(next);
    setImmediate(() => {
      if (!next.view.webContents.isDestroyed()) {
        next.view.webContents.focus();
      }
    });
  };

  const closeTab = (tabId) => {
    const index = tabs.findIndex((tab) => tab.id === tabId);
    if (index === -1) return;
    const tab = tabs[index];

    if (tabs.length === 1) {
      if (!isWelcomeUrl(tab.url)) {
        tab.url = welcomeUrl;
        tab.title = filenameFromUrl(welcomeUrl);
        tab.view.webContents.loadURL(welcomeUrl);
      }
      activateTab(tab.id);
      return;
    }

    const wasActive = activeTabId === tabId;
    tabs.splice(index, 1);

    try {
      win.contentView.removeChildView(tab.view);
    } catch (_) {
      // The tab may be inactive.
    }
    tab.view.webContents.destroy();

    if (!wasActive) {
      sendTabState();
      return;
    }

    const replacement = tabs[index] || tabs[index - 1] || tabs[0];
    if (replacement) {
      activateTab(replacement.id);
    } else {
      sendTabState();
      syncWindowTitle();
    }
  };

  const openTab = (targetUrl, { activate = true, baseUrl = initialUrl } = {}) => {
    const normalizedUrl = normalizeUrl(targetUrl, baseUrl);
    const tab = {
      id: `tab-${++nextTabId}`,
      url: normalizedUrl,
      title: filenameFromUrl(normalizedUrl),
      devtoolsOpened: false,
      view: new WebContentsView({
        webPreferences: {
          contextIsolation: true,
          sandbox: false,
        },
      }),
    };

    const updateTitle = (url = tab.view.webContents.getURL() || tab.url) => {
      tab.url = url || tab.url;
      tab.title = filenameFromUrl(tab.url);
      if (tab.id === activeTabId) {
        syncWindowTitle();
      }
      sendTabState();
    };

    tab.view.webContents.setWindowOpenHandler(({ url }) => {
      openTab(url, { activate: true, baseUrl: tab.url });
      return { action: 'deny' };
    });

    tab.view.webContents.on('will-navigate', (event, url) => {
      const currentUrl = tab.view.webContents.getURL() || tab.url;
      const target = normalizeUrl(url, currentUrl || initialUrl);
      if (isWelcomeUrl(currentUrl) && !isWelcomeUrl(target)) {
        event.preventDefault();
        openTab(target, { activate: true, baseUrl: currentUrl || initialUrl });
      }
    });

    tab.view.webContents.on('did-navigate', (_event, url) => updateTitle(url));
    tab.view.webContents.on('did-navigate-in-page', (_event, url) => updateTitle(url));
    tab.view.webContents.on('did-finish-load', () => {
      updateTitle();
      if (tab.id === activeTabId) {
        maybeOpenDevTools(tab);
      }
    });

    tabs.push(tab);
    if (activate) {
      activateTab(tab.id);
    } else {
      sendTabState();
    }
    tab.view.webContents.loadURL(normalizedUrl);
    return tab;
  };

  const onlyFromStrip = (handler) => (event, ...args) => {
    if (event.sender !== tabStripView.webContents) return;
    handler(...args);
  };

  const onTabsReady = onlyFromStrip(() => sendTabState());
  const onTabActivate = onlyFromStrip((tabId) => activateTab(tabId));
  const onTabClose = onlyFromStrip((tabId) => closeTab(tabId));
  const onOpenWelcomeTab = onlyFromStrip(() => openTab(welcomeUrl, { activate: true }));

  ipcMain.on('field4-tabs-ready', onTabsReady);
  ipcMain.on('field4-tabs-activate', onTabActivate);
  ipcMain.on('field4-tabs-close', onTabClose);
  ipcMain.on('field4-tabs-open-welcome', onOpenWelcomeTab);

  const relayout = () => applyLayout();
  ['resize', 'maximize', 'unmaximize', 'enter-full-screen', 'leave-full-screen'].forEach((eventName) => {
    win.on(eventName, relayout);
  });

  win.on('closed', () => {
    ipcMain.removeListener('field4-tabs-ready', onTabsReady);
    ipcMain.removeListener('field4-tabs-activate', onTabActivate);
    ipcMain.removeListener('field4-tabs-close', onTabClose);
    ipcMain.removeListener('field4-tabs-open-welcome', onOpenWelcomeTab);
  });

  openTab(initialUrl, { activate: true });
}

app.whenReady().then(() => {
  installDockIcon();
  installAppMenu();
  createMainWindow();
});

app.on('activate', () => {
  if (BrowserWindow.getAllWindows().length === 0) {
    createMainWindow();
  }
});

app.on('window-all-closed', () => {
  app.quit();
});
