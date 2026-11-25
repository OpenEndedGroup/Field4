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
