// Downloads from web() pages go into the document's files, without a Save dialog.
//
// A file a site offers for download (a link with `download`, an attachment, a file a page
// generates) in a web() page is saved into the downloads folder of the document that shows the
// page: `files/downloads/` unless box code says otherwise. Each `Box.web` run tells Electron that
// folder for its Field tab (`downloads-dir`), because `will-download` must choose the path at once.
// A tab that has not been told keeps Chromium's usual Save dialog. Field's own downloads (from its
// own origin, or a blob/data URL the Field page made) are left alone.
//
// When a download ends, the Field tab is sent `{ kind: "download", ... }` on the web-profile event
// channel; the page passes it to the server, where `Box.onDownload` callbacks hear it.

const fs = require("node:fs");
const path = require("node:path");

const folders = new Map(); // Field tab webContents id -> downloads folder
const watched = new WeakSet();
let fieldOrigin = null;
let log = () => {};

function originOf(url) {
  try { return new URL(String(url)).origin; } catch (_) { return null; }
}

// Names handed out this run. Chromium writes a download under a temporary name and moves it into
// place at the end, so a second download of the same file can start before the first's name exists
// on disk; checking the disk alone gave both the same name and the second overwrote the first.
const reserved = new Set();

// A file name in [folder] that is not taken: `name.ext`, then `name (1).ext`, `name (2).ext`, ...
function uniquePath(folder, filename) {
  const safe = String(filename || "download").replace(/[\\/:\u0000]/g, "_").replace(/^\.+/, "_") || "download";
  const ext = path.extname(safe);
  const stem = safe.slice(0, safe.length - ext.length);
  for (let i = 0; ; i++) {
    const candidate = path.join(folder, i === 0 ? safe : `${stem} (${i})${ext}`);
    if (!fs.existsSync(candidate) && !reserved.has(candidate)) {
      reserved.add(candidate);
      return candidate;
    }
  }
}

// Whether a download that [contents] started is Field's own rather than a site's.
function isFieldsOwn(item, contents) {
  if (contents.getType && contents.getType() === "webview") return false;
  const url = item.getURL();
  if (/^(blob|data):/i.test(url)) {
    // A blob: URL carries the origin that made it; a data: URL in a Field tab is Field's.
    const inner = url.toLowerCase().startsWith("blob:") ? originOf(url.slice(5)) : fieldOrigin;
    return inner === fieldOrigin;
  }
  return originOf(url) === fieldOrigin;
}

// The Field tab a download belongs to: the tab itself, or the tab hosting a webview.
function tabOf(contents) {
  return contents.getType && contents.getType() === "webview" ? contents.hostWebContents : contents;
}

function handleDownload(_event, item, contents) {
  if (!contents || isFieldsOwn(item, contents)) return;
  const tab = tabOf(contents);
  const folder = tab && folders.get(tab.id);
  if (!folder) return; // no web() run has said where: Chromium asks as usual
  fs.mkdirSync(folder, { recursive: true });
  const file = uniquePath(folder, item.getFilename());
  item.setSavePath(file);
  const guestId = contents.getType && contents.getType() === "webview" ? contents.id : null;
  log("web-download", { url: item.getURL(), file });
  item.once("done", (_e, done) => {
    // A CDP client that set Browser.setDownloadBehavior (Playwright attached to the tab does) makes
    // Chromium save under its own name in its own folder whatever path was chosen here, while still
    // reporting this path as completed. Say so rather than hand box code a file that is not there.
    const state = done === "completed" && !fs.existsSync(file) ? "elsewhere" : done;
    if (state === "elsewhere") log("web-download-elsewhere", { url: item.getURL(), file });
    if (tab.isDestroyed()) return;
    tab.send("field4-web-profile-event", {
      kind: "download",
      url: item.getURL(),
      file,
      state, // completed | cancelled | interrupted | elsewhere
      bytes: item.getReceivedBytes(),
      mimeType: item.getMimeType(),
      page: guestId != null ? { guestId } : null,
    });
  });
}

// Saves [session]'s web() downloads into document folders. Call once per session.
function watchSession(session) {
  if (watched.has(session)) return;
  watched.add(session);
  session.on("will-download", handleDownload);
}

// The Field tab [contents] keeps downloads from its web() pages in [folder].
function setFolder(contents, folder) {
  if (!folder || !path.isAbsolute(folder)) throw new Error(`A downloads folder must be an absolute path, not ${folder}`);
  folders.set(contents.id, folder);
  contents.once("destroyed", () => folders.delete(contents.id));
  return true;
}

function configure({ fieldUrl, log: logger } = {}) {
  fieldOrigin = originOf(fieldUrl);
  if (logger) log = logger;
}

module.exports = { watchSession, setFolder, configure, uniquePath, isFieldsOwn, handleDownload };
