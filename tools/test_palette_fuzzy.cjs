// node --test tools/test_palette_fuzzy.cjs
// The palette matcher (src/commonMain/resources/paletteFuzzy.js) used by Open Anything and the
// Command Palette.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { paletteFuzzyScore: score, paletteFuzzyMatch: match } = require('../src/commonMain/resources/paletteFuzzy.js');

const matches = (q, h) => Number.isFinite(score(q, h));

test('subsequences across word starts and humps match', () => {
    assert.ok(matches('rndr', 'renderer'));
    assert.ok(matches('fsel', 'Frame Selected'));
    assert.ok(matches('rcz', 'Reset Canvas Zoom'));
    assert.ok(matches('agc', 'agent_graphics_context'));
    assert.ok(matches('rtt', 'RenderToTexture'));
});

test('every query word must match, in any order', () => {
    assert.ok(matches('ren box', 'my renderer Box'));
    assert.ok(matches('box ren', 'my renderer Box'));
    assert.ok(!matches('ren qqq', 'my renderer Box'));
});

test('one dropped character is tolerated for longer words', () => {
    assert.ok(matches('rednerer', 'renderer'));
    assert.ok(matches('rendrer', 'renderer'));
    assert.ok(matches('selectde', 'Frame Selected'));
    assert.ok(!matches('xyz', 'Reset Canvas Zoom'));
});

test('missing letters do not match', () => {
    assert.ok(!matches('glctx', 'agent_graphics_context'));
    assert.ok(!matches('fs', 'Reset Canvas Zoom'));
});

test('better alignments rank higher', () => {
    assert.ok(score('frame', 'Frame Selected') > score('frame', 'Toggle drawing frame overlay'));
    assert.ok(score('fs', 'Frame Selected') > score('fs', 'diffuse shading'));
    assert.ok(score('renderer', 'renderer') > score('rndr', 'renderer'));
    assert.ok(score('cam', [['Camera', 1], ['', 0.6]]) > score('cam', [['Lights', 1], ['camera rig', 0.6]]));
});

test('positions pick the best alignment for highlighting', () => {
    assert.deepEqual(match('fs', 'Frame Selected').positions, [0, 6]);
    assert.deepEqual(match('sel', 'Frame Selected').positions, [6, 7, 8]);
});

test('long text falls back to contiguous matching', () => {
    const code = 'val x = 1\n'.repeat(60) + 'renderer.draw()';
    assert.ok(matches('renderer', code));
    assert.ok(!matches('rndr', code));
});
