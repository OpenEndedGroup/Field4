// Rehearse cue navigation and source reset in a disposable native starter.
// Captures come from its actual Electron windows.
// See dev_docs/testing/PRESENTATION_CAPTURES.md for the disposable-document setup.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {execFileSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import {fileURLToPath} from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const [endpoint, documentPath] = process.argv.slice(2);
assert.ok(endpoint && documentPath, 'Usage: node tools/presentation/rehearse-lessons.mjs http://127.0.0.1:29482 /tmp/workspace/docs-talk');
assert.ok(['localhost', '127.0.0.1'].includes(new URL(endpoint).hostname), 'CDP must be loopback');
const document = fs.realpathSync(documentPath);
const source = fs.readFileSync(path.join(document, 'present_talk_code_.kt'), 'utf8');
assert.equal(source, fs.readFileSync(path.join(root, 'templates/presentation/present_talk_code_.kt'), 'utf8'), 'Capture an unchanged disposable copy of this checkout’s starter');
const output = path.resolve(process.argv[4] || '/tmp/field-lesson-rehearsal');
fs.mkdirSync(output, {recursive: true});
const sockets = [];
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
async function until(read, predicate, label) {
  const deadline = Date.now() + 30000;
  while (Date.now() < deadline) { const value = await read(); if (predicate(value)) return value; await pause(100); }
  throw Error(`Timed out: ${label}`);
}
async function connect(target) {
  const socket = new WebSocket(target.webSocketDebuggerUrl); sockets.push(socket);
  await new Promise((resolve, reject) => { socket.addEventListener('open', resolve, {once: true}); socket.addEventListener('error', reject, {once: true}); });
  let sequence = 0;
  const pending = new Map();
  socket.addEventListener('message', event => {
    const message = JSON.parse(event.data); const request = pending.get(message.id); if (!request) return;
    pending.delete(message.id); clearTimeout(request.timer);
    if (message.error) request.reject(Error(message.error.message)); else request.resolve(message.result);
  });
  const call = (method, params = {}) => new Promise((resolve, reject) => {
    const id = ++sequence;
    const timer = setTimeout(() => { pending.delete(id); reject(Error(`CDP timeout: ${method}`)); }, 30000);
    pending.set(id, {resolve, reject, timer}); socket.send(JSON.stringify({id, method, params}));
  });
  return {call, evaluate: async expression => {
    const response = await call('Runtime.evaluate', {expression, awaitPromise: true, returnByValue: true});
    if (response.exceptionDetails) throw Error(response.exceptionDetails.exception?.description || response.exceptionDetails.text);
    return response.result.value;
  }};
}
const targets = async () => (await fetch(`${endpoint}/json/list`)).json();

