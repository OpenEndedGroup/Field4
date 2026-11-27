const test = require("node:test");
const assert = require("node:assert/strict");
const vm = require("node:vm");
const { standInSource, runtimeSources } = require("./page-heads.cjs");

// The stand-in socket in a fake isolated world, with the CDP binding recording what it sends.
function world() {
  const sent = [];
  const context = vm.createContext({
    __fieldHeadSend: payload => sent.push(JSON.parse(payload)),
    btoa: s => Buffer.from(s, "binary").toString("base64"),
    atob: s => Buffer.from(s, "base64").toString("binary"),
    Uint8Array, ArrayBuffer,
  });
  context.window = context;
  return { context, sent, run: js => vm.runInContext(js, context) };
}

test("the stand-in is installed once per document", () => {
  const w = world();
  assert.equal(w.run(standInSource()), true);
  assert.equal(w.run(standInSource()), false);
});

test("the stand-in behaves as the WebSocket field.js expects, through the binding", () => {
  const w = world();
  w.run(standInSource());
  w.run(`
    var events = [];
    var s = new __FieldPageSocket("wss://github.com/ws");
    s.onopen = () => events.push(["open", s.readyState === __FieldPageSocket.OPEN]);
    s.onmessage = e => events.push(["message", typeof e.data === "string" ? e.data : Array.from(new Uint8Array(e.data))]);
    s.onclose = e => events.push(["close", e.code]);
  `);
  assert.equal(w.run("s.readyState"), 0);
  w.run("window.__fieldPageHead.open()");
  w.run("s.send('{\"address\":\"initialize\"}')");
  w.run("s.send(new Uint8Array([1, 2, 255]).buffer)");
  w.run("window.__fieldPageHead.deliver('hello')");
  w.run(`window.__fieldPageHead.deliverBinary(${JSON.stringify(Buffer.from([9, 8]).toString("base64"))})`);
  w.run("window.__fieldPageHead.closed()");
  assert.deepEqual(JSON.parse(JSON.stringify(w.run("events"))), [["open", true], ["message", "hello"], ["message", [9, 8]], ["close", 1006]]);
  assert.deepEqual(w.sent, [{ text: "{\"address\":\"initialize\"}" }, { binary: Buffer.from([1, 2, 255]).toString("base64") }]);
});

test("the runtime comes from the Field server, with field.js's WebSocket swapped and nothing else", async () => {
  const asked = [];
  const fetchImpl = async url => {
    asked.push(url);
    const body = url.endsWith("field.js") ? "if (s.readyState === WebSocket.OPEN) new WebSocket(u); var WebSocketish = 1" : "var x = WebSocket";
    return { ok: true, text: async () => body };
  };
  const sources = await runtimeSources("http://127.0.0.1:19999", fetchImpl);
  assert.equal(asked[0], "http://127.0.0.1:19999/extensions/remote_ar/field/errors.js");
  const field = sources.find(s => s.includes("sourceURL=field-page-head/field.js"));
  assert.match(field, /__FieldPageSocket\.OPEN\) new __FieldPageSocket\(u\); var WebSocketish/);
  assert.match(sources[0], /var x = WebSocket\n/);
});
