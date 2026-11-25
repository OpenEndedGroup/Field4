// Let a Field page frame any site. Most sites send `X-Frame-Options` or a CSP `frame-ancestors`
// directive that tells the browser not to show them inside another site's page, which leaves an
// output-area `<iframe src="https://github.com">` blank. Those headers protect a page from being
// framed by a site it does not trust; here the framing page is Field itself, on this machine.
//
// So, for a frame (never a top-level page) whose tab is showing a Field page, drop the two
// headers before Chromium reads them. Every other response, and every frame under a non-Field
// page, keeps its headers. Presentation links never needed this: they open as top-level views.
//
// A Field page can also be cross-origin isolated: with a Patchy build installed the server sends
// `Cross-Origin-Embedder-Policy: require-corp` so threaded WebAssembly gets SharedArrayBuffer.
// Under that policy a frame loads only if its own response opts in, which no ordinary site does,
// so every framed site is blocked (even ones that allow framing). For frames under such a page
// the relief also adds `Cross-Origin-Resource-Policy: cross-origin` and, unless the site set
// one, `Cross-Origin-Embedder-Policy: credentialless` (its cross-origin subresources then load
// without cookies, as a third-party frame's mostly do anyway).

const FRAME_ANCESTORS = /^\s*frame-ancestors(\s|$)/i;

function headerValues(value) {
  return (Array.isArray(value) ? value : [value]).map(String);
}

// Returns the response headers with framing refusals removed, or null when nothing changed.
// With [isolatedParent], the frame is also allowed under a COEP-isolated Field page.
function relaxFramingHeaders(responseHeaders, { isolatedParent = false } = {}) {
  if (!responseHeaders) return null;
  let changed = false;
  let hasEmbedderPolicy = false;
  const result = {};
  for (const [name, value] of Object.entries(responseHeaders)) {
    const lower = name.toLowerCase();
    if (lower === "x-frame-options") { changed = true; continue; }
    if (lower === "content-security-policy") {
      const values = headerValues(value).map(policy => {
        const directives = policy.split(";");
        const kept = directives.filter(directive => !FRAME_ANCESTORS.test(directive));
        if (kept.length !== directives.length) changed = true;
        return kept.join(";").trim();
      }).filter(policy => policy.replace(/;/g, "").trim() !== "");
      if (values.length) result[name] = values;
      else changed = true;
      continue;
    }
    if (isolatedParent && lower === "cross-origin-resource-policy") {
      if (headerValues(value).some(v => v.trim().toLowerCase() !== "cross-origin")) changed = true;
      continue; // replaced below
    }
    if (isolatedParent && lower === "cross-origin-embedder-policy") {
      const policy = headerValues(value)[0].trim().toLowerCase();
      if (policy.startsWith("require-corp") || policy.startsWith("credentialless")) hasEmbedderPolicy = true;
      else { changed = true; continue; } // "unsafe-none" would still be refused
    }
    result[name] = value;
  }
  if (isolatedParent) {
    if (!changed && !("cross-origin-resource-policy" in lowerKeys(responseHeaders))) changed = true;
    result["Cross-Origin-Resource-Policy"] = ["cross-origin"];
    if (!hasEmbedderPolicy) { result["Cross-Origin-Embedder-Policy"] = ["credentialless"]; changed = true; }
  }
  return changed ? result : null;
}

function lowerKeys(headers) {
  return Object.fromEntries(Object.keys(headers).map(name => [name.toLowerCase(), true]));
}

// True when a top-level response turns on cross-origin isolation for its page.
function isolatesPage(responseHeaders) {
  for (const [name, value] of Object.entries(responseHeaders || {})) {
    if (name.toLowerCase() !== "cross-origin-embedder-policy") continue;
    const policy = headerValues(value)[0].trim().toLowerCase();
    return policy.startsWith("require-corp") || policy.startsWith("credentialless");
  }
  return false;
}

function originOf(url) {
  try { return new URL(String(url)).origin; } catch (_) { return null; }
}

