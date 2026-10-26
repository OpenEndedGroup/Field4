const path = require('node:path');
const fs = require('node:fs');
const {pathToFileURL} = require('node:url');
const {app, BrowserWindow, WebContentsView, dialog, ipcMain, shell} = require('electron');
const {validRect, guestUrl, fieldActions, validateGuideState} = require('./presentation-cues');
const {paintTransitionFrame} = require('./presentation-transition');

const PRELOAD = path.join(__dirname, 'presentation-preload.js');
const SERVER_MODULE = path.resolve(__dirname, '../tools/presentation/server.mjs');
const TYPESCRIPT_MODULE = path.resolve(__dirname, '../tools/presentation/typescript-service.mjs');
const ALLOWED_ACTIONS = new Set(['next', 'previous', 'reset-checkpoint', 'run-cue', 'first', 'last', 'goto', 'reload']);
let active = null;
let fieldHost = null;
let guideUpdates = Promise.resolve();

function emitError(session, error) {
  if (session?.control && !session.control.isDestroyed()) {
    session.control.webContents.send('field-presentation-error-to-control', String(error));
  }
}

function clearGuest(session) {
  if (!session?.guest) return;
  try { session.audience.contentView.removeChildView(session.guest); } catch (_) {}
  try { session.guest.webContents.close(); } catch (_) {}
  session.guest = null;
  session.guestUrl = null;
  session.guestCue = null;
}

function clearTransition(session) {
  if (!session?.transition) return;
  clearTimeout(session.transitionTimer);
  try { session.audience.contentView.removeChildView(session.transition); } catch (_) {}
  try { session.transition.webContents.close(); } catch (_) {}
  session.transition = null;
}

async function beginTransition(session) {
  try { await prepareTransition(session); }
  catch (error) {
    clearTransition(session);
    // A covered, suspended or protected native surface may not offer a frame. Advancing the
    // lesson must still work; in that case use a direct switch instead of stranding the cue.
    console.warn('[presentation] Switching without an outgoing snapshot:', String(error));
  }
}

async function prepareTransition(session) {
  clearTransition(session);
  const guestBounds = session.guest?.getBounds();
  // A native-content step has a plain black HTML backing. Chromium may stop painting that
  // fully covered page, so asking it for another frame can wait forever after a document guide.
  const background = session.native && session.guest ? null : await captureFrame(session.audience.webContents);
  const guestImage = session.guest && !session.guest.webContents.isDestroyed()
    ? await captureFrame(session.guest.webContents) : null;
  if (active !== session || session.audience.isDestroyed()) return;
  const overlay = new WebContentsView({webPreferences: {
    sandbox: true, contextIsolation: true, nodeIntegration: false, backgroundThrottling: false,
  }});
  session.transition = overlay;
  session.transitionTimer = setTimeout(() => clearTransition(session), 12000);
  overlay.setBackgroundColor('#00000000');
  const [width, height] = session.audience.getContentSize();
  overlay.setBounds({x: 0, y: 0, width, height});
  await bounded(overlay.webContents.loadURL('data:text/html,<html><style>html,body{margin:0;width:100%;height:100%;overflow:hidden;background:transparent}body{opacity:1}canvas{position:absolute;visibility:hidden}</style><body><canvas id="base" style="inset:0;width:100%;height:100%"></canvas><canvas id="guest"></canvas></body></html>'), 'The transition view did not load');
  if (session.transition !== overlay) return;
  // Chromium can defer image decoding for an unattached view. Attach a transparent page first;
  // its images stay hidden until both have decoded, while the outgoing live content remains below.
  session.audience.contentView.addChildView(overlay);
  // Drawing captured pixels onto a canvas forces decoding even if Image.decode() is deferred
  // for this Electron view. The view stays transparent until those pixels are ready.
  await bounded(overlay.webContents.executeJavaScript(`(${paintTransitionFrame.toString()})(${JSON.stringify({
    baseSource: background?.toDataURL() || null,
    guestSource: guestImage?.toDataURL() || null,
    guestStyle: guestBounds
      ? `left:${guestBounds.x}px;top:${guestBounds.y}px;width:${guestBounds.width}px;height:${guestBounds.height}px;border:0;outline:0`
      : 'display:none',
  })})`), 'The transition images did not decode');
  if (session.transition !== overlay || active !== session) return;
}

