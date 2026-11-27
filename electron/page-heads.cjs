// RemoteJS heads inside `web(...)` pages: a Javascript box connected under a box that shows a web
// page runs in that page, with print, errors on their lines, Stop, launch and output channels, as
// RemoteJS boxes do in a browser tab.
//
// On a phone the RemoteJS head is a page Field serves, and it reaches Field over its own WebSocket,
// because Field does not own that browser. Here Field owns the browser, and the page is someone
// else's site, whose CSP refuses both a socket to Field and the `eval` the head runs box code with.
// So the head runs in an isolated world of the page's frame, made through the DevTools protocol
// (cdp-targets.cjs), the way an extension's content script does: it shares the page's DOM, not its
// scripts' variables, and the page's CSP does not apply to it. The head's runtime is the same files
// remote_ar/index.html loads, with field.js's WebSocket swapped for a stand-in. Electron opens the
// real WebSocket to Field, on the loopback HTTP mirror, on the head's behalf; the stand-in sends
// through a CDP binding, and Electron delivers with Runtime.evaluate. To the server it is an
// ordinary head.
//
// The head's id is the page's name (`field-live-iframe-web-<box uid>~<random>`), which is how the
// server routes a box's code to the page of the web() box above it. A head lasts as long as the
// document: when the page navigates its world is destroyed, the socket is closed, and the next run
// of a box under it puts a new head into the new document.

const cdp = require("./cdp-targets.cjs");
const WORLD = "field-head";
const BINDING = "__fieldHeadSend";

const RUNTIME_FILES = ["errors.js", "runners.js", "maps.js", "asta.js", "js-object-pretty-print.js", "kv.js", "completion.js", "field.js"];
const STAND_IN = "__FieldPageSocket";

let runtimeCache = null; // { base, sources }

// The head runtime's sources, from the Field server at [httpBase], with field.js's WebSocket swapped.
async function runtimeSources(httpBase, fetchImpl = fetch) {
  if (runtimeCache && runtimeCache.base === httpBase) return runtimeCache.sources;
  const sources = [];
  for (const file of RUNTIME_FILES) {
    const response = await fetchImpl(`${httpBase}/extensions/remote_ar/field/${file}`);
    if (!response.ok) throw new Error(`Could not load the RemoteJS head's ${file}: HTTP ${response.status}`);
    let source = await response.text();
    if (file === "field.js") source = source.replace(/\bWebSocket\b/g, STAND_IN);
    sources.push(`${source}\n//# sourceURL=field-page-head/${file}`);
  }
  runtimeCache = { base: httpBase, sources };
  return sources;
}

// The stand-in socket and the page side of the relay. Returns false if this document already has one.
function standInSource() {
  return `(() => {
  if (window.__fieldPageHead) return false;
  let socket = null;
  class ${STAND_IN} {
    constructor(url) { this.url = url; this.readyState = 0; this.binaryType = "blob"; socket = this; }
    send(data) {
      if (typeof data === "string") ${BINDING}(JSON.stringify({ text: data }));
      else {
        const bytes = new Uint8Array(data instanceof ArrayBuffer ? data : data.buffer);
        let text = ""; for (const b of bytes) text += String.fromCharCode(b);
        ${BINDING}(JSON.stringify({ binary: btoa(text) }));
      }
    }
    close() { this.readyState = 3; ${BINDING}(JSON.stringify({ close: true })); }
  }
  ${STAND_IN}.CONNECTING = 0; ${STAND_IN}.OPEN = 1; ${STAND_IN}.CLOSING = 2; ${STAND_IN}.CLOSED = 3;
  window.${STAND_IN} = ${STAND_IN};
  window.__fieldPageHead = {
    open() { if (socket) { socket.readyState = 1; socket.onopen && socket.onopen({}); } },
    deliver(text) { socket && socket.onmessage && socket.onmessage({ data: text }); },
    deliverBinary(b64) {
      const text = atob(b64); const bytes = new Uint8Array(text.length);
      for (let i = 0; i < text.length; i++) bytes[i] = text.charCodeAt(i);
      socket && socket.onmessage && socket.onmessage({ data: bytes.buffer });
    },
    closed() { if (socket) { socket.readyState = 3; socket.onclose && socket.onclose({ code: 1006, reason: "Field closed the head" }); } },
  };
  return true;
})()`;
}

const heads = new Map(); // head id -> { socket, stop }

