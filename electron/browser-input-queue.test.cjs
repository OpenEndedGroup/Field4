const test = require("node:test");
const assert = require("node:assert/strict");
const { createBrowserInputQueue } = require("./browser-input-queue.cjs");
const method = "Input.dispatchMouseEvent";
const move = x => ({ type: "mouseMoved", x, y: 20, buttons: 0, modifiers: 0 });
const wheel = (x, deltaY = 1) => ({ ...move(x), type: "mouseWheel", deltaX: 2, deltaY });

test("a 120 Hz move/wheel stream has at most one pending batch and retains displacement", async () => {
  const sent = [];
  let release;
  const gate = new Promise(resolve => { release = resolve; });
  const q = createBrowserInputQueue(async (_, p) => {
    sent.push(p);
    if (sent.length === 1) await gate;
  }, error => { throw error; });
  q.queue(method, move(0));
  await Promise.resolve();
  for (let i = 1; i <= 120; i++) {
    q.queue(method, move(i)); q.queue(method, wheel(i));
  }
  release(); await q.flush();
  assert.equal(sent.length, 3);
  assert.equal(sent[1].x, 120);
  assert.equal(sent[2].deltaY, 120);
  assert.equal(sent[2].deltaX, 240);
  // Once delivered there is no timer that can keep replaying a released stick.
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(sent.length, 3);
});

test("clicks, keyboard input and modifier changes seal continuous batches", async () => {
  const sent = [];
  const q = createBrowserInputQueue(async (m, p) => sent.push([m, p]), error => { throw error; });
  q.queue(method, move(10)); q.queue(method, wheel(10, 3));
  q.queue(method, { ...move(10), type: "mousePressed", buttons: 1 });
  q.queue("Input.dispatchKeyEvent", { type: "keyDown", key: "a" });
  q.queue(method, { ...wheel(30, -2), modifiers: 2 });
  q.queue(method, wheel(40, 5));
  q.queue(method, { ...move(40), type: "mouseReleased" });
  await q.flush();
  assert.deepEqual(sent.map(([, p]) => p.type), ["mouseMoved", "mouseWheel", "mousePressed", "keyDown", "mouseWheel", "mouseWheel", "mouseReleased"]);
  assert.deepEqual(sent.filter(([, p]) => p.type === "mouseWheel").map(([, p]) => p.deltaY), [3, -2, 5]);
});
