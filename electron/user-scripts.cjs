// User scripts and styles for `web(...)` pages: Field's TamperMonkey.
//
// Box code registers a script or stylesheet for a URL pattern (`Box.userScript` / `userStyle`);
// it runs in every matching web() page from the start of each document, before the site's own
// scripts, and in a matching page that is already open as soon as it is registered.
//
// Chromium has no preload for a cross-origin <iframe> without `nodeIntegrationInSubFrames`, which
// would also put Field's unsandboxed preload into every framed site. So this uses the DevTools
// protocol instead: Field attaches a debugger to each Field tab (and each profile webview),
// auto-attaches to its out-of-process frames, paused at their start, and gives each one a loader
// with `Page.addScriptToEvaluateOnNewDocument` before letting it run. The loader holds every
// registered script as a function body (not a string to eval, which a page's CSP would refuse),
// and decides in the page whether this document is a web() page and which scripts match it.
//
// Only web() pages run user scripts: in a Field tab, frames whose name starts with
// `field-live-iframe-web-` (`Box.web` names its iframe that way, and a browsing context keeps its
// name across navigations); in a profile webview, its top frame. Frames inside the site (ads,
// embeds) and Field's own pages never do. Nothing is attached until a first script is registered.

const WEB_FRAME_PREFIX = "field-live-iframe-web-";
const RUN_AT = new Set(["start", "end", "idle"]);

const registry = new Map(); // key -> { key, token, kind, match, runAt, source, profile }
const targets = new Set();  // Target records: { contents, guestProfile, sessions: Map<sessionId|"", identifier> }
let log = () => {};

// The loader one target runs at the start of each document. [guestProfile] is null for a Field
// tab, or the profile directory ("" for none) of a webview's page.
function loaderSource(entries, guestProfile) {
  const scripts = entries.map(entry => {
    const run = entry.kind === "style"
      ? `__fieldUserStyle(${JSON.stringify(entry.key)}, ${JSON.stringify(entry.source)})`
      : `(function () {\n${entry.source}\n})`;
    return `{ key: ${JSON.stringify(entry.key)}, token: ${JSON.stringify(entry.token)}, kind: ${JSON.stringify(entry.kind)}, ` +
      `match: new RegExp(${JSON.stringify(entry.match)}), runAt: ${JSON.stringify(entry.runAt)}, ` +
      `profile: ${JSON.stringify(entry.profile)}, run: ${entry.kind === "style" ? `() => ${run}` : run} }`;
  });
  return `(() => {
  const guest = ${guestProfile === null ? "false" : "true"};
  const pageProfile = ${JSON.stringify(guestProfile === null ? "" : guestProfile)};
  const isPage = guest ? window.top === window : String(window.name).startsWith(${JSON.stringify(WEB_FRAME_PREFIX)});
  if (!isPage) return;
  const applied = window.__fieldUserScriptsApplied || (window.__fieldUserScriptsApplied = new Set());
  function __fieldUserStyle(key, css) {
    const sheets = window.__fieldUserStyles || (window.__fieldUserStyles = new Map());
    let sheet = sheets.get(key);
    if (!sheet) {
      sheet = new CSSStyleSheet();
      sheets.set(key, sheet);
      document.adoptedStyleSheets = [...document.adoptedStyleSheets, sheet];
    }
    sheet.replaceSync(css);
  }
  const entries = [${scripts.join(",\n")}];
  for (const entry of entries) {
    if (entry.profile !== null && entry.profile !== pageProfile) continue;
    if (!entry.match.test(location.href)) continue;
    const id = entry.key + "@" + entry.token;
    if (applied.has(id)) continue;
    applied.add(id);
    const run = () => {
      try { entry.run(); }
      catch (error) { console.error("[Field user script " + entry.key + "]", error); }
    };
    if (entry.kind === "style" || entry.runAt === "start" || (entry.runAt === "end" && document.readyState !== "loading") ||
        (entry.runAt === "idle" && document.readyState === "complete")) run();
    else if (entry.runAt === "end") document.addEventListener("DOMContentLoaded", run, { once: true });
    else window.addEventListener("load", run, { once: true });
  }
})();`;
}

// A pattern as TamperMonkey's @match writes it (`*://github.com/*`), as a regular expression.
function globToRegExp(glob) {
  return "^" + String(glob).split("*").map(part => part.replace(/[.+?^${}()|[\]\\]/g, "\\$&")).join(".*") + "$";
}

async function send(target, method, params = {}, sessionId) {
  return target.contents.debugger.sendCommand(method, params, sessionId || undefined);
}

// Gives one CDP session (a frame target, or the guest's own page as "") the current loader.
async function install(target, sessionId) {
  const previous = target.sessions.get(sessionId);
  if (previous) await send(target, "Page.removeScriptToEvaluateOnNewDocument", { identifier: previous }, sessionId).catch(() => {});
  const entries = [...registry.values()];
  if (entries.length === 0) { target.sessions.set(sessionId, null); return; }
  const { identifier } = await send(target, "Page.addScriptToEvaluateOnNewDocument",
    { source: loaderSource(entries, target.guestProfile) }, sessionId);
  target.sessions.set(sessionId, identifier);
}