// The URL of the top-level page the request belongs to: the tab's own page.
function topLevelUrl(details) {
  try {
    const contents = details.webContents;
    if (contents && !contents.isDestroyed()) return contents.getURL();
  } catch (_) {}
  try { return details.frame?.top?.url || null; } catch (_) { return null; }
}

function shouldRelax(details, fieldOrigin) {
  return details.resourceType === "subFrame" && !!fieldOrigin && originOf(topLevelUrl(details)) === fieldOrigin;
}

// A site framed in a Field page is third-party to it, and Chromium neither stores nor sends a
// third-party cookie unless it says `SameSite=None; Secure`. Most session cookies say Lax (or
// nothing, which means Lax), so a login inside an output iframe posted its form without the
// session its CSRF token belonged to (GitHub: "Your browser did something unexpected"). For
// https responses to anything inside a Field tab other than Field itself, each Set-Cookie is
// rewritten to SameSite=None; Secure. The cookies live in this Electron session only; the cost is
// that any page in it can then make credentialed requests to those sites.
function relaxCookie(cookie) {
  const parts = String(cookie).split(";");
  const kept = parts.filter((part, index) => index === 0 || !/^\s*samesite\s*=/i.test(part));
  if (!kept.slice(1).some(part => /^\s*secure\s*$/i.test(part))) kept.push(" Secure");
  kept.push(" SameSite=None");
  return kept.join(";");
}

function relaxThirdPartyCookies(responseHeaders) {
  if (!responseHeaders) return null;
  let changed = false;
  const result = {};
  for (const [name, value] of Object.entries(responseHeaders)) {
    if (name.toLowerCase() !== "set-cookie") { result[name] = value; continue; }
    result[name] = headerValues(value).map(cookie => {
      const relaxed = relaxCookie(cookie);
      if (relaxed !== cookie) changed = true;
      return relaxed;
    });
  }
  return changed ? result : null;
}

function shouldRelaxCookies(details, fieldOrigin) {
  if (details.resourceType === "mainFrame" || !fieldOrigin) return false;
  const origin = originOf(details.url);
  return !!origin && origin.startsWith("https:") && origin !== fieldOrigin &&
    originOf(topLevelUrl(details)) === fieldOrigin;
}

// Installs the relief on [session] for frames inside pages served from [fieldUrl]'s origin.
// A session has one onHeadersReceived listener; this one owns the default session's.
function installFramingRelief(session, fieldUrl, log = () => {}) {
  const fieldOrigin = originOf(fieldUrl);
  const isolatedTabs = new Set(); // webContents ids whose current Field page is COEP-isolated
  session.webRequest.onHeadersReceived((details, callback) => {
    if (details.resourceType === "mainFrame") {
      if (details.webContentsId != null) {
        if (originOf(details.url) === fieldOrigin && isolatesPage(details.responseHeaders)) isolatedTabs.add(details.webContentsId);
        else isolatedTabs.delete(details.webContentsId);
      }
      callback({});
      return;
    }
    let headers = details.responseHeaders;
    let changed = false;
    if (shouldRelax(details, fieldOrigin)) {
      const isolatedParent = isolatedTabs.has(details.webContentsId);
      const relaxed = relaxFramingHeaders(headers, { isolatedParent });
      if (relaxed) {
        headers = relaxed; changed = true;
        log("framing-relief", { url: details.url, top: topLevelUrl(details), isolatedParent });
      }
    }
    if (shouldRelaxCookies(details, fieldOrigin)) {
      const relaxed = relaxThirdPartyCookies(headers);
      if (relaxed) { headers = relaxed; changed = true; }
    }
    callback(changed ? { responseHeaders: headers } : {});
  });
  return fieldOrigin;
}

module.exports = {
  relaxFramingHeaders, isolatesPage, shouldRelax,
  relaxCookie, relaxThirdPartyCookies, shouldRelaxCookies, installFramingRelief,
};