async function captureFrame(contents) {
  return bounded(contents.capturePage(), 'The audience did not provide a transition frame');
}

async function bounded(promise, message) {
  let timer;
  try {
    return await Promise.race([
      promise,
      new Promise((_, reject) => { timer = setTimeout(() => reject(new Error(message)), 3000); }),
    ]);
  } finally { clearTimeout(timer); }
}

function finishTransition(session) {
  const overlay = session.transition;
  if (!overlay || overlay.webContents.isDestroyed()) return;
  // A newly inserted guest view sits above existing views. Put the captured frame back on top.
  session.audience.contentView.removeChildView(overlay);
  session.audience.contentView.addChildView(overlay);
  clearTimeout(session.transitionTimer);
  const duration = Math.max(0, Math.min(2000, Number(session.manifest.transitionMs ?? 450) || 0));
  void overlay.webContents.executeJavaScript(`document.body.style.transition='opacity ${duration}ms linear';
    requestAnimationFrame(()=>requestAnimationFrame(()=>document.body.style.opacity='0'));`).catch(() => {});
  session.transitionTimer = setTimeout(() => clearTransition(session), duration + 100);
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

async function showGuest(session, cue) {
  const generation = (session.guestGeneration || 0) + 1;
  session.guestGeneration = generation;
  const slide = session.manifest.slides[cue];
  const url = guestUrl(slide, session.url, fieldHost?.getFieldUrl());
  const rect = validRect(slide?.rect) || [0, 0, 1, 1];
  if (session.guestUrl === url && JSON.stringify(session.guestRect) === JSON.stringify(rect)) {
    session.guestCue = cue;
    return;
  }
  clearGuest(session);
  if (!url) {
    if (slide?.type === 'field-document') emitError(session, 'Open a Field document in this app before presenting a Field document cue.');
    return;
  }
  if (slide?.type === 'field-document') {
    const info = await fieldHost?.getFieldInfo().catch(() => null);
    const documentDir = info?.workspace && path.join(info.workspace, slide.document);
    if (!documentDir || !fs.statSync(path.join(documentDir, 'metadata.edn'), {throwIfNoEntry: false})?.isFile()) {
      emitError(session, `Field document “${slide.document}” was not found in the running Field workspace.`);
      return;
    }
  }
  if (generation !== session.guestGeneration || active !== session) return;
  const guest = new WebContentsView({webPreferences: {
    sandbox: true, contextIsolation: true, nodeIntegration: false, backgroundThrottling: false,
    ...(slide?.type === 'field-document' ? {preload: path.join(__dirname, 'field-preload.js')} : {}),
    partition: 'persist:field-presentations',
  }});
  session.guest = guest;
  session.guestUrl = url;
  session.guestRect = rect;
  session.guestCue = cue;
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
  // Keep the outgoing frame on top while the destination loads, including its first paint.
  if (session.transition) {
    session.audience.contentView.removeChildView(session.transition);
    session.audience.contentView.addChildView(session.transition);
  }
  await guest.webContents.loadURL(url).catch(error => emitError(session, error));
}

async function runFieldAction(session, action) {
  const slide = session.manifest.slides[session.lastCue];
  if (slide?.type !== 'field-document' || !session.guest || session.guestCue !== session.lastCue) {
    throw new Error('This cue does not show a Field document');
  }
  const actions = fieldActions(slide);
  if (action === 'show-guide' && actions.guide) {
    return session.guest.webContents.executeJavaScript(`import('/afield.mjs').then(api=>{
      api.send('slideshow-command', {command:'stop', guideOnly:true}); return window.showDocumentGuide?.();
    })`);
  }
  const guideCommand = {'start-guide': 'start', 'guide-next': 'next', 'guide-previous': 'previous', 'stop-guide': 'stop'}[action];
  if (guideCommand && actions.interactiveGuide) {
    return session.guest.webContents.executeJavaScript(
      `import('/afield.mjs').then(api=>{
        if (${JSON.stringify(guideCommand)} === 'start' && document.getElementById('document-guide')) window.closeDocumentGuide?.();
        api.send('slideshow-command', {command:${JSON.stringify(guideCommand)}, guideOnly:true});
      })`);
  }
  if (action === 'run-macro' && actions.macro) {
    if (session.macroRunning) throw new Error('The cue macro is already running');
    session.macroRunning = true;
    try {
      const {name, scope, project} = actions.macro;
      return await session.guest.webContents.executeJavaScript(
        `window.macroPaletteRun(${JSON.stringify(name)}, ${JSON.stringify(scope)}, ${JSON.stringify(project)})`);
    } finally { session.macroRunning = false; }
  }
  throw new Error('This cue has no such Field action');
}

function sendAction(session, action, id) {
  if (session?.native) {
    void navigateGuide(session, action, id).catch(error => emitError(session, error));
    return;
  }
  if (session?.audience && !session.audience.isDestroyed()) {
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
    webPreferences: {preload: PRELOAD, sandbox: true, contextIsolation: true, nodeIntegration: false, backgroundThrottling: false},
  });
}

