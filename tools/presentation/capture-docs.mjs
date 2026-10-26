// Reproducible captures of the real native starter, never an HTML reconstruction.
// See dev_docs/testing/PRESENTATION_CAPTURES.md for the disposable-document setup.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {execFileSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import {fileURLToPath} from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const [endpoint, documentPath] = process.argv.slice(2);
assert.ok(endpoint && documentPath, 'Usage: node tools/presentation/capture-docs.mjs http://127.0.0.1:29482 /tmp/workspace/docs-talk');
assert.ok(['localhost', '127.0.0.1'].includes(new URL(endpoint).hostname), 'CDP must be loopback');
const document = fs.realpathSync(documentPath);
const source = fs.readFileSync(path.join(document, 'present_talk_code_.kt'), 'utf8');
assert.equal(source, fs.readFileSync(path.join(root, 'templates/presentation/present_talk_code_.kt'), 'utf8'), 'Capture an unchanged disposable copy of this checkout’s starter');
const output = path.join(root, 'public/docs/assets/presentation_studio');
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
    const timer = setTimeout(() => { pending.delete(id); reject(Error(`CDP timeout: ${method} ${String(params.expression || '').slice(0, 180)}`)); }, 30000);
    pending.set(id, {resolve, reject, timer}); socket.send(JSON.stringify({id, method, params}));
  });
  return {call, evaluate: async expression => {
    const response = await call('Runtime.evaluate', {expression, awaitPromise: true, returnByValue: true});
    if (response.exceptionDetails) throw Error(response.exceptionDetails.exception?.description || response.exceptionDetails.text);
    return response.result.value;
  }};
}
const targets = async () => (await fetch(`${endpoint}/json/list`)).json();
const pictures = [];
async function capture(page, name, width, height) {
  await page.call('Emulation.setDeviceMetricsOverride', {width, height, deviceScaleFactor: 1, mobile: false});
  try {
    await page.evaluate('document.fonts.ready.then(()=>new Promise(r=>document.visibilityState==="visible"?requestAnimationFrame(()=>requestAnimationFrame(r)):setTimeout(r,150)))');
    const result = await page.call('Page.captureScreenshot', {format: 'png', captureBeyondViewport: false});
    const bytes = Buffer.from(result.data, 'base64'); fs.writeFileSync(path.join(output, name), bytes);
    pictures.push({file: name, width, height, sha256: createHash('sha256').update(bytes).digest('hex')});
  } finally { await page.call('Emulation.clearDeviceMetricsOverride'); }
}
try {
  const list = await targets();
  const sourceTarget = list.find(t => t.url.includes('/index.html?file=') && new URL(t.url).searchParams.get('file') === path.basename(document));
  assert.ok(sourceTarget, 'Open this disposable starter document in Electron first');
  const sourcePage = await connect(sourceTarget);
  const control = await connect(list.find(t => t.url.endsWith('/presentation-control.html')));
  const audience = await connect(list.find(t => t.url.endsWith('/presentation-audience.html')));
  assert.equal(await control.evaluate('document.querySelectorAll("#slides button.slide").length'), 9 + JSON.parse(fs.readFileSync(path.join(document, 'files/talk.slides.json'), 'utf8')).slides.length);
  assert.equal(await control.evaluate('document.querySelectorAll("#slides details").length'), 5);
  const settled = async () => {
    await until(targets, ts => !ts.some(t => t.url.startsWith('data:text/html,<html><style>html,body')), 'crossfade settles');
    await until(() => audience.evaluate('!!document.querySelector("iframe")?.contentDocument?.querySelector(".field-slide")'), Boolean, 'audience slide');
  };
  const goto = async title => {
    await control.evaluate(`[...document.querySelectorAll('#slides button.slide')].find(b=>b.dataset.slideTitle===${JSON.stringify(title)}).click()`);
    await until(() => control.evaluate('document.getElementById("status").textContent'), v => v.includes(title), title);
    await settled();
  };
  await goto('A figure, fullscreen');
  assert.equal(await audience.evaluate('(()=>{const d=document.querySelector("iframe").contentDocument;const i=d.querySelector("img");return i.complete&&i.naturalWidth>0&&d.querySelector("figcaption").textContent.includes("editable SVG")})()'), true);
  await capture(audience, 'image-fullscreen.png', 1280, 720);
  await goto('A figure and an idea');
  assert.equal(await audience.evaluate('document.querySelector("iframe").contentDocument.querySelectorAll("ol li").length'), 2);
  await capture(audience, 'image-beside.png', 1280, 720);
  await capture(control, 'outline.png', 1000, 760);
  await sourcePage.evaluate('window.switchToProperty("present_styles", "contents")');
  await until(() => sourcePage.evaluate('window.globalEditor?.getModel()?.getLanguageId()'), v => v === 'css', 'CSS editor');
  await capture(sourcePage, 'stylesheet.png', 1280, 820);
  const tracked = ['src/commonMain/resources/presentation-control.js', 'src/commonMain/resources/presentation-control.html', 'src/commonMain/resources/presentation-search.js',
    'src/commonMain/resources/presentation-audience.js', 'src/jvmMain/kotlin/afield/ui/slides/AudienceBuilder.kt',
    'templates/presentation/present_talk_code_.kt', 'templates/presentation/files/presentation.css', 'templates/presentation/files/talk.slides.json',
    'templates/presentation/present_wave_experiment_code_.kt', 'templates/presentation/macros/run_wave.yaml'];
  fs.writeFileSync(path.join(output, 'capture.json'), JSON.stringify({
    capturedAt: new Date().toISOString(), commit: execFileSync('git', ['rev-parse', 'HEAD'], {cwd: root, encoding: 'utf8'}).trim(),
    inputs: Object.fromEntries(tracked.map(file => [file, createHash('sha256').update(fs.readFileSync(path.join(root, file))).digest('hex')])),
    targets: {source: sourceTarget.url, audience: '/presentation-audience.html', controller: '/presentation-control.html'}, pictures,
  }, null, 2) + '\n');
  console.log(`Captured ${pictures.length} real Electron views in ${output}`);
} finally { sockets.forEach(socket => socket.close()); }