// Runs the loader now in the pages this target already shows, so a script reaches an open page.
function applyNow(target) {
  const entries = [...registry.values()];
  if (!entries.length || target.contents.isDestroyed()) return;
  const frames = target.guestProfile === null
    ? target.contents.mainFrame.framesInSubtree.filter(frame => frame !== target.contents.mainFrame &&
      String(frame.name).startsWith(WEB_FRAME_PREFIX))
    : [target.contents.mainFrame];
  const source = loaderSource(entries, target.guestProfile);
  for (const frame of frames) frame.executeJavaScript(source).catch(() => {});
}

async function attach(target) {
  const { contents } = target;
  if (contents.isDestroyed()) return;
  const debug = contents.debugger;
  if (!debug.isAttached()) debug.attach("1.3");
  debug.on("message", async (_event, method, params, sessionId) => {
    if (method === "Target.attachedToTarget") {
      const child = params.sessionId;
      try {
        if (params.targetInfo?.type === "iframe") {
          await send(target, "Page.enable", {}, child);
          await install(target, child);
        }
      } catch (error) {
        log("user-scripts-install-failed", { error: String(error?.message || error) });
      } finally {
        // A frame waits, paused, until told to run: it must always be told, even after a failure.
        send(target, "Runtime.runIfWaitingForDebugger", {}, child).catch(() => {});
      }
    } else if (method === "Target.detachedFromTarget") {
      target.sessions.delete(params.sessionId);
    }
  });
  contents.once("destroyed", () => targets.delete(target));
  if (target.guestProfile === null) {
    // A Field tab: the page is Field's own; its out-of-process frames are the sites.
    await send(target, "Target.setAutoAttach", { autoAttach: true, waitForDebuggerOnStart: true, flatten: true });
  } else {
    await send(target, "Page.enable");
    await install(target, "");
  }
  // The loader covers documents that start from now on; one already loading or loaded (a
  // webview attaches while its first page is on its way) gets it directly.
  applyNow(target);
}

// Watches a Field tab's web() pages, or a profile webview's page ([guestProfile] its directory).
// Nothing is attached until a script is registered.
function watch(contents, guestProfile = null) {
  const target = { contents, guestProfile, sessions: new Map(), attached: false };
  targets.add(target);
  if (registry.size) ensureAttached(target);
  return target;
}

function ensureAttached(target) {
  if (target.attached) return Promise.resolve();
  target.attached = true;
  return attach(target).catch(error => {
    target.attached = false;
    log("user-scripts-attach-failed", { error: String(error?.message || error) });
  });
}

async function refresh() {
  for (const target of [...targets]) {
    if (target.contents.isDestroyed()) { targets.delete(target); continue; }
    await ensureAttached(target);
    for (const sessionId of [...target.sessions.keys()]) {
      await install(target, sessionId).catch(error => log("user-scripts-install-failed", { error: String(error?.message || error) }));
    }
    applyNow(target);
  }
}

// Adds or replaces the script or style under [key]. Returns the number of watched targets.
async function register({ key, token, kind = "script", match, runAt = "end", source, profile = null }) {
  if (!key || !token) throw new Error("A user script needs a key and a token");
  if (kind !== "script" && kind !== "style") throw new Error(`Unknown user script kind '${kind}'`);
  if (!RUN_AT.has(runAt)) throw new Error(`runAt must be start, end or idle, not '${runAt}'`);
  if (typeof source !== "string") throw new Error("A user script needs its source");
  let pattern;
  try { pattern = new RegExp(String(match)); } catch (error) { throw new Error(`Bad URL pattern: ${error.message}`); }
  registry.set(key, { key, token, kind, match: pattern.source, runAt, source, profile: profile === undefined ? null : profile });
  await refresh();
  return targets.size;
}

// Removes the script under [key], if it is still the registration [token] names. A style comes
// off open pages at once; a script stays in the documents it already ran in until they reload.
async function unregister({ key, token }) {
  const entry = registry.get(key);
  if (!entry || entry.token !== token) return false;
  registry.delete(key);
  await refresh();
  if (entry.kind === "style") {
    const removal = `(() => { const s = window.__fieldUserStyles?.get(${JSON.stringify(key)}); if (s) s.replaceSync(""); })()`;
    for (const target of targets) {
      if (target.contents.isDestroyed()) continue;
      for (const frame of target.contents.mainFrame.framesInSubtree) frame.executeJavaScript(removal).catch(() => {});
    }
  }
  return true;
}

function list() {
  return [...registry.values()].map(({ key, kind, match, runAt, profile }) => ({ key, kind, match, runAt, profile }));
}

function setLog(fn) { log = fn; }

module.exports = { register, unregister, list, watch, setLog, loaderSource, globToRegExp, WEB_FRAME_PREFIX };
