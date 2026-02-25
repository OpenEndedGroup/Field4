const fs = require('node:fs');
const path = require('node:path');
const { app, BrowserWindow, Menu } = require('electron');

const APP_NAME = 'Field4';
const APP_ICON_1024 = path.join(__dirname, 'icon-1024.png');
const WITH_DEVTOOLS = ['1', 'true', 'yes', 'on'].includes(String(process.env.WITH_DEVTOOLS || '').toLowerCase());
app.setName(APP_NAME);
process.title = APP_NAME;

app.commandLine.appendSwitch('ignore-certificate-errors');

function filenameFromUrl(rawUrl) {
  try {
    const parsed = new URL(rawUrl);
    const file = parsed.searchParams.get('file');
    if (file && file.trim()) return file.trim();
    if (parsed.pathname.endsWith('/welcome')) return 'welcome';
  } catch (_) {
    // Ignore invalid URLs and fall back below.
  }
  return 'welcome';
}

function titleForUrl(rawUrl) {
  return `${APP_NAME} — ${filenameFromUrl(rawUrl)}`;
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
  const appUrl = process.env.APP_URL || 'https://localhost:9092/welcome';
  const win = new BrowserWindow({
    width: 1400,
    height: 900,
    title: titleForUrl(appUrl),
    autoHideMenuBar: true,
  });

  if (process.platform !== 'darwin') {
    win.removeMenu();
  }

  const updateTitle = (targetUrl) => {
    win.setTitle(titleForUrl(targetUrl));
  };

  win.webContents.on('did-navigate', (_event, url) => updateTitle(url));
  win.webContents.on('did-navigate-in-page', (_event, url) => updateTitle(url));
  win.webContents.on('did-finish-load', () => updateTitle(win.webContents.getURL() || appUrl));

  win.loadURL(appUrl);
  if (WITH_DEVTOOLS) {
    win.webContents.openDevTools({ mode: 'detach' });
  }
}

app.whenReady().then(() => {
  installDockIcon();
  installAppMenu();
  createMainWindow();
});

app.on('window-all-closed', () => {
  app.quit();
});
