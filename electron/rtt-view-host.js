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
// Linux reverses allocation: the JVM owns a dma-buf image ring and sends its FDs here.
// Native Wayland was pixel-verified on NVIDIA; see dev_docs/ELECTRON_RTT_VIEW.md.
const path = require("node:path");
const fs = require("node:fs");
const { app, ipcMain, sharedTexture } = require("electron");

const BRIDGE_PATH =
  process.env.FIELD_BROWSER_BRIDGE_NODE ||
  path.resolve(__dirname, `../build/field-browser-bridge/${process.platform === "linux" ? "linux" : "macos"}/field_browser_bridge.node`);

let bridge = null;
const loadBridge = () => {
  if (!bridge) bridge = require(BRIDGE_PATH);
  return bridge;
};

const nowMs = () => performance.timeOrigin + performance.now();

// key: `${webContents.id}:${viewId}`
const views = new Map();

const closeLinuxFds = (view) => {
  for (const fd of view.fds || []) loadBridge().closeDmaBuf(fd);
  view.fds = [];
};

const closeView = (key) => {
  const view = views.get(key);
  if (!view) return;
  views.delete(key);
  if (view.linux) {
    view.closed = true;
    if (view.listener !== undefined) loadBridge().closeDmaBuf(view.listener);
    if (view.service) { try { fs.unlinkSync(view.service); } catch (_) {} }
    // Main's references were released after transfer. Keep the FDs valid until
    // Chromium releases its remaining GPU references, without releasing twice.
    if (!view.pending.size) closeLinuxFds(view);
    return;
  }
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
  if (!["darwin", "linux"].includes(process.platform) || !sharedTexture) return;

  ipcMain.handle("field4-rtt-open", async (event, { viewId, service, width, height, count, slots: layouts }) => {
    const key = `${event.sender.id}:${viewId}`;
    closeView(key);
    const b = loadBridge();
    if (process.platform === "linux") {
      if (!process.env.WAYLAND_DISPLAY || app.commandLine.getSwitchValue("ozone-platform") === "x11")
        return { ok: false, error: "Linux RTT view requires native Wayland; Xorg and XWayland imports were blank on NVIDIA" };
      const view = { linux: true, slots: [], fds: [], pending: new Set(), service };
      views.set(key, view);
      try {
        if (!Array.isArray(layouts) || layouts.length !== count || count < 1 || count > 4)
          throw new Error("Invalid dma-buf ring metadata");
        view.listener = b.openDmaBufReceiver(service);
        const deadline = Date.now() + 10000;
        while (!view.fds.length) {
          if (views.get(key) !== view || event.sender.isDestroyed() || Date.now() > deadline)
            throw new Error("Timed out receiving JVM dma-buf ring");
          const fds = b.receiveDmaBuf(view.listener);
          if (fds) view.fds = fds;
          else await new Promise(resolve => setTimeout(resolve, 10));
        }
        if (view.fds.length !== count) throw new Error("Wrong dma-buf ring length");
        view.slots = layouts.map((plane, i) => ({ textureInfo: {
          pixelFormat: "bgra", codedSize: { width, height },
          colorSpace: { primaries: "bt709", transfer: "srgb", matrix: "rgb", range: "full" },
          handle: { nativePixmap: { planes: [{ stride: plane.stride, offset: plane.offset,
            size: plane.size, fd: view.fds[i] }], modifier: plane.modifier,
            supportsZeroCopyWebGpuImport: false } }
        } }));
        if (!event.sender.__fieldRttCleanup) {
          event.sender.__fieldRttCleanup = true;
          const id = event.sender.id;
          event.sender.once("destroyed", () => {
            for (const k of [...views.keys()]) if (k.startsWith(`${id}:`)) closeView(k);
          });
        }
        return { ok: true, count };
      } catch (error) { closeView(key); return { ok: false, error: String(error.stack || error) }; }
    }
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
    if (view.linux) {
      const meta = { viewId, slot, seq, readyAt, generation, mainReceivedAt };
      let imported;
      try {
        // A fresh Chromium lease per frame permits a GPU-completion callback for ring reuse.
        // The FD and Vulkan image are persistent; no pixels cross the CPU.
        imported = sharedTexture.importSharedTexture({ textureInfo: entry.textureInfo,
          allReferencesReleased: () => {
            view.pending.delete(imported);
            if (view.closed && !view.pending.size) closeLinuxFds(view);
            if (!event.sender.isDestroyed()) event.sender.send("field4-rtt-released", meta);
          } });
        view.pending.add(imported);
        await sharedTexture.sendSharedTexture({ frame: event.senderFrame, importedSharedTexture: imported }, meta);
      } catch (error) {
        if (!event.sender.isDestroyed()) event.sender.send("field4-rtt-error", { ...meta, error: String(error) });
      } finally { if (imported) imported.release(); }
      return;
    }
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
