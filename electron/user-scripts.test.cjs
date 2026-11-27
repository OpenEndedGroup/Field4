const test = require("node:test");
const assert = require("node:assert/strict");
const vm = require("node:vm");
const { loaderSource, WEB_FRAME_PREFIX } = require("./user-scripts.cjs");

// Runs a loader in a stand-in page; returns what its scripts did.
function runIn({ name = WEB_FRAME_PREFIX + "x", top = false, href = "https://en.wikipedia.org/wiki/HTML", readyState = "loading" }, entries, guestProfile = null) {
  const listeners = {};
  const did = [];
  const document = {
    readyState,
    adoptedStyleSheets: [],
    addEventListener: (event, fn) => { (listeners[event] ??= []).push(fn); },
  };
  const window = { name, location: { href }, document, addEventListener: (event, fn) => { (listeners[event] ??= []).push(fn); } };
  window.top = top ? window : {};
  class CSSStyleSheet { replaceSync(css) { this.css = css; } }
  const context = vm.createContext({ window, document, location: window.location, CSSStyleSheet, did, console: { error: (...a) => did.push(["error", String(a[1])]) } });
  context.window.did = did;
  vm.runInContext(loaderSource(entries, guestProfile), context);
  return { did, document, fire: event => (listeners[event] || []).forEach(fn => fn()) };
}

const entry = (key, over = {}) => ({ key, token: "t1", kind: "script", match: "^https://en\\.wikipedia\\.org/.*$", runAt: "start",
  source: `did.push(${JSON.stringify(key)})`, profile: null, ...over });

test("a matching web() iframe runs its scripts; other frames and addresses do not", () => {
  assert.deepEqual(runIn({}, [entry("a")]).did, ["a"]);
  assert.deepEqual(runIn({ name: "plot-output" }, [entry("a")]).did, []);
  assert.deepEqual(runIn({ href: "https://github.com/" }, [entry("a")]).did, []);
});

test("a webview runs in its top frame only, and profile scripts only in their profile", () => {
  const scripts = [entry("any"), entry("mine", { profile: "/p/alpha" }), entry("theirs", { profile: "/p/beta" })];
  assert.deepEqual(runIn({ name: "", top: true }, scripts, "/p/alpha").did, ["any", "mine"]);
  assert.deepEqual(runIn({ name: "", top: false }, scripts, "/p/alpha").did, []);
  assert.deepEqual(runIn({}, scripts, null).did, ["any"]);
});

test("runAt waits for the document: end at DOMContentLoaded, idle at load", () => {
  const page = runIn({}, [entry("s"), entry("e", { runAt: "end" }), entry("i", { runAt: "idle" })]);
  assert.deepEqual(page.did, ["s"]);
  page.fire("DOMContentLoaded");
  assert.deepEqual(page.did, ["s", "e"]);
  page.fire("load");
  assert.deepEqual(page.did, ["s", "e", "i"]);
  assert.deepEqual(runIn({ readyState: "complete" }, [entry("e", { runAt: "end" }), entry("i", { runAt: "idle" })]).did, ["e", "i"]);
});

test("a style is an adopted stylesheet, and a throwing script does not stop the next", () => {
  const page = runIn({}, [entry("bad", { source: "throw new Error('boom')" }), entry("ok"),
    { key: "css", token: "t", kind: "style", match: ".*", runAt: "start", source: "body{color:red}", profile: null }]);
  assert.deepEqual(page.did.filter(d => typeof d === "string"), ["ok"]);
  assert.equal(page.did.find(d => Array.isArray(d))[1], "Error: boom");
  assert.equal(page.document.adoptedStyleSheets.length, 1);
  assert.equal(page.document.adoptedStyleSheets[0].css, "body{color:red}");
});

test("a source with quotes, backticks and closing braces stays one function", () => {
  const tricky = "did.push(`a${'}'}` + \"})()\" + '\\'')";
  assert.deepEqual(runIn({}, [entry("x", { source: tricky })]).did, ["a}})()'"]);
});
