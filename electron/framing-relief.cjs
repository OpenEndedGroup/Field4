// Let a Field page frame any site. Most sites send `X-Frame-Options` or a CSP `frame-ancestors`
// directive that tells the browser not to show them inside another site's page, which leaves an
// output-area `<iframe src="https://github.com">` blank. Those headers protect a page from being
// framed by a site it does not trust; here the framing page is Field itself, on this machine.
//
// So, for a frame (never a top-level page) whose tab is showing a Field page, drop the two
// headers before Chromium reads them. Every other response, and every frame under a non-Field
// page, keeps its headers. Presentation links never needed this: they open as top-level views.

const FRAME_ANCESTORS = /^\s*frame-ancestors(\s|$)/i;

// Returns the response headers with framing refusals removed, or null when nothing changed.
function relaxFramingHeaders(responseHeaders) {
  if (!responseHeaders) return null;
  let changed = false;
  const result = {};
  for (const [name, value] of Object.entries(responseHeaders)) {
    const lower = name.toLowerCase();
    if (lower === "x-frame-options") { changed = true; continue; }
    if (lower === "content-security-policy") {
      const values = (Array.isArray(value) ? value : [value]).map(policy => {
        const directives = String(policy).split(";");
        const kept = directives.filter(directive => !FRAME_ANCESTORS.test(directive));
        if (kept.length !== directives.length) changed = true;
        return kept.join(";").trim();
      }).filter(policy => policy.replace(/;/g, "").trim() !== "");
      if (values.length) result[name] = values;
      else changed = true;
      continue;
    }
    result[name] = value;
  }
  return changed ? result : null;
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

// Installs the relief on [session] for frames inside pages served from [fieldUrl]'s origin.
// A session has one onHeadersReceived listener; this one owns the default session's.
function installFramingRelief(session, fieldUrl, log = () => {}) {
  const fieldOrigin = originOf(fieldUrl);
  session.webRequest.onHeadersReceived((details, callback) => {
    if (!shouldRelax(details, fieldOrigin)) { callback({}); return; }
    const relaxed = relaxFramingHeaders(details.responseHeaders);
    if (!relaxed) { callback({}); return; }
    log("framing-relief", { url: details.url, top: topLevelUrl(details) });
    callback({ responseHeaders: relaxed });
  });
  return fieldOrigin;
}

module.exports = { relaxFramingHeaders, shouldRelax, installFramingRelief };
