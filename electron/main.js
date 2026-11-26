const fs = require("node:fs");
const { useAngleVulkan } = require("./gpu-policy.cjs");
const { remoteFieldUrl, isRemoteFieldUrl, configureRemoteSession } = require("./remote-field.cjs");
const { discoverRemoteFields } = require("./field-discovery.cjs");
const path = require("node:path");
const { pathToFileURL } = require("node:url");
const {
  app,
  BrowserWindow,
  Menu,
  WebContentsView,
  ipcMain,
  screen,
  session,
  systemPreferences,
  dialog,
} = require("electron");

const APP_NAME = "Field4";
const APP_USER_MODEL_ID = "com.field4.desktop";
const APP_ICON_ICO = path.join(__dirname, "Field4.ico");
const APP_ICON_1024 = path.join(__dirname, "icon-1024.png");
const TAB_STRIP_PRELOAD = path.join(__dirname, "tabstrip-preload.js");
const FIELD_PRELOAD = path.join(__dirname, "field-preload.js");
// Chromium's generic `monospace` is Courier on macOS. It shows wherever a page names no font (a bare
// <textarea>, <pre>, <code>) or a stack falls through to `monospace` before Iosevka Web has loaded.
// Point the generic at a real coding font for every page, iframe included, in a Field tab.
const FIELD_DEFAULT_FONT_FAMILY = {
  monospace:
    process.platform === "darwin" ? "Menlo" : process.platform === "win32" ? "Consolas" : "DejaVu Sans Mono",
};
const TAB_STRIP_HEIGHT = 44;
const TAB_STRIP_REVEAL_ZONE_HEIGHT = 6;
const TAB_TRAFFIC_LIGHT_INSET = process.platform === "darwin" ? 88 : 12;
const TAB_WINDOW_CONTROL_INSET = process.platform === "win32" ? 150 : 12;
const NAV_BACK_TITLE =
  process.platform === "darwin" ? "Back (⌘[)" : "Back (Alt+Left)";
const NAV_FORWARD_TITLE =
  process.platform === "darwin" ? "Forward (⌘])" : "Forward (Alt+Right)";
const SHELL_BACKGROUND = "#000000";
const TAB_STRIP_BACKGROUND = "#2a2b2f";
// Welcome and the documentation paint their own #141414 page; their view matches it so the shell's
// black never shows behind them while they load.
const OWN_PAGE_BACKGROUND = "rgb(20, 20, 20)";
const ZOOM_LEVEL_STEP = 0.5;
const MIN_ZOOM_LEVEL = -8;
const MAX_ZOOM_LEVEL = 8;
// History screenshot budget -- see screenshotPayload(). Downscale to logical (non-retina) pixels,
// capped, and encode JPEG: the work runs on the input-routing thread, so it must stay cheap.
const HISTORY_SHOT_MAX_WIDTH = 1600;
const HISTORY_SHOT_JPEG_QUALITY = 70;
const HISTORY_SHOT_RESIZE_QUALITY = "good";
const TAB_STRIP_ASSET_BASE_URL = pathToFileURL(
  path.join(__dirname, "..", "public") + path.sep,
).toString();
const WITH_DEVTOOLS = ["1", "true", "yes", "on"].includes(
  String(process.env.WITH_DEVTOOLS || "").toLowerCase(),
);
const LOG_GPU_STATUS =
  process.platform === "linux" ||
  ["1", "true", "yes", "on"].includes(
    String(process.env.FIELD_ELECTRON_LOG_GPU || "").toLowerCase(),
  );

const DIAGNOSTIC_LOG_RETENTION = 20;
const diagnosticStartedAt = Date.now();
let diagnosticLogPath = null;
let diagnosticLogStream = null;


// AI4 push-to-talk (voiceInput.js). macOS only grants the microphone to an app that asks, and a
// page's getUserMedia alone reports a refusal as a bare "Permission denied". Asking here shows the
// system prompt the first time and lets the page say where to re-enable it after a "no".
async function onMicrophoneAccess() {
  if (process.platform !== "darwin") return "granted";
  let status = systemPreferences.getMediaAccessStatus("microphone");
  if (status === "not-determined") {
    await systemPreferences.askForMediaAccess("microphone");
    status = systemPreferences.getMediaAccessStatus("microphone");
  }
  diagnosticLog("microphone-access", { status });
  return status;
}
function diagnosticError(error) {
  if (!error) return null;
  return {
    name: error.name || "Error",
    message: error.message || String(error),
    stack: error.stack || null,
  };
}

function initializeDiagnosticLog() {
  try {
    const directory = path.join(app.getPath("userData"), "diagnostics");
    fs.mkdirSync(directory, { recursive: true });
    const stamp = new Date().toISOString().replace(/[:.]/g, "-");
    diagnosticLogPath = path.join(
      directory,
      `field4-desktop-${stamp}-${process.pid}.jsonl`,
    );
    fs.writeFileSync(diagnosticLogPath, "", "utf8");
    diagnosticLogStream = fs.createWriteStream(diagnosticLogPath, {
      flags: "a",
      encoding: "utf8",
    });
    diagnosticLogStream.on("error", (error) => {
      console.warn("[electron,diagnostic] diagnostic stream failed", error);
      diagnosticLogStream = null;
    });

    const oldLogs = fs
      .readdirSync(directory)
      .filter(
        (name) => name.startsWith("field4-desktop-") && name.endsWith(".jsonl"),
      )
      .map((name) => ({ name, path: path.join(directory, name) }))
      .sort((a, b) => b.name.localeCompare(a.name));
    oldLogs.slice(DIAGNOSTIC_LOG_RETENTION).forEach((entry) => {
      try {
        fs.unlinkSync(entry.path);
      } catch (_) {
        // Diagnostics must never interfere with startup.
      }
    });

    console.log(`[electron,diagnostic] writing ${diagnosticLogPath}`);
    diagnosticLog("desktop-start", {
      argv: process.argv.slice(1),
      platform: process.platform,
      electron: process.versions.electron,
      chrome: process.versions.chrome,
      node: process.versions.node,
    });
  } catch (error) {
    diagnosticLogPath = null;
    diagnosticLogStream = null;
    console.warn("[electron,diagnostic] diagnostics disabled", error);
  }
}

function diagnosticLog(event, details = {}) {
  if (!diagnosticLogStream) return;
  const record = {
    timestamp: new Date().toISOString(),
    elapsedMs: Date.now() - diagnosticStartedAt,
    pid: process.pid,
    event,
    details,
  };
  try {
    diagnosticLogStream.write(`${JSON.stringify(record)}\n`);
  } catch (error) {
    console.warn("[electron,diagnostic] could not queue diagnostic record", error);
  }
}

// Electron's default handler for an uncaught main-process error is a modal NSAlert
// ("A JavaScript error occurred in the main process"). A modal run loop stops Chromium
// servicing browser-process tasks, so every tab freezes mid-load — a newly opened tab
// commits its navigation and then renders black forever. Worse, the dialog is a small
// window that readily hides behind the main window or sits on another Space, so the app
// looks hung for no reason and only a restart clears it. Log and keep running instead:
// a broken tab is recoverable, a frozen browser process is not.
process.on("uncaughtException", (error) => {
  console.error("[electron,uncaught] main-process exception", error);
  diagnosticLog("main-process-uncaught-exception", {
    error: diagnosticError(error),
  });
});
process.on("unhandledRejection", (reason) => {
  console.error("[electron,uncaught] unhandled rejection", reason);
  diagnosticLog("main-process-unhandled-rejection", {
    error: diagnosticError(
      reason instanceof Error ? reason : new Error(String(reason)),
    ),
  });
});

app.setName(APP_NAME);
// A capture Electron (afield.tools.docs.ElectronCaptureBrowser) gets its own profile, so the user's
// per-host zoom, cookies and window state never reach a docs capture, and their running Field4 app
// keeps its profile to itself.
if (process.env.FIELD_ELECTRON_USER_DATA) {
  app.setPath("userData", process.env.FIELD_ELECTRON_USER_DATA);
}
if (process.platform === "win32") {
  app.setAppUserModelId(APP_USER_MODEL_ID);
}
process.title = APP_NAME;

function appendChromiumSwitchIfMissing(name, value) {
  if (!app.commandLine.hasSwitch(name)) {
    app.commandLine.appendSwitch(name, value);
  }
}

// Chromium keeps one --enable-features list; add to it rather than replacing what is there.
function enableChromiumFeature(feature) {
  const current = app.commandLine.hasSwitch("enable-features")
    ? app.commandLine.getSwitchValue("enable-features").split(",").filter(Boolean)
    : [];
  if (!current.includes(feature)) {
    app.commandLine.appendSwitch("enable-features", [...current, feature].join(","));
  }
}

// SharedArrayBuffer for every Field page, without cross-origin isolation. Threaded WebAssembly
// (Patchy's Qt build) needs it, and the only other way to get it is COOP/COEP headers on the
// whole Field page, which block every cross-origin iframe in every output area. Pages in an
// external browser still need AFIELD_CROSS_ORIGIN_ISOLATION=1 on the server for it.
enableChromiumFeature("SharedArrayBuffer");

function installChromiumGpuSwitches() {
  if (process.env.FIELD_ELECTRON_SOFTWARE_GPU === "1") {
    console.log("[electron,gpu] software GPU mode requested by FIELD_ELECTRON_SOFTWARE_GPU=1");
    return;
  }

  if (process.platform !== "linux") return;

  appendChromiumSwitchIfMissing("ozone-platform-hint", "auto");
  // The desktop must work on mixed GPUs and ordinary drivers. Let Chromium select its backend.
  // The offscreen browser-texture producer configures its own accelerated switches separately.
  if (!shouldUseAngleVulkan()) return;
  enableChromiumFeature("Vulkan");
  appendChromiumSwitchIfMissing("use-angle", "vulkan");
}

function shouldUseAngleVulkan() {
  return useAngleVulkan(process.platform, process.env.FIELD_ELECTRON_ANGLE_VULKAN);
}

installChromiumGpuSwitches();

function installLinuxFractionalGlyphPositioning() {
  if (process.platform !== "linux") return;
  if (process.env.FIELD_ELECTRON_FRACTIONAL_GLYPHS !== "1") return;

  // Chromium only enables subpixel glyph positioning above a 1x device scale.
  // Crossing that threshold by 0.1% preserves the visible 1x UI scale while
  // preventing monospaced advances from snapping to whole CSS pixels.
  appendChromiumSwitchIfMissing("force-device-scale-factor", "1.001");
}

installLinuxFractionalGlyphPositioning();

function logChromiumGpuSwitches() {
  const names = [
    "ignore-gpu-blocklist",
    "disable-gpu-process-crash-limit",
    "enable-gpu-rasterization",
    "enable-zero-copy",
    "enable-features",
    "use-angle",
    "ozone-platform-hint",
    "force-device-scale-factor",
    "disable-gpu",
    "disable-software-rasterizer",
  ];
  const switches = Object.fromEntries(
    names.map((name) => [
      name,
      app.commandLine.hasSwitch(name) ? app.commandLine.getSwitchValue(name) || true : false,
    ]),
  );
  console.log("[electron,gpu] requested switches", switches);
}

