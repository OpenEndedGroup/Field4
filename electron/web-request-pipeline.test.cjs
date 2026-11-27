const test = require("node:test");
const assert = require("node:assert/strict");
const { WebRequestPipeline, fromCallbackHandler } = require("./web-request-pipeline.cjs");

// A stand-in for session.webRequest that records the one listener per event, as Electron does.
function fakeSession() {
  const listeners = {};
  const webRequest = new Proxy({}, { get: (_, event) => listener => { listeners[event] = listener; } });
  return { webRequest, listeners };
}

function fire(session, event, details) {
  return new Promise(resolve => session.listeners[event](details, resolve));
}

test("headers thread through handlers in order and each sees its own copy", async () => {
  const session = fakeSession();
  const pipeline = new WebRequestPipeline(session);
  const seen = [];
  pipeline.add("onHeadersReceived", "second", details => {
    seen.push(Object.keys(details.responseHeaders));
    details.responseHeaders.Junk = ["mutated"]; // must not leak
    return { responseHeaders: { ...details.responseHeaders, B: ["2"] } };
  }, { order: 20 });
  pipeline.add("onHeadersReceived", "first", details => {
    delete details.responseHeaders.Original; // a mutation without a result must not leak
    return { responseHeaders: { A: ["1"], Original: details.responseHeaders.Keep } };
  }, { order: 10 });
  const reply = await fire(session, "onHeadersReceived", { url: "https://x", responseHeaders: { Original: ["o"], Keep: ["k"] } });
  assert.deepEqual(seen, [["A", "Original"]]);
  assert.deepEqual(reply, { responseHeaders: { A: ["1"], Original: ["k"], Junk: ["mutated"], B: ["2"] } });
  assert.deepEqual(pipeline.describe(), { onHeadersReceived: ["first", "second"] });
});

test("nothing changed means an empty reply", async () => {
  const session = fakeSession();
  const pipeline = new WebRequestPipeline(session);
  pipeline.add("onHeadersReceived", "quiet", () => null);
  assert.deepEqual(await fire(session, "onHeadersReceived", { responseHeaders: { A: ["1"] } }), {});
});

test("the first cancel or redirect decides and later handlers are not asked", async () => {
  const session = fakeSession();
  const pipeline = new WebRequestPipeline(session);
  let asked = false;
  pipeline.add("onBeforeRequest", "redirect", async () => ({ redirectURL: "data:," }), { order: 1 });
  pipeline.add("onBeforeRequest", "late", () => { asked = true; return { cancel: true }; }, { order: 2 });
  assert.deepEqual(await fire(session, "onBeforeRequest", { url: "https://ads" }), { redirectURL: "data:," });
  assert.equal(asked, false);
});

test("a throwing handler is skipped, logged, and the rest still run", async () => {
  const session = fakeSession();
  const logged = [];
  const pipeline = new WebRequestPipeline(session, (event, data) => logged.push([event, data.handler]));
  pipeline.add("onBeforeRequest", "broken", () => { throw new Error("boom"); }, { order: 1 });
  pipeline.add("onBeforeRequest", "blocker", () => Promise.resolve({ cancel: true }), { order: 2 });
  assert.deepEqual(await fire(session, "onBeforeRequest", { url: "https://ads" }), { cancel: true });
  assert.deepEqual(logged, [["web-request-handler-error", "broken"]]);
});

test("adding a name again replaces it in place, and remove takes it out", async () => {
  const session = fakeSession();
  const pipeline = new WebRequestPipeline(session);
  pipeline.add("onBeforeRequest", "a", () => ({ cancel: true }));
  const removeB = pipeline.add("onBeforeRequest", "b", () => null);
  pipeline.add("onBeforeRequest", "a", () => null);
  assert.deepEqual(pipeline.describe(), { onBeforeRequest: ["a", "b"] });
  assert.deepEqual(await fire(session, "onBeforeRequest", {}), {});
  removeB();
  assert.deepEqual(pipeline.describe(), { onBeforeRequest: ["a"] });
});

test("callback-style handlers adapt, and observers all hear an event", async () => {
  const session = fakeSession();
  const pipeline = new WebRequestPipeline(session);
  pipeline.add("onBeforeRequest", "legacy", fromCallbackHandler((details, callback) => callback({ cancel: details.url === "https://ads" })));
  assert.deepEqual(await fire(session, "onBeforeRequest", { url: "https://ads" }), { cancel: true });
  assert.deepEqual(await fire(session, "onBeforeRequest", { url: "https://ok" }), {});
  const heard = [];
  pipeline.add("onCompleted", "one", d => heard.push(["one", d.url]));
  pipeline.add("onCompleted", "two", () => { throw new Error("ignored"); });
  pipeline.add("onCompleted", "three", d => heard.push(["three", d.url]));
  session.listeners.onCompleted({ url: "https://x" });
  assert.deepEqual(heard, [["one", "https://x"], ["three", "https://x"]]);
});