async function closePresentation() {
  const session = active;
  active = null;
  if (!session) return;
  if (session.manifestWatcher) fs.unwatchFile(session.manifestWatcher);
  clearTimeout(session.navigationTimer);
  session.typescript?.dispose();
  fieldHost?.enableTypeScript([]);
  clearTransition(session);
  clearGuest(session);
  if (!session.control.isDestroyed()) session.control.close();
  if (!session.audience.isDestroyed()) session.audience.close();
  if (session.sourceClosed) session.source.removeListener('destroyed', session.sourceClosed);
  if (session.native && !session.stopped && !session.source.isDestroyed()) {
    await commandGuide(session, 'stop').catch(() => {});
  }
  await session.server?.close();
}

async function commandGuide(session, command) {
  if (session.source.isDestroyed()) throw new Error('The Field document hosting this presentation has closed');
  await session.source.executeJavaScript(`import('/afield.mjs').then(api=>api.send('slideshow-command',
    ${JSON.stringify({command, slideshowId: session.slideshowId})}))`);
}

async function navigateGuide(session, action, id) {
  if (session.navigating) return;
  const state = session.state;
  let command = {first: 'home', last: String(state.titles.length - 1), reload: 'refresh'}[action] || action;
  if (action === 'goto') command = String(id);
  if ((action === 'next' && state.index === state.titles.length - 1 && state.cueIndex === state.cueTitles.length) ||
      (action === 'previous' && state.index === 0 && state.cueIndex === 0) ||
      (action === 'goto' && Number(id) === state.index)) return;
  session.navigating = true;
  try {
    await beginTransition(session);
    if (active !== session) return;
    await commandGuide(session, command);
    if (session.navigating) session.navigationTimer = setTimeout(() => {
      session.navigating = false;
      clearTransition(session);
      emitError(session, 'The guide did not respond to navigation. Check the Presentation box for an error.');
    }, 12000);
  } catch (error) {
    session.navigating = false;
    clearTransition(session);
    throw error;
  }
}