try {
  const list = await until(targets, xs => xs.some(x => x.url.endsWith('/presentation-control.html')), 'presenter');
  const sourceIds = new Set(list.filter(x => x.url.includes('/index.html?file=')).map(x => x.id));
  const control = await connect(list.find(x => x.url.endsWith('/presentation-control.html')));
  const audience = await connect(list.find(x => x.url.endsWith('/presentation-audience.html')));
  const click = id => control.evaluate(`document.getElementById(${JSON.stringify(id)}).click()`);
  const status = () => control.evaluate('document.getElementById("status").textContent');
  const body = () => audience.evaluate('document.querySelector("iframe")?.contentDocument?.body.innerText');
  const settle = async () => {
    await until(targets, xs => !xs.some(x => x.url.startsWith('data:text/html,<html><style>html,body')), 'transition finished');
    assert.equal(await control.evaluate('document.getElementById("error").textContent'), '');
  };
  await control.evaluate(`[...document.querySelectorAll('#slides button.slide')].find(b=>b.dataset.slideTitle==='Predict a wave').click()`);
  await until(status, x => x.includes('Predict a wave') && x.includes('cue 0 / 2'), 'initial wave cue');
  await settle();
  await click('next');
  await until(status, x => x.includes('cue 1 / 2'), 'explanation');
  await settle();
  assert.ok((await body()).includes('Half the wavelength.'));
  const png = await audience.call('Page.captureScreenshot', {format: 'png'});
  fs.writeFileSync(path.join(output, 'wave-reveal.png'), Buffer.from(png.data, 'base64'));
  await click('previous');
  await until(status, x => x.includes('cue 0 / 2'), 'back to prediction');
  await settle();
  assert.ok((await body()).includes('Twice the frequency?'));
  await click('next');
  await until(status, x => x.includes('cue 1 / 2'), 'reveal again');
  await settle();
  await click('next');
  await until(status, x => x.includes('cue 2 / 2'), 'live experiment');
  await settle();
  const guests = await until(targets, xs => xs.filter(x => x.url.includes('/index.html?file=')).length >= 2, 'audience document');
  const guestTarget = guests.filter(x => x.url.includes('/index.html?file=') && !sourceIds.has(x.id));
  assert.equal(guestTarget.length, 1, 'one newly opened native audience document');
  let guest = await connect(guestTarget[0]);
  await until(() => guest.evaluate('typeof window.switchToProperty'), x => x === 'function', 'Field editor ready');
  await guest.evaluate('window.switchToProperty("present_wave_experiment", "code")');
  await until(() => guest.evaluate('window.globalEditor?.getModel()?.getValue()'), x => x?.includes('val frequency = 1.0'), 'baseline source');
  const original = fs.readFileSync(path.join(document, 'present_live_field_code_.kt'), 'utf8');
  await guest.evaluate(`(()=>{const e=window.globalEditor;const m=e.getModel();e.executeEdits('lesson-rehearsal',[{range:m.getFullModelRange(),text:m.getValue().replace('val frequency = 1.0','val frequency = 2.0')}]);return true})()`);
  await pause(700);
  const port = new URL(guests.find(x => x.url.includes('/index.html?file=')).url).port;
  execFileSync('uv', ['run', '--project', '.', '--group', 'backdoor', 'python', 'tools/field_session.py', 'call', '--port', port, '--no-reset', '--command', `open ${JSON.stringify(path.basename(document))}`, '--command', 'run present_wave_experiment'], {cwd:root, stdio:'pipe'});
  await until(() => guest.evaluate('document.querySelector("polyline")?.getAttribute("points")'), Boolean, 'wave output');
  const changedPoints = await guest.evaluate('document.querySelector("polyline").getAttribute("points")');
  await click('reset-checkpoint');
  await until(status, x => x.includes('cue 0 / 2'), 'explicit reset');
  await settle();
  await click('next'); await until(status, x => x.includes('cue 1 / 2'), 'repeat reveal'); await settle();
  await click('next'); await until(status, x => x.includes('cue 2 / 2'), 'repeat experiment'); await settle();
  const nextTargets = await targets();
  guest = await connect(nextTargets.find(x => x.url.includes('/index.html?file=') && !sourceIds.has(x.id)));
  await until(() => guest.evaluate('typeof window.switchToProperty'), x => x === 'function', 'reopened editor');
  await guest.evaluate('window.switchToProperty("present_wave_experiment", "code")');
  await until(() => guest.evaluate('window.globalEditor?.getModel()?.getValue()'), x => x?.includes('val frequency = 1.0'), 'restored source');
  await until(() => guest.evaluate('document.querySelector("polyline")?.getAttribute("points")'), Boolean, 'restored wave output');
  assert.notEqual(await guest.evaluate('document.querySelector("polyline").getAttribute("points")'), changedPoints);
  assert.equal(fs.readFileSync(path.join(document, 'present_live_field_code_.kt'), 'utf8'), original);
  await control.call('Emulation.setDeviceMetricsOverride', {width:1000,height:760,deviceScaleFactor:1,mobile:false});
  const controls = await control.call('Page.captureScreenshot', {format:'png'});
  fs.writeFileSync(path.join(output, 'wave-controls.png'), Buffer.from(controls.data, 'base64'));
  await control.call('Emulation.clearDeviceMetricsOverride');
  fs.writeFileSync(path.join(output, 'rehearsal.json'), JSON.stringify({passed:true, checks:['forward/back reveals','live editor source change','explicit source reset','setup rerun','unrelated source preserved'], sourceSha256:createHash('sha256').update(source).digest('hex'), slidesSha256:createHash('sha256').update(fs.readFileSync(path.join(document,'files/talk.slides.json'))).digest('hex'), pictures:['wave-reveal.png','wave-controls.png'].map(file=>({file,sha256:createHash('sha256').update(fs.readFileSync(path.join(output,file))).digest('hex')}))}, null, 2)+'\n');
  console.log('PASS: real Electron reveals, live editing, source reset, setup rerun and repeat lesson');
} finally { for (const socket of sockets) socket.close(); }
