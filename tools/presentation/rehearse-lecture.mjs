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
assert.ok(endpoint && documentPath, 'Usage: node tools/presentation/rehearse-lecture.mjs http://127.0.0.1:29495 /tmp/workspace/lecture-layouts');
assert.ok(['localhost', '127.0.0.1'].includes(new URL(endpoint).hostname), 'CDP must be loopback');
const document = fs.realpathSync(documentPath);
const source = fs.readFileSync(path.join(document, 'present_talk_code_.kt'), 'utf8');
assert.equal(source, fs.readFileSync(path.join(root, 'templates/presentation/files/examples/lecture.kt'), 'utf8'), 'Capture the bundled four-slide Lecture example in a disposable document');
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
    // Native windows can be occluded while visibilityState still says visible.
    await page.evaluate('document.fonts.ready.then(()=>new Promise(r=>{setTimeout(r,150);requestAnimationFrame(()=>requestAnimationFrame(r));}))');
    const result = await page.call('Page.captureScreenshot', {format: 'png', captureBeyondViewport: false});
    const bytes = Buffer.from(result.data, 'base64'); fs.writeFileSync(path.join(output, name), bytes);
    pictures.push({file: name, width: bytes.readUInt32BE(16), height: bytes.readUInt32BE(20), sha256: createHash('sha256').update(bytes).digest('hex')});
  } finally { await page.call('Emulation.clearDeviceMetricsOverride'); }
}
const themed = '.field-image-full > img, .field-image-full > figcaption, .field-image-beside > figure > img, .field-image-beside figcaption, .field-image-text > *, .lecture-banner > *, .lecture-list > h1, .lecture-list li';
const samples = [];
try {
  const list = await targets();
  assert.ok(list.some(t=>t.url.includes('/index.html?file=') && new URL(t.url).searchParams.get('file')===path.basename(document)), 'Open the disposable Lecture document in this Electron instance');
  for (const file of ['themes/lecture.css','examples/lecture.kt','examples/lecture-lines.svg','examples/lecture-comparison.svg']) {
    assert.deepEqual(fs.readFileSync(path.join(document,'files',file)), fs.readFileSync(path.join(root,'templates/presentation/files',file)), `Unchanged bundled asset: ${file}`);
  }
  assert.deepEqual(fs.readFileSync(path.join(output,'lecture.css')),fs.readFileSync(path.join(document,'files/themes/lecture.css')),'Public stylesheet download matches the installed theme');
  const control = await connect(list.find(t=>t.url.endsWith('/presentation-control.html')));
  const audience = await connect(list.find(t=>t.url.endsWith('/presentation-audience.html')));
  const read = code => audience.evaluate(`(async()=>{const d=document.querySelector('iframe').contentDocument;${code}})()`);
  const state = () => control.evaluate('document.getElementById("status").textContent');
  const parts = () => read(`return [...d.querySelectorAll(${JSON.stringify(themed)})].map(e=>{const s=getComputedStyle(e);return {opacity:Number(s.opacity),transform:s.transform,delay:s.animationDelay,name:s.animationName}})`);
  const settled = async () => {
    await until(targets,ts=>!ts.some(t=>t.url.startsWith('data:text/html,<html><style>html,body')),'native crossfade');
    await until(()=>read('return d.documentElement.dataset.fieldBuilds'),v=>v!=='waiting','build release');
    await until(parts,ps=>ps.every(p=>p.opacity===1),'stagger completes');
    await read('await d.fonts.ready;return true');
    assert.equal(await control.evaluate('document.getElementById("error").textContent'),'');
  };
  const click = id => control.evaluate(`document.getElementById(${JSON.stringify(id)}).click()`);
  const goto = async (title,forward=false) => {
    await control.evaluate(`[...document.querySelectorAll('button.slide')].find(b=>b.dataset.slideTitle===${JSON.stringify(title)}).click()`);
    await until(state,s=>s.includes(title),title);
    if (forward) {
      assert.equal(await read('return d.documentElement.dataset.fieldBuilds'),'waiting','Native crossfade holds element animation');
      const waiting = await parts();
      assert.ok(waiting.every(p=>p.opacity>=.78),'Composition stays visible while the native crossfade runs');
      await until(()=>read('return d.documentElement.dataset.fieldBuilds'),v=>v==='active','stagger starts');
      const active=await parts(); samples.push({title,waiting,active});
      assert.ok(active.some(p=>p.opacity<1),'Entry has a real intermediate animation state');
    }
    await settled();
  };
  const bounds = async selector => {
    const rects=await read(`return [...d.querySelectorAll(${JSON.stringify(selector)})].map(e=>{const r=e.getBoundingClientRect();return {text:e.textContent.slice(0,60),left:r.left,top:r.top,right:r.right,bottom:r.bottom}})`);
    assert.ok(rects.length);
    for(const r of rects) assert.ok(r.left>=28 && r.top>=28 && r.right<=1892 && r.bottom<=1052,JSON.stringify(r));
  };
  await until(()=>control.evaluate('document.querySelectorAll("button.slide").length'),n=>n===4,'four-slide Lecture example');
  await goto('A large image');
  assert.equal(await read('const i=d.querySelector("img");return i.complete&&i.naturalWidth>0'),true);
  await bounds('.field-image-full > img, .field-image-full > figcaption');
  assert.equal(await read('return getComputedStyle(d.querySelector("figcaption")).textAlign'),'right');
  await capture(audience,'lecture-image.png',1280,720);
  await goto('A figure and some text',true);
  assert.equal(await read('const i=d.querySelector("img");return i.complete&&i.naturalWidth>0'),true);
  await bounds('.field-image-beside > figure, .field-image-text');
  await capture(audience,'lecture-split.png',1280,720);
  await goto('Mapping',true);
  await bounds('.lecture-banner h1, .lecture-tagline');
  assert.ok(await read('return Math.abs(d.querySelector("h1").getBoundingClientRect().right-d.querySelector(".lecture-tagline").getBoundingClientRect().right)<1'),'Tagline aligns to the title’s right edge');
  await capture(audience,'lecture-banner.png',1280,720);
  await goto('Media art and design',true);
  await bounds('.lecture-list h1, .lecture-list li');
  const listParts=await parts();
  assert.equal(await read('return d.querySelectorAll(".lecture-list li").length'),7);
  assert.ok(Number.parseFloat(listParts.at(-1).delay)>Number.parseFloat(listParts[1].delay),'List items have increasing delays');
  await capture(audience,'lecture-list.png',1280,720);
  await click('previous'); await until(state,s=>s.includes('Mapping'),'Back'); await settled();
  assert.equal(await read('return d.documentElement.dataset.fieldBuilds'),'complete');
  assert.ok((await parts()).every(p=>p.opacity===1&&p.name==='none'),'Back shows completed elements');
  const styleBefore=await read('return d.querySelector("link[data-field-presentation-style]").href');
  await click('reload');
  await until(()=>read('return d.querySelector("link[data-field-presentation-style]").href'),v=>v!==styleBefore,'Refresh loads a new stylesheet');
  await settled();
  assert.ok((await parts()).every(p=>p.opacity===1&&p.name==='none'),'Refresh stays complete');
  await audience.call('Emulation.setEmulatedMedia',{features:[{name:'prefers-reduced-motion',value:'reduce'}]});
  await goto('Media art and design');
  assert.ok((await parts()).every(p=>p.opacity===1&&p.name==='none'),'Reduced motion disables the stagger');
  await audience.call('Emulation.setEmulatedMedia',{features:[]});
  await goto('Mapping');
  const inputs=['src/commonMain/resources/presentation-audience.js','electron/presentation.js','templates/presentation/files/themes/lecture.css','templates/presentation/files/examples/lecture.kt','templates/presentation/files/examples/lecture-lines.svg','templates/presentation/files/examples/lecture-comparison.svg'];
  fs.writeFileSync(path.join(output,'lecture.json'),JSON.stringify({passed:true,capturedAt:new Date().toISOString(),
    checks:['four native layouts','images ready','credits and content stay in frame','right-aligned tagline','crossfade holds entry motion','incoming content stays visible','intermediate opacity','staggered list delays','Back complete','Refresh complete','reduced motion'],
    inputs:Object.fromEntries(inputs.map(file=>[file,createHash('sha256').update(fs.readFileSync(path.join(root,file))).digest('hex')])),samples,pictures},null,2)+'\n');
  console.log('PASS: four real Lecture layouts, crossfade release, stagger, Back, Refresh and reduced motion');
} finally { for(const socket of sockets) socket.close(); }
