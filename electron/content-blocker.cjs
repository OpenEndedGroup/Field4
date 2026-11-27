// Ad and tracker blocking for web pages shown in Field, using Ghostery's filter engine
// (@ghostery/adblocker, MPL-2.0). It reads the same filter-list syntax as uBlock Origin
// (EasyList, EasyPrivacy, uBO's own lists) and does three things:
//
// - network blocking: requests that match a filter are cancelled or redirected to a neutered
//   stand-in, as a handler on the session's webRequest pipeline;
// - CSP injection: lists can add Content-Security-Policy directives to a page;
// - cosmetic filtering: element-hiding CSS and scriptlets for each frame's site.
//
// Ghostery's own `enableBlockingInSession` cannot be used. It registers webRequest listeners
// directly (which would replace the pipeline's), and its cosmetic half is a frame preload, which
// Chromium runs in an <iframe> only when the tab sets `nodeIntegrationInSubFrames`: that would also
// run Field's unsandboxed preload inside every framed site. So the network handlers go on the
// pipeline, and cosmetics are injected from the main process when each frame's DOM is ready,
// through an adopted stylesheet, which a page's CSP does not refuse.
//
// Field's own pages and loopback addresses are never filtered: a generic element-hiding rule
// such as `.ad-banner` must not reach Field's UI.
//
// AFIELD_WEB_BLOCKING selects the lists: `ads` (default: ads and trackers), `full` (adds
// annoyances such as cookie banners), or `off`. The compiled engine is cached under userData and
// refreshed from Ghostery's CDN when older than a few days; offline, the cached engine is used.

const fs = require("node:fs");
const path = require("node:path");
const { pipelineFor } = require("./web-request-pipeline.cjs");

const REFRESH_AFTER_MS = 4 * 24 * 3600 * 1000;
const RECENT_LIMIT = 50;
const LOOPBACK = new Set(["localhost", "127.0.0.1", "[::1]", "::1"]);

function parseUrl(url) {
  try { return new URL(String(url)); } catch (_) { return null; }
}

function blockingMode(value = process.env.AFIELD_WEB_BLOCKING) {
  const mode = String(value || "ads").trim().toLowerCase();
  if (["0", "off", "false", "no", "none"].includes(mode)) return "off";
  if (mode === "full") return "full";
  return "ads";
}

// True for addresses the blocker must leave alone: Field itself and anything on this machine.
function isExempt(url, fieldOrigin) {
  const parsed = parseUrl(url);
  if (!parsed) return true;
  if (parsed.protocol !== "http:" && parsed.protocol !== "https:") return true;
  if (fieldOrigin && parsed.origin === fieldOrigin) return true;
  return LOOPBACK.has(parsed.hostname);
}

function safeFrameUrl(frame) {
  try { return frame && !frame.isDestroyed?.() ? frame.url : null; } catch (_) { return null; }
}

// The frame that is "the page" for [frame]: walking up, the last frame before Field's own page
// (or the top). Ads nested inside a framed site count against that site's frame.
function pageFrameOf(frame, fieldOrigin) {
  let current = frame;
  try {
    while (current?.parent) {
      if (isExempt(safeFrameUrl(current.parent), fieldOrigin)) break;
      current = current.parent;
    }
  } catch (_) {}
  return current;
}

function frameKey(frame) {
  if (!frame) return null;
  try {
    if (frame.frameTreeNodeId != null) return `node:${frame.frameTreeNodeId}`;
    return `rfh:${frame.processId}:${frame.routingId}`;
  } catch (_) { return null; }
}

// The script a frame runs to apply cosmetic filters: CSS through an adopted stylesheet (a <style>
// element would be refused by a page whose CSP restricts style-src), once per document.
function cosmeticScript(styles) {
  return `(() => {
    const marker = "__fieldContentBlockerStyles";
    if (window[marker]) return false;
    try {
      const sheet = new CSSStyleSheet();
      sheet.replaceSync(${JSON.stringify(styles)});
      document.adoptedStyleSheets = [...document.adoptedStyleSheets, sheet];
      window[marker] = true;
      return true;
    } catch (error) { return String(error); }
  })()`;
}

class ContentBlocker {
  constructor({ engine, fieldOrigin, allowlistPath, mode, log = () => {} }) {
    this.engine = engine;
    this.fieldOrigin = fieldOrigin;
    this.allowlistPath = allowlistPath;
    this.mode = mode;
    this.log = log;
    this.enabled = !!engine;
    this.allowed = new Set(readAllowlist(allowlistPath));
    this.totalBlocked = 0;
    this.pages = new Map(); // frameKey -> { url, blocked, recent: [{url, type, redirected}] }
  }

