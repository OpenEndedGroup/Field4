const path = require('node:path');
const fs = require('node:fs');
const {pathToFileURL} = require('node:url');
const {app, BrowserWindow, WebContentsView, dialog, ipcMain, shell} = require('electron');

const PRELOAD = path.join(__dirname, 'presentation-preload.js');
const SERVER_MODULE = path.resolve(__dirname, '../tools/presentation/server.mjs');
const TYPESCRIPT_MODULE = path.resolve(__dirname, '../tools/presentation/typescript-service.mjs');
const ALLOWED_ACTIONS = new Set(['next', 'previous', 'first', 'last', 'goto', 'reload']);
let active = null;
let fieldHost = null;

function validRect(value) {
  if (!Array.isArray(value) || value.length !== 4 || !value.every(Number.isFinite)) return null;
  const [x, y, width, height] = value;
  if (x < 0 || y < 0 || width <= 0 || height <= 0 || x + width > 1 || y + height > 1) return null;
  return value;
}

function guestUrl(slide, base) {
  if (!slide || typeof slide !== 'object') return null;
  if (slide.type === 'youtube') {
    if (!/^[A-Za-z0-9_-]{11}$/.test(slide.videoId || '')) return null;
    return `${base}/field-youtube.html?id=${encodeURIComponent(slide.videoId)}`;
  }
  if (!['webpage', 'video'].includes(slide.type) || typeof slide.url !== 'string') return null;
  try {
    const url = new URL(slide.url, base);
    if (!['http:', 'https:'].includes(url.protocol)) return null;
    return url.href;
  } catch { return null; }
}

function emitError(session, error) {
  if (!session?.control?.isDestroyed()) {
    session.control.webContents.send('field-presentation-error-to-control', String(error));
  }
}

function clearGuest(session) {
  if (!session?.guest) return;
  try { session.audience.contentView.removeChildView(session.guest); } catch (_) {}
  try { session.guest.webContents.close(); } catch (_) {}
  session.guest = null;
  session.guestUrl = null;
}

function layoutGuest(session) {
  if (!session.guest || session.audience.isDestroyed()) return;
  const [width, height] = session.audience.getContentSize();
  const [x, y, w, h] = session.guestRect;
  session.guest.setBounds({
    x: Math.round(x * width), y: Math.round(y * height),
    width: Math.round(w * width), height: Math.round(h * height),
  });
}

