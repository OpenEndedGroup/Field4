// Capture the explainer pilot's live-figure slides from the real Electron audience window.
//
// A figure is a native view laid over its slot, so a CDP screenshot of the audience page alone
// shows an empty slot. This driver captures the composited window instead, with macOS
// `screencapture -l <window id>`, after bringing the audience forward: a covered window's figure
// views are not composited. It never builds substitute HTML or composites views itself.
//
//   FIELD_ELECTRON_PID=<pid> node tools/presentation/capture-figures.mjs http://127.0.0.1:29771 <document dir> [output]
//
// <document dir> is a disposable copy of dev_docs/agents/transcripts/explainer_sine_pitch.yaml
// replayed into a workspace, with its deck presented in this checkout's Electron
// (dev_docs/testing/PRESENTATION_CAPTURES.md, "Live figures"). macOS only.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {execFileSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import {fileURLToPath} from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const [endpoint, documentPath, outputArg] = process.argv.slice(2);
assert.ok(endpoint && documentPath, 'Usage: node tools/presentation/capture-figures.mjs http://127.0.0.1:29771 <document dir> [output]');
assert.ok(['localhost', '127.0.0.1'].includes(new URL(endpoint).hostname), 'CDP must be loopback');
assert.equal(process.platform, 'darwin', 'The composited window capture uses macOS screencapture');
const deckSource = fs.readFileSync(path.join(fs.realpathSync(documentPath), 'esp_deck_code_.kt'), 'utf8');
const transcript = fs.readFileSync(path.join(root, 'dev_docs/agents/transcripts/explainer_sine_pitch.yaml'), 'utf8');
assert.ok(deckSource.split('\n').filter(line => line.trim()).every(line => transcript.includes(line.trim())),
  'Capture an unchanged replay of the pilot transcript');
const output = path.resolve(outputArg || path.join(os.tmpdir(), 'field-explainer-figures'));
fs.mkdirSync(output, {recursive: true});

// The window list: CGWindowListCopyWindowInfo through a ten-line Swift program, compiled once.
const lister = path.join(os.tmpdir(), 'field-window-list');
if (!fs.existsSync(lister)) {
  const source = `${lister}.swift`;
  fs.writeFileSync(source, `import CoreGraphics
let list = CGWindowListCopyWindowInfo([.optionOnScreenOnly], kCGNullWindowID) as! [[String: Any]]
for w in list {
  print("\\(w[kCGWindowNumber as String] as? Int ?? 0)\\t\\(w[kCGWindowOwnerPID as String] as? Int ?? 0)\\t\\(w[kCGWindowName as String] as? String ?? "")")
}
`);
  execFileSync('swiftc', ['-O', source, '-o', lister]);
}
// Several Electrons may be open (other decks); FIELD_ELECTRON_PID names the one being captured.
const electronPid = process.env.FIELD_ELECTRON_PID;
const windowId = title => {
  const rows = execFileSync(lister, {encoding: 'utf8'}).split('\n').map(line => line.split('\t'));
  const row = rows.find(([, pid, name]) => name === title && (!electronPid || pid === electronPid));
  assert.ok(row, `No on-screen window titled ${title}`);
  return row[0];
};

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
const pictures = [];

try {
  const list = await until(targets, xs => xs.some(x => x.url.endsWith('/presentation-control.html')), 'presenter');
  const control = await connect(list.find(x => x.url.endsWith('/presentation-control.html')));
  const status = () => control.evaluate('document.getElementById("status").textContent');
  const action = (name, id) => control.evaluate(`window.fieldPresentation.action(${JSON.stringify(name)}${id == null ? '' : `, ${JSON.stringify(id)}`}), 1`);
  async function visit(title) {
    await control.evaluate(`[...document.querySelectorAll('#slides button.slide')].find(b=>b.dataset.slideTitle===${JSON.stringify(title)}).click()`);
    await until(status, x => x.includes(title), title);
  }
  async function figureSettled(expectFrequency) {
    const figure = (await until(targets, xs => xs.some(x => x.url.includes('figure=') && x.webSocketDebuggerUrl), 'figure view'))
      .find(x => x.url.includes('figure=') && x.webSocketDebuggerUrl);
    const page = await connect(figure);
    await until(() => page.evaluate(`(() => { const h = document.getElementById('field-figure-host');
      const value = h?.querySelector('.field-slider .field-value')?.textContent || '';
      return !!h?.querySelector('.field-figure-box svg') && h.style.getPropertyValue('--field-figure-zoom') !== '' && value; })()`),
      value => value && Number.parseFloat(value) === expectFrequency, `figure at ${expectFrequency} Hz`);
  }
  async function capture(name) {
    await action('show-audience');
    await until(targets, xs => !xs.some(x => x.url.startsWith('data:text/html,<html><style>html,body')), 'transition finished');
    await pause(2000);
    assert.equal(await control.evaluate('document.getElementById("error").textContent'), '');
    const file = path.join(output, name);
    execFileSync('screencapture', ['-x', '-o', '-l', windowId('Field audience'), file]);
    pictures.push({file: name, sha256: createHash('sha256').update(fs.readFileSync(file)).digest('hex')});
  }
  // Start on prose so the figure view opens fresh.
  await visit('A sine wave and its pitch');
  await pause(1500);
  await visit('How often it repeats');
  await figureSettled(220);
  await capture('figure-slide.png');
  await visit('Twice as often');
  await figureSettled(220);
  await action('next');
  await until(status, x => x.includes('Up an octave'), 'glide cue');
  await figureSettled(440);
  await capture('figure-glide.png');
  const buttons = await control.evaluate('[...document.querySelectorAll("button")].filter(b=>b.offsetParent!==null).map(b=>b.textContent.trim())');
  assert.ok(buttons.includes('Reset: figures'), 'figure slides offer Reset: figures');
  fs.writeFileSync(path.join(output, 'figures.json'), JSON.stringify({passed: true,
    transcriptSha256: createHash('sha256').update(transcript).digest('hex'), pictures}, null, 2) + '\n');
  console.log('PASS: live figures in the real Electron audience window');
} finally { for (const socket of sockets) socket.close(); }
