// Drag a `three` figure in the Electron audience and check that it orbits.
//
//   node tools/presentation/orbit-figure.mjs <CDP endpoint> <figure box uid> <output dir> [--then-next]
//
// The slide showing the figure must be up. The drag is real mouse input into the figure's own view
// (CDP Input.dispatchMouseEvent on that WebContentsView, so it passes the figure page's scale and the
// three.js iframe's hit-testing), across the middle of the iframe. The viewer's camera pose
// (FieldViewer.cameraPose) must then have turned about its target: the eye moved, the target and the
// eye's distance from it did not. With --then-next the presenter's Next runs (a cue that redraws the
// figure) and the pose must survive it. Writes before.png / after.png (and next.png), CDP screenshots
// of the figure page, and orbit.json.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';

const [endpoint, box, outputArg, flag] = process.argv.slice(2);
assert.ok(endpoint && box && outputArg, 'Usage: node tools/presentation/orbit-figure.mjs <CDP endpoint> <figure box uid> <output dir> [--then-next]');
assert.ok(['localhost', '127.0.0.1'].includes(new URL(endpoint).hostname), 'CDP must be loopback');
const output = path.resolve(outputArg);
fs.mkdirSync(output, {recursive: true});
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
const sockets = [];
async function connect(match) {
  const target = (await (await fetch(`${endpoint}/json/list`)).json()).find(t => match(t.url) && t.webSocketDebuggerUrl);
  assert.ok(target, 'no matching CDP target');
  const socket = new WebSocket(target.webSocketDebuggerUrl); sockets.push(socket);
  await new Promise((resolve, reject) => { socket.addEventListener('open', resolve, {once: true}); socket.addEventListener('error', reject, {once: true}); });
  let sequence = 0; const pending = new Map();
  socket.addEventListener('message', event => { const m = JSON.parse(event.data); pending.get(m.id)?.(m); pending.delete(m.id); });
  const call = (method, params = {}) => new Promise((resolve, reject) => {
    const id = ++sequence; pending.set(id, m => m.error ? reject(Error(m.error.message)) : resolve(m.result));
    socket.send(JSON.stringify({id, method, params}));
  });
  const evaluate = async expression => {
    const r = await call('Runtime.evaluate', {expression, returnByValue: true, awaitPromise: true});
    if (r.exceptionDetails) throw Error(r.exceptionDetails.exception?.description || r.exceptionDetails.text);
    return r.result.value;
  };
  return {call, evaluate};
}
const sub = (a, b) => a.map((v, i) => v - b[i]);
const length = v => Math.hypot(...v);

try {
  const figure = await connect(url => url.includes('figure=') && new URL(url).searchParams.get('figure') === box);
  const pose = () => figure.evaluate(`document.querySelector('#field-figure-host iframe')?.contentWindow?.FieldViewer?.cameraPose() || null`);
  const shot = async name => fs.writeFileSync(path.join(output, name), Buffer.from((await figure.call('Page.captureScreenshot', {format: 'png'})).data, 'base64'));
  let before = null;
  for (let i = 0; i < 100 && !before; i++) { before = await pose(); if (!before) await pause(200); }
  assert.ok(before, 'the figure has no three.js viewer');
  await shot('before.png');
  const rect = await figure.evaluate(`(() => { const r = document.querySelector('#field-figure-host iframe').getBoundingClientRect(); return {x: r.x, y: r.y, w: r.width, h: r.height}; })()`);
  const x0 = rect.x + rect.w / 2, y0 = rect.y + rect.h / 2, dx = rect.w * 0.3;
  await figure.call('Input.dispatchMouseEvent', {type: 'mouseMoved', x: x0, y: y0});
  await figure.call('Input.dispatchMouseEvent', {type: 'mousePressed', x: x0, y: y0, button: 'left', buttons: 1, clickCount: 1});
  for (let k = 1; k <= 20; k++) {
    await figure.call('Input.dispatchMouseEvent', {type: 'mouseMoved', x: x0 + dx * k / 20, y: y0, button: 'left', buttons: 1});
    await pause(25);
  }
  await figure.call('Input.dispatchMouseEvent', {type: 'mouseReleased', x: x0 + dx, y: y0, button: 'left', buttons: 0, clickCount: 1});
  await pause(800);
  const after = await pose();
  await shot('after.png');
  const radius = [length(sub(before.eye, before.target)), length(sub(after.eye, after.target))];
  const moved = length(sub(after.eye, before.eye));
  const result = {box, rect, before, after, radius, moved};
  assert.ok(moved > 0.05 * radius[0], `the drag did not move the camera: ${JSON.stringify(result)}`);
  assert.ok(length(sub(after.target, before.target)) < 1e-6 * Math.max(1, radius[0]), `the drag panned instead of orbiting: ${JSON.stringify(result)}`);
  assert.ok(Math.abs(radius[1] - radius[0]) < 1e-3 * radius[0], `the drag changed the distance: ${JSON.stringify(result)}`);
  if (flag === '--then-next') {
    const control = await connect(url => url.endsWith('/presentation-control.html'));
    await control.evaluate('window.fieldPresentation.action("next"), 1');
    await pause(5000);
    const next = await pose();
    await shot('next.png');
    result.next = next;
    assert.ok(length(sub(next.eye, after.eye)) < 1e-3 * radius[0], `redrawing the figure reset its camera: ${JSON.stringify(result)}`);
  }
  fs.writeFileSync(path.join(output, 'orbit.json'), JSON.stringify(result, null, 2) + '\n');
  console.log(`PASS: ${box} orbits (eye moved ${moved.toFixed(3)} at radius ${radius[0].toFixed(3)})${flag ? ', and keeps its camera across Next' : ''}`);
} finally { for (const socket of sockets) socket.close(); }
