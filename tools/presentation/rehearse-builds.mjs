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
assert.ok(endpoint && documentPath, 'Usage: node tools/presentation/rehearse-builds.mjs http://127.0.0.1:29482 /tmp/workspace/docs-talk');
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
const stylesheet = path.join(document, 'files/presentation.css');
const originalStyle = fs.readFileSync(stylesheet);
try {
  const list = await targets();
  const sourceTarget = list.find(t => t.url.includes('/index.html?file=') && new URL(t.url).searchParams.get('file') === path.basename(document));
  assert.ok(sourceTarget, 'Open this disposable starter in native Electron first');
  const control = await connect(list.find(t => t.url.endsWith('/presentation-control.html')));
  const audience = await connect(list.find(t => t.url.endsWith('/presentation-audience.html')));
  const status = () => control.evaluate('document.getElementById("status").textContent');
  const read = expression => audience.evaluate(`(()=>{const d=document.querySelector('iframe').contentDocument;${expression}})()`);
  const settle = async () => {
    await until(targets, ts => !ts.some(t => t.url.startsWith('data:text/html,<html><style>html,body')), 'crossfade complete');
    await until(() => read('return d.documentElement.dataset.fieldBuilds'), v => v !== 'waiting', 'build released');
    assert.equal(await control.evaluate('document.getElementById("error").textContent'), '');
  };
  const click = id => control.evaluate(`document.getElementById(${JSON.stringify(id)}).click()`);
  const refresh = async () => {
    const before = await read('return d.querySelector("link[data-field-presentation-style]")?.href');
    await click('reload');
    await until(() => read('return d.querySelector("link[data-field-presentation-style]")?.href'), x => !!x && x !== before, 'new stylesheet revision');
    await settle();
  };
  const goto = async title => {
    await control.evaluate(`[...document.querySelectorAll('#slides button.slide')].find(b=>b.dataset.slideTitle===${JSON.stringify(title)}).click()`);
    await until(status, x => x.includes(title) && (!x.includes("cue ") || x.includes("cue 0 /")), title); await settle();
  };
  await goto('Field / present');
  await goto('A figure, fullscreen');
  await click('next');
  await until(status, x => x.includes('cue 1 / 1'), 'layered cue');
  assert.equal(await read('return d.documentElement.dataset.fieldBuilds'), 'waiting', 'fade waits for outgoing transition');
  assert.equal(await read('return getComputedStyle(d.querySelector(".field-fade-in")).opacity'), '0');
  await until(() => read('return d.documentElement.dataset.fieldBuilds'), x => x === 'active', 'fade begins');
  const earlyOpacity = Number(await read('return getComputedStyle(d.querySelector(".field-fade-in")).opacity'));
  assert.ok(earlyOpacity < 1, 'new content must actually animate');
  await until(() => read('return getComputedStyle(d.querySelector(".field-fade-in")).opacity'), x => x === '1', 'fade completes');
  assert.equal(await read('return getComputedStyle(d.querySelector(".field-retained")).opacity'), '0.25');
  assert.equal(await read('return d.querySelector(".field-retained").inert'), true);
  assert.equal(await read('const i=d.querySelector("img");return i.complete && i.naturalWidth>0'), true);
  await capture(audience, 'layered-build.png', 1280, 720);
  await click('previous'); await until(status, x => x.includes('cue 0 / 1'), 'back to base'); await settle();
  assert.equal(await read('return !!d.querySelector(".field-retained")'), false);
  await goto('A figure and an idea'); await click('previous');
  await until(status, x => x.includes('cue 1 / 1'), 'back to completed build'); await settle();
  assert.equal(await read('return d.documentElement.dataset.fieldBuilds'), 'complete');
  assert.equal(await read('return getComputedStyle(d.querySelector(".field-fade-in")).opacity'), '1');
  await refresh();
  assert.equal(await read('return d.documentElement.dataset.fieldBuilds'), 'complete');
  // A real OS/browser reduced-motion preference must defeat the fade, even before release.
  await audience.call('Emulation.setEmulatedMedia', {features:[{name:'prefers-reduced-motion',value:'reduce'}]});
  await click('previous'); await until(status, x => x.includes('cue 0 / 1'), 'reduced motion base'); await settle();
  await click('next'); await until(status, x => x.includes('cue 1 / 1'), 'reduced motion build');
  assert.equal(await read('return getComputedStyle(d.querySelector(".field-fade-in")).opacity'), '1');
  await settle();
  await audience.call('Emulation.setEmulatedMedia', {features:[]});
  await goto('Work in the open');
  for (const [name, background] of [['black','rgb(0, 0, 0)'],['paper','rgb(244, 241, 234)'],['slate','rgb(23, 28, 34)']]) {
    fs.copyFileSync(path.join(document,`files/themes/${name}.css`), stylesheet);
    await refresh();
    await until(() => read('return getComputedStyle(d.body).backgroundColor'), x => x === background, `${name} stylesheet loaded`);
    await settle();
    await goto('Predict a wave');
    assert.equal(await read('return getComputedStyle(d.body).backgroundColor'), background, 'experiment slide shares the theme');
    await goto('Work in the open');
    await capture(audience, `theme-${name}.png`, 1280, 720);
  }
  fs.writeFileSync(path.join(output,'builds.json'),JSON.stringify({passed:true, capturedAt:new Date().toISOString(),
    checks:['fade waits for crossfade','fade has intermediate opacity','retained image loaded at 0.25 opacity','retained layer inert','Back restores base','Back to completed build','Refresh completed build','reduced motion','three standalone themes'],
    inputs:Object.fromEntries(['src/jvmMain/kotlin/afield/ui/slides/Presentation.kt','src/jvmMain/kotlin/afield/ui/slides/AudienceBuilder.kt','src/commonMain/resources/presentation-audience.js','electron/presentation.js','templates/presentation/present_talk_code_.kt',...['black','paper','slate'].map(n=>`templates/presentation/files/themes/${n}.css`)].map(file=>[file,createHash('sha256').update(fs.readFileSync(path.join(root,file))).digest('hex')])),pictures},null,2)+'\n');
  console.log('PASS: native fades, retained image layers, Back, Refresh, reduced motion and three CSS themes');
} finally {
  fs.writeFileSync(stylesheet,originalStyle);
  for (const socket of sockets) socket.close();
}
