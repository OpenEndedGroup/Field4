const net = require("node:net");
const path = require("node:path");
const { app, BrowserWindow, ipcMain } = require("electron");

const required = (name) => {
  const value = process.env[name];
  if (!value) throw new Error(`${name} is required`);
  return value;
};

const targetUrl = required("FIELD_BROWSER_TEXTURE_URL");
const textureWidth = Number(required("FIELD_BROWSER_TEXTURE_WIDTH"));
const textureHeight = Number(required("FIELD_BROWSER_TEXTURE_HEIGHT"));
const deviceScaleFactor = Number(process.env.FIELD_BROWSER_TEXTURE_DEVICE_SCALE_FACTOR || "1");
const controlPort = Number(required("FIELD_BROWSER_TEXTURE_CONTROL_PORT"));
const machService = required("FIELD_BROWSER_TEXTURE_MACH_SERVICE");
const bridgePath = required("FIELD_BROWSER_TEXTURE_BRIDGE");
const streamId = BigInt(required("FIELD_BROWSER_TEXTURE_STREAM_ID"));
const slotCount = Number(process.env.FIELD_BROWSER_TEXTURE_SLOTS || "3");
let readyBoxUid = process.env.FIELD_BROWSER_TEXTURE_READY_BOX_UID || "";
const bridge = require(path.resolve(bridgePath));

app.commandLine.appendSwitch("ignore-gpu-blocklist");

// Field's development server uses a self-signed certificate. Keep that exception local instead
// of weakening certificate validation for every site this general-purpose browser can visit.
app.on("certificate-error", (event, _webContents, url, _error, _certificate, callback) => {
  let localField = false;
  try {
    const hostname = new URL(url).hostname;
    localField = hostname === "localhost" || hostname === "127.0.0.1" || hostname === "::1";
  } catch (_) {
    localField = false;
  }
  if (localField) {
    event.preventDefault();
    callback(true);
  } else {
    callback(false);
  }
});

let control;
let controlReady = false;
let window;
let frameId = 0n;
let eventSequence = 0;
let navigationGeneration = 0;
let debuggerDomainsEnabled = false;
let forwardAllCdpEvents = false;
let crashed = false;
let unresponsive = false;
let cursor = "default";
let inputTail = Promise.resolve();
const leases = new Map();
const pendingPageRequests = new Map();

function send(message) {
  if (!control || control.destroyed) return;
  control.write(`${JSON.stringify(message)}\n`);
}

function emit(name, payload = {}) {
  send({ type: "event", sequence: ++eventSequence, name, payload });
}

function errorObject(error) {
  if (error && typeof error === "object") {
    return {
      name: error.name || "Error",
      message: error.message || String(error),
      stack: error.stack || null,
      code: error.code ?? null,
      details: error.details ?? null,
    };
  }
  return { name: "Error", message: String(error), stack: null, code: null, details: null };
}

function stateSnapshot() {
  if (!window || window.isDestroyed()) {
    return {
      url: "about:blank", title: "", loading: false, canGoBack: false, canGoForward: false,
      audible: false, focused: false, unresponsive, crashed, cursor, navigationGeneration,
    };
  }
  const contents = window.webContents;
  return {
    url: contents.getURL() || "about:blank",
    title: contents.getTitle() || "",
    loading: contents.isLoading(),
    canGoBack: contents.navigationHistory.canGoBack(),
    canGoForward: contents.navigationHistory.canGoForward(),
    audible: contents.isCurrentlyAudible(),
    focused: contents.isFocused(),
    unresponsive,
    crashed,
    cursor,
    navigationGeneration,
  };
}

function sendState(reason) {
  emit("state", { reason, state: stateSnapshot() });
}

function release(token, reason) {
  const lease = leases.get(token);
  if (!lease) return;
  leases.delete(token);
  lease.release();
  if (reason === "INVALID" || reason === "IMPORT_FAILED") {
    console.warn(`[field-browser-texture] frame ${token} released ${reason}`);
  }
}

async function ensureDebugger() {
  const debug = window.webContents.debugger;
  if (!debug.isAttached()) {
    debug.attach("1.3");
    debuggerDomainsEnabled = false;
  }
  if (!debuggerDomainsEnabled) {
    await debug.sendCommand("Page.enable");
    await debug.sendCommand("Runtime.enable");
    debuggerDomainsEnabled = true;
  }
  return debug;
}

async function cdp(method, params = {}, sessionId) {
  const debug = await ensureDebugger();
  return debug.sendCommand(method, params, sessionId);
}