function showGuest(session, cue) {
  const slide = session.manifest.slides[cue];
  const url = guestUrl(slide, session.url);
  const rect = validRect(slide?.rect) || [0, 0, 1, 1];
  if (session.guestUrl === url && JSON.stringify(session.guestRect) === JSON.stringify(rect)) return;
  clearGuest(session);
  if (!url) return;
  const guest = new WebContentsView({webPreferences: {
    sandbox: true, contextIsolation: true, nodeIntegration: false,
    partition: 'persist:field-presentations',
  }});
  session.guest = guest;
  session.guestUrl = url;
  session.guestRect = rect;
  guest.setBackgroundColor('#000000');
  guest.webContents.setWindowOpenHandler(({url: target}) => {
    if (/^https?:\/\//i.test(target)) void shell.openExternal(target);
    return {action: 'deny'};
  });
  guest.webContents.on('did-fail-load', (_, code, description, attemptedUrl, isMainFrame) => {
    if (isMainFrame && code !== -3) emitError(session, `Web page could not load: ${description} (${attemptedUrl})`);
  });
  guest.webContents.on('before-input-event', (event, input) => {
    if (input.type !== 'keyDown') return;
    const action = input.key === 'F9' ? 'next' : input.key === 'F8' ? 'previous' : null;
    if (action) { event.preventDefault(); sendAction(session, action); }
  });
  session.audience.contentView.addChildView(guest);
  layoutGuest(session);
  void guest.webContents.loadURL(url).catch(error => emitError(session, error));
}

function sendAction(session, action, id) {
  if (!session?.audience?.isDestroyed()) {
    session.audience.webContents.send('field-presentation-action-to-audience', action, id);
  }
}

function sourcePaths(session) {
  const scenes = [];
  const visit = directory => {
    for (const entry of fs.readdirSync(directory, {withFileTypes: true})) {
      const target = path.join(directory, entry.name);
      if (entry.isDirectory()) visit(target);
      else if (entry.isFile() && /\.(ts|tsx)$/.test(entry.name)) scenes.push(target);
    }
  };
  visit(path.join(session.root, 'src/scenes'));
  return [path.join(session.root, 'src/project.ts'), ...scenes.sort(),
    path.join(session.root, 'presentation.json')];
}

function makeWindow(title, width, height) {
  return new BrowserWindow({
    title, width, height, minWidth: 640, minHeight: 360,
    backgroundColor: '#050505', show: false,
    webPreferences: {preload: PRELOAD, sandbox: true, contextIsolation: true, nodeIntegration: false},
  });
}

async function closePresentation() {
  const session = active;
  active = null;
  if (!session) return;
  if (session.manifestWatcher) fs.unwatchFile(session.manifestWatcher);
  session.typescript.dispose();
  fieldHost?.enableTypeScript([]);
  clearGuest(session);
  if (!session.control.isDestroyed()) session.control.close();
  if (!session.audience.isDestroyed()) session.audience.close();
  await session.server.close();
}

async function openPresentation(directory) {
  const {validateProject, startPresentationServer} = await import(pathToFileURL(SERVER_MODULE).href);
  const {createPresentationTypeScriptService} = await import(pathToFileURL(TYPESCRIPT_MODULE).href);
  const {root, manifest} = validateProject(directory);
  const typescript = createPresentationTypeScriptService(root);
  const started = await startPresentationServer(root).catch(error => {
    typescript.dispose();
    throw error;
  });
  await closePresentation();
  const audience = makeWindow(`${manifest.title || 'Presentation'} · audience`, 1280, 720);
  const control = makeWindow(`${manifest.title || 'Presentation'} · controls`, 720, 400);
  const session = {server: started.server, url: started.url, root, manifest, audience, control, typescript,
    guest: null, guestUrl: null, guestRect: [0, 0, 1, 1], lastCue: null,
    manifestWatcher: path.join(root, 'presentation.json')};
  active = session;
  fs.watchFile(session.manifestWatcher, {interval: 500}, () => {
    try {
      session.manifest = JSON.parse(fs.readFileSync(session.manifestWatcher, 'utf8'));
      showGuest(session, session.lastCue);
    } catch (error) { emitError(session, `presentation.json: ${error}`); }
  });
  audience.on('resize', () => layoutGuest(session));
  audience.on('closed', () => { if (active === session) void closePresentation(); });
  control.on('closed', () => { if (active === session) void closePresentation(); });
  audience.webContents.on('before-input-event', (event, input) => {
    if (input.type !== 'keyDown') return;
    const action = ['ArrowRight', 'PageDown', 'Space'].includes(input.key) ? 'next' :
      ['ArrowLeft', 'PageUp'].includes(input.key) ? 'previous' : null;
    if (action) { event.preventDefault(); sendAction(session, action); }
  });
  await Promise.all([
    audience.loadURL(`${session.url}/field-presenter.html`),
    control.loadURL(`${session.url}/field-control.html`),
  ]);
  audience.show();
  control.show();
  fieldHost?.openPreview(`${session.url}/`);
  fieldHost?.enableTypeScript(sourcePaths(session));
  return session;
}

async function choosePresentation() {
  const selected = await dialog.showOpenDialog({
    title: 'Open presentation folder',
    properties: ['openDirectory'],
  });
  if (selected.canceled || !selected.filePaths[0]) return;
  try { await openPresentation(selected.filePaths[0]); }
  catch (error) { dialog.showErrorBox('Could not open presentation', String(error)); }
}

function install() {
  ipcMain.handle('field-presentation-tsx', async (event, request) => {
    if (!active || !fieldHost?.isFieldContents(event.sender)) throw new Error('No active Field presentation');
    if (request?.action === 'sources') return sourcePaths(active);
    if (request?.action === 'diagnostics') return active.typescript.diagnostics(request);
    if (request?.action === 'completions') return active.typescript.completions(request);
    throw new Error('Unknown TSX action');
  });
  ipcMain.on('field-presentation-action', (event, {action, id} = {}) => {
    if (event.sender !== active?.control.webContents) return;
    if (action === 'fullscreen') {
      active.audience.setFullScreen(!active.audience.isFullScreen());
      return;
    }
    if (action === 'attach-sources') {
      if (!fieldHost?.attachSources(sourcePaths(active))) {
        emitError(active, 'Open a Field document before attaching presentation source.');
      }
      return;
    }
    if (!ALLOWED_ACTIONS.has(action)) return;
    sendAction(active, action, typeof id === 'string' ? id : undefined);
  });
  ipcMain.on('field-presentation-state', (event, info) => {
    if (event.sender !== active?.audience.webContents || !info || typeof info !== 'object') return;
    const cue = typeof info.currentSlideId === 'string' ? info.currentSlideId : null;
    active.lastCue = cue;
    showGuest(active, cue);
    if (!active.control.isDestroyed()) active.control.webContents.send('field-presentation-state-to-control', info);
  });
  ipcMain.on('field-presentation-error', (event, message) => {
    if (event.sender === active?.audience.webContents) emitError(active, message);
  });
  app.on('before-quit', () => { void closePresentation(); });
}

function registerFieldHost(window, actions) {
  fieldHost = actions;
  window.on('closed', () => { if (fieldHost === actions) fieldHost = null; });
}

module.exports = {install, choosePresentation, openPresentation, closePresentation, registerFieldHost, guestUrl, validRect};
