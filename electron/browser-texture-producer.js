const net = require("node:net");
const path = require("node:path");
const { app, BrowserWindow } = require("electron");

const required = (name) => {
  const value = process.env[name];
  if (!value) throw new Error(`${name} is required`);
  return value;
};

const targetUrl = required("FIELD_BROWSER_TEXTURE_URL");
const width = Number(required("FIELD_BROWSER_TEXTURE_WIDTH"));
const height = Number(required("FIELD_BROWSER_TEXTURE_HEIGHT"));
const controlPort = Number(required("FIELD_BROWSER_TEXTURE_CONTROL_PORT"));
const machService = required("FIELD_BROWSER_TEXTURE_MACH_SERVICE");
const bridgePath = required("FIELD_BROWSER_TEXTURE_BRIDGE");
const streamId = BigInt(required("FIELD_BROWSER_TEXTURE_STREAM_ID"));
const slotCount = Number(process.env.FIELD_BROWSER_TEXTURE_SLOTS || "3");
const readyBoxUid = process.env.FIELD_BROWSER_TEXTURE_READY_BOX_UID || "";
const bridge = require(path.resolve(bridgePath));

app.commandLine.appendSwitch("ignore-certificate-errors");
app.commandLine.appendSwitch("ignore-gpu-blocklist");

let control;
let controlReady = false;
let window;
let frameId = 0n;
const leases = new Map();

function send(message) {
  if (!control || control.destroyed) return;
  control.write(`${JSON.stringify(message)}\n`);
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
      if (message.type === "close") app.quit();
    }
  });
  control.on("connect", () => {
    controlReady = true;
    send({ type: "ready", pid: process.pid });
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

function createProducer() {
  window = new BrowserWindow({
    show: false,
    width,
    height,
    backgroundColor: "#000000",
    webPreferences: {
      offscreen: {
        useSharedTexture: true,
        sharedTexturePixelFormat: "argb",
        deviceScaleFactor: 1.0,
      },
      contextIsolation: true,
      sandbox: true,
    },
  });
  window.webContents.setFrameRate(30);
  window.webContents.on("paint", (event) => {
    const texture = event.texture;
    if (!texture) return;
    if (!controlReady) {
      texture.release();
      return;
    }
    const info = texture.textureInfo;
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
        width: info.codedSize.width,
        height: info.codedSize.height,
        pixelFormat: info.pixelFormat,
        colorSpace: info.colorSpace,
        visibleRect: info.visibleRect,
        contentRect: info.contentRect,
        timestamp: info.timestamp,
        ioSurfaceId,
      });
    } catch (error) {
      texture.release();
      console.error("[field-browser-texture] frame transfer failed", error);
    }
  });
  window.webContents.on("did-fail-load", (_event, code, description, url) => {
    send({ type: "error", message: `load failed ${code} ${description}: ${url}` });
  });
  window.webContents.on("render-process-gone", (_event, details) => {
    send({ type: "error", message: `renderer exited: ${JSON.stringify(details)}` });
  });
  window.webContents.on("did-finish-load", () => {
    (async () => {
      if (readyBoxUid) {
        const deadline = Date.now() + 30000;
        let ready = false;
        while (!ready && Date.now() < deadline) {
          ready = await window.webContents.executeJavaScript(
            `Array.from(document.querySelectorAll('.box')).some((box) => box.uid === ${JSON.stringify(readyBoxUid)})`,
          );
          if (!ready) await new Promise((resolve) => setTimeout(resolve, 50));
        }
        if (!ready) {
          send({ type: "error", message: `Field box ${readyBoxUid} did not appear before the readiness timeout` });
          return;
        }
      }
      send({ type: "loaded", afterFrameId: frameId.toString(), url: window.webContents.getURL() });
      window.webContents.invalidate();
    })().catch((error) => {
      send({ type: "error", message: error.stack || String(error) });
    });
  });
  window.loadURL(targetUrl).catch((error) => {
    send({ type: "error", message: error.stack || String(error) });
  });
}

app.whenReady().then(() => {
  installControl();
  createProducer();
});

app.on("before-quit", () => {
  for (const [token] of leases) release(token, "CLOSED");
  control?.destroy();
});

app.on("window-all-closed", () => app.quit());
