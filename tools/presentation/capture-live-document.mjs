// Capture the starter's "A live Field document" step from its actual Electron windows:
// the audience window showing the live Field document, and the presenter controls with
// its macro and document-guide buttons.
// See dev_docs/testing/PRESENTATION_CAPTURES.md for the disposable-document setup.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {fileURLToPath} from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const [endpoint, documentPath] = process.argv.slice(2);
assert.ok(endpoint && documentPath, 'Usage: node tools/presentation/capture-live-document.mjs http://127.0.0.1:29482 /tmp/workspace/docs-talk [output]');
assert.ok(['localhost', '127.0.0.1'].includes(new URL(endpoint).hostname), 'CDP must be loopback');
const document = fs.realpathSync(documentPath);
const source = fs.readFileSync(path.join(document, 'present_talk_code_.kt'), 'utf8');
assert.equal(source, fs.readFileSync(path.join(root, 'templates/presentation/present_talk_code_.kt'), 'utf8'), 'Capture an unchanged disposable copy of this checkout’s starter');
const output = path.resolve(process.argv[4] || '/tmp/field-live-document');
fs.mkdirSync(output, {recursive: true});
const sockets = [];
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
async function until(read, predicate, label, ms = 30000) {
  const deadline = Date.now() + ms;
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
const save = async (page, file) => {
  const png = await page.call('Page.captureScreenshot', {format: 'png'});
  fs.writeFileSync(path.join(output, file), Buffer.from(png.data, 'base64'));
};

try {
  let list = await until(targets, xs => xs.some(x => x.url.endsWith('/presentation-control.html')), 'presenter');
  // Start from another step so the live document opens fresh, even on a rerun.
  const first = await connect(list.find(x => x.url.endsWith('/presentation-control.html')));
  await first.evaluate(`[...document.querySelectorAll('#slides button.slide')].find(b=>b.dataset.slideTitle==='A real webpage').click()`);
  await until(() => first.evaluate('document.getElementById("status").textContent'), x => x.includes('A real webpage'), 'webpage step');
  await pause(1500);
  list = await targets();
  const sourceIds = new Set(list.filter(x => x.url.includes('/index.html?file=')).map(x => x.id));
  const control = await connect(list.find(x => x.url.endsWith('/presentation-control.html')));
  const status = () => control.evaluate('document.getElementById("status").textContent');
  await control.evaluate(`[...document.querySelectorAll('#slides button.slide')].find(b=>b.dataset.slideTitle==='A live Field document').click()`);
  await until(status, x => x.includes('A live Field document'), 'live document step');
  await until(targets, xs => !xs.some(x => x.url.startsWith('data:text/html,<html><style>html,body')), 'transition finished');
  assert.equal(await control.evaluate('document.getElementById("error").textContent'), '');
  const buttons = () => control.evaluate('[...document.querySelectorAll("button")].filter(b=>b.offsetParent!==null).map(b=>b.textContent.trim())');
  await until(buttons, xs => xs.some(x => x.includes('Run reveal_wave')) && xs.some(x => x.includes('Start document guide')), 'macro and guide controls');
  const guests = await until(targets, xs => xs.some(x => x.url.includes('/index.html?file=') && !sourceIds.has(x.id)), 'audience document');
  const guest = await connect(guests.find(x => x.url.includes('/index.html?file=') && !sourceIds.has(x.id)));
  await until(() => guest.evaluate('document.querySelectorAll(".box, [data-box-id], .afield-box").length'), n => n > 0, 'document boxes drawn');
  await pause(1500);
  await save(guest, 'live-document-audience.png');
  await control.call('Emulation.setDeviceMetricsOverride', {width: 1000, height: 760, deviceScaleFactor: 1, mobile: false});
  await pause(300);
  await save(control, 'live-document-controls.png');
  await control.call('Emulation.clearDeviceMetricsOverride');
  const pictures = ['live-document-audience.png', 'live-document-controls.png'];
  fs.writeFileSync(path.join(output, 'live-document.json'), JSON.stringify({passed: true, controls: (await buttons()).filter(x => /reveal_wave|document guide|Document notes/.test(x)), sourceSha256: createHash('sha256').update(source).digest('hex'), pictures: pictures.map(file => ({file, sha256: createHash('sha256').update(fs.readFileSync(path.join(output, file))).digest('hex')}))}, null, 2) + '\n');
  console.log('PASS: real Electron live-document step and its presenter controls');
} finally { for (const socket of sockets) socket.close(); }
