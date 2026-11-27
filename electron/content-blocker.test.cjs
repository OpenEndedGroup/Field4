const test = require("node:test");
const assert = require("node:assert/strict");
const os = require("node:os");
const fs = require("node:fs");
const path = require("node:path");
const { ElectronBlocker } = require("@ghostery/adblocker-electron");
const { ContentBlocker, blockingMode, isExempt, pageFrameOf } = require("./content-blocker.cjs");

const FIELD = "https://localhost:9092";

// A filter engine built from a few lines of uBO/EasyList syntax, so the test needs no network.
function engine() {
  return ElectronBlocker.parse([
    "||ads.example^",
    "||tracker.example^$third-party",
    "news.example##.sponsored",
    "##.ad-banner",
  ].join("\n"), { loadCosmeticFilters: true, loadNetworkFilters: true });
}

// WebFrameMain stand-ins: Field's page with a framed site, which itself frames an ad.
function frames() {
  const field = { url: `${FIELD}/index.html?file=doc`, parent: null, frameTreeNodeId: 1 };
  const site = { url: "https://news.example/today", parent: field, frameTreeNodeId: 2 };
  const ad = { url: "https://ads.example/slot", parent: site, frameTreeNodeId: 3 };
  return { field, site, ad };
}

function blocker() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "field-blocker-"));
  return new ContentBlocker({ engine: engine(), fieldOrigin: FIELD, allowlistPath: path.join(dir, "allowed.json"), mode: "ads" });
}

test("mode comes from AFIELD_WEB_BLOCKING", () => {
  assert.equal(blockingMode(undefined), "ads");
  assert.equal(blockingMode("full"), "full");
  assert.equal(blockingMode("0"), "off");
  assert.equal(blockingMode("OFF"), "off");
});

test("Field and loopback are exempt, other sites are not", () => {
  assert.equal(isExempt(`${FIELD}/ads/x.js`, FIELD), true);
  assert.equal(isExempt("http://127.0.0.1:19092/x", FIELD), true);
  assert.equal(isExempt("data:text/plain,x", FIELD), true);
  assert.equal(isExempt("https://ads.example/x", FIELD), false);
});

test("the page frame is the frame just below Field's own page", () => {
  const { field, site, ad } = frames();
  assert.equal(pageFrameOf(ad, FIELD), site);
  assert.equal(pageFrameOf(site, FIELD), site);
  assert.equal(pageFrameOf(field, FIELD), field);
});

test("an ad inside a framed site is blocked and counted against the site", () => {
  const b = blocker();
  const { site, ad } = frames();
  assert.deepEqual(b.beforeRequest({ id: 1, url: "https://ads.example/banner.js", resourceType: "script", frame: site }), { cancel: true });
  assert.deepEqual(b.beforeRequest({ id: 2, url: "https://ads.example/pixel.gif", resourceType: "image", frame: ad }), { cancel: true });
  assert.equal(b.beforeRequest({ id: 3, url: "https://news.example/story.js", resourceType: "script", frame: site }), null);
  const status = b.statusFor(site);
  assert.equal(status.site, "news.example");
  assert.equal(status.blocked, 2);
  assert.equal(status.recent[0].url, "https://ads.example/banner.js");
  assert.equal(status.totalBlocked, 2);
});

test("Field's own page and its resources are never blocked", () => {
  const b = blocker();
  const { field } = frames();
  assert.equal(b.beforeRequest({ id: 1, url: `${FIELD}/ads.example/x.js`, resourceType: "script", frame: field }), null);
  // Field's page asking a third-party script for itself is Field's business, not a framed site's.
  assert.equal(b.beforeRequest({ id: 2, url: "https://ads.example/x.js", resourceType: "script", frame: field }), null);
  assert.equal(b.beforeRequest({ id: 3, url: "https://news.example/", resourceType: "mainFrame", frame: field }), null);
});

test("an allowed site is not filtered and the choice is saved", () => {
  const b = blocker();
  const { site } = frames();
  b.setAllowed("news.example", true);
  assert.equal(b.beforeRequest({ id: 1, url: "https://ads.example/banner.js", resourceType: "script", frame: site }), null);
  assert.deepEqual(JSON.parse(fs.readFileSync(b.allowlistPath, "utf8")), ["news.example"]);
  b.setAllowed("news.example", false);
  assert.deepEqual(b.beforeRequest({ id: 2, url: "https://ads.example/banner.js", resourceType: "script", frame: site }), { cancel: true });
});

test("before the engine loads, requests pass", () => {
  const b = new ContentBlocker({ engine: null, fieldOrigin: FIELD, allowlistPath: null, mode: "ads" });
  assert.equal(b.beforeRequest({ id: 1, url: "https://ads.example/x.js", resourceType: "script", frame: frames().site }), null);
});

test("cosmetics reach a framed site but never Field's page", async () => {
  const b = blocker();
  const { field, site } = frames();
  const scripts = [];
  const run = frame => ({ ...frame, executeJavaScript: async js => { scripts.push([frame.url, js]); } });
  await b.injectCosmetics(run(site));
  await b.injectCosmetics(run(field));
  assert.equal(scripts.length >= 1, true);
  assert.equal(scripts.every(([url]) => url === site.url), true);
  assert.match(scripts[0][1], /\.sponsored/);
  assert.match(scripts[0][1], /adoptedStyleSheets/);
});