function appIconPath() {
  if (process.platform === "win32" && fs.existsSync(APP_ICON_ICO)) {
    return APP_ICON_ICO;
  }
  if (fs.existsSync(APP_ICON_1024)) {
    return APP_ICON_1024;
  }
  return undefined;
}

function normalizeUrl(target, base) {
  try {
    return new URL(String(target), base).toString();
  } catch (_) {
    return String(target || "");
  }
}

function filenameFromUrl(rawUrl) {
  try {
    const parsed = new URL(rawUrl);
    const file = parsed.searchParams.get("file");
    if (file && file.trim()) return file.trim();

    const pathname = parsed.pathname.replace(/\/+$/, "");
    if (pathname.endsWith("/welcome")) return "Welcome";
    if (isDocsUrl(rawUrl)) return "Documentation";
    if (pathname.endsWith("/dir")) return "Directory";
    if (pathname.endsWith("/code-use")) return "Code Use";
    if (pathname.endsWith("/index.html")) return "document";
    if (pathname.endsWith("/docs-pass.html")) return "Docs Pass";
    if (pathname.endsWith("/refactor-pass.html")) return "Refactoring";
    // Any other Field page (/history.html, /logs.html, ...) is named by its file, not the host.
    const page = pathname.match(/\/([^/]+)\.html$/);
    if (page) return page[1].charAt(0).toUpperCase() + page[1].slice(1);
    if (parsed.hostname) return parsed.hostname;
  } catch (_) {
    // Ignore invalid URLs and fall back below.
  }
  return "Welcome";
}

// A utility tab is any Field page that is not a document (welcome, terminal, docs pass, ...).
function isUtilityUrl(rawUrl) {
  return documentKeyFromUrl(rawUrl) === null;
}

function documentKeyFromUrl(rawUrl) {
  try {
    const parsed = new URL(rawUrl);
    const file = parsed.searchParams.get("file");
    if (!file || !file.trim()) return null;
    return `${parsed.origin}\u0000${file.trim()}`;
  } catch (_) {
    return null;
  }
}

function isWelcomeUrl(rawUrl) {
  try {
    return new URL(rawUrl).pathname.replace(/\/+$/, "").endsWith("/welcome");
  } catch (_) {
    return false;
  }
}

// `/field-docs/` is Field's mirror of the docs site; `/field4/` is the site itself.
function isDocsUrl(rawUrl) {
  try {
    return /\/(field-docs|field4)(\/|$)/.test(new URL(rawUrl).pathname.replace(/\/+$/, ""));
  } catch (_) {
    return false;
  }
}

function paintsOwnBackground(rawUrl) {
  return isWelcomeUrl(rawUrl) || isDocsUrl(rawUrl);
}

function titleForUrl(rawUrl) {
  return `${APP_NAME} — ${filenameFromUrl(rawUrl)}`;
}

function setViewBackground(view, color = SHELL_BACKGROUND) {
  if (!view || typeof view.setBackgroundColor !== "function") return;
  view.setBackgroundColor(color);
}

function colorForUrl(rawUrl) {
  return paintsOwnBackground(rawUrl) ? OWN_PAGE_BACKGROUND : SHELL_BACKGROUND;
}

function displayScaleFactor(win) {
  try {
    return screen.getDisplayMatching(win.getBounds()).scaleFactor || 1;
  } catch (_) {
    return 1;
  }
}

function installPageBackgroundGuard(tab) {
  const webContents = tab?.view?.webContents;
  if (!webContents) return;
  const insert = () => {
    if (webContents.isDestroyed()) return;
    if (paintsOwnBackground(webContents.getURL() || tab.url)) return;
    webContents
      .insertCSS(
        `
      html,
      body {
        background-color: ${SHELL_BACKGROUND} !important;
      }
    `,
      )
      .catch(() => {});
  };
  webContents.on("dom-ready", insert);
}

