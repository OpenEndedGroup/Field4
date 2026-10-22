// Exercise the actual Electron windows. Run against a disposable starter workspace.
import assert from 'node:assert/strict';

const endpoint = process.argv[2] || 'http://127.0.0.1:29482';
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
const targets = async () => (await fetch(`${endpoint}/json/list`)).json();
const connections = [];

async function connect(target) {
  const socket = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => {
    socket.addEventListener('open', resolve, {once: true});
    socket.addEventListener('error', reject, {once: true});
  });
  let sequence = 0;
  const pending = new Map();
  socket.addEventListener('message', event => {
    const message = JSON.parse(event.data);
    const request = pending.get(message.id);
    if (!request) return;
    pending.delete(message.id);
    clearTimeout(request.timer);
    if (message.error) request.reject(new Error(message.error.message));
    else request.resolve(message.result);
  });
  connections.push(socket);
  return async expression => {
    const id = ++sequence;
    const response = await new Promise((resolve, reject) => {
      const timer = setTimeout(() => { pending.delete(id); reject(new Error('CDP request timed out')); }, 30000);
      pending.set(id, {resolve, reject, timer});
      socket.send(JSON.stringify({id, method: 'Runtime.evaluate', params: {
        expression, awaitPromise: true, returnByValue: true,
      }}));
    });
    if (response.exceptionDetails) throw new Error(response.exceptionDetails.exception?.description || response.exceptionDetails.text);
    return response.result.value;
  };
}

async function until(read, predicate, label, timeout = 30000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    const value = await read();
    if (predicate(value)) return value;
    await pause(100);
  }
  throw new Error(`Timed out: ${label}`);
}

try {
  const initial = await targets();
  const controlTarget = initial.find(target => target.url.endsWith('/field-control.html'));
  assert.ok(controlTarget, 'Open the starter presentation in Electron first');
  const control = await connect(controlTarget);
  const click = id => control(`document.getElementById(${JSON.stringify(id)}).click()`);
  const goto = cue => control(`[...document.querySelectorAll('#slides button')].find(button=>button.textContent===${JSON.stringify(cue)}).click()`);
  await until(() => control(`document.getElementById('status').textContent`), value => /· \S+:\S+/.test(value), 'presenter startup');

  await goto('intro:intro');
  await until(() => control(`document.getElementById('status').textContent`), value => value.includes('intro:intro'), 'intro cue');
  await until(targets, list => !list.some(target => target.url.startsWith('data:text/html,<html><style>html,body')), 'intro transition settles', 15000);
  await goto('intro:intro');
  await pause(150);
  assert.equal((await targets()).filter(target => target.url.startsWith('data:text/html,<html><style>html,body')).length, 0,
    'Selecting the current cue must not leave a transition overlay');

  let observedFrames = 0;
  await goto('browser:web');
  const transition = await until(targets, list => list.find(target => target.url.startsWith('data:text/html,<html><style>html,body')), 'cross-fade overlay');
  const overlay = transition.find(target => target.url.startsWith('data:text/html,<html><style>html,body'));
  const inspectOverlay = await connect(overlay);
  // The destination can spend several seconds loading before the fade starts.
  for (let frame = 0; frame < 500; frame++) {
    if (!(await targets()).some(target => target.id === overlay.id)) break;
    try {
      const state = await inspectOverlay(`({opacity:Number(getComputedStyle(document.body).opacity),images:[...document.images].map(image=>({hidden:image.hidden||getComputedStyle(image).display==='none',width:image.naturalWidth}))})`);
      assert.ok(state.images.every(image => image.hidden || image.width > 0), 'Every visible transition image must be decoded');
      assert.ok(Number.isFinite(state.opacity) && state.opacity >= 0 && state.opacity <= 1);
      observedFrames++;
    } catch (error) {
      if ((await targets()).some(target => target.id === overlay.id)) throw error;
    }
    await pause(30);
  }
  assert.ok(observedFrames > 0, 'The prepared transition frame must be inspectable');
  await until(targets, list => !list.some(target => target.id === overlay.id), 'overlay removal', 5000);
  assert.ok((await targets()).some(target => target.url === 'https://example.org/'), 'The website cue must open a native guest');

  const beforeField = new Set((await targets()).map(target => target.id));
  await goto('field:field');
  const withField = await until(targets, list => list.some(target => !beforeField.has(target.id) && target.url.includes('/index.html?file=')), 'Field audience client');
  const field = await connect(withField.find(target => !beforeField.has(target.id) && target.url.includes('/index.html?file=')));
  await until(() => field(`typeof window.macroPaletteRun`), value => value === 'function', 'Field startup');
  await click('start-guide');
  await until(() => field(`window.currentSelectedCode?.map(box=>box.name)`), value => value?.includes('Live Field'), 'guide selection');
  await click('guide-next');
  await until(() => field(`document.body.innerText.includes('Make the next part live')`), Boolean, 'guide next step');
  await click('run-macro');
  await until(() => field(`!!document.querySelector('polyline') && document.querySelector('polyline').getBoundingClientRect().height>0`), Boolean, 'visible macro output');
  await click('show-guide');
  await until(() => field(`document.querySelector('#document-guide')?.hidden === false`), Boolean, 'Markdown notes');
  assert.equal(await control(`document.getElementById('error').textContent`), '');
  console.log('PASS: cue navigation, decoded cross-fade, native webpage, live Field document, interactive guide, macro output and Markdown notes');
} catch (error) {
  console.error(error.stack || error);
  process.exitCode = 1;
} finally {
  for (const socket of connections) socket.close();
}