async function isolatedContextId() {
  const { frameTree } = await cdp("Page.getFrameTree");
  const { executionContextId } = await cdp("Page.createIsolatedWorld", {
    frameId: frameTree.frame.id,
    worldName: "field4-puppet",
    grantUniveralAccess: false,
  });
  return executionContextId;
}

async function evaluate(params) {
  const request = {
    expression: String(params.javascript || ""),
    awaitPromise: true,
    returnByValue: params.returnMode !== "HANDLE",
    userGesture: Boolean(params.userGesture),
    includeCommandLineAPI: false,
    silent: false,
  };
  if (params.timeoutMillis > 0) request.timeout = Number(params.timeoutMillis);
  if (params.world === "ISOLATED") request.contextId = await isolatedContextId();
  const response = await cdp("Runtime.evaluate", request);
  if (response.exceptionDetails) {
    const exception = new Error(
      response.exceptionDetails.exception?.description || response.exceptionDetails.text || "JavaScript evaluation failed",
    );
    exception.name = "JavascriptException";
    exception.details = response.exceptionDetails;
    throw exception;
  }
  const remote = response.result || {};
  const result = {
    type: remote.type || "undefined",
    description: remote.description ?? null,
    unserializableValue: remote.unserializableValue ?? null,
    objectId: remote.objectId ?? null,
  };
  if (Object.prototype.hasOwnProperty.call(remote, "value")) result.value = remote.value;
  return result;
}

function queueInput(method, params) {
  inputTail = inputTail
    .then(() => cdp(method, params))
    .catch((error) => emit("input-error", { method, error: errorObject(error) }));
}

async function command(name, params = {}) {
  const contents = window.webContents;
  switch (name) {
    case "navigate":
      await contents.loadURL(String(params.url));
      return stateSnapshot();
    case "back":
      if (contents.navigationHistory.canGoBack()) await contents.navigationHistory.goBack();
      return stateSnapshot();
    case "forward":
      if (contents.navigationHistory.canGoForward()) await contents.navigationHistory.goForward();
      return stateSnapshot();
    case "reload":
      if (params.ignoreCache) contents.reloadIgnoringCache(); else contents.reload();
      return stateSnapshot();
    case "stop":
      contents.stop();
      return stateSnapshot();
    case "focus":
      contents.focus();
      return stateSnapshot();
    case "blur":
      contents.blur();
      return stateSnapshot();
    case "flush-input":
      await inputTail;
      return {};
    case "evaluate":
      return evaluate(params);
    case "release-object":
      await cdp("Runtime.releaseObject", { objectId: String(params.objectId) });
      return {};
    case "cdp":
      return cdp(String(params.method), params.params || {}, params.sessionId);
    case "forward-cdp-events":
      forwardAllCdpEvents = Boolean(params.enabled);
      return {};
    case "state":
      return stateSnapshot();
    default:
      throw new Error(`Unknown puppet browser command '${name}'`);
  }
}

function post(name, params = {}) {
  switch (name) {
    case "pointer":
      queueInput("Input.dispatchMouseEvent", params);
      break;
    case "key":
      queueInput("Input.dispatchKeyEvent", params);
      break;
    case "insert-text":
      queueInput("Input.insertText", params);
      break;
    case "composition":
      queueInput("Input.imeSetComposition", params);
      break;
    case "touch":
      queueInput("Input.dispatchTouchEvent", params);
      break;
    default:
      emit("protocol-error", { message: `Unknown puppet browser post '${name}'` });
  }
}

function resolvePageResponse(message) {
  const pending = pendingPageRequests.get(String(message.id));
  if (!pending) return;
  pendingPageRequests.delete(String(message.id));
  clearTimeout(pending.timeout);
  if (message.ok) pending.resolve(message.result ?? null);
  else pending.reject(new Error(message.error?.message || "Field rejected the page request"));
}