function createTabStripMarkup() {
  return `<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="color-scheme" content="dark">
  <style>
    @font-face {
      font-family: "Iosevka Web";
      font-display: block;
      font-style: normal;
      font-stretch: semi-condensed;
      font-weight: 300;
      src: url("woff2/iosevka-semicondensedlight.woff2") format("woff2");
    }

    :root {
      --traffic-inset: ${TAB_TRAFFIC_LIGHT_INSET}px;
      --window-control-inset: ${TAB_WINDOW_CONTROL_INSET}px;
      --bg: #2a2b2f;
      --tab-bg: #1d1f23;
      --tab-hover-bg: #222428;
      --tab-active-bg: rgb(20, 20, 20);
      --tab-separator: #3b3d43;
      --tab-bottom-corner: 5px;
      --control-bg: #1d1f23;
      --text: rgba(242, 242, 242, 0.94);
      --text-soft: rgba(173, 173, 173, 0.88);
    }

    * {
      box-sizing: border-box;
      font-family: "Iosevka Web", "Iosevka", "Iosevka Term", "Iosevka Nerd Font", monospace;
      font-feature-settings: "dlig" 1, "TXTR" 1;
      font-stretch: semi-condensed;
      font-weight: 300;
      letter-spacing: 0;
    }

    html, body {
      margin: 0;
      width: 100%;
      height: 100%;
      overflow: hidden;
      background: var(--bg);
      color: var(--text);
      user-select: none;
    }

    #tabstrip {
      height: 100%;
      display: flex;
      align-items: center;
      gap: 0;
      padding: 7px var(--window-control-inset) 0 var(--traffic-inset);
      background: var(--bg);
      -webkit-app-region: drag;
    }

    #tabs {
      flex: 0 1 auto;
      min-width: 0;
      display: flex;
      align-items: stretch;
      gap: 0;
      height: 100%;
      overflow-x: auto;
      scrollbar-width: none;
      -webkit-app-region: no-drag;
    }

    #tabs::-webkit-scrollbar {
      display: none;
    }

    .tab {
      position: relative;
      display: inline-flex;
      align-items: center;
      gap: 8px;
      min-width: 128px;
      max-width: 240px;
      padding: 0 10px 0 14px;
      border: 0;
      border-bottom: 8px solid transparent;
      background: transparent;
      color: var(--text-soft);
      cursor: pointer;
      height: 100%;
      margin: 0;
      opacity:0.5;
      font: "Iosevka Web";
    }

    .tab.active {
      border-bottom-color: var(--tab-active-bg);
      border-top-color: transparent;
      color: var(--text);
      opacity:1;
    }

    .tab:hover {
      color: var(--text);
    }

    .tab.utility {
      background: rgba(255, 255, 255, 0.04);
    }

    .tab.active:hover {
      border-bottom-color: var(--tab-active-bg);
    }

    .tab-label {
      flex: 1 1 auto;
      min-width: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      font-size: 12px;
      line-height: 1.2;
      text-align: left;
    }

    .tab-close,
    .tab-add {
      width: 22px;
      height: 22px;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      border-radius: 4px 4px 0 0;
      border: 0;
      background: transparent;
      color: inherit;
      cursor: pointer;
      font-size: 14px;
      line-height: 1;
    }

    .tab-close {
      opacity: 0;
      pointer-events: none;
      transition: opacity 0.12s ease, color 0.12s ease;
    }

    .tab:hover .tab-close,
    .tab:focus-within .tab-close {
      opacity: 1;
      pointer-events: auto;
    }

    .tab-close:hover {
      background: rgba(255, 255, 255, 0.1);
    }

    .tab-add {
      flex: 0 0 auto;
      width: 34px;
      height: 30px;
      margin-left: 8px;
      border-radius: 5px 5px 0 0;
      color: var(--text-soft);
      background: transparent;
      font-size: 24px;
      line-height: 1;
      -webkit-app-region: no-drag;
    }

    .tab-add:hover {
      background: transparent;
      color: var(--text);
    }

    .tab-spacer {
      flex: 1 1 auto;
      min-width: 12px;
    }

    #remote-open, #remote-form {
      -webkit-app-region: no-drag;
    }

    #remote-open {
      flex: 0 0 auto;
      display: flex;
      align-items: center;
      justify-content: center;
    }

    #remote-form {
      display: flex;
      align-items: center;
      gap: 4px;
      width: 100%;
    }

    #remote-form[hidden], #tabstrip.connecting > :not(#remote-form) {
      display: none;
    }

    #remote-address, #remote-servers {
      min-width: 0;
      height: 26px;
      padding: 0 8px;
      border: 1px solid var(--tab-separator);
      border-radius: 4px;
      background: var(--tab-bg);
      color: var(--text);
    }

    #remote-address {
      flex: 1;
    }

    #remote-server-picker {
      position: relative;
      min-width: 0;
      max-width: 40%;
    }

    #remote-servers {
      display: block;
      max-width: 100%;
      padding-inline-end: 28px;
      appearance: none;
      outline: none;
    }

    #remote-servers:focus-visible {
      background: var(--tab-hover-bg);
    }

    #remote-server-picker::after {
      content: "";
      position: absolute;
      inset-inline-end: 11px;
      top: 50%;
      width: 6px;
      height: 6px;
      border-right: 1px solid currentColor;
      border-bottom: 1px solid currentColor;
      transform: translateY(-70%) rotate(45deg);
      pointer-events: none;
    }

    #remote-server-picker:has(select:disabled)::after {
      opacity: 0.3;
    }

    /* Back/forward and find-in-page for the active page tab (Documentation and other utility pages).
       A document tab shows only the find bar, as its box search, while that is open. */
    #page-tools {
      flex: 0 0 auto;
      display: flex;
      align-items: center;
      gap: 6px;
      /* #tabstrip's 7px top padding seats the tabs on the page; step over it so these centre on the
         whole strip, not on the part below the padding. */
      height: calc(100% + 7px);
      margin-top: -7px;
      -webkit-app-region: no-drag;
    }

    #page-tools[hidden],
    #find[hidden],
    #nav[hidden] {
      display: none;
    }

    .tool-button {
      height: 24px;
      min-width: 26px;
      padding: 0 6px;
      border: 0;
      border-radius: 4px;
      background: transparent;
      color: var(--text-soft);
      cursor: pointer;
      font-size: 14px;
      line-height: 1;
    }

    .tool-button:hover:not(:disabled) {
      background: rgba(255, 255, 255, 0.1);
      color: var(--text);
    }

    .tool-button:disabled {
      opacity: 0.3;
      cursor: default;
    }

    #nav {
      display: flex;
      gap: 2px;
    }

    #find {
      display: flex;
      align-items: center;
      gap: 2px;
      height: 26px;
      padding: 0 2px 0 8px;
      border: 1px solid var(--tab-separator);
      border-radius: 5px;
      background: var(--tab-bg);
    }

    #find-input {
      width: 180px;
      border: 0;
      outline: none;
      background: transparent;
      color: var(--text);
      font-size: 12px;
      user-select: text;
    }

    #find-input::placeholder {
      color: var(--text-soft);
    }

    #find-count {
      min-width: 44px;
      padding-right: 4px;
      color: var(--text-soft);
      font-size: 11px;
      text-align: right;
      white-space: nowrap;
    }

    #find.no-match #find-count {
      color: #e8837c;
    }
  </style>
</head>
<body>
  <div id="tabstrip">
    <div id="tabs"></div>
    <button id="tab-add" class="tab-add" type="button" aria-label="Open welcome tab">+</button>
    <form id="remote-form" hidden>
      <div id="remote-server-picker">
        <select id="remote-servers" aria-label="Discovered Fields" disabled></select>
      </div>
      <button id="remote-refresh" class="tool-button" type="button" aria-label="Refresh discovered Fields" title="Refresh discovered Fields">↻</button>
      <input id="remote-address" type="url" aria-label="Remote Field URL" placeholder="https://field-host:9092" required spellcheck="false" autocomplete="off">
      <button class="tool-button" type="submit">Connect</button>
      <button id="remote-cancel" class="tool-button" type="button">Cancel</button>
    </form>
    <div class="tab-spacer"></div>
    <div id="page-tools" hidden>
      <div id="find" hidden>
        <input id="find-input" type="text" placeholder="Find in page" spellcheck="false" autocomplete="off">
        <span id="find-count"></span>
        <button id="find-prev" class="tool-button" type="button" title="Previous match (Shift+Enter)">&uarr;</button>
        <button id="find-next" class="tool-button" type="button" title="Next match (Enter)">&darr;</button>
        <button id="find-close" class="tool-button" type="button" title="Close (Esc)">&times;</button>
      </div>
      <div id="nav" hidden>
        <button id="nav-back" class="tool-button" type="button" title="${NAV_BACK_TITLE}" disabled>&larr;</button>
        <button id="nav-forward" class="tool-button" type="button" title="${NAV_FORWARD_TITLE}" disabled>&rarr;</button>
      </div>
    </div>
    <button id="remote-open" class="tool-button" type="button" aria-label="Open remote Field" title="Open remote Field…"><svg width="15" height="15" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.3" aria-hidden="true"><circle cx="8" cy="8" r="6.3"/><ellipse cx="8" cy="8" rx="2.6" ry="6.3"/><path d="M1.9 6h12.2M1.9 10h12.2"/></svg></button>
  </div>
  <script>
    const tabsNode = document.getElementById('tabs');
    const addButton = document.getElementById('tab-add');
    const remoteButton = document.getElementById('remote-open');
    const remoteForm = document.getElementById('remote-form');
    const remoteAddress = document.getElementById('remote-address');
    const remoteServers = document.getElementById('remote-servers');
    const remoteRefresh = document.getElementById('remote-refresh');
    const tabStrip = document.getElementById('tabstrip');
    const pageTools = document.getElementById('page-tools');
    const navNode = document.getElementById('nav');
    const backButton = document.getElementById('nav-back');
    const forwardButton = document.getElementById('nav-forward');
    const findNode = document.getElementById('find');
    const findInput = document.getElementById('find-input');
    const findCount = document.getElementById('find-count');
    let welcomeOpen = false;
    let findTabId = null;
    let remoteSearch = 0;

    // The find bar is static DOM (render() rebuilds only #tabs), so typing in it never loses the caret.
    function renderPageTools(page) {
      pageTools.hidden = !page;
      if (!page) return;
      navNode.hidden = !page.navigable;
      backButton.disabled = !page.canGoBack;
      forwardButton.disabled = !page.canGoForward;
      const find = page.find || {};
      findNode.hidden = !find.open;
      if (page.tabId !== findTabId) {
        findInput.value = find.query || '';
        findTabId = page.tabId;
      }
      findInput.placeholder = find.placeholder || 'Find in page';
      const hasQuery = !!find.query;
      const matches = Number.isFinite(find.matches) ? find.matches : null;
      // Box search counts matches before you step to one ("12 matches"), then names the property
      // the active match is in ("3/12 · code"); find-in-page always has an active match.
      const detail = find.detail ? ' \u00b7 ' + find.detail : '';
      findCount.textContent =
        !hasQuery || matches === null ? ''
          : matches === 0 ? 'No matches'
          : find.active > 0 ? find.active + '/' + matches + detail
          : matches === 1 ? '1 match' : matches + ' matches';
      findNode.classList.toggle('no-match', hasQuery && matches === 0);
      if (find.open && find.focus) {
        findInput.focus();
        findInput.select();
      }
    }

    backButton.addEventListener('click', () => window.fieldTabs.navigate(-1));
    forwardButton.addEventListener('click', () => window.fieldTabs.navigate(1));
    findInput.addEventListener('input', () => window.fieldTabs.findQuery(findInput.value));
    findInput.addEventListener('keydown', (event) => {
      const mod = event.metaKey || event.ctrlKey;
      const key = event.key.toLowerCase();
      if (event.key === 'Enter' || (mod && key === 'g')) {
        event.preventDefault();
        window.fieldTabs.findStep(!event.shiftKey);
      } else if (event.key === 'Escape') {
        event.preventDefault();
        window.fieldTabs.findClose();
      } else if (mod && key === 'f') {
        event.preventDefault();
        findInput.select();
      }
    });
    document.getElementById('find-prev').addEventListener('click', () => window.fieldTabs.findStep(false));
    document.getElementById('find-next').addEventListener('click', () => window.fieldTabs.findStep(true));
    document.getElementById('find-close').addEventListener('click', () => window.fieldTabs.findClose());

    function render(state) {
      renderPageTools(state?.page || null);
      const tabs = Array.isArray(state?.tabs) ? state.tabs : [];
      welcomeOpen = tabs.some((tab) => tab.welcome);
      addButton.hidden = welcomeOpen;
      addButton.disabled = welcomeOpen;
      tabsNode.innerHTML = tabs.map((tab) => {
        const title = String(tab.title || 'Welcome')
          .replace(/&/g, '&amp;')
          .replace(/</g, '&lt;')
          .replace(/>/g, '&gt;')
          .replace(/"/g, '&quot;')
          .replace(/'/g, '&#39;');
        const activeClass = (tab.active ? ' active' : '') + (tab.utility ? ' utility' : '');
        return \`
          <button type="button" class="tab\${activeClass}" data-tab-id="\${tab.id}" title="\${title}" aria-pressed="\${tab.active ? 'true' : 'false'}">
            <span class="tab-label">\${title}</span>
            <span class="tab-close" data-tab-close="\${tab.id}" aria-label="Close \${title}">&times;</span>
          </button>
        \`;
      }).join('');

      tabsNode.querySelectorAll('[data-tab-id]').forEach((node) => {
        node.addEventListener('click', (event) => {
          const closeNode = event.target.closest('[data-tab-close]');
          if (closeNode) return;
          window.fieldTabs.activateTab(node.getAttribute('data-tab-id'));
        });
      });

      tabsNode.querySelectorAll('[data-tab-close]').forEach((node) => {
        node.addEventListener('click', (event) => {
          event.preventDefault();
          event.stopPropagation();
          window.fieldTabs.closeTab(node.getAttribute('data-tab-close'));
        });
      });
    }

    addButton.addEventListener('click', () => {
      if (welcomeOpen) return;
      window.fieldTabs.openWelcomeTab();
    });
    function closeRemoteForm() {
      remoteSearch++;
      remoteForm.hidden = true;
      tabStrip.classList.remove('connecting');
    }
    function cancelRemoteForm() {
      closeRemoteForm();
      remoteButton.focus();
    }
    async function findRemoteFields() {
      const search = ++remoteSearch;
      remoteServers.disabled = remoteRefresh.disabled = true;
      remoteServers.replaceChildren(new Option('Finding Fields…', ''));
      const fields = await window.fieldTabs.discoverRemoteFields().catch(() => []);
      if (search !== remoteSearch) return;
      remoteRefresh.disabled = false;
      remoteServers.disabled = !fields.length;
      const prompt = new Option(fields.length ? 'Discovered Fields' : 'No Fields found', '', true, true);
      prompt.disabled = true;
      prompt.hidden = fields.length > 0;
      remoteServers.replaceChildren(prompt);
      for (const field of fields) {
        const host = new URL(field.url).host;
        remoteServers.add(new Option(field.name + ' · ' + host + ' (' + field.source + ')', field.url));
      }
    }
    remoteButton.addEventListener('click', () => {
      tabStrip.classList.add('connecting');
      remoteForm.hidden = false;
      remoteAddress.focus();
      remoteAddress.select();
      findRemoteFields();
    });
    remoteRefresh.addEventListener('click', findRemoteFields);
    remoteServers.addEventListener('change', () => {
      if (!remoteServers.value) return;
      remoteAddress.value = remoteServers.value;
      remoteAddress.setCustomValidity('');
    });
    remoteAddress.addEventListener('input', () => remoteAddress.setCustomValidity(''));
    document.getElementById('remote-cancel').addEventListener('click', cancelRemoteForm);
    remoteForm.addEventListener('keydown', (event) => {
      if (event.key === 'Escape') cancelRemoteForm();
    });
    remoteForm.addEventListener('submit', async (event) => {
      event.preventDefault();
      const error = await window.fieldTabs.openRemoteTab(remoteAddress.value);
      if (error) {
        remoteAddress.setCustomValidity(error);
        remoteAddress.reportValidity();
      } else {
        closeRemoteForm();
      }
    });
    document.body.addEventListener('mouseenter', () => window.fieldTabs.revealTabs());
    document.body.addEventListener('mouseleave', () => {
      if (remoteForm.hidden) window.fieldTabs.hideTabs();
    });
    window.fieldTabs.onState(render);
    window.fieldTabs.ready();
  </script>
</body>
</html>`;
}

function createTabRevealMarkup() {
  return `<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="color-scheme" content="dark">
  <style>
    html,
    body {
      margin: 0;
      width: 100%;
      height: 100%;
      overflow: hidden;
      background: #000;
    }
  </style>
</head>
<body>
  <script>
    document.body.addEventListener('mouseenter', () => window.fieldTabs.revealTabs());
    document.body.addEventListener('mousemove', () => window.fieldTabs.revealTabs());
  </script>
</body>
</html>`;
}

function installAppMenu() {
  if (process.platform !== "darwin") return;

  const template = [
    {
      label: APP_NAME,
      submenu: [
        { role: "about" },
        { type: "separator" },
        { role: "services" },
        { type: "separator" },
        { role: "hide" },
        { role: "hideOthers" },
        { role: "unhide" },
        { type: "separator" },
        { role: "quit" },
      ],
    },
    {
      label: "Edit",
      submenu: [
        { role: "undo" },
        { role: "redo" },
        { type: "separator" },
        { role: "cut" },
        { role: "copy" },
        { role: "paste" },
        { role: "selectAll" },
      ],
    },
    {
      label: "View",
      submenu: [
        { role: "reload" },
        { role: "forceReload" },
        { role: "toggleDevTools" },
        { type: "separator" },
        { role: "resetZoom" },
        { role: "zoomIn" },
        { role: "zoomOut" },
        { type: "separator" },
        { role: "togglefullscreen" },
      ],
    },
    {
      label: "Window",
      submenu: [
        { role: "minimize" },
        { role: "zoom" },
        { type: "separator" },
        { role: "front" },
      ],
    },
    {
      label: "Presentation",
      submenu: [
        {
          label: "New Presentation…",
          click: () => require("./presentation").newPresentation(),
        },
        {
          label: "Present Current Guide",
          click: () => require("./presentation").presentCurrentGuide().catch(error => dialog.showErrorBox("Could not present guide", String(error))),
        },
        { type: "separator" },
        {
          label: "Open Motion Canvas Project…",
          click: () => require("./presentation").choosePresentation(),
        },
      ],
    },
  ];

  Menu.setApplicationMenu(Menu.buildFromTemplate(template));
}

