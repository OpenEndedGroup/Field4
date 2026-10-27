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
assert.ok(endpoint && documentPath, 'Usage: node tools/presentation/rehearse-search.mjs http://127.0.0.1:29482 /tmp/workspace/docs-talk');
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
    // Native windows can be occluded while visibilityState still says visible.
    await page.evaluate('document.fonts.ready.then(()=>new Promise(r=>{setTimeout(r,150);requestAnimationFrame(()=>requestAnimationFrame(r));}))');
    const result = await page.call('Page.captureScreenshot', {format: 'png', captureBeyondViewport: false});
    const bytes = Buffer.from(result.data, 'base64'); fs.writeFileSync(path.join(output, name), bytes);
    pictures.push({file: name, width, height, sha256: createHash('sha256').update(bytes).digest('hex')});
  } finally { await page.call('Emulation.clearDeviceMetricsOverride'); }
}
const slideFile = path.join(document, 'files/talk.slides.json');
const original = fs.readFileSync(slideFile);
assert.deepEqual(original, fs.readFileSync(path.join(root,'templates/presentation/files/talk.slides.json')), 'Use an unchanged disposable starter deck');
let restored = false;
let rerun;
try {
  const list = await targets();
  const source = list.find(t=>t.url.includes('/index.html?file=') && new URL(t.url).searchParams.get('file')===path.basename(document));
  assert.ok(source, 'Open the disposable starter in Electron first');
  const port = new URL(source.url).port;
  const control = await connect(list.find(t=>t.url.endsWith('/presentation-control.html')));
  const audience = await connect(list.find(t=>t.url.endsWith('/presentation-audience.html')));
  rerun = () => {
    const result=JSON.parse(execFileSync('uv',['run','--project','.','--group','backdoor','python','tools/field_session.py','call','--port',port,'--no-reset','--command',`open ${JSON.stringify(path.basename(document))}`,'--command','run present_talk'],{cwd:root,encoding:'utf8'}));
    assert.equal(result.ok,true,'Presentation must execute successfully');
  };
  const status = () => control.evaluate('document.getElementById("status").textContent');
  const body = () => audience.evaluate('document.querySelector("iframe").contentDocument.body.textContent');
  const results = () => control.evaluate('[...document.querySelectorAll("button.slide")].filter(x=>!x.hidden).map(x=>({title:x.dataset.slideTitle,excerpt:x.querySelector(".slide-excerpt").textContent,choice:x.classList.contains("search-choice")}))');
  const key = async (key,code,windowsVirtualKeyCode,modifiers=0) => {
    await control.call('Input.dispatchKeyEvent',{type:'keyDown',key,code,windowsVirtualKeyCode,modifiers});
    await control.call('Input.dispatchKeyEvent',{type:'keyUp',key,code,windowsVirtualKeyCode,modifiers});
  };
  const query = async (text,modifiers=4) => {
    await key('f','KeyF',70,modifiers);
    assert.equal(await control.evaluate('document.activeElement.id'),'slide-search','search shortcut focuses the input');
    await control.call('Input.insertText',{text});
  };
  const settle = async () => {
    await until(targets, ts=>!ts.some(t=>t.url.startsWith('data:text/html,<html><style>html,body')),'crossfade');
    assert.equal(await control.evaluate('document.getElementById("error").textContent'),'');
  };
  const goto = async title => {
    await control.evaluate(`[...document.querySelectorAll('button.slide')].find(x=>x.dataset.slideTitle===${JSON.stringify(title)}).click()`);
    await until(status,x=>x.includes(title),title); await settle();
  };
  await goto('Field / present');
  const deck = JSON.parse(original);
  for(let i=1;i<=100;i++) {
    const n=String(i).padStart(3,'0');
    deck.slides.push({id:`keyboard-${n}`,title:`Question ${n}`,section:['A lesson we can edit'],
      markdown:`# Case ${n}\n\nTelescope alignment case-${n}. A body-only search phrase.${i===64?' Café predictions.':''}`});
  }
  fs.writeFileSync(slideFile,JSON.stringify(deck,null,2)+'\n'); rerun();
  await until(()=>control.evaluate('document.querySelectorAll("button.slide").length'),n=>n===111,'111-slide outline');
  await goto('Field / present');
  const initialStatus = await status(); const initialBody = await body();
  await control.evaluate('document.activeElement.blur()');
  await key('/','Slash',191);
  assert.equal(await control.evaluate('document.activeElement.id'),'slide-search');
  await control.call('Input.insertText',{text:'telescope'});
  assert.equal((await results()).length,100,'body text finds all 100 added slides');
  assert.equal((await results())[0].title,'Question 001');
  assert.ok((await results())[0].excerpt.includes('Telescope alignment'));
  await key('ArrowDown','ArrowDown',40);
  assert.equal((await results()).find(x=>x.choice).title,'Question 002');
  assert.equal(await status(),initialStatus); assert.equal(await body(),initialBody,'typing and choosing must leave the audience alone');
  await key('Enter','Enter',13); await until(status,x=>x.includes('Question 002'),'keyboard jump'); await settle();
  assert.equal(await control.evaluate('document.getElementById("slide-search").value'),'telescope','query stays ready');
  await key('ArrowDown','ArrowDown',40); await key('Enter','Enter',13);
  await until(status,x=>x.includes('Question 003'),'repeat keyboard jump'); await settle();
  await query('cafe'); assert.equal((await results()).length,1);
  assert.equal((await results())[0].title,'Question 064');
  await key('Enter','Enter',13); await until(status,x=>x.includes('Question 064'),'accent-insensitive jump'); await settle();
  await query('ALIGNMENT case-073',2); assert.equal((await results()).length,1);
  await key('Enter','Enter',13); await until(status,x=>x.includes('Question 073'),'multiple words'); await settle();
  const beforeNoMatches = await status();
  await query('no-such-slide-content'); assert.equal((await results()).length,0);
  await key('ArrowDown','ArrowDown',40); await key('Enter','Enter',13);
  assert.equal(await status(),beforeNoMatches,'no results must not navigate');
  deck.slides.find(s=>s.id==='keyboard-073').markdown += '\n\nInterferometer recalibration.';
  fs.writeFileSync(slideFile,JSON.stringify(deck,null,2)+'\n');
  await query('interferometer'); assert.equal((await results()).length,0,'index is cached until Refresh');
  await control.evaluate('document.getElementById("reload").click()');
  await until(results,x=>x.length===1 && x[0].title==='Question 073','refreshed text index'); await settle();
  await key('Escape','Escape',27);
  assert.equal(await control.evaluate('document.getElementById("slide-search").value'),'');
  assert.equal(await control.evaluate('document.activeElement.dataset.slideTitle'),'Question 073');
  await key('ArrowRight','ArrowRight',39); await until(status,x=>x.includes('Question 074'),'normal presentation navigation resumes'); await settle();
  fs.writeFileSync(slideFile,original); rerun();
  await until(()=>control.evaluate('document.querySelectorAll("button.slide").length'),n=>n===11,'starter outline restored'); restored=true;
  await goto('Field / present');
  await query('propagation');
  const found=await results(); assert.equal(found.length,1); assert.equal(found[0].title,'Predict a wave');
  assert.ok(found[0].excerpt.includes('propagation speed'),'reveals are searchable before entering them');
  await capture(control,'search.png',1000,580);
  const inputs=['src/jsMain/resources/iosevka.css', 'src/commonMain/resources/field.css', 'src/commonMain/resources/presentation-control.js','src/commonMain/resources/presentation-control.html','src/commonMain/resources/presentation-search.js','src/jvmMain/kotlin/afield/ui/slides/PresentationSearch.kt','src/jvmMain/kotlin/afield/ui/slides/SlideShow.kt','templates/presentation/present_talk_code_.kt','templates/presentation/files/talk.slides.json'];
  fs.writeFileSync(path.join(output,'search.json'),JSON.stringify({passed:true,capturedAt:new Date().toISOString(),
    checks:['111 slides','slash and Cmd/Ctrl+F','incremental body matching and excerpts','arrow selection without audience changes','Enter and repeat hops','accent-insensitive search','multiple terms','zero matches do not navigate','Refresh rebuilds cached index','Escape resumes normal keyboard navigation','reveal text searchable','starter restored before capture'],
    inputs:Object.fromEntries(inputs.map(file=>[file,createHash('sha256').update(fs.readFileSync(path.join(root,file))).digest('hex')])),pictures},null,2)+'\n');
  console.log('PASS: native keyboard search through 111 slides, body excerpts, repeat jumps, refresh and reveal search');
} finally {
  fs.writeFileSync(slideFile,original);
  if(!restored && rerun) { try { rerun(); } catch(error) { console.error('Could not restore the running starter:',String(error)); } }
  for(const socket of sockets)socket.close();
}
