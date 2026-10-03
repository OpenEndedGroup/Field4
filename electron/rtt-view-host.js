// Main-process half of the RTT view: afield.four render targets drawn straight into a box's output
// area, with no readback and no encode.
//
// Electron owns the pixels. For each view it allocates a ring of BGRA IOSurfaces, imports each into
// Chromium's GPU process once (sharedTexture.importSharedTexture), and sends each to Field over
// Mach (the browser bridge's sendIOSurface, the transport the browser-texture producer already
// uses). Field writes a finished frame into a free slot with one GPU copy (IOSurfaceHostBuffer) and
// says which slot through the page; the page asks for it here, and sendSharedTexture hands that
// slot's import to the renderer, where field-preload.js draws it into the view's canvas.
//
// macOS only for now (KosmicKrisp is the assumed driver; see dev_docs/ELECTRON_RTT_VIEW.md).
const path = require("node:path");
const { ipcMain, sharedTexture } = require("electron");

const BRIDGE_PATH =
  process.env.FIELD_BROWSER_BRIDGE_NODE ||
  path.resolve(__dirname, "../build/field-browser-bridge/macos/field_browser_bridge.node");

let bridge = null;
const loadBridge = () => {
  if (!bridge) bridge = require(BRIDGE_PATH);
  return bridge;
};

const nowMs = () => performance.timeOrigin + performance.now();

// key: `${webContents.id}:${viewId}`
const views = new Map();

const closeView = (key) => {
  const view = views.get(key);
  if (!view) return;
  views.delete(key);
  for (const slot of view.slots) {
    try {
      slot.imported.release();
    } catch (_) {}
    try {
      loadBridge().releaseIOSurface(slot.handle);
    } catch (_) {}
  }
};

const install = () => {
  if (process.platform !== "darwin" || !sharedTexture) return;

  ipcMain.handle("field4-rtt-open", (event, { viewId, service, width, height, count }) => {
    const key = `${event.sender.id}:${viewId}`;
    closeView(key);
    const b = loadBridge();
    const slots = [];
    try {
      for (let i = 0; i < count; i++) {
        const handle = b.createIOSurface(width, height);
        const imported = sharedTexture.importSharedTexture({
          textureInfo: {
            pixelFormat: "bgra",
            codedSize: { width, height },
            // Field writes sRGB-encoded bytes (a *_SRGB render target, copied as raw texels).
            colorSpace: { primaries: "bt709", transfer: "srgb", matrix: "rgb", range: "full" },
            handle: { ioSurface: handle },
          },
        });
        slots.push({ handle, imported });
        // The token is the slot index; the Mach service is already unique to this view.
        b.sendIOSurface(service, handle, BigInt(i));
      }
    } catch (error) {
      views.set(key, { slots });
      closeView(key);
      return { ok: false, error: String(error && error.stack ? error.stack : error) };
    }
    views.set(key, { slots, frame: event.senderFrame });
    if (!event.sender.__fieldRttCleanup) {
      event.sender.__fieldRttCleanup = true;
      const id = event.sender.id;
      event.sender.once("destroyed", () => {
        for (const k of [...views.keys()]) if (k.startsWith(`${id}:`)) closeView(k);
      });
    }
    return { ok: true, count: slots.length };
  });

  ipcMain.on("field4-rtt-present", async (event, { viewId, slot, seq, readyAt, generation }) => {
    const view = views.get(`${event.sender.id}:${viewId}`);
    const entry = view && view.slots[slot];
    if (!entry) return;
    const mainReceivedAt = nowMs();
    try {
      await sharedTexture.sendSharedTexture(
        { frame: event.senderFrame, importedSharedTexture: entry.imported },
        { viewId, slot, seq, readyAt, generation, mainReceivedAt },
      );
    } catch (error) {
      event.sender.send("field4-rtt-error", { viewId, slot, seq, error: String(error) });
    }
  });

  ipcMain.on("field4-rtt-close", (event, { viewId }) => closeView(`${event.sender.id}:${viewId}`));
};

module.exports = { install };