function clampZoomLevel(level) {
  return Math.min(MAX_ZOOM_LEVEL, Math.max(MIN_ZOOM_LEVEL, level));
}

// Electron shows no context menu of its own, so without this a text field underlines a misspelling and
// offers no way to fix it. Only editable fields get one: the canvas, Monaco and the drawing layer handle
// their own right-clicks (they preventDefault, and then this event never fires).
function installEditableContextMenu(webContents) {
  webContents.on("context-menu", (_event, params) => {
    if (!params.isEditable) return;
    const template = [];
    const word = params.misspelledWord;
    if (word) {
      const guesses = (params.dictionarySuggestions || []).slice(0, 6);
      if (guesses.length === 0) template.push({ label: "No Guesses Found", enabled: false });
      guesses.forEach((guess) => template.push({ label: guess, click: () => webContents.replaceMisspelling(guess) }));
      template.push(
        { type: "separator" },
        { label: `Learn Spelling of "${word}"`, click: () => webContents.session.addWordToSpellCheckerDictionary(word) },
        { type: "separator" },
      );
    }
    const flags = params.editFlags || {};
    template.push(
      { role: "cut", enabled: !!flags.canCut },
      { role: "copy", enabled: !!flags.canCopy },
      { role: "paste", enabled: !!flags.canPaste },
      { type: "separator" },
      { role: "selectAll", enabled: !!flags.canSelectAll },
    );
    Menu.buildFromTemplate(template).popup();
  });
}

function installPageShortcuts(webContents) {
  if (process.platform === "darwin") return;
  webContents.on("before-input-event", (event, input) => {
    if (input.type !== "keyDown") return;
    if (!(input.control || input.meta)) return;

    const key = String(input.key || "");
    const code = String(input.code || "");
    const isReload = key.toLowerCase() === "r" || code === "KeyR";

    if (!input.alt && isReload) {
      event.preventDefault();
      if (input.shift) {
        webContents.reloadIgnoringCache();
      } else {
        webContents.reload();
      }
      return;
    }

    if (input.alt || input.shift) return;

    const isZoomIn =
      key === "+" ||
      key === "=" ||
      code === "Equal" ||
      code === "NumpadAdd";
    const isZoomOut =
      key === "-" ||
      code === "Minus" ||
      code === "NumpadSubtract";
    const isReset =
      key === "0" ||
      code === "Digit0" ||
      code === "Numpad0";

    if (!isZoomIn && !isZoomOut && !isReset) return;
    event.preventDefault();

    if (isReset) {
      webContents.setZoomLevel(0);
      return;
    }

    const delta = isZoomIn ? ZOOM_LEVEL_STEP : -ZOOM_LEVEL_STEP;
    webContents.setZoomLevel(
      clampZoomLevel(webContents.getZoomLevel() + delta),
    );
  });
}

function installDockIcon() {
  if (process.platform !== "darwin") return;
  if (!app.dock || typeof app.dock.setIcon !== "function") return;
  if (!fs.existsSync(APP_ICON_1024)) {
    console.warn(
      `[electron] Dock icon not found at ${APP_ICON_1024}. Add a 1024x1024 PNG there.`,
    );
    return;
  }

  app.dock.setIcon(APP_ICON_1024);
}

// The docs/backdoor capture Electron (afield.tools.docs.ElectronCaptureBrowser) sets
// FIELD_ELECTRON_CAPTURE_SIZE=WxH: the page area is opened at exactly that size, so a capture of that
// size needs no viewport emulation (which leaves the rest of a larger window black and flickering),
// and the window is shown without taking focus from whatever the person is doing.
const CAPTURE_SIZE = (() => {
  const m = /^(\d+)x(\d+)$/.exec(process.env.FIELD_ELECTRON_CAPTURE_SIZE || "");
  return m ? { width: Number(m[1]), height: Number(m[2]) } : null;
})();

function initialWindowBounds() {
  const { x, y, width, height } = screen.getPrimaryDisplay().workArea;
  if (CAPTURE_SIZE) {
    const w = CAPTURE_SIZE.width;
    const h = CAPTURE_SIZE.height + TAB_STRIP_HEIGHT;
    return { x: x + Math.max(0, width - w), y: y + Math.max(0, height - h), width: w, height: h, useContentSize: true };
  }
  const windowWidth = Math.floor(width * 0.8);
  const windowHeight = Math.floor(height * 0.8);

  return {
    x: x + Math.floor((width - windowWidth) / 2),
    y: y + Math.floor((height - windowHeight) / 2),
    width: windowWidth,
    height: windowHeight,
  };
}

function presentWindow(win) {
  if (!win || win.isDestroyed()) return;
  if (win.isMinimized()) win.restore();

  if (CAPTURE_SIZE) {
    win.showInactive();
    return;
  }

  win.show();

  if (process.platform === "darwin") {
    app.focus({ steal: true });
  } else if (typeof win.moveTop === "function") {
    win.moveTop();
  }

  win.focus();
}

function presentWindowOnStartup(win) {
  const present = () => presentWindow(win);
  setImmediate(present);
  setTimeout(present, 250);
}

