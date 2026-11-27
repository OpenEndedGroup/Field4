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

module.exports = { PARTITION_PREFIX, profileDirectory, sessionForDirectory, applyProfilePreferences, installProfileWebviews };