  // Whether requests from the page at [url] are filtered: not when its site is allow-listed.
  filtersPage(url) {
    const parsed = parseUrl(url);
    return !(parsed && this.allowed.has(parsed.hostname));
  }

  pageRecord(frame) {
    const page = pageFrameOf(frame, this.fieldOrigin);
    const key = frameKey(page);
    if (!key) return null;
    const url = safeFrameUrl(page);
    let record = this.pages.get(key);
    if (!record || record.url !== url) {
      record = { url, blocked: 0, recent: [] };
      this.pages.set(key, record);
      if (this.pages.size > 500) this.pages.delete(this.pages.keys().next().value);
    }
    return { record, url };
  }

  // onBeforeRequest handler. Returns null to let the request through.
  beforeRequest(details) {
    if (!this.enabled || details.resourceType === "mainFrame") return null;
    if (isExempt(details.url, this.fieldOrigin)) return null;
    // A frame that is Field's own page loads its own resources; leave them be.
    const frameUrl = safeFrameUrl(details.frame);
    if (details.resourceType !== "subFrame" && frameUrl && isExempt(frameUrl, this.fieldOrigin)) return null;
    const page = details.frame ? this.pageRecord(details.frame) : null;
    if (page && page.url && !isExempt(page.url, this.fieldOrigin) && !this.filtersPage(page.url)) return null;
    const verdict = this.match(details);
    if (!verdict) return null;
    this.totalBlocked++;
    if (page) {
      page.record.blocked++;
      page.record.recent.push({ url: details.url, type: details.resourceType, redirected: !!verdict.redirectURL });
      if (page.record.recent.length > RECENT_LIMIT) page.record.recent.shift();
    }
    return verdict;
  }

  match(details) {
    const { Request } = require("@ghostery/adblocker-electron");
    const request = Request.fromRawDetails({
      requestId: `${details.id}`,
      sourceUrl: details.referrer || safeFrameUrl(details.frame) || "",
      tabId: details.webContentsId,
      type: details.resourceType || "other",
      url: details.url,
    });
    if (request.type === "other") request.guessTypeOfRequest();
    const { redirect, match } = this.engine.match(request);
    if (redirect) return { redirectURL: redirect.dataUrl };
    if (match) return { cancel: true };
    return null;
  }

  // onHeadersReceived handler: CSP directives some lists add to a page.
  headersReceived(details) {
    if (!this.enabled || (details.resourceType !== "mainFrame" && details.resourceType !== "subFrame")) return null;
    if (isExempt(details.url, this.fieldOrigin) || !this.filtersPage(details.url)) return null;
    return new Promise(resolve => this.engine.onHeadersReceived(details, response => resolve(response || null)));
  }

  // Applies element hiding and scriptlets to [frame] for its current document.
  async injectCosmetics(frame) {
    if (!this.enabled || !frame) return;
    const url = safeFrameUrl(frame);
    if (!url || isExempt(url, this.fieldOrigin) || !this.filtersPage(url)) return;
    const { parse } = require("tldts-experimental");
    const parsed = parse(url);
    const { active, styles, scripts } = this.engine.getCosmeticsFilters({
      url,
      hostname: parsed.hostname || "",
      domain: parsed.domain || "",
      getBaseRules: true,
      getInjectionRules: true,
      getExtendedRules: false,
      getRulesFromHostname: true,
      getRulesFromDOM: false,
    });
    if (active === false) return;
    try {
      if (styles) await frame.executeJavaScript(cosmeticScript(styles));
      for (const script of scripts) await frame.executeJavaScript(script);
    } catch (error) {
      // The frame navigated or was destroyed between dom-ready and the injection.
      this.log("content-blocker-cosmetics-skipped", { url, error: String(error?.message || error) });
    }
  }

  // What is known about the page [frame] belongs to, for `LiveOutputIframe.blocking()`.
  statusFor(frame) {
    const page = frame ? this.pageRecord(frame) : null;
    const url = page?.url || safeFrameUrl(frame);
    const host = parseUrl(url)?.hostname || "";
    return {
      mode: this.mode,
      enabled: this.enabled,
      site: host,
      allowed: this.allowed.has(host),
      blocked: page?.record.blocked || 0,
      recent: page?.record.recent.slice() || [],
      totalBlocked: this.totalBlocked,
    };
  }