function createMainWindow() {
  const initialUrl = normalizeUrl(
    process.env.APP_URL || "https://localhost:9092/welcome",
  );
  const welcomeUrl = normalizeUrl("/welcome", initialUrl);
  const windowBounds = initialWindowBounds();
  const win = new BrowserWindow({
    ...windowBounds,
    title: titleForUrl(initialUrl),
    icon: appIconPath(),
    backgroundColor: SHELL_BACKGROUND,
    autoHideMenuBar: true,
    titleBarStyle: "hidden",
    titleBarOverlay: {
      color: TAB_STRIP_BACKGROUND,
      symbolColor: "#eeeeee",
      height: TAB_STRIP_HEIGHT,
    },
    trafficLightPosition:
      process.platform === "darwin" ? { x: 16, y: 14 } : undefined,
  });
  diagnosticLog("window-created", {
    windowId: win.id,
    initialUrl,
    bounds: windowBounds,
  });

  if (process.platform !== "darwin") {
    win.removeMenu();
  }

  // The window's own webContents is never used (every page is a WebContentsView), so it never gets
  // a renderer, and a CDP client that auto-attaches to it waits forever: Playwright's
  // connectOverCDP hangs on its Page.enable. The docs capture path (afield.tools.docs.ElectronCaptureBrowser)
  // attaches over CDP, so when remote debugging is on, give it a page that answers.
  if (app.commandLine.hasSwitch("remote-debugging-port")) {
    win.webContents.loadURL("about:blank");
  }

  const tabStripView = new WebContentsView({
    webPreferences: {
      preload: TAB_STRIP_PRELOAD,
      contextIsolation: true,
      sandbox: false,
    },
  });
  setViewBackground(tabStripView);
  const tabStripUrl = `data:text/html;charset=UTF-8,${encodeURIComponent(createTabStripMarkup())}`;
  win.contentView.addChildView(tabStripView);
  tabStripView.webContents.loadURL(tabStripUrl, {
    baseURLForDataURL: TAB_STRIP_ASSET_BASE_URL,
  });

  const tabRevealView = new WebContentsView({
    webPreferences: {
      preload: TAB_STRIP_PRELOAD,
      contextIsolation: true,
      sandbox: false,
    },
  });
  setViewBackground(tabRevealView);
  const tabRevealUrl = `data:text/html;charset=UTF-8,${encodeURIComponent(createTabRevealMarkup())}`;
  win.contentView.addChildView(tabRevealView);
  tabRevealView.webContents.loadURL(tabRevealUrl, {
    baseURLForDataURL: TAB_STRIP_ASSET_BASE_URL,
  });

  let nextTabId = 0;
  let nextRemoteSessionId = 0;
  const tabs = [];
  const remoteSessions = new Map();
  let activeTabId = null;
  let tabStripRevealedInFullScreen = false;

  const currentBounds = () => {
    const [width, height] = win.getContentSize();
    return { width, height };
  };

  // A tab's WebContents can go away underneath us — a destroyed view, a renderer that
  // was torn down out of band — while the tab entry is still in `tabs`. Reading through
  // `tab.view.webContents` unguarded then throws, and an uncaught main-process throw
  // freezes the whole browser process behind a modal (see the uncaughtException handler
  // above). Every access goes through these two helpers instead.
  const liveContents = (tab) => {
    const contents = tab?.view?.webContents;
    if (!contents) return null;
    try {
      return contents.isDestroyed() ? null : contents;
    } catch (_) {
      return null;
    }
  };

  const contentsId = (tab) => liveContents(tab)?.id ?? null;

  const contentsUrl = (tab) => {
    const contents = liveContents(tab);
    if (!contents) return tab?.url || "";
    try {
      return contents.getURL() || tab.url;
    } catch (_) {
      return tab.url;
    }
  };

  // Drop tabs whose contents died without going through closeTab, so they can't be
  // handed back by activeTab()/existingTabForDocument() and re-thrown on next touch.
  const reapDeadTabs = () => {
    for (let i = tabs.length - 1; i >= 0; i -= 1) {
      if (liveContents(tabs[i])) continue;
      const [dead] = tabs.splice(i, 1);
      diagnosticLog("tab-reaped-dead-contents", {
        windowId: win.id,
        tabId: dead.id,
        url: dead.url,
      });
      if (activeTabId === dead.id) activeTabId = null;
    }
  };

  const activeTab = () => tabs.find((tab) => tab.id === activeTabId) || null;

  const existingTabForDocument = (targetUrl, remoteOrigin, exceptTabId = null) => {
    const key = documentKeyFromUrl(targetUrl);
    if (!key) return null;
    reapDeadTabs();
    return (
      tabs.find(
        (tab) => tab.id !== exceptTabId && tab.remoteOrigin === remoteOrigin && documentKeyFromUrl(tab.url) === key,
      ) || null
    );
  };

  const tabTitle = (url, remoteOrigin) => {
    const title = filenameFromUrl(url);
    return remoteOrigin ? `${title} · ${new URL(remoteOrigin).host}` : title;
  };

  const remoteSession = (origin) => {
    if (!remoteSessions.has(origin)) {
      const isolatedSession = session.fromPartition(`field-remote:${win.id}:${++nextRemoteSessionId}`);
      configureRemoteSession(isolatedSession, origin, async (fingerprint) => {
        if (win.isDestroyed() || !tabs.some(tab => tab.remoteOrigin === origin && tab.connecting)) return false;
        const choice = await dialog.showMessageBox(win, {
          type: "warning",
          title: "Remote Field certificate",
          message: `Trust the self-signed certificate for ${origin}?`,
          detail: `Compare this SHA-256 fingerprint with the certificate on the Field computer.\n\n${fingerprint}`,
          buttons: ["Cancel", "Trust for This Session"],
          defaultId: 0,
          cancelId: 0,
        });
        if (choice.response === 1) return true;
        // Chromium caches certificate decisions. A fresh partition lets the user try again.
        remoteSessions.delete(origin);
        return false;
      });
      remoteSessions.set(origin, isolatedSession);
    }
    return remoteSessions.get(origin);
  };

  // A page tab (Documentation, Welcome, history, logs, ...) gets find-in-page in the tab strip, and
  // all but Welcome (whose links open new tabs) get back/forward. A document tab gets no back/forward
  // ("back" would leave the document), and its find bar searches boxes, not the page: the page decides
  // when Cmd+F means that (Monaco keeps its own Find) and runs the search itself (documentSearch.kt),
  // relayed over "field4-canvas-find" and answered on "field4-canvas-find-result".
  const isPageTab = (tab) => !!liveContents(tab) && isUtilityUrl(contentsUrl(tab));
  const isDocumentTab = (tab) => !!liveContents(tab) && !isUtilityUrl(contentsUrl(tab));
  const isFindTab = (tab) => isPageTab(tab) || isDocumentTab(tab);
  const isNavigableTab = (tab) => isPageTab(tab) && !isWelcomeUrl(contentsUrl(tab));

  const historyFlags = (tab) => {
    if (!isNavigableTab(tab)) return { canGoBack: false, canGoForward: false };
    try {
      const history = liveContents(tab).navigationHistory;
      return { canGoBack: history.canGoBack(), canGoForward: history.canGoForward() };
    } catch (_) {
      return { canGoBack: false, canGoForward: false };
    }
  };

  const findState = (tab) => {
    if (!tab.find) {
      tab.find = { open: false, query: "", matches: null, active: 0, detail: "", fresh: true, requestId: null };
    }
    return tab.find;
  };

  const pageToolsState = (tab, focusFind) => {
    const canvas = isDocumentTab(tab);
    if (!isPageTab(tab) && !(canvas && tab.find?.open)) return null;
    const find = findState(tab);
    return {
      tabId: tab.id,
      navigable: isNavigableTab(tab),
      ...historyFlags(tab),
      find: {
        open: find.open,
        query: find.query,
        matches: find.matches,
        active: find.active,
        // Which property the active box match is in ("code", "fragment", ...), shown after the count.
        detail: canvas ? find.detail : "",
        placeholder: canvas ? "Search boxes" : "Find in page",
        focus: !!focusFind,
      },
    };
  };

  const sendTabState = ({ focusFind = false } = {}) => {
    if (tabStripView.webContents.isDestroyed()) return;
    tabStripView.webContents.send("tabs-state", {
      tabs: tabs.map((tab) => ({
        id: tab.id,
        title: tab.title,
        active: tab.id === activeTabId,
        welcome: !tab.remoteOrigin && isWelcomeUrl(tab.url),
        utility: isUtilityUrl(tab.url),
      })),
      activeTabId,
      page: pageToolsState(activeTab(), focusFind),
    });
  };

  const syncWindowTitle = () => {
    const tab = activeTab();
    win.setTitle(tab ? `${APP_NAME} — ${tab.title}` : titleForUrl(welcomeUrl));
  };

  const isTabStripVisible = () =>
    !win.isFullScreen() || tabStripRevealedInFullScreen;

  const ensureChromeOnTop = () => {
    try {
      win.contentView.removeChildView(tabStripView);
    } catch (_) {
      // View is either not attached yet or already on top.
    }
    try {
      win.contentView.removeChildView(tabRevealView);
    } catch (_) {
      // View is either not attached yet or already on top.
    }
    // A hidden strip must be detached, not just shrunk to zero height: a WebContentsView keeps
    // answering the window's drag hit-test with its last `-webkit-app-region: drag` rectangle
    // while it stays attached, so in full screen the top TAB_STRIP_HEIGHT pixels of the canvas
    // went dead. Detaching it unregisters that drag region.
    if (isTabStripVisible()) {
      win.contentView.addChildView(tabStripView);
    }
    win.contentView.addChildView(tabRevealView);
  };

  const applyLayout = () => {
    const { width, height } = currentBounds();
    const stripVisible = isTabStripVisible();
    const tabOffset = stripVisible ? TAB_STRIP_HEIGHT : 0;
    tabStripView.setBounds({
      x: 0,
      y: 0,
      width,
      height: stripVisible ? TAB_STRIP_HEIGHT : 0,
    });
    tabRevealView.setBounds({
      x: 0,
      y: 0,
      width,
      height: stripVisible ? 0 : TAB_STRIP_REVEAL_ZONE_HEIGHT,
    });
    const tab = activeTab();
    if (!tab) return;
    tab.view.setBounds({
      x: 0,
      y: tabOffset,
      width,
      height: Math.max(0, height - tabOffset),
    });
    ensureChromeOnTop();
  };

  const maybeOpenDevTools = (tab) => {
    if (!WITH_DEVTOOLS || !tab || tab.devtoolsOpened) return;
    const contents = liveContents(tab);
    if (!contents) return;
    contents.openDevTools({ mode: "detach" });
    tab.devtoolsOpened = true;
  };

  const activateTab = (tabId) => {
    reapDeadTabs();
    const next = tabs.find((tab) => tab.id === tabId);
    if (!next) return;

    const previous = activeTab();
    if (previous && previous !== next) {
      try {
        win.contentView.removeChildView(previous.view);
      } catch (_) {
        // The previous view may already be detached.
      }
    }

    activeTabId = next.id;
    diagnosticLog("tab-activated", {
      windowId: win.id,
      previousTabId: previous?.id || null,
      tabId: next.id,
      webContentsId: contentsId(next),
      url: contentsUrl(next),
    });

    try {
      win.contentView.removeChildView(next.view);
    } catch (_) {
      // The next view is not attached yet.
    }
    win.contentView.addChildView(next.view);
    ensureChromeOnTop();
    applyLayout();
    syncWindowTitle();
    sendTabState();
    maybeOpenDevTools(next);
    setImmediate(() => {
      liveContents(next)?.focus();
    });
  };

  const closeTab = (tabId) => {
    const index = tabs.findIndex((tab) => tab.id === tabId);
    if (index === -1) return;
    const tab = tabs[index];
    diagnosticLog("tab-close-requested", {
      windowId: win.id,
      tabId: tab.id,
      webContentsId: contentsId(tab),
      url: contentsUrl(tab),
    });

    if (tabs.length === 1 && !tab.remoteOrigin) {
      if (!isWelcomeUrl(tab.url)) {
        tab.url = welcomeUrl;
        tab.title = filenameFromUrl(welcomeUrl);
        setViewBackground(tab.view, colorForUrl(welcomeUrl));
        liveContents(tab)?.loadURL(welcomeUrl);
      }
      activateTab(tab.id);
      return;
    }

    const wasActive = activeTabId === tabId;
    tabs.splice(index, 1);

    try {
      win.contentView.removeChildView(tab.view);
    } catch (_) {
      // The tab may be inactive.
    }
    liveContents(tab)?.destroy();

    if (!wasActive) {
      sendTabState();
      return;
    }

    const replacement = tabs[index] || tabs[index - 1] || tabs[0];
    if (replacement) {
      activateTab(replacement.id);
    } else {
      openTab(welcomeUrl);
    }
  };

  const openTab = (
    targetUrl,
    { activate = true, baseUrl = initialUrl, remoteOrigin = null } = {},
  ) => {
    const normalizedUrl = normalizeUrl(targetUrl, baseUrl);
    if (remoteOrigin && !isRemoteFieldUrl(normalizedUrl, remoteOrigin)) return null;
    diagnosticLog("tab-open-requested", {
      windowId: win.id,
      targetUrl: String(targetUrl || ""),
      baseUrl,
      normalizedUrl,
      activate,
      activeTabId,
    });
    const existing = existingTabForDocument(normalizedUrl, remoteOrigin) ||
      (remoteOrigin && tabs.find(tab => tab.remoteOrigin === remoteOrigin && tab.url === normalizedUrl && liveContents(tab)));
    if (existing) {
      diagnosticLog("tab-open-reused", {
        windowId: win.id,
        tabId: existing.id,
        webContentsId: contentsId(existing),
        normalizedUrl,
      });
      activateTab(existing.id);
      return existing;
    }

    const tab = {
      id: `tab-${++nextTabId}`,
      url: normalizedUrl,
      title: tabTitle(normalizedUrl, remoteOrigin),
      remoteOrigin,
      connecting: !!remoteOrigin,
      devtoolsOpened: false,
      view: new WebContentsView({
        webPreferences: {
          contextIsolation: true,
          ...(remoteOrigin
            ? { session: remoteSession(remoteOrigin), sandbox: true }
            : { preload: FIELD_PRELOAD, sandbox: false }),
          defaultFontFamily: FIELD_DEFAULT_FONT_FAMILY,
        },
      }),
    };
    diagnosticLog("tab-created", {
      windowId: win.id,
      tabId: tab.id,
      webContentsId: contentsId(tab),
      normalizedUrl,
    });
    setViewBackground(tab.view, colorForUrl(normalizedUrl));
    installPageBackgroundGuard(tab);
    installPageShortcuts(tab.view.webContents);
    installEditableContextMenu(tab.view.webContents);

    const updateTitle = (url = contentsUrl(tab)) => {
      tab.url = url || tab.url;
      tab.title = tabTitle(tab.url, remoteOrigin);
      setViewBackground(tab.view, colorForUrl(tab.url));
      if (tab.id === activeTabId) {
        syncWindowTitle();
      }
      sendTabState();
    };

    tab.view.webContents.setWindowOpenHandler(({ url }) => {
      diagnosticLog("tab-window-open", {
        windowId: win.id,
        tabId: tab.id,
        webContentsId: contentsId(tab),
        currentUrl: contentsUrl(tab),
        targetUrl: url,
      });
      openTab(url, { activate: true, baseUrl: tab.url, remoteOrigin });
      return { action: "deny" };
    });

    let blockedRemoteRedirect = false;
    tab.view.webContents.on("will-redirect", (event) => {
      if (remoteOrigin && event.isMainFrame && !isRemoteFieldUrl(event.url, remoteOrigin)) {
        blockedRemoteRedirect = tab.connecting;
        event.preventDefault();
      }
    });

    tab.view.webContents.on("will-navigate", (event) => {
      const currentUrl = contentsUrl(tab);
      const target = normalizeUrl(event.url, currentUrl || initialUrl);
      if (remoteOrigin && !isRemoteFieldUrl(target, remoteOrigin)) {
        event.preventDefault();
        return;
      }
      const existing = existingTabForDocument(target, remoteOrigin, tab.id);
      if (existing) {
        diagnosticLog("tab-will-navigate", {
          windowId: win.id,
          tabId: tab.id,
          webContentsId: contentsId(tab),
          currentUrl,
          targetUrl: target,
          decision: "activate-existing-tab",
          existingTabId: existing.id,
        });
        event.preventDefault();
        activateTab(existing.id);
        return;
      }
      if (isWelcomeUrl(currentUrl) && !isWelcomeUrl(target)) {
        diagnosticLog("tab-will-navigate", {
          windowId: win.id,
          tabId: tab.id,
          webContentsId: contentsId(tab),
          currentUrl,
          targetUrl: target,
          decision: "open-new-tab",
        });
        event.preventDefault();
        openTab(target, { activate: true, baseUrl: currentUrl || initialUrl, remoteOrigin });
        return;
      }
      diagnosticLog("tab-will-navigate", {
        windowId: win.id,
        tabId: tab.id,
        webContentsId: contentsId(tab),
        currentUrl,
        targetUrl: target,
        decision: "allow",
      });
      setViewBackground(tab.view, colorForUrl(target));
    });

    const tabDetails = (details = {}) => ({
      windowId: win.id,
      tabId: tab.id,
      webContentsId: contentsId(tab),
      active: tab.id === activeTabId,
      url: contentsUrl(tab),
      ...details,
    });

    tab.view.webContents.on("did-start-loading", () => {
      diagnosticLog("tab-did-start-loading", tabDetails());
    });
    tab.view.webContents.on("did-start-navigation", (details) => {
      diagnosticLog(
        "tab-did-start-navigation",
        tabDetails({
          targetUrl: details.url,
          isMainFrame: details.isMainFrame,
          isSameDocument: details.isSameDocument,
        }),
      );
    });
    tab.view.webContents.on("did-redirect-navigation", (details) => {
      diagnosticLog(
        "tab-did-redirect-navigation",
        tabDetails({
          targetUrl: details.url,
          isMainFrame: details.isMainFrame,
          isSameDocument: details.isSameDocument,
        }),
      );
    });

    tab.view.webContents.on("did-navigate", (_event, url) => {
      tab.connecting = false;
      diagnosticLog("tab-did-navigate", tabDetails({ targetUrl: url }));
      // A new page: the find bar stays open with its query, but the old counts no longer apply.
      // A reloaded document has lost its box highlights, so its box search closes.
      if (tab.find) {
        tab.find.matches = null;
        tab.find.active = 0;
        tab.find.detail = "";
        tab.find.fresh = true;
        if (isDocumentTab(tab)) tab.find.open = false;
        if (tab.id === activeTabId) sendTabState();
      }
      updateTitle(url);
    });
    tab.view.webContents.on("found-in-page", (_event, result) => {
      const find = tab.find;
      if (!find || !find.query || result.requestId !== find.requestId) return;
      if (Number.isFinite(result.matches)) find.matches = result.matches;
      if (Number.isFinite(result.activeMatchOrdinal)) find.active = result.activeMatchOrdinal;
      if (tab.id === activeTabId) sendTabState();
    });
    tab.view.webContents.on("before-input-event", (event, input) => {
      if (input.type !== "keyDown" || input.key !== "Escape" || !tab.find?.open) return;
      if (input.meta || input.control || input.alt || input.shift) return;
      event.preventDefault();
      closeFind(tab);
    });
    tab.view.webContents.on("did-navigate-in-page", (_event, url) => {
      diagnosticLog("tab-did-navigate-in-page", tabDetails({ targetUrl: url }));
      updateTitle(url);
    });
    tab.view.webContents.on("dom-ready", () => {
      diagnosticLog("tab-dom-ready", tabDetails());
    });
    tab.view.webContents.on("did-finish-load", () => {
      diagnosticLog("tab-did-finish-load", tabDetails());
      updateTitle();
      if (tab.id === activeTabId) {
        maybeOpenDevTools(tab);
      }
    });
    tab.view.webContents.on("did-stop-loading", () => {
      diagnosticLog("tab-did-stop-loading", tabDetails());
    });
    tab.view.webContents.on(
      "did-fail-load",
      (_event, errorCode, errorDescription, validatedURL, isMainFrame) => {
        diagnosticLog(
          "tab-did-fail-load",
          tabDetails({
            errorCode,
            errorDescription,
            validatedURL,
            isMainFrame,
          }),
        );
      },
    );
    tab.view.webContents.on("preload-error", (_event, preloadPath, error) => {
      diagnosticLog(
        "tab-preload-error",
        tabDetails({ preloadPath, error: diagnosticError(error) }),
      );
    });
    tab.view.webContents.on("unresponsive", () => {
      diagnosticLog("tab-unresponsive", tabDetails());
      tab.view.webContents
        .getProcessMemoryInfo()
        .then((memory) =>
          diagnosticLog("tab-unresponsive-memory", tabDetails({ memory })),
        )
        .catch((error) =>
          diagnosticLog(
            "tab-unresponsive-memory-error",
            tabDetails({ error: diagnosticError(error) }),
          ),
        );
    });
    tab.view.webContents.on("responsive", () => {
      diagnosticLog("tab-responsive", tabDetails());
    });
    tab.view.webContents.on("render-process-gone", (_event, details) => {
      diagnosticLog("tab-render-process-gone", tabDetails({ ...details }));
    });
    tab.view.webContents.on(
      "console-message",
      (details) => {
        const item = {
          level: details.level,
          message: details.message,
          line: details.lineNumber,
          sourceId: details.sourceId,
        };
        if (
          item.level === "warning" ||
          item.level === "error" ||
          item.level === 2 ||
          item.level === 3
        ) {
          diagnosticLog("tab-console-message", tabDetails(item));
        }
      },
    );

    tabs.push(tab);
    if (activate) {
      activateTab(tab.id);
    } else {
      sendTabState();
    }
    diagnosticLog("tab-load-url-called", tabDetails({ targetUrl: normalizedUrl }));
    tab.view.webContents.loadURL(normalizedUrl).then(
      () => diagnosticLog("tab-load-url-resolved", tabDetails()),
      (error) => {
        diagnosticLog(
          "tab-load-url-rejected",
          tabDetails({ error: diagnosticError(error) }),
        );
        tab.connecting = false;
        const contents = liveContents(tab);
        if (!remoteOrigin || !contents || win.isDestroyed()) return;
        if (error.code === "ERR_ABORTED" && !blockedRemoteRedirect) return;
        const certificateCanceled = contents.session !== remoteSessions.get(remoteOrigin);
        closeTab(tab.id);
        if (certificateCanceled) return;
        dialog.showMessageBox(win, {
          type: "error",
          title: "Remote Field connection failed",
          message: `Could not open ${remoteOrigin}`,
          detail: blockedRemoteRedirect
            ? "The server redirected to a different origin. Enter its final Field URL."
            : String(error.code || error.message),
        }).catch(() => {});
      },
    );
    return tab;
  };

  const onRendererDiagnostic = (event, payload) => {
    const tab = tabs.find((candidate) => candidate.view.webContents === event.sender);
    if (!tab) return;
    diagnosticLog("renderer-diagnostic", {
      windowId: win.id,
      tabId: tab.id,
      webContentsId: event.sender.id,
      active: tab.id === activeTabId,
      url: event.sender.getURL() || tab.url,
      payload,
    });
  };
  ipcMain.on("field4-renderer-diagnostic", onRendererDiagnostic);

  const onTabIdentity = (event) => {
    const tab = tabs.find((candidate) => liveContents(candidate) === event.sender);
    if (!tab) return null;
    return {
      windowId: win.id,
      tabId: tab.id,
      webContentsId: event.sender.id,
      active: tab.id === activeTabId,
      title: tab.title,
      url: contentsUrl(tab),
      document: (() => {
        try { return new URL(contentsUrl(tab)).searchParams.get("file"); }
        catch (_) { return null; }
      })(),
      utility: isUtilityUrl(contentsUrl(tab)),
      diagnosticLogPath,
    };
  };
  ipcMain.handle("field4-tab-identity", onTabIdentity);

  const onlyFromStrip =
    (handler) =>
    (event, ...args) => {
      if (event.sender !== tabStripView.webContents) return;
      handler(...args);
    };
  const onlyFromChrome =
    (handler) =>
    (event, ...args) => {
      if (
        event.sender !== tabStripView.webContents &&
        event.sender !== tabRevealView.webContents
      )
        return;
      handler(...args);
    };

  const revealTabs = () => {
    if (!win.isFullScreen()) return;
    if (tabStripRevealedInFullScreen) return;
    tabStripRevealedInFullScreen = true;
    applyLayout();
  };

  const hideTabs = () => {
    if (!win.isFullScreen()) return;
    if (!tabStripRevealedInFullScreen) return;
    // In full screen the strip hides when the pointer leaves it; an open find bar keeps it shown.
    if (activeTab()?.find?.open) return;
    tabStripRevealedInFullScreen = false;
    applyLayout();
  };

  const navigateTab = (tab, direction) => {
    const { canGoBack, canGoForward } = historyFlags(tab);
    const contents = liveContents(tab);
    if (!contents) return;
    diagnosticLog("tab-history-navigate", { windowId: win.id, tabId: tab.id, direction, canGoBack, canGoForward });
    if (direction < 0 && canGoBack) contents.navigationHistory.goBack();
    else if (direction > 0 && canGoForward) contents.navigationHistory.goForward();
  };

  // Find in page: Electron's findInPage, driven from the find bar in the tab strip. `fresh` starts a
  // new find session (after the query or the page changed); a follow-up request steps to the next
  // or previous match. Counts come back through the tab's "found-in-page" event.
  //
  // In a document tab the same bar searches boxes instead: a fresh request sends the query to the
  // page and a follow-up steps through its matches; the page answers on "field4-canvas-find-result".
  const runFind = (tab, forward = true) => {
    const contents = liveContents(tab);
    if (!contents || !isFindTab(tab)) return;
    const find = findState(tab);
    if (isDocumentTab(tab)) {
      if (!find.query) {
        find.matches = null;
        find.active = 0;
        find.detail = "";
        sendTabState();
      }
      contents.send(
        "field4-canvas-find",
        find.fresh || !find.query ? { action: "query", query: find.query } : { action: "step", forward },
      );
      find.fresh = !find.query;
      return;
    }
    if (!find.query) {
      contents.stopFindInPage("clearSelection");
      find.matches = null;
      find.active = 0;
      find.fresh = true;
      sendTabState();
      return;
    }
    find.requestId = contents.findInPage(find.query, { forward, findNext: find.fresh });
    find.fresh = false;
  };

  const openFind = (tab) => {
    if (!isFindTab(tab) || tab.id !== activeTabId) return;
    const find = findState(tab);
    find.open = true;
    revealTabs();
    tabStripView.webContents.focus();
    sendTabState({ focusFind: true });
    if (find.query) {
      find.fresh = true;
      runFind(tab, true);
    }
  };

  const stepFind = (tab, forward) => {
    if (!isFindTab(tab)) return;
    const find = findState(tab);
    if (!find.query) {
      openFind(tab);
      return;
    }
    if (!find.open) {
      find.open = true;
      sendTabState();
    }
    runFind(tab, forward);
  };

  const setFindQuery = (tab, text) => {
    if (!isFindTab(tab)) return;
    const find = findState(tab);
    find.query = String(text || "").slice(0, 1000);
    find.fresh = true;
    runFind(tab, true);
  };

  const closeFind = (tab) => {
    const contents = liveContents(tab);
    if (!contents || !tab.find?.open) return;
    const find = tab.find;
    find.open = false;
    find.matches = null;
    find.active = 0;
    find.detail = "";
    find.fresh = true;
    if (isDocumentTab(tab)) {
      contents.send("field4-canvas-find", { action: "close" });
    } else {
      // Keep the active match selected, as a browser does, so Escape leaves you where you found it.
      contents.stopFindInPage("keepSelection");
    }
    sendTabState();
    if (tab.id === activeTabId) contents.focus();
  };

  const setCanvasFindResult = (tab, result) => {
    const find = tab.find;
    if (!find?.open || !result || typeof result !== "object") return;
    const matches = Number(result.matches);
    const active = Number(result.active);
    find.matches = Number.isFinite(matches) && matches >= 0 ? Math.floor(matches) : null;
    find.active = Number.isFinite(active) && active > 0 ? Math.floor(active) : 0;
    find.detail = typeof result.detail === "string" ? result.detail.slice(0, 80) : "";
    if (tab.id === activeTabId) sendTabState();
  };

  const onTabsReady = onlyFromStrip(() => sendTabState());
  const onTabActivate = onlyFromStrip((tabId) => activateTab(tabId));
  const onTabClose = onlyFromStrip((tabId) => closeTab(tabId));
  const onOpenWelcomeTab = onlyFromStrip(() => {
    const existing = tabs.find((tab) => !tab.remoteOrigin && isWelcomeUrl(tab.url));
    if (existing) {
      activateTab(existing.id);
      return;
    }
    openTab(welcomeUrl, { activate: true });
  });
  const onOpenRemoteTab = (event, address) => {
    if (event.sender !== tabStripView.webContents) return;
    let url;
    try {
      url = remoteFieldUrl(address);
    } catch (_) {
      return "Enter an HTTP or HTTPS Field URL without credentials.";
    }
    if (url.pathname === "/" && !url.search && !url.hash) url.pathname = "/welcome";
    openTab(url.href, { remoteOrigin: url.origin });
  };
  let discoveryRequest = null;
  const onDiscoverRemoteFields = (event) => {
    if (event.sender !== tabStripView.webContents) return [];
    discoveryRequest ??= discoverRemoteFields(initialUrl, (url, options) => session.defaultSession.fetch(url, options))
      .finally(() => { discoveryRequest = null; });
    return discoveryRequest;
  };
  const onRevealTabs = onlyFromChrome(() => revealTabs());
  const onHideTabs = onlyFromChrome(() => hideTabs());
  const afterStripCommand = (tab) => {
    // A click in the strip focuses the strip; hand the keyboard back to the page.
    if (tab && tab.id === activeTabId && !tab.find?.open) liveContents(tab)?.focus();
  };
  const onStripNavigate = onlyFromStrip((direction) => {
    const tab = activeTab();
    navigateTab(tab, Math.sign(Number(direction) || 0));
    afterStripCommand(tab);
  });
  const onStripFindQuery = onlyFromStrip((text) => setFindQuery(activeTab(), text));
  const onStripFindStep = onlyFromStrip((forward) => stepFind(activeTab(), !!forward));
  const onStripFindClose = onlyFromStrip(() => closeFind(activeTab()));
  // The same commands from a page's own keys and mouse buttons (field-preload.js).
  const fromPageTab =
    (handler) =>
    (event, ...args) => {
      const tab = tabs.find((candidate) => liveContents(candidate) === event.sender);
      if (!tab || tab.id !== activeTabId || !isPageTab(tab)) return;
      handler(tab, ...args);
    };
  const onPageNavigate = fromPageTab((tab, direction) => navigateTab(tab, Math.sign(Number(direction) || 0)));
  const onPageFindOpen = fromPageTab((tab) => openFind(tab));
  const onPageFindStep = fromPageTab((tab, forward) => stepFind(tab, !!forward));
  // A document tab opens its box search, and reports its matches, from documentSearch.kt.
  const fromDocumentTab =
    (handler) =>
    (event, ...args) => {
      const tab = tabs.find((candidate) => liveContents(candidate) === event.sender);
      if (!tab || !isDocumentTab(tab)) return;
      handler(tab, ...args);
    };
  const onCanvasFindOpen = fromDocumentTab((tab) => openFind(tab));
  const onCanvasFindResult = fromDocumentTab((tab, result) => setCanvasFindResult(tab, result));
  // capturePage() hands back *device* pixels, so on a 2x display a 1644x988 window arrives as a
  // 3288x1976 image. Every step after it -- resize, encode, base64 -- is synchronous on the
  // browser/main thread, which is also the only thread that routes input to the renderers: a
  // millisecond spent here is a millisecond of keystroke latency in every open document (see the
  // canvas-capture note in Main.kt, where this showed up as a periodic Monaco typing hiccup).
  //
  // History displays these as 132x78 thumbnails and a viewport-sized preview, so logical
  // resolution and JPEG are ample. Full-res PNG cost ~540ms and a 2.3MB data URL per shot.
  const screenshotPayload = (image) => {
    if (!image || image.isEmpty()) return null;
    const size = image.getSize();
    if (!size.width || !size.height) return null;

    const logicalWidth = Math.round(size.width / displayScaleFactor(win));
    const targetWidth = Math.max(1, Math.min(HISTORY_SHOT_MAX_WIDTH, logicalWidth));

    const resizeStart = performance.now();
    const scaled =
      targetWidth < size.width
        ? image.resize({ width: targetWidth, quality: HISTORY_SHOT_RESIZE_QUALITY })
        : image;
    const encodeStart = performance.now();
    const jpeg = scaled.toJPEG(HISTORY_SHOT_JPEG_QUALITY);
    if (!jpeg || jpeg.length < 8) return null;
    const base64Start = performance.now();
    const base64 = jpeg.toString("base64");
    const encodedAt = performance.now();

    const scaledSize = scaled.getSize();
    return {
      dataUrl: `data:image/jpeg;base64,${base64}`,
      width: scaledSize.width,
      height: scaledSize.height,
      timings: {
        sourceWidth: size.width,
        outWidth: scaledSize.width,
        bytes: jpeg.length,
        resizeMs: +(encodeStart - resizeStart).toFixed(1),
        encodeMs: +(base64Start - encodeStart).toFixed(1),
        base64Ms: +(encodedAt - base64Start).toFixed(1),
      },
    };
  };
  const captureScreenshotPayload = async (capture) => {
    try {
      const startedAt = performance.now();
      const image = await capture();
      const capturedAt = performance.now();
      const payload = screenshotPayload(image);
      if (payload) {
        payload.timings.captureMs = +(capturedAt - startedAt).toFixed(1);
        payload.timings.mainThreadMs = +(performance.now() - startedAt).toFixed(1);
      }
      return payload;
    } catch (_) {
      return null;
    }
  };
  const onHistoryCaptureWindow = async (event) => {
    const tab = activeTab();
    const contents = liveContents(tab);
    if (!contents || event.sender !== contents) return null;
    const payload =
      (await captureScreenshotPayload(() => contents.capturePage())) ||
      (await captureScreenshotPayload(() => win.capturePage()));
    if (payload) diagnosticLog("history-screenshot", payload.timings);
    return payload;
  };

  // This command reaches the user's live tab. The renderer first resolves the box and output
  // channel to one iframe and gives it a unique name; Electron resolves that name again in its
  // frame tree. No independently launched capture page or remote debugging port is involved.
  const onLiveOutputIframe = async (event, request) => {
    const tab = activeTab();
    const contents = liveContents(tab);
    if (!contents || event.sender !== contents ||
        event.senderFrame?.routingId !== contents.mainFrame.routingId ||
        event.senderFrame?.processId !== contents.mainFrame.processId) {
      throw new Error("The requesting Field tab is no longer active");
    }
    if (!request || typeof request.id !== "string" ||
        request.frameName !== `field-live-iframe-${request.id}`) {
      throw new Error("Invalid live iframe request");
    }
    const candidates = contents.mainFrame.framesInSubtree.filter(frame =>
      !frame.isDestroyed() && frame !== contents.mainFrame);
    let frames = candidates.filter(frame => frame.name === request.frameName);
    // WebFrameMain.name can retain the navigation-time name after an iframe is renamed. Ask each
    // child in its own context; this also works when that child is on another localhost origin.
    if (frames.length === 0) {
      const names = await Promise.all(candidates.map(async frame => {
        try { return await frame.executeJavaScript("window.name"); }
        catch (_) { return null; }
      }));
      frames = candidates.filter((_, index) => names[index] === request.frameName);
    }
    // A unique URL is useful for ordinary already-loaded frames whose page resets window.name.
    // If two frames share it, refuse to guess which one the box/channel phrase meant.
    if (frames.length === 0 && typeof request.frameUrl === "string") {
      frames = candidates.filter(frame => frame.url === request.frameUrl);
    }
    if (frames.length !== 1) {
      throw new Error(`Could not identify one live iframe for ${request.frameName}; found ${frames.length}`);
    }
    const frame = frames[0];
    const timeoutMillis = Math.max(1, Math.min(Number(request.timeoutMillis) || 10000, 120000));
    const work = async () => {
      if (request.operation === "evaluate") {
        const javascript = String(request.javascript || "");
        const source = JSON.stringify(javascript);
        try {
          const result = await frame.executeJavaScript(
            `Promise.resolve((0, eval)(${source})).then(value => JSON.stringify({ value }))`);
          return { value: JSON.parse(result).value, frameUrl: frame.url };
        } catch (error) {
          // A page whose Content-Security-Policy forbids eval (GitHub's does) refuses the wrapper.
          // executeJavaScript itself is not bound by the page's policy and returns the script's
          // last value, so run the script as it is; the value comes back by structured clone.
          if (!/EvalError|unsafe-eval/.test(String(error?.message || error))) throw error;
          const value = await frame.executeJavaScript(javascript);
          return { value: value === undefined ? undefined : JSON.parse(JSON.stringify(value)), frameUrl: frame.url };
        }
      }
      if (request.operation === "screenshot") {
        const rect = request.captureRect;
        if (!rect || ![rect.x, rect.y, rect.width, rect.height].every(Number.isFinite) ||
            rect.width <= 0 || rect.height <= 0 || rect.width > 8192 || rect.height > 8192) {
          throw new Error("Invalid live iframe screenshot rectangle");
        }
        const image = await contents.capturePage({
          x: Math.floor(rect.x), y: Math.floor(rect.y),
          width: Math.ceil(rect.width), height: Math.ceil(rect.height),
        });
        if (!image || image.isEmpty()) throw new Error("Electron captured no visible iframe pixels");
        const size = image.getSize();
        const scale = Math.min(1, 1600 / Math.max(size.width, size.height));
        const bounded = scale < 1
          ? image.resize({ width: Math.max(1, Math.round(size.width * scale)), quality: "best" })
          : image;
        const jpeg = bounded.toJPEG(85);
        return { value: `data:image/jpeg;base64,${jpeg.toString("base64")}`, frameUrl: frame.url };
      }
      if (request.operation === "devtools") {
        // DevTools for the tab, with this frame's <iframe> selected: Chromium resolves a point over
        // an out-of-process frame to its owner element. Its #document, and its entry in Console's
        // context menu, are the framed site. Detached, so the Field canvas keeps its size.
        // inspectElement takes the point in the tab's zoomed pixels.
        const rect = request.captureRect;
        if (!rect || ![rect.x, rect.y, rect.width, rect.height].every(Number.isFinite) ||
            rect.width <= 0 || rect.height <= 0) {
          throw new Error("The iframe has no visible area to inspect");
        }
        const zoom = contents.getZoomFactor() || 1;
        const x = Math.round((rect.x + rect.width / 2) * zoom);
        const y = Math.round((rect.y + rect.height / 2) * zoom);
        if (!contents.isDevToolsOpened()) contents.openDevTools({ mode: "detach", activate: true });
        contents.inspectElement(x, y);
        contents.devToolsWebContents?.focus();
        return { value: frame.url, frameUrl: frame.url };
      }
      if (request.operation === "cdp") {
        const debug = contents.debugger;
        if (!debug.isAttached()) debug.attach("1.3");
        const value = await debug.sendCommand(
          String(request.method || ""), request.params || {}, request.sessionId || undefined);
        return { value, frameUrl: frame.url };
      }
      throw new Error(`Unknown live iframe operation '${request.operation}'`);
    };
    let timer;
    try {
      return await Promise.race([
        work(),
        new Promise((_, reject) => {
          timer = setTimeout(() => reject(new Error(`Live iframe command timed out after ${timeoutMillis}ms`)), timeoutMillis);
        }),
      ]);
    } finally {
      clearTimeout(timer);
    }
  };

  ipcMain.on("field4-tabs-ready", onTabsReady);
  ipcMain.on("field4-tabs-activate", onTabActivate);
  ipcMain.on("field4-tabs-close", onTabClose);
  ipcMain.on("field4-tabs-open-welcome", onOpenWelcomeTab);
  ipcMain.handle("field4-tabs-open-remote", onOpenRemoteTab);
  ipcMain.handle("field4-tabs-discover-remote", onDiscoverRemoteFields);
  ipcMain.on("field4-tabs-reveal", onRevealTabs);
  ipcMain.on("field4-tabs-hide", onHideTabs);
  ipcMain.on("field4-tabs-navigate", onStripNavigate);
  ipcMain.on("field4-tabs-find-query", onStripFindQuery);
  ipcMain.on("field4-tabs-find-step", onStripFindStep);
  ipcMain.on("field4-tabs-find-close", onStripFindClose);
  ipcMain.on("field4-tab-navigate", onPageNavigate);
  ipcMain.on("field4-tab-find-open", onPageFindOpen);
  ipcMain.on("field4-tab-find-step", onPageFindStep);
  ipcMain.on("field4-canvas-find-open", onCanvasFindOpen);
  ipcMain.on("field4-canvas-find-result", onCanvasFindResult);
  ipcMain.removeHandler("field4-history-capture-window");
  ipcMain.handle("field4-history-capture-window", onHistoryCaptureWindow);
  ipcMain.removeHandler("field4-live-output-iframe");
  ipcMain.handle("field4-live-output-iframe", onLiveOutputIframe);
  ipcMain.removeHandler("field4-microphone-access");
  ipcMain.handle("field4-microphone-access", onMicrophoneAccess);

  const relayout = () => applyLayout();
  const enterFullScreen = () => {
    tabStripRevealedInFullScreen = false;
    applyLayout();
  };
  const leaveFullScreen = () => {
    tabStripRevealedInFullScreen = false;
    applyLayout();
  };
  ["resize", "maximize", "unmaximize"].forEach((eventName) => {
    win.on(eventName, relayout);
  });
  win.on("enter-full-screen", enterFullScreen);
  win.on("leave-full-screen", leaveFullScreen);

  win.on("closed", () => {
    diagnosticLog("window-closed", { windowId: win.id });
    ipcMain.removeListener("field4-tabs-ready", onTabsReady);
    ipcMain.removeListener("field4-tabs-activate", onTabActivate);
    ipcMain.removeListener("field4-tabs-close", onTabClose);
    ipcMain.removeListener("field4-tabs-open-welcome", onOpenWelcomeTab);
    ipcMain.removeHandler("field4-tabs-open-remote");
    ipcMain.removeHandler("field4-tabs-discover-remote");
    ipcMain.removeListener("field4-tabs-reveal", onRevealTabs);
    ipcMain.removeListener("field4-tabs-hide", onHideTabs);
    ipcMain.removeListener("field4-tabs-navigate", onStripNavigate);
    ipcMain.removeListener("field4-tabs-find-query", onStripFindQuery);
    ipcMain.removeListener("field4-tabs-find-step", onStripFindStep);
    ipcMain.removeListener("field4-tabs-find-close", onStripFindClose);
    ipcMain.removeListener("field4-tab-navigate", onPageNavigate);
    ipcMain.removeListener("field4-tab-find-open", onPageFindOpen);
    ipcMain.removeListener("field4-tab-find-step", onPageFindStep);
    ipcMain.removeListener("field4-canvas-find-open", onCanvasFindOpen);
    ipcMain.removeListener("field4-canvas-find-result", onCanvasFindResult);
    ipcMain.removeHandler("field4-history-capture-window");
    ipcMain.removeHandler("field4-tab-identity");
    ipcMain.removeHandler("field4-kit-folder");
    ipcMain.removeListener("field4-renderer-diagnostic", onRendererDiagnostic);
  });

  const isFieldTab = (tab) => {
    try {
      if (!tab || tab.remoteOrigin) return false;
      const url = new URL(contentsUrl(tab));
      return url.origin === new URL(initialUrl).origin && url.pathname.endsWith('/index.html');
    } catch (_) { return false; }
  };
  const presentationFieldTab = () => (isFieldTab(activeTab()) ? activeTab() : tabs.find(isFieldTab));
  const isFieldServerTab = tab => {
    try { return tab && !tab.remoteOrigin && new URL(contentsUrl(tab)).origin === new URL(initialUrl).origin; }
    catch (_) { return false; }
  };
  const presentationServerTab = () => presentationFieldTab() ||
    (isFieldServerTab(activeTab()) ? activeTab() : tabs.find(isFieldServerTab));
  ipcMain.removeHandler("field4-kit-folder");
  ipcMain.handle("field4-kit-folder", async event => {
    const tab = tabs.find(t => liveContents(t) === event.sender);
    if (!isFieldServerTab(tab) || !isWelcomeUrl(contentsUrl(tab))) throw new Error("Choose kits from Field Welcome");
    const result = await dialog.showOpenDialog(win, {title: "Add a Field kit", properties: ["openDirectory"]});
    return result.canceled ? null : result.filePaths[0];
  });
  const presentationHost = {
    getFieldContents: () => { const tab = presentationFieldTab(); return tab && liveContents(tab); },
    openDocument: name => {
      const tab = presentationServerTab();
      const url = new URL(tab ? contentsUrl(tab) : initialUrl);
      url.pathname = '/index.html'; url.search = new URLSearchParams({file: name}).toString(); url.hash = '';
      return openTab(url.href, {activate: true});
    },
    openPreview: (url) => openTab(url, { activate: true }),
    getFieldUrl: () => {
      const fieldTab = presentationFieldTab();
      return fieldTab ? contentsUrl(fieldTab) : null;
    },
    getFieldInfo: async () => {
      const fieldTab = presentationServerTab();
      const contents = fieldTab && liveContents(fieldTab);
      if (!contents || contents.isDestroyed()) return null;
      return contents.executeJavaScript("fetch('/api/instance-info').then(response => response.ok ? response.json() : null)");
    },
    isFieldContents: (contents) => tabs.some(tab => isFieldTab(tab) && liveContents(tab) === contents),
    enableTypeScript: (paths) => {
      const fieldTab = presentationFieldTab();
      const contents = fieldTab && liveContents(fieldTab);
      if (!contents || contents.isDestroyed()) return;
      const send = () => {
        if (!contents.isDestroyed()) contents.send('field4-presentation-project', paths);
      };
      if (contents.isLoadingMainFrame()) contents.once('did-finish-load', send);
      else send();
    },
    attachSources: (paths) => {
      const fieldTab = presentationFieldTab();
      const contents = fieldTab && liveContents(fieldTab);
      if (!contents || contents.isDestroyed()) return false;
      contents.send('field4-presentation-sources', paths);
      activateTab(fieldTab.id);
      return true;
    },
  };
  require("./presentation").registerFieldHost(win, presentationHost);
  openTab(initialUrl, { activate: true });
  presentWindowOnStartup(win);
  return win;
}

