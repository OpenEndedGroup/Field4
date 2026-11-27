// One DevTools-protocol attachment per webContents, shared by user-scripts.cjs and page-heads.cjs.
//
// A webContents has one `debugger`, and `Target.setAutoAttach` is one setting per session: two
// modules each attaching and auto-attaching would undo each other's choices. So this module owns
// both. It attaches to a Field tab (or a profile webview), auto-attaches to its out-of-process
// frames paused at their start (`waitForDebuggerOnStart`), runs every registered hook on each new
// frame, and only then lets it run, so a hook can install something before the page's first script.
// The `cdp(...)` operation and DevTools share the same debugger, which Chromium allows.

const records = new Map(); // webContents id -> record
const hooks = [];          // async (record, sessionId, targetInfo) => void, run on each new frame target

function send(record, method, params = {}, sessionId) {
  return record.contents.debugger.sendCommand(method, params, sessionId || undefined);
}

// Registers [hook] for every frame target attached from now on, and runs it on those already attached.
function onFrameAttached(hook) {
  hooks.push(hook);
  for (const record of records.values()) {
    for (const [sessionId, info] of record.sessions) Promise.resolve(hook(record, sessionId, info)).catch(() => {});
  }
}

// Attaches to [contents] (once), auto-attaching to its frames. Resolves to its record.
function ensure(contents, { log = () => {} } = {}) {
  let record = records.get(contents.id);
  if (record) return record.ready;
  record = { contents, sessions: new Map(), listeners: new Set(), ready: null };
  records.set(contents.id, record);
  record.ready = (async () => {
    const debug = contents.debugger;
    if (!debug.isAttached()) debug.attach("1.3");
    debug.on("message", async (_event, method, params, sessionId) => {
      for (const listener of record.listeners) {
        try { listener(method, params, sessionId); } catch (_) {}
      }
      if (method === "Target.attachedToTarget") {
        const child = params.sessionId;
        record.sessions.set(child, params.targetInfo || {});
        try {
          for (const hook of hooks) await hook(record, child, params.targetInfo || {});
        } catch (error) {
          log("cdp-frame-hook-failed", { error: String(error?.message || error) });
        } finally {
          // A frame waits, paused, until told to run: it must always be told, even after a failure.
          send(record, "Runtime.runIfWaitingForDebugger", {}, child).catch(() => {});
        }
      } else if (method === "Target.detachedFromTarget") {
        record.sessions.delete(params.sessionId);
      }
    });
    contents.once("destroyed", () => records.delete(contents.id));
    await send(record, "Target.setAutoAttach", { autoAttach: true, waitForDebuggerOnStart: true, flatten: true });
    return record;
  })().catch(error => {
    records.delete(contents.id);
    throw error;
  });
  return record.ready;
}

// Hears every CDP event of [record]'s sessions. Returns a function that stops listening.
function listen(record, listener) {
  record.listeners.add(listener);
  return () => record.listeners.delete(listener);
}

// The CDP session and frame id of the out-of-process frame in [contents] called [name], or null.
async function frameNamed(contents, name, options) {
  const record = await ensure(contents, options);
  for (const [sessionId, info] of record.sessions) {
    if (info.type !== "iframe") continue;
    try {
      const { frameTree } = await send(record, "Page.getFrameTree", {}, sessionId);
      if (frameTree.frame.name === name) return { record, sessionId, frameId: frameTree.frame.id };
    } catch (_) {}
  }
  return null;
}

// The root session (the page itself) of a webview's [contents], as { record, sessionId: "", frameId }.
async function rootFrame(contents, options) {
  const record = await ensure(contents, options);
  const { frameTree } = await send(record, "Page.getFrameTree");
  return { record, sessionId: "", frameId: frameTree.frame.id };
}

module.exports = { ensure, onFrameAttached, listen, frameNamed, rootFrame, send };
