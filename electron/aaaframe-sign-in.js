// Main-process half of Field's aaaframe sign-in (dev_docs/WEBRTC_SOURCES_SINKS.md).
//
// aaaframe's login is a Quart session cookie, HttpOnly and SameSite=Lax, set by its GitHub OAuth
// callback. No page can read it, and Field's WebRTC signalling bridge in the JVM needs its value. So
// a student signs in once in a window Field owns: it loads aaaframe's own /login page in a persistent
// partition, aaaframe and GitHub do their normal flow, and this process reads the cookie from that
// partition's cookie jar. A cookie alone proves nothing (aaaframe writes one before login to hold the
// OAuth state), so each candidate is checked against /api/shadertrinket-config, which answers 401
// until the session holds a user. The value goes back to the page that asked, which hands it to Field.
const { BrowserWindow, ipcMain, session } = require("electron");

const PARTITION = "persist:field-aaaframe";
const pending = new Map(); // base URL -> Promise of the result

const normalize = (url) => {
  const parsed = new URL(String(url));
  if (!["http:", "https:"].includes(parsed.protocol)) throw new Error("aaaframe's URL must be http(s): " + url);
  return parsed.origin + parsed.pathname.replace(/\/+$/, "");
};

// The signed-in login for this cookie value, or null.
async function loginFor(base, value) {
  const response = await fetch(base + "/api/shadertrinket-config", {
    headers: { Cookie: "session=" + value },
    redirect: "manual",
  });
  if (response.status !== 200) return null;
  const config = await response.json().catch(() => null);
  const login = config && config.user && config.user.login;
  return login ? String(login) : null;
}

async function signedIn(ses, base) {
  const cookies = await ses.cookies.get({ url: base + "/", name: "session" });
  for (const cookie of cookies) {
    const login = await loginFor(base, cookie.value).catch(() => null);
    if (login) return { ok: true, base, session: cookie.value, login };
  }
  return null;
}

function signIn(parent, base, { force }) {
  const ses = session.fromPartition(PARTITION);
  return new Promise(async (resolve) => {
    // Already signed in from an earlier run (the partition persists): no window at all.
    if (!force) {
      const existing = await signedIn(ses, base).catch(() => null);
      if (existing) return resolve(existing);
    } else {
      await ses.cookies.remove(base + "/", "session").catch(() => {});
    }

    const win = new BrowserWindow({
      width: 560,
      height: 760,
      title: "Sign in to aaaframe",
      parent: parent || undefined,
      autoHideMenuBar: true,
      webPreferences: { partition: PARTITION, contextIsolation: true, sandbox: true, nodeIntegration: false },
    });
    let done = false;
    const finish = (result) => {
      if (done) return;
      done = true;
      ses.cookies.removeListener("changed", onCookie);
      clearTimeout(timeout);
      if (!win.isDestroyed()) win.close();
      resolve(result);
    };
    const check = () => signedIn(ses, base).then((r) => r && finish(r)).catch(() => {});
    const onCookie = (_event, cookie) => { if (cookie.name === "session") check(); };
    ses.cookies.on("changed", onCookie);
    win.webContents.on("did-navigate", check);
    win.on("closed", () => finish({ ok: false, base, error: "the sign-in window was closed before signing in" }));
    const timeout = setTimeout(() => finish({ ok: false, base, error: "sign-in timed out after 10 minutes" }), 10 * 60 * 1000);
    win.loadURL(base + "/login?next=" + encodeURIComponent("/api/shadertrinket-config"))
      .catch((error) => finish({ ok: false, base, error: "could not load " + base + "/login: " + error.message }));
  });
}

const install = () => {
  ipcMain.handle("field4-aaaframe-sign-in", async (event, { url, force } = {}) => {
    let base;
    try { base = normalize(url); } catch (error) { return { ok: false, error: String(error.message || error) }; }
    // One window per server, however many panels ask at once.
    if (!pending.has(base)) {
      const parent = BrowserWindow.fromWebContents(event.sender);
      pending.set(base, signIn(parent, base, { force: !!force }).finally(() => pending.delete(base)));
    }
    return pending.get(base);
  });
};

module.exports = { install };