async function logGpuStatus(label) {
  try {
    console.log(`[electron,gpu] ${label} feature status`, app.getGPUFeatureStatus());
  } catch (error) {
    console.warn(`[electron,gpu] ${label} feature status unavailable`, error);
  }

  try {
    console.log(`[electron,gpu] ${label} basic info`, await app.getGPUInfo("basic"));
  } catch (error) {
    console.warn(`[electron,gpu] ${label} basic info unavailable`, error);
  }
}

// A capture Electron (afield.tools.docs.ElectronCaptureBrowser) is owned by the Field JVM that launched it,
// and a JVM that dies without running its shutdown hooks would otherwise leave it running forever.
const ownerPid = Number(process.env.FIELD_ELECTRON_OWNER_PID || 0);
if (ownerPid > 0) {
  setInterval(() => {
    try {
      process.kill(ownerPid, 0);
    } catch (_) {
      app.quit();
    }
  }, 2000).unref();
}

app.whenReady().then(() => {
  initializeDiagnosticLog();
  if (LOG_GPU_STATUS) {
    logChromiumGpuSwitches();
  }
  installDockIcon();
  installAppMenu();
  // The desktop server uses a self-signed development certificate. Remote tabs have their
  // own sessions and use Chromium verification or a certificate the user explicitly trusts.
  const loopbackHosts = new Set(["localhost", "127.0.0.1", "::1"]);
  session.defaultSession.setCertificateVerifyProc(({ hostname }, callback) => {
    callback(loopbackHosts.has(hostname) ? 0 : -3);
  });
  require("./presentation").install();
  require("./rtt-view-host").install();
  require("./aaaframe-sign-in").install();
  // Output-area iframes show sites that refuse framing (X-Frame-Options, CSP frame-ancestors).
  require("./framing-relief.cjs").installFramingRelief(
    session.defaultSession,
    process.env.APP_URL || "https://localhost:9092/welcome",
    diagnosticLog,
  );
  createMainWindow();
  if (process.env.FIELD_PRESENTATION_PROJECT) {
    require("./presentation").openPresentation(process.env.FIELD_PRESENTATION_PROJECT)
      .catch(error => console.error("[presentation]", error));
  }
  if (LOG_GPU_STATUS) {
    setTimeout(() => logGpuStatus("delayed"), 2000);
  }
});

app.on("activate", () => {
  if (BrowserWindow.getAllWindows().length === 0) {
    createMainWindow();
  }
});

app.on("window-all-closed", () => {
  diagnosticLog("desktop-window-all-closed");
  app.quit();
});

app.on("before-quit", () => {
  diagnosticLog("desktop-before-quit");
  diagnosticLogStream?.end();
});
