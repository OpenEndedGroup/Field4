const test = require("node:test");
const assert = require("node:assert/strict");
const { relaxFramingHeaders, shouldRelax } = require("./framing-relief.cjs");

test("X-Frame-Options is dropped in any casing", () => {
  assert.deepEqual(
    relaxFramingHeaders({ "X-Frame-Options": ["DENY"], "Content-Type": ["text/html"] }),
    { "Content-Type": ["text/html"] });
  assert.deepEqual(relaxFramingHeaders({ "x-frame-options": ["sameorigin"] }), {});
});

test("frame-ancestors leaves the CSP and the rest of the policy stays", () => {
  const relaxed = relaxFramingHeaders({
    "content-security-policy": ["default-src 'self'; frame-ancestors 'none'; img-src *"],
  });
  assert.deepEqual(relaxed, { "content-security-policy": ["default-src 'self'; img-src *"] });
});

test("a CSP that only said frame-ancestors is removed, not left empty", () => {
  assert.deepEqual(relaxFramingHeaders({ "Content-Security-Policy": ["frame-ancestors 'self';"] }), {});
});

test("report-only policies and unrelated headers are untouched", () => {
  assert.equal(relaxFramingHeaders({ "Content-Security-Policy-Report-Only": ["frame-ancestors 'none'"] }), null);
  assert.equal(relaxFramingHeaders({ "Content-Security-Policy": ["script-src 'self'"] }), null);
  assert.equal(relaxFramingHeaders(undefined), null);
});

const contents = url => ({ isDestroyed: () => false, getURL: () => url });
const field = "https://localhost:9092";

test("only frames inside a Field page are relaxed", () => {
  assert.equal(shouldRelax({ resourceType: "subFrame", webContents: contents(`${field}/index.html?file=a`) }, field), true);
  assert.equal(shouldRelax({ resourceType: "mainFrame", webContents: contents(`${field}/index.html`) }, field), false);
  assert.equal(shouldRelax({ resourceType: "subFrame", webContents: contents("https://example.com/") }, field), false);
  assert.equal(shouldRelax({ resourceType: "subFrame", webContents: contents("https://localhost:9093/") }, field), false);
  assert.equal(shouldRelax({ resourceType: "subFrame", frame: { top: { url: `${field}/x` } } }, field), true);
});

test("under an isolated Field page a frame is given CORP and a credentialless COEP", () => {
  assert.deepEqual(relaxFramingHeaders({ "Content-Type": ["text/html"] }, { isolatedParent: true }), {
    "Content-Type": ["text/html"],
    "Cross-Origin-Resource-Policy": ["cross-origin"],
    "Cross-Origin-Embedder-Policy": ["credentialless"],
  });
  // A site's own require-corp stays; a same-site CORP is widened; unsafe-none is replaced.
  assert.deepEqual(relaxFramingHeaders({ "cross-origin-embedder-policy": ["require-corp"], "cross-origin-resource-policy": ["same-site"] }, { isolatedParent: true }), {
    "cross-origin-embedder-policy": ["require-corp"],
    "Cross-Origin-Resource-Policy": ["cross-origin"],
  });
  assert.equal(relaxFramingHeaders({ "Cross-Origin-Embedder-Policy": ["unsafe-none"] }, { isolatedParent: true })["Cross-Origin-Embedder-Policy"][0], "credentialless");
});

test("a non-isolated Field page never adds isolation headers to its frames", () => {
  assert.equal(relaxFramingHeaders({ "Content-Type": ["text/html"] }), null);
});

test("isolatesPage reads the top-level COEP", () => {
  const { isolatesPage } = require("./framing-relief.cjs");
  assert.equal(isolatesPage({ "Cross-Origin-Embedder-Policy": ["require-corp"] }), true);
  assert.equal(isolatesPage({ "cross-origin-embedder-policy": ["credentialless"] }), true);
  assert.equal(isolatesPage({ "Cross-Origin-Embedder-Policy": ["unsafe-none"] }), false);
  assert.equal(isolatesPage({}), false);
});

test("a third-party cookie becomes SameSite=None; Secure and keeps its other attributes", () => {
  const { relaxCookie, relaxThirdPartyCookies } = require("./framing-relief.cjs");
  assert.equal(relaxCookie("_gh_sess=abc; path=/; secure; HttpOnly; SameSite=Lax"), "_gh_sess=abc; path=/; secure; HttpOnly; SameSite=None");
  assert.equal(relaxCookie("logged_in=no; domain=github.com; path=/"), "logged_in=no; domain=github.com; path=/; Secure; SameSite=None");
  assert.equal(relaxCookie("__Host-x=1; Path=/; Secure; SameSite=Strict"), "__Host-x=1; Path=/; Secure; SameSite=None");
  assert.deepEqual(relaxThirdPartyCookies({ "Set-Cookie": ["a=1; SameSite=Lax", "b=2"], "Content-Type": ["text/html"] }), {
    "Set-Cookie": ["a=1; Secure; SameSite=None", "b=2; Secure; SameSite=None"], "Content-Type": ["text/html"],
  });
  assert.equal(relaxThirdPartyCookies({ "Content-Type": ["text/html"] }), null);
});

test("cookies are relaxed for other https sites inside a Field tab, never for Field or outside it", () => {
  const { shouldRelaxCookies } = require("./framing-relief.cjs");
  const inField = { isDestroyed: () => false, getURL: () => `${field}/index.html?file=a` };
  assert.equal(shouldRelaxCookies({ resourceType: "xhr", url: "https://github.com/session", webContents: inField }, field), true);
  assert.equal(shouldRelaxCookies({ resourceType: "subFrame", url: "https://github.com/", webContents: inField }, field), true);
  assert.equal(shouldRelaxCookies({ resourceType: "xhr", url: `${field}/api/x`, webContents: inField }, field), false);
  assert.equal(shouldRelaxCookies({ resourceType: "xhr", url: "http://example.com/", webContents: inField }, field), false);
  assert.equal(shouldRelaxCookies({ resourceType: "mainFrame", url: "https://github.com/", webContents: inField }, field), false);
  assert.equal(shouldRelaxCookies({ resourceType: "xhr", url: "https://github.com/", webContents: contents("https://example.com/") }, field), false);
});