function installControl() {
  control = net.createConnection({ host: "127.0.0.1", port: controlPort });
  control.setEncoding("utf8");
  let buffered = "";
  control.on("data", (chunk) => {
    buffered += chunk;
    for (;;) {
      const newline = buffered.indexOf("\n");
      if (newline < 0) break;
      const line = buffered.slice(0, newline);
      buffered = buffered.slice(newline + 1);
      if (!line) continue;
      const message = JSON.parse(line);
      if (message.type === "release") release(String(message.token), message.reason);
      else if (message.type === "close") app.quit();
      else if (message.type === "post") post(message.command, message.params || {});
      else if (message.type === "page-response") resolvePageResponse(message);
      else if (message.type === "command") {
        Promise.resolve(command(message.command, message.params || {})).then(
          (result) => send({ type: "response", id: String(message.id), ok: true, result }),
          (error) => send({ type: "response", id: String(message.id), ok: false, error: errorObject(error) }),
        );
      }
    }
  });
  control.on("connect", () => {
    controlReady = true;
    send({ type: "ready", pid: process.pid, state: stateSnapshot() });
  });
  control.on("error", (error) => {
    console.error("[field-browser-texture] control channel failed", error);
    app.quit();
  });
  control.on("close", () => {
    controlReady = false;
    app.quit();
  });
}

function installPageBridge() {
  ipcMain.on("field-puppet-event", (event, message) => {
    if (!window || event.sender !== window.webContents) return;
    emit("page-event", { name: message?.name, value: message?.value ?? null });
  });
  ipcMain.handle("field-puppet-request", (event, message) => {
    if (!window || event.sender !== window.webContents) throw new Error("Unknown puppet browser page");
    const id = `page-${Date.now()}-${Math.random().toString(16).slice(2)}`;
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        pendingPageRequests.delete(id);
        reject(new Error(`Field page request '${message?.name}' timed out`));
      }, 10000);
      pendingPageRequests.set(id, { resolve, reject, timeout });
      send({ type: "page-request", id, name: message?.name, value: message?.value ?? null });
    });
  });
}

function installBrowserEvents() {
  const contents = window.webContents;
  contents.on("did-start-loading", () => sendState("did-start-loading"));
  contents.on("did-stop-loading", () => sendState("did-stop-loading"));
  contents.on("did-start-navigation", (details) => {
    if (details.isMainFrame && !details.isSameDocument) navigationGeneration += 1;
    emit("navigation-started", {
      url: details.url, isSameDocument: details.isSameDocument, isMainFrame: details.isMainFrame,
    });
    sendState("did-start-navigation");
  });
  contents.on("did-navigate", (_event, url, httpResponseCode, httpStatusText) => {
    emit("navigated", { url, httpResponseCode, httpStatusText });
    sendState("did-navigate");
  });
  contents.on("did-navigate-in-page", (_event, url, isMainFrame) => {
    emit("navigated-in-page", { url, isMainFrame });
    sendState("did-navigate-in-page");
  });
  contents.on("did-fail-load", (_event, errorCode, errorDescription, validatedURL, isMainFrame) => {
    emit("load-failed", { errorCode, errorDescription, url: validatedURL, isMainFrame });
    sendState("did-fail-load");
  });
  contents.on("page-title-updated", (_event, title, explicitSet) => {
    emit("title", { title, explicitSet });
    sendState("page-title-updated");
  });
  contents.on("console-message", (details) => emit("console", {
    level: details.level,
    message: details.message,
    lineNumber: details.lineNumber,
    sourceId: details.sourceId,
  }));
  contents.on("cursor-changed", (_event, type) => {
    cursor = type || "default";
    emit("cursor", { cursor });
    sendState("cursor-changed");
  });
  contents.on("context-menu", (_event, params) => emit("context-menu", {
    x: params.x, y: params.y, linkURL: params.linkURL, srcURL: params.srcURL,
    selectionText: params.selectionText, mediaType: params.mediaType, isEditable: params.isEditable,
  }));
  contents.on("audio-state-changed", (details) => {
    emit("audio", { audible: details.audible });
    sendState("audio-state-changed");
  });
  contents.on("render-process-gone", (_event, details) => {
    crashed = details.reason !== "clean-exit";
    emit("renderer-gone", { reason: details.reason, exitCode: details.exitCode });
    sendState("render-process-gone");
  });
  contents.on("unresponsive", () => {
    unresponsive = true;
    emit("unresponsive");
    sendState("unresponsive");
  });
  contents.on("responsive", () => {
    unresponsive = false;
    emit("responsive");
    sendState("responsive");
  });
  contents.on("focus", () => sendState("focus"));
  contents.on("blur", () => sendState("blur"));
  contents.setWindowOpenHandler((details) => {
    emit("window-open", {
      url: details.url, frameName: details.frameName, features: details.features, disposition: details.disposition,
    });
    setImmediate(() => contents.loadURL(details.url).catch((error) => {
      emit("load-failed", { url: details.url, error: errorObject(error), isMainFrame: true });
    }));
    return { action: "deny" };
  });
  contents.session.setPermissionRequestHandler((requestingContents, permission, callback, details) => {
    const allowed = requestingContents === contents && permission === "fullscreen";
    emit("permission-request", {
      permission, allowed, requestingUrl: details.requestingUrl, isMainFrame: details.isMainFrame,
    });
    callback(allowed);
  });
  contents.debugger.on("detach", (_event, reason) => {
    debuggerDomainsEnabled = false;
    emit("cdp-detached", { reason });
  });
  contents.debugger.on("message", (_event, method, params, sessionId) => {
    if (method === "Page.javascriptDialogOpening") emit("javascript-dialog", params || {});
    if (method === "Runtime.exceptionThrown") emit("javascript-exception", params || {});
    if (forwardAllCdpEvents) emit("cdp", { method, params: params || {}, sessionId: sessionId || null });
  });
}

