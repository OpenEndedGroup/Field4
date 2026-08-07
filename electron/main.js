const fs = require("node:fs");
const path = require("node:path");
const { pathToFileURL } = require("node:url");
const {
  app,
  BrowserWindow,
  Menu,
  WebContentsView,
  ipcMain,
  screen,
} = require("electron");

const APP_NAME = "Field4";
const APP_USER_MODEL_ID = "com.field4.desktop";
const APP_ICON_ICO = path.join(__dirname, "Field4.ico");
const APP_ICON_1024 = path.join(__dirname, "icon-1024.png");
const TAB_STRIP_PRELOAD = path.join(__dirname, "tabstrip-preload.js");
const FIELD_PRELOAD = path.join(__dirname, "field-preload.js");
const TAB_STRIP_HEIGHT = 44;
const TAB_STRIP_REVEAL_ZONE_HEIGHT = 6;
const TAB_TRAFFIC_LIGHT_INSET = process.platform === "darwin" ? 88 : 12;
const TAB_WINDOW_CONTROL_INSET = process.platform === "win32" ? 150 : 12;
const SHELL_BACKGROUND = "#000000";
const TAB_STRIP_BACKGROUND = "#2a2b2f";
const WELCOME_BACKGROUND = "rgb(20, 20, 20)";
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
if (process.platform === "win32") {
  app.setAppUserModelId(APP_USER_MODEL_ID);
}
process.title = APP_NAME;
app.commandLine.appendSwitch("ignore-certificate-errors");

function appendChromiumSwitchIfMissing(name, value) {
  if (!app.commandLine.hasSwitch(name)) {
    app.commandLine.appendSwitch(name, value);
  }
}

function installChromiumGpuSwitches() {
  if (process.env.FIELD_ELECTRON_SOFTWARE_GPU === "1") {
    console.log("[electron,gpu] software GPU mode requested by FIELD_ELECTRON_SOFTWARE_GPU=1");
    return;
  }

  if (process.platform !== "linux") return;

  appendChromiumSwitchIfMissing("ignore-gpu-blocklist");
  appendChromiumSwitchIfMissing("disable-gpu-process-crash-limit");
  appendChromiumSwitchIfMissing("enable-gpu-rasterization");
  appendChromiumSwitchIfMissing("enable-zero-copy");
  appendChromiumSwitchIfMissing("ozone-platform-hint", "auto");
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
    if (pathname.endsWith("/welcome")) return "welcome";
    if (pathname.endsWith("/dir")) return "/dir";
    if (pathname.endsWith("/code-use")) return "Code Use";
    if (pathname.endsWith("/index.html")) return "document";
    if (parsed.hostname) return parsed.hostname;
  } catch (_) {
    // Ignore invalid URLs and fall back below.
  }
  return "welcome";
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

function titleForUrl(rawUrl) {
  return `${APP_NAME} — ${filenameFromUrl(rawUrl)}`;
}

function setViewBackground(view, color = SHELL_BACKGROUND) {
  if (!view || typeof view.setBackgroundColor !== "function") return;
  view.setBackgroundColor(color);
}