async function openGuidePresentation(source, state) {
  await closePresentation();
  const url = new URL(source.getURL()).origin;
  const audience = makeWindow(`${state.title} · audience`, 1280, 720);
  const control = makeWindow(`${state.title} · controls`, 860, 680);
  const session = {native: true, source, url, audience, control, state, slideshowId: state.slideshowId,
    hostUid: state.hostUid, manifest: {slides: {}, transitionMs: state.transitionMs},
    guest: null, guestUrl: null, guestRect: [0, 0, 1, 1], guestCue: null, lastCue: null};
  active = session;
  session.sourceClosed = () => { if (active === session) void closePresentation(); };
  source.once('destroyed', session.sourceClosed);
  audience.on('resize', () => {
    layoutGuest(session);
    if (session.transition) {
      const [width, height] = audience.getContentSize();
      session.transition.setBounds({x: 0, y: 0, width, height});
    }
  });
  audience.on('closed', () => { if (active === session) void closePresentation(); });
  control.on('closed', () => { if (active === session) void closePresentation(); });
  audience.webContents.on('before-input-event', (event, input) => {
    if (input.type !== 'keyDown') return;
    const action = ['ArrowRight', 'PageDown', 'Space', 'F9'].includes(input.key) ? 'next' :
      ['ArrowLeft', 'PageUp', 'F8'].includes(input.key) ? 'previous' : null;
    if (action) { event.preventDefault(); sendAction(session, action); }
  });
  await Promise.all([audience.loadURL(`${url}/presentation-audience.html`), control.loadURL(`${url}/presentation-control.html`)]);
  return session;
}

async function updateGuidePresentation(source, state) {
  if (source.isDestroyed()) return;
  state = validateGuideState(state);
  if (state.index === -1) {
    if (active?.native && active.source === source && active.slideshowId === state.slideshowId) {
      active.stopped = true;
      await closePresentation();
    }
    return;
  }
  let session = active?.native && active.source === source && active.hostUid === state.hostUid ? active : null;
  const opening = !session;
  if (!session) session = await openGuidePresentation(source, state);
  else if (!session.transition && !session.navigating) await beginTransition(session);
  if (active !== session) return;
  session.state = state;
  session.slideshowId = state.slideshowId;
  session.lastCue = `${state.index}:${state.cueIndex}`;
  session.manifest = {transitionMs: state.transitionMs, slides: {[session.lastCue]: state.content}};
  try {
    const document = new URL(source.getURL()).searchParams.get('file') || '';
    const baseUrl = `${session.url}/project/${encodeURIComponent(document)}/files/`;
    await session.audience.webContents.executeJavaScript(`window.renderPresentation(${JSON.stringify({...state, baseUrl})})`);
    await showGuest(session, session.lastCue);
    if (active !== session) return;
    finishTransition(session);
    session.control.webContents.send('field-presentation-state-to-control', {
      index: state.index, count: state.titles.length, title: state.title,
      currentSlideId: String(state.index), slides: state.titles.map((_, i) => String(i)), titles: state.titles, sectionPaths: state.sectionPaths || [],
      field: fieldActions(state.content),
      cueIndex: state.cueIndex, cueTitles: state.cueTitles, cueAction: state.cueAction, checkpoint: state.checkpoint,
    });
    if (opening) {
      session.audience.show(); session.control.show();
      app.focus({steal: true}); session.control.focus();
    }
  } catch (error) {
    clearTransition(session);
    emitError(session, error);
    if (opening) session.control.show();
  } finally {
    clearTimeout(session.navigationTimer);
    session.navigating = false;
  }
}

async function presentCurrentGuide() {
  const contents = fieldHost?.getFieldContents();
  if (!contents) return dialog.showErrorBox('No Field document', 'Open a Field document containing a guide first.');
  await contents.executeJavaScript(`import('/afield.mjs').then(api=>api.send('slideshow-command', {command:'present'}))`);
}