  // Turns filtering off (allowed=true) or back on for pages on [host].
  setAllowed(host, allowed) {
    if (!host) throw new Error("This page has no site to allow");
    if (allowed) this.allowed.add(host);
    else this.allowed.delete(host);
    writeAllowlist(this.allowlistPath, [...this.allowed]);
  }
}

function readAllowlist(file) {
  try {
    const value = JSON.parse(fs.readFileSync(file, "utf8"));
    return Array.isArray(value) ? value.filter(v => typeof v === "string") : [];
  } catch (_) { return []; }
}

function writeAllowlist(file, hosts) {
  if (!file) return;
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, JSON.stringify(hosts.sort(), null, 2));
}

// Loads the compiled filter engine for [mode], from the cache when it is fresh, otherwise from
// Ghostery's CDN (falling back to a stale cache when the network fails).
async function loadEngine(mode, cacheDir, fetchImpl, log) {
  const { ElectronBlocker } = require("@ghostery/adblocker-electron");
  const cacheFile = path.join(cacheDir, `engine-${mode}.bin`);
  const fresh = (() => {
    try { return Date.now() - fs.statSync(cacheFile).mtimeMs < REFRESH_AFTER_MS; } catch (_) { return false; }
  })();
  if (fresh) {
    try { return ElectronBlocker.deserialize(new Uint8Array(fs.readFileSync(cacheFile))); }
    catch (error) { log("content-blocker-cache-unreadable", { cacheFile, error: String(error) }); }
  }
  try {
    const engine = mode === "full"
      ? await ElectronBlocker.fromPrebuiltFull(fetchImpl)
      : await ElectronBlocker.fromPrebuiltAdsAndTracking(fetchImpl);
    fs.mkdirSync(cacheDir, { recursive: true });
    fs.writeFileSync(cacheFile, engine.serialize());
    return engine;
  } catch (error) {
    log("content-blocker-download-failed", { mode, error: String(error?.message || error) });
    if (fs.existsSync(cacheFile)) return ElectronBlocker.deserialize(new Uint8Array(fs.readFileSync(cacheFile)));
    throw error;
  }
}

let installed = null;

// Installs blocking on [session]'s pipeline; `contentBlocker().attach(other)` adds another session
// (a web profile's) to the same engine, allow list and counts. The engine loads in the background; until it has,
// requests pass unfiltered. Resolves to the ContentBlocker (or null when blocking is off).
async function installContentBlocker(session, fieldUrl, { app, log = () => {} } = {}) {
  const mode = blockingMode();
  if (mode === "off") {
    log("content-blocker", { mode });
    return null;
  }
  const userData = app.getPath("userData");
  const blocker = new ContentBlocker({
    engine: null,
    fieldOrigin: parseUrl(fieldUrl)?.origin || null,
    allowlistPath: path.join(userData, "web-blocking", "allowed-sites.json"),
    mode,
    log,
  });
  installed = blocker;
  blocker.sessions = new Set();
  blocker.attach = target => {
    if (blocker.sessions.has(target)) return;
    blocker.sessions.add(target);
    const pipeline = pipelineFor(target, log);
    pipeline.add("onBeforeRequest", "content-blocker", details => blocker.beforeRequest(details), { order: 10 });
    pipeline.add("onHeadersReceived", "content-blocker", details => blocker.headersReceived(details), { order: 30 });
  };
  blocker.attach(session);
  app.on("web-contents-created", (_event, contents) => {
    contents.on("frame-created", (_e, { frame }) => {
      if (!blocker.sessions.has(contents.session)) return;
      frame?.on?.("dom-ready", () => blocker.injectCosmetics(frame));
    });
    contents.on("did-frame-finish-load", (_e, _isMain, processId, routingId) => {
      if (!blocker.sessions.has(contents.session)) return;
      const { webFrameMain } = require("electron");
      blocker.injectCosmetics(webFrameMain.fromId(processId, routingId));
    });
  });
  const started = Date.now();
  try {
    blocker.engine = await loadEngine(mode, path.join(userData, "web-blocking"), (url, init) => session.fetch(url, init), log);
    blocker.enabled = true;
    log("content-blocker", { mode, ready: true, millis: Date.now() - started });
  } catch (error) {
    log("content-blocker", { mode, ready: false, error: String(error?.message || error) });
  }
  return blocker;
}

function contentBlocker() { return installed; }

module.exports = {
  ContentBlocker, installContentBlocker, contentBlocker, blockingMode, isExempt, pageFrameOf, cosmeticScript,
};