// Puts a RemoteJS head into the web() page called [headId], connected to Field through
// [socketUrl] (the loopback HTTP mirror's /ws). [frame] is the page's frame; [guest] is its
// webview's webContents when it has a profile, otherwise [contents] is the Field tab holding the
// iframe. Resolves once the head is connected.
async function injectHead({ frame, contents, guest = null }, headId, socketUrl,
                          { log = () => {}, fetchImpl = fetch, WebSocketImpl = WebSocket } = {}) {
  if (!/^wss?:\/\/(127\.0\.0\.1|localhost|\[::1\])(:\d+)?\/ws$/.test(String(socketUrl))) {
    throw new Error(`A page head connects only to Field on this machine, not ${socketUrl}`);
  }
  // A page that has not loaded yet (a webview just made is on about:blank) gets a head once it has.
  for (let waited = 0; !/^https?:/.test(frame.url) && waited < 10000; waited += 100) await new Promise(r => setTimeout(r, 100));
  if (!/^https?:/.test(frame.url)) throw new Error(`The page has not loaded (it is on ${frame.url || "nothing"})`);

  const target = guest ? await cdp.rootFrame(guest, { log }) : await cdp.frameNamed(contents, headId, { log });
  if (!target) throw new Error(`Could not reach the page ${headId} through the DevTools protocol`);
  const { record, sessionId, frameId } = target;
  const send = (method, params = {}) => cdp.send(record, method, params, sessionId);

  const existing = heads.get(headId);
  if (existing && existing.socket.readyState <= 1 && existing.frameId === frameId) {
    const alive = await send("Runtime.evaluate", { expression: "!!window.__fieldPageHead", contextId: existing.contextId, returnByValue: true })
      .then(r => r.result?.value === true, () => false);
    if (alive) return "connected";
  }
  closeHead(headId);

  await send("Runtime.enable");
  const { executionContextId: contextId } = await send("Page.createIsolatedWorld",
    { frameId, worldName: WORLD, grantUniveralAccess: true });
  await send("Runtime.addBinding", { name: BINDING, executionContextName: WORLD });
  const evaluate = async expression => {
    const reply = await send("Runtime.evaluate", { expression, contextId, returnByValue: true });
    if (reply.exceptionDetails) throw new Error(reply.exceptionDetails.exception?.description || reply.exceptionDetails.text);
    return reply.result?.value;
  };

  const httpBase = String(socketUrl).replace(/^ws/, "http").replace(/\/ws$/, "");
  const sources = await runtimeSources(httpBase, fetchImpl);
  if (await evaluate(standInSource())) {
    for (const source of sources) await evaluate(source);
  }

  const socket = new WebSocketImpl(socketUrl);
  socket.binaryType = "arraybuffer";
  const head = { socket, contextId, frameId, stop: null };
  heads.set(headId, head);
  const deliver = expression => send("Runtime.evaluate", { expression, contextId }).catch(() => closeHead(headId));
  socket.onmessage = event => {
    if (typeof event.data === "string") deliver(`window.__fieldPageHead && window.__fieldPageHead.deliver(${JSON.stringify(event.data)})`);
    else deliver(`window.__fieldPageHead && window.__fieldPageHead.deliverBinary(${JSON.stringify(Buffer.from(event.data).toString("base64"))})`);
  };
  socket.onclose = () => {
    deliver("window.__fieldPageHead && window.__fieldPageHead.closed()");
    closeHead(headId);
  };
  await new Promise((resolve, reject) => {
    socket.onopen = resolve;
    socket.onerror = () => reject(new Error(`Could not connect the page head to Field at ${socketUrl}`));
  });
  socket.onerror = () => log("page-head-socket-error", { headId });

  head.stop = cdp.listen(record, (method, params, eventSession) => {
    if ((eventSession || "") !== (sessionId || "")) return;
    if (method === "Runtime.bindingCalled" && params.name === BINDING && params.executionContextId === contextId) {
      let message;
      try { message = JSON.parse(params.payload); } catch (_) { return; }
      if (typeof message.text === "string") socket.send(message.text);
      else if (message.binary) socket.send(Buffer.from(message.binary, "base64"));
      else if (message.close) closeHead(headId);
    } else if ((method === "Runtime.executionContextDestroyed" && params.executionContextId === contextId) ||
               method === "Runtime.executionContextsCleared") {
      closeHead(headId); // the page navigated or went away, and its head with it
    }
  });
  // The head starts: field.js sends "initialize" with this id through the stand-in.
  await evaluate(`(() => { _field.evalRequestWS(${JSON.stringify(headId)}, function () {}); window.__fieldPageHead.open(); return true; })()`);
  log("page-head", { headId, url: frame.url });
  return "connected";
}

function closeHead(headId) {
  const head = heads.get(headId);
  if (!head) return;
  heads.delete(headId);
  if (head.stop) head.stop();
  try { head.socket.close(1000, "The page's head is gone"); } catch (_) {}
}

function headIds() { return [...heads.keys()]; }

module.exports = { injectHead, closeHead, headIds, standInSource, runtimeSources, RUNTIME_FILES };