function colorForUrl(rawUrl) {
  return isWelcomeUrl(rawUrl) ? WELCOME_BACKGROUND : SHELL_BACKGROUND;
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
    if (isWelcomeUrl(webContents.getURL() || tab.url)) return;
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
  </style>
</head>
<body>
  <div id="tabstrip">
    <div id="tabs"></div>
    <button id="tab-add" class="tab-add" type="button" aria-label="Open welcome tab">+</button>
    <div class="tab-spacer"></div>
  </div>
  <script>
    const tabsNode = document.getElementById('tabs');
    const addButton = document.getElementById('tab-add');
    let welcomeOpen = false;

    function render(state) {
      const tabs = Array.isArray(state?.tabs) ? state.tabs : [];
      welcomeOpen = tabs.some((tab) => tab.welcome);
      addButton.hidden = welcomeOpen;
      addButton.disabled = welcomeOpen;
      tabsNode.innerHTML = tabs.map((tab) => {
        const title = String(tab.title || 'welcome')
          .replace(/&/g, '&amp;')
          .replace(/</g, '&lt;')
          .replace(/>/g, '&gt;')
          .replace(/"/g, '&quot;')
          .replace(/'/g, '&#39;');
        const activeClass = tab.active ? ' active' : '';
        return \`
          <button type="button" class="tab\${activeClass}" data-tab-id="\${tab.id}" aria-pressed="\${tab.active ? 'true' : 'false'}">
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
    document.body.addEventListener('mouseenter', () => window.fieldTabs.revealTabs());
    document.body.addEventListener('mouseleave', () => window.fieldTabs.hideTabs());
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
  ];

  Menu.setApplicationMenu(Menu.buildFromTemplate(template));
}

function clampZoomLevel(level) {
  return Math.min(MAX_ZOOM_LEVEL, Math.max(MIN_ZOOM_LEVEL, level));
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

function initialWindowBounds() {
  const { x, y, width, height } = screen.getPrimaryDisplay().workArea;
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
  const tabs = [];
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

  const existingTabForDocument = (targetUrl, exceptTabId = null) => {
    const key = documentKeyFromUrl(targetUrl);
    if (!key) return null;
    reapDeadTabs();
    return (
      tabs.find(
        (tab) => tab.id !== exceptTabId && documentKeyFromUrl(tab.url) === key,
      ) || null
    );
  };

  const sendTabState = () => {
    if (tabStripView.webContents.isDestroyed()) return;
    tabStripView.webContents.send("tabs-state", {
      tabs: tabs.map((tab) => ({
        id: tab.id,
        title: tab.title,
        active: tab.id === activeTabId,
        welcome: isWelcomeUrl(tab.url),
      })),
      activeTabId,
    });
  };

  const syncWindowTitle = () => {
    const tab = activeTab();
    win.setTitle(titleForUrl(tab ? tab.url : welcomeUrl));
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
    if (isTabStripVisible()) {
      win.contentView.addChildView(tabStripView);
      win.contentView.addChildView(tabRevealView);
      return;
    }
    win.contentView.addChildView(tabStripView);
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

    if (tabs.length === 1) {
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
      sendTabState();
      syncWindowTitle();
    }
  };

  const openTab = (
    targetUrl,
    { activate = true, baseUrl = initialUrl } = {},
  ) => {
    const normalizedUrl = normalizeUrl(targetUrl, baseUrl);
    diagnosticLog("tab-open-requested", {
      windowId: win.id,
      targetUrl: String(targetUrl || ""),
      baseUrl,
      normalizedUrl,
      activate,
      activeTabId,
    });
    const existing = existingTabForDocument(normalizedUrl);
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
      title: filenameFromUrl(normalizedUrl),
      devtoolsOpened: false,
      view: new WebContentsView({
        webPreferences: {
          preload: FIELD_PRELOAD,
          contextIsolation: true,
          sandbox: false,
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

    const updateTitle = (url = contentsUrl(tab)) => {
      tab.url = url || tab.url;
      tab.title = filenameFromUrl(tab.url);
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
      openTab(url, { activate: true, baseUrl: tab.url });
      return { action: "deny" };
    });

    tab.view.webContents.on("will-navigate", (event, url) => {
      const currentUrl = contentsUrl(tab);
      const target = normalizeUrl(url, currentUrl || initialUrl);
      const existing = existingTabForDocument(target, tab.id);
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
        openTab(target, { activate: true, baseUrl: currentUrl || initialUrl });
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
      diagnosticLog("tab-did-navigate", tabDetails({ targetUrl: url }));
      updateTitle(url);
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
      (details, level, message, line, sourceId) => {
        const item = details?.message
          ? {
              level: details.level,
              message: details.message,
              line: details.lineNumber,
              sourceId: details.sourceId,
            }
          : { level, message, line, sourceId };
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
      (error) =>
        diagnosticLog(
          "tab-load-url-rejected",
          tabDetails({ error: diagnosticError(error) }),
        ),
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
    tabStripRevealedInFullScreen = false;
    applyLayout();
  };

  const onTabsReady = onlyFromStrip(() => sendTabState());
  const onTabActivate = onlyFromStrip((tabId) => activateTab(tabId));
  const onTabClose = onlyFromStrip((tabId) => closeTab(tabId));
  const onOpenWelcomeTab = onlyFromStrip(() => {
    const existing = tabs.find((tab) => isWelcomeUrl(tab.url));
    if (existing) {
      activateTab(existing.id);
      return;
    }
    openTab(welcomeUrl, { activate: true });
  });
  const onRevealTabs = onlyFromChrome(() => revealTabs());
  const onHideTabs = onlyFromChrome(() => hideTabs());
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

  ipcMain.on("field4-tabs-ready", onTabsReady);
  ipcMain.on("field4-tabs-activate", onTabActivate);
  ipcMain.on("field4-tabs-close", onTabClose);
  ipcMain.on("field4-tabs-open-welcome", onOpenWelcomeTab);
  ipcMain.on("field4-tabs-reveal", onRevealTabs);
  ipcMain.on("field4-tabs-hide", onHideTabs);
  ipcMain.removeHandler("field4-history-capture-window");
  ipcMain.handle("field4-history-capture-window", onHistoryCaptureWindow);

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
    ipcMain.removeListener("field4-tabs-reveal", onRevealTabs);
    ipcMain.removeListener("field4-tabs-hide", onHideTabs);
    ipcMain.removeHandler("field4-history-capture-window");
    ipcMain.removeListener("field4-renderer-diagnostic", onRendererDiagnostic);
  });

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

app.whenReady().then(() => {
  initializeDiagnosticLog();
  if (LOG_GPU_STATUS) {
    logChromiumGpuSwitches();
  }
  installDockIcon();
  installAppMenu();
  createMainWindow();
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