async function announceLoaded() {
  const contents = window.webContents;
  const expectedBox = readyBoxUid;
  readyBoxUid = "";
  if (expectedBox) {
    const deadline = Date.now() + 30000;
    let ready = false;
    while (!ready && Date.now() < deadline) {
      ready = await contents.executeJavaScript(
        `Array.from(document.querySelectorAll('.box')).some((box) => box.uid === ${JSON.stringify(expectedBox)})`,
      );
      if (!ready) await new Promise((resolve) => setTimeout(resolve, 50));
    }
    if (!ready) {
      emit("readiness-timeout", { boxUid: expectedBox, url: contents.getURL() });
      return;
    }
  }
  send({ type: "loaded", afterFrameId: frameId.toString(), url: contents.getURL(), state: stateSnapshot() });
}

function createProducer() {
  window = new BrowserWindow({
    show: false,
    width: Math.max(1, Math.round(textureWidth / deviceScaleFactor)),
    height: Math.max(1, Math.round(textureHeight / deviceScaleFactor)),
    backgroundColor: "#000000",
    webPreferences: {
      preload: path.join(__dirname, "browser-texture-preload.js"),
      offscreen: {
        useSharedTexture: true,
        sharedTexturePixelFormat: "argb",
        deviceScaleFactor,
      },
      contextIsolation: true,
      sandbox: true,
      nodeIntegration: false,
    },
  });
  installPageBridge();
  installBrowserEvents();
  window.webContents.setFrameRate(60);
  window.webContents.on("paint", (event) => {
    const texture = event.texture;
    if (!texture) return;
    if (!controlReady) {
      texture.release();
      return;
    }
    const info = texture.textureInfo;
    if (info?.widgetType && info.widgetType !== "frame") {
      emit("popup-paint", {
        widgetType: info.widgetType,
        codedSize: info.codedSize,
        visibleRect: info.visibleRect,
        contentRect: info.contentRect,
        metadata: info.metadata,
      });
      texture.release();
      return;
    }
    const ioSurface = info?.handle?.ioSurface;
    if (!Buffer.isBuffer(ioSurface)) {
      texture.release();
      return;
    }

    frameId += 1n;
    const token = frameId.toString();
    try {
      const ioSurfaceId = bridge.sendIOSurface(machService, ioSurface, frameId);
      leases.set(token, texture);
      send({
        type: "frame",
        streamId: streamId.toString(),
        frameId: token,
        token,
        slot: Number(frameId % BigInt(slotCount)),
        widgetType: info.widgetType || "frame",
        width: info.codedSize.width,
        height: info.codedSize.height,
        pixelFormat: info.pixelFormat,
        colorSpace: info.colorSpace,
        visibleRect: info.visibleRect,
        contentRect: info.contentRect,
        metadata: info.metadata,
        timestamp: info.timestamp,
        ioSurfaceId,
      });
    } catch (error) {
      texture.release();
      emit("frame-transfer-failed", { error: errorObject(error) });
      console.error("[field-browser-texture] frame transfer failed", error);
    }
  });
  window.webContents.on("did-finish-load", () => {
    announceLoaded().catch((error) => emit("readiness-error", { error: errorObject(error) }));
  });
  window.loadURL(targetUrl).catch((error) => emit("load-failed", { url: targetUrl, error: errorObject(error) }));
}

app.whenReady().then(() => {
  installControl();
  createProducer();
});

app.on("before-quit", () => {
  for (const [token] of leases) release(token, "CLOSED");
  for (const [id, pending] of pendingPageRequests) {
    clearTimeout(pending.timeout);
    pending.reject(new Error("Puppet browser closed"));
    pendingPageRequests.delete(id);
  }
  control?.destroy();
});

app.on("window-all-closed", () => app.quit());
