import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {createRequire} from 'node:module';
const {paintTransitionFrame} = createRequire(import.meta.url)('../../../electron/presentation-transition.js');

function startFrame({baseSource = null} = {}) {
  const images = [];
  const events = [];
  const canvases = Object.fromEntries(['base', 'guest'].map(id => [id, {
    style: {}, getContext: () => ({drawImage: () => events.push(`draw ${id}`)}),
  }]));
  const bodyStyle = {};
  Object.defineProperty(bodyStyle, 'background', {set: value => events.push(`background ${value}`)});
  class Image {
    constructor() { images.push(this); }
    load() { this.naturalWidth = 1280; this.naturalHeight = 720; this.onload(); }
  }
  // Use the same serialized function Electron executes, with image completion controlled by the test.
  const done = vm.runInNewContext(`(${paintTransitionFrame.toString()})(args)`, {
    Image, document: {body: {style: bodyStyle}, getElementById: id => canvases[id]},
    args: {baseSource, guestSource: 'data:image/png;captured-guest', guestStyle: 'left:0;top:0'},
  });
  return {images, events, canvases, done};
}

test('a native document stays visible until its transition snapshot is drawn', async () => {
  const frame = startFrame();
  assert.deepEqual(frame.events, [], 'the overlay must stay transparent while the image loads');
  frame.images[0].load();
  await frame.done;
  assert.deepEqual(frame.events, ['draw guest', 'background #000']);
  assert.equal(frame.canvases.guest.style.visibility, 'visible');
});

test('a failed native snapshot never reveals an empty black overlay', async () => {
  const frame = startFrame();
  frame.images[0].onerror();
  await assert.rejects(frame.done, /Could not load/);
  assert.deepEqual(frame.events, []);
});
