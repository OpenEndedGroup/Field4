const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { test } = require("node:test");
const vm = require("node:vm");

const context = {
  URL,
  window: {},
  document: {
    currentScript: { src: "file:///code-diff-player.js" },
    readyState: "loading",
    addEventListener() {},
    createElement() { return {}; },
    head: { appendChild() {} },
  },
};
const source = path.join(__dirname, "../public/docs/assets/code-diff-player/code-diff-player.js");
vm.runInNewContext(fs.readFileSync(source, "utf8"), context);
const { DiffPlayer } = context.window.CodeDiffPlayer;
DiffPlayer.prototype.buildDom = function () {};
DiffPlayer.prototype.render = function () {};

test("an unrelated replacement displays the final code as whole lines", () => {
  const before = "play(world, w = w, h = h)";
  const after = [
    "var traced = emptyList<SpatialFact>()",
    "",
    'world.source("highlight") {',
    "    nextFrame()",
    "}",
  ].join("\n");
  const player = new DiffPlayer({}, { before, after });
  assert.equal(player.stateLines.join("\n"), after);
  assert.equal(player.inlineHighlights.size, 0);
  assert.equal(player.markers.get(0), "marker-insert");
});

test("an added line does not shift an adjacent inline edit onto the wrong row", () => {
  const before = 'onEnter("bond", seeds.pairsWithin(110.0)) {';
  const after = 'var bondsMade = 0\nonEnter("bond", seeds.pairsWithin(110.0, leave = 130.0)) {';
  const player = new DiffPlayer({}, { before, after });
  assert.equal(player.stateLines.join("\n"), after);
  assert.equal(player.inlineHighlights.has(0), false);
  const spans = player.inlineHighlights.get(1);
  assert.equal(spans.map((span) => span.text).join(""), after.split("\n")[1]);
  assert.equal(spans.filter((span) => span.type === "insert").map((span) => span.text).join(""), ", leave = 130.0");
});

test("distant edits retain context around each change and fold the middle", () => {
  const before = Array.from({ length: 40 }, (_, index) => `val item${index} = ${index}`);
  const after = before.slice();
  after[0] = "val item0 = 1";
  after[39] = "val item39 = 40";
  const player = new DiffPlayer({}, { before: before.join("\n"), after: after.join("\n") });
  assert.equal(JSON.stringify(player.contextGaps(after.length)), '[{"start":4,"end":36}]');
  assert.equal(player.stateLines.join("\n"), after.join("\n"));
});
