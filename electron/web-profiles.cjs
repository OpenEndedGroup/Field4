// Web profiles: a `Box.web(url, profile = ...)` page keeps its cookies, logins, storage and cache
// in a directory of its own, usually inside the document's or project's files.
//
// An <iframe> always shares its tab's session, so a page with a profile is a <webview> instead.
// Its `partition` attribute is `field-path:` plus the URL-encoded absolute directory; when a
// Field tab attaches the webview, `will-attach-webview` swaps that for
// `session.fromPath(directory)`, the session Chromium stores in that directory. The profile's
// session gets the content blocker and no preload; the guest is sandboxed and isolated, and a
// popup it asks for opens in the same webview.
//
// A webview with any other partition, or none, is refused: Field's pages only make webviews
// for profiles, and a stray one must not borrow Field's own session (which has a preload and the
// relaxed framing and cookie rules).

const fs = require("node:fs");
const path = require("node:path");

const PARTITION_PREFIX = "field-path:";

// The profile directory a webview partition names, or null when it names none or a bad one.
function profileDirectory(partition) {
  if (typeof partition !== "string" || !partition.startsWith(PARTITION_PREFIX)) return null;
  let directory;
  try { directory = decodeURIComponent(partition.slice(PARTITION_PREFIX.length)); } catch (_) { return null; }
  if (!path.isAbsolute(directory) || path.normalize(directory) !== directory.replace(/[\\/]+$/, "")) return null;
  return directory;
}

const sessions = new Map(); // directory -> Session

// The session stored in [directory], made (and given the content blocker) on first use.
function sessionForDirectory(directory, { session, log = () => {}, onNewSession = () => {} }) {
  let profile = sessions.get(directory);
  if (profile) return profile;
  fs.mkdirSync(directory, { recursive: true });
  profile = session.fromPath(directory);
  sessions.set(directory, profile);
  onNewSession(profile, directory);
  log("web-profile-session", { directory });
  return profile;
}

// Rewrites a webview's preferences for [directory]'s session, the way `will-attach-webview` wants.
function applyProfilePreferences(webPreferences, profileSession) {
  delete webPreferences.preload;
  delete webPreferences.preloadURL;
  delete webPreferences.partition;
  webPreferences.session = profileSession;
  webPreferences.nodeIntegration = false;
  webPreferences.nodeIntegrationInSubFrames = false;
  webPreferences.contextIsolation = true;
  webPreferences.sandbox = true;
  webPreferences.webviewTag = false;
}

// Lets the Field page [contents] attach profile webviews and nothing else.
function installProfileWebviews(contents, { session, log = () => {}, onNewSession = () => {} }) {
  contents.on("will-attach-webview", (event, webPreferences, params) => {
    const directory = profileDirectory(params.partition);
    if (!directory) {
      log("web-profile-refused", { partition: String(params.partition || ""), src: params.src });
      event.preventDefault();
      return;
    }
    applyProfilePreferences(webPreferences, sessionForDirectory(directory, { session, log, onNewSession }));
  });
  contents.on("did-attach-webview", (_event, guest) => {
    // A link with target=_blank, or window.open, stays in this page rather than opening a window.
    guest.setWindowOpenHandler(({ url }) => {
      if (/^https?:/i.test(url)) guest.loadURL(url).catch(() => {});
      return { action: "deny" };
    });
  });
}

// The cookie fields a caller may set; Electron rejects unknown ones.
const COOKIE_FIELDS = ["url", "name", "value", "domain", "path", "secure", "httpOnly", "expirationDate", "sameSite"];
const FILTER_FIELDS = ["url", "name", "domain", "path", "secure", "session", "httpOnly"];

function pick(source, fields) {
  const result = {};
  for (const field of fields) {
    if (source && source[field] !== undefined && source[field] !== null && source[field] !== "") result[field] = source[field];
  }
  return result;
}

const watchers = new Map(); // Session -> Set<webContents> told about its cookie changes

// Answers a `profile` request from a Field page: [request.directory] names a profile ("" is the
// shared session Field's own pages use), [request.action] what to do with its cookies.
async function handleProfileRequest(request, sender, { session, log = () => {}, onNewSession = () => {} }) {
  const directory = request?.directory ? profileDirectory(PARTITION_PREFIX + encodeURIComponent(request.directory)) : "";
  if (directory === null) throw new Error(`Not a profile directory: ${request?.directory}`);
  const target = directory ? sessionForDirectory(directory, { session, log, onNewSession }) : session.defaultSession;
  const params = request?.params || {};
  switch (request?.action) {
    case "cookies-get":
      return await target.cookies.get(pick(params, FILTER_FIELDS));
    case "cookies-set": {
      const cookie = pick(params, COOKIE_FIELDS);
      if (!cookie.url) throw new Error("A cookie needs the url it belongs to");
      await target.cookies.set(cookie);
      return true;
    }
    case "cookies-remove":
      if (!params.url || !params.name) throw new Error("Removing a cookie needs its url and name");
      await target.cookies.remove(String(params.url), String(params.name));
      return true;
    case "cookies-flush":
      await target.cookies.flushStore();
      return true;
    case "cookies-watch": {
      let listeners = watchers.get(target);
      if (!listeners) {
        listeners = new Set();
        watchers.set(target, listeners);
        target.cookies.on("changed", (_event, cookie, cause, removed) => {
          for (const contents of listeners) {
            if (contents.isDestroyed()) { listeners.delete(contents); continue; }
            contents.send("field4-web-profile-event", { directory, kind: "cookie", cookie, cause, removed });
          }
        });
      }
      listeners.add(sender);
      return true;
    }
    default:
      throw new Error(`Unknown web profile action '${request?.action}'`);
  }
}

module.exports = { handleProfileRequest, PARTITION_PREFIX, profileDirectory, sessionForDirectory, applyProfilePreferences, installProfileWebviews };