async function newPresentation() {
  try {
    const info = await fieldHost?.getFieldInfo();
    if (!info?.workspace) throw new Error('Open a Field workspace first.');
    const chosen = await dialog.showSaveDialog({title: 'New presentation document',
      buttonLabel: 'Create presentation', defaultPath: path.join(info.workspace, 'my-talk')});
    if (chosen.canceled || !chosen.filePath) return;
    const name = require('./presentation-starter').createPresentationDocument(info.workspace, chosen.filePath);
    fieldHost.openDocument(name);
  } catch (error) { dialog.showErrorBox('Could not create presentation', String(error)); }
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
    guest: null, guestUrl: null, guestRect: [0, 0, 1, 1], guestCue: null, lastCue: null,
    transition: null, transitionTimer: null, macroRunning: false,
    manifestWatcher: path.join(root, 'presentation.json')};
  active = session;
  fs.watchFile(session.manifestWatcher, {interval: 500}, () => {
    try {
      session.manifest = validateProject(session.root).manifest;
      void showGuest(session, session.lastCue).catch(error => emitError(session, error));
    } catch (error) { emitError(session, `presentation.json: ${error}`); }
  });
  audience.on('resize', () => {
    layoutGuest(session);
    if (session.transition) {
      const [width, height] = audience.getContentSize();
      session.transition.setBounds({x: 0, y: 0, width, height});
    }
  });
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
  app.focus({steal: true});
  control.focus();
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
  ipcMain.on('field-presentation-native-state', (event, state) => {
    if (!fieldHost?.isFieldContents(event.sender)) return;
    const source = event.sender;
    guideUpdates = guideUpdates.catch(() => {}).then(() => updateGuidePresentation(source, state))
      .catch(error => {
        if (active) emitError(active, error);
        else dialog.showErrorBox('Could not present guide', String(error));
      });
  });
  ipcMain.handle('field-presentation-begin-transition', async event => {
    if (event.sender !== active?.audience.webContents) return;
    const session = active;
    try { await beginTransition(session); }
    catch (error) { clearTransition(session); throw error; }
  });
  ipcMain.handle('field-presentation-tsx', async (event, request) => {
    const fieldGuest = active?.manifest.slides[active.lastCue]?.type === 'field-document' &&
      event.sender === active?.guest?.webContents && event.sender.getURL() === active.guestUrl;
    const allowed = fieldGuest || fieldHost?.isFieldContents(event.sender);
    if (allowed && request?.action === 'sources' && (!active || active.native)) return [];
    if (!active || active.native || !allowed) throw new Error('No active Field presentation');
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
    if (action === 'show-audience') {
      active.audience.show();
      app.focus({steal: true});
      active.audience.focus();
      return;
    }
    if (action === 'attach-sources') {
      if (active.native) return;
      if (!fieldHost?.attachSources(sourcePaths(active))) {
        emitError(active, 'Open a Field document before attaching presentation source.');
      }
      return;
    }
    if (['show-guide', 'run-macro', 'start-guide', 'guide-next', 'guide-previous', 'stop-guide'].includes(action)) {
      const session = active;
      void runFieldAction(session, action).catch(error => emitError(session, error));
      return;
    }
    if (!ALLOWED_ACTIONS.has(action)) return;
    sendAction(active, action, typeof id === 'string' ? id : undefined);
  });
  ipcMain.on('field-presentation-state', (event, info) => {
    if (event.sender !== active?.audience.webContents || !info || typeof info !== 'object') return;
    const cue = typeof info.currentSlideId === 'string' ? info.currentSlideId : null;
    const changed = active.lastCue !== cue;
    active.lastCue = cue;
    const session = active;
    if (changed) void showGuest(session, cue).catch(error => emitError(session, error)).finally(() => {
      if (active === session && session.lastCue === cue) finishTransition(session);
    });
    const field = fieldActions(active.manifest.slides[cue]);
    if (!active.control.isDestroyed()) active.control.webContents.send('field-presentation-state-to-control', {...info, field});
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

module.exports = {install, choosePresentation, openPresentation, closePresentation, registerFieldHost, presentCurrentGuide, newPresentation};
