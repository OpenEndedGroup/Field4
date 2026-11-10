// Walk a presentation from its first view to its last in this checkout's Electron app and capture
// each view from the real composited audience window, figures included.
//
//   node tools/presentation/capture-deck.mjs <CDP endpoint> <Electron PID> <output dir> [step ids...]
//
// The deck must already be presented (run its Presentation box with this Electron attached), and that
// Electron started with --disable-backgrounding-occluded-windows --disable-renderer-backgrounding:
// otherwise a covered audience window gets no animation frames, its figure panels never size to new
// content, and its figure views come out black. Each
// view is reached with Next; before each capture every live figure page is waited for, and the window is captured with macOS `screencapture -l`, choosing the
// "Field audience" window whose owner is <Electron PID> (other Electron apps may have windows of the
// same title). Files are NN-<step id>.png, with -cueK for a view a cue reached; give the deck's step
// ids in order, or the step titles are used. A view whose presenter shows an error stops the walk.
// Each figure page is also saved on its own as NN-<step id>.figure-<box>.png, a CDP screenshot of that
// page alone: evidence of what the figure drew, not a picture of the slide. Look at every window
// capture: a locked or covered screen does not composite figure views, and the slot comes out black.
// macOS only; never composites anything itself. See dev_docs/testing/PRESENTATION_CAPTURES.md.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {execFileSync} from 'node:child_process';

const [endpoint, ownerPid, outputArg, ...stepIds] = process.argv.slice(2);
// CAPTURE_ONLY=id,id captures only those steps' views and passes the others quickly.
const only = new Set((process.env.CAPTURE_ONLY || '').split(',').map(s => s.trim()).filter(Boolean));
assert.ok(endpoint && ownerPid && outputArg, 'Usage: node tools/presentation/capture-deck.mjs <CDP endpoint> <Electron PID> <output dir> [step ids...]');
assert.ok(['localhost', '127.0.0.1'].includes(new URL(endpoint).hostname), 'CDP must be loopback');
assert.equal(process.platform, 'darwin', 'The composited window capture uses macOS screencapture');
const output = path.resolve(outputArg);
fs.mkdirSync(output, {recursive: true});

const lister = path.join(os.tmpdir(), 'field-window-list-owner');
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
// The window list is front to back, so ours is in front when it is the first audience window.
const audienceWindow = () => {
  const rows = execFileSync(lister, {encoding: 'utf8'}).split('\n').map(line => line.split('\t'));
  const row = rows.find(([, pid, name]) => pid === String(ownerPid) && name === 'Field audience');
  assert.ok(row, `No on-screen "Field audience" window owned by PID ${ownerPid}`);
  const front = rows.find(([, , name]) => name === 'Field audience');
  return {id: row[0], inFront: front[0] === row[0]};
};

const sockets = [];
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
async function until(read, predicate, label, ms = 30000) {
  const deadline = Date.now() + ms;
  while (Date.now() < deadline) { const value = await read().catch(() => undefined); if (predicate(value)) return value; await pause(150); }
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
  return {close: () => socket.close(), call, evaluate: expression => new Promise((resolve, reject) => {
    const id = ++sequence;
    const timer = setTimeout(() => { pending.delete(id); reject(Error('CDP timeout')); }, 30000);
    pending.set(id, {resolve: r => r.exceptionDetails ? reject(Error(r.exceptionDetails.text)) : resolve(r.result.value), reject, timer});
    socket.send(JSON.stringify({id, method: 'Runtime.evaluate', params: {expression, awaitPromise: true, returnByValue: true}}));
  })};
}
const targets = async () => (await fetch(`${endpoint}/json/list`)).json();

// Every figure page this view shows has placed its box and scaled it.
async function figuresSettled(prefix) {
  const pages = (await targets()).filter(x => x.url.includes('figure=') && x.webSocketDebuggerUrl);
  for (const target of pages) {
    const page = await connect(target);
    try {
      // Placed and scaled, every image decoded, no panel clipping its content, and the scale
      // unchanged for a second and a half: a figure whose box is still compiling or whose panel
      // has not yet grown to a new image is not ready.
      let lastZoom = null, steadySince = Date.now();
      await until(async () => {
        const zoom = await page.evaluate(`(() => { const h = document.getElementById('field-figure-host');
          if (!h?.querySelector('.field-figure-box .outputArea')) return null;
          if ([...h.querySelectorAll('img')].some(i => !i.complete)) return null;
          const clipped = [...h.querySelectorAll('.disclosure:not(.field-figure-hidden) .disclosure-content')]
            .some(c => c.scrollHeight > c.clientHeight + 4 && !c.querySelector('iframe'));
          return clipped ? null : h.style.getPropertyValue('--field-figure-zoom') || null; })()`);
        if (zoom !== lastZoom) { lastZoom = zoom; steadySince = Date.now(); }
        return zoom && Date.now() - steadySince > 1500;
      }, Boolean, `figure ${target.url}`, 60000);
      const box = new URL(target.url).searchParams.get('figure');
      const shot = await page.call('Page.captureScreenshot', {format: 'png'});
      fs.writeFileSync(path.join(output, `${prefix}.figure-${box}.png`), Buffer.from(shot.data, 'base64'));
    } finally { page.close(); }
  }
  return pages.length;
}


try {
  const control = await connect((await until(targets, xs => xs.some(x => x.url.endsWith('/presentation-control.html')), 'presenter'))
    .find(x => x.url.endsWith('/presentation-control.html')));
  const status = () => control.evaluate('document.getElementById("status").textContent');
  const error = () => control.evaluate('document.getElementById("error").textContent');
  const action = name => control.evaluate(`window.fieldPresentation.action(${JSON.stringify(name)}), 1`);
  await action('first');
  await until(status, s => s?.startsWith('1 /'), 'first view');
  const views = [];
  let previous = null;
  for (let n = 1; n < 400; n++) {
    const now = await until(status, s => s && s !== previous, `view ${n}`);
    const [, step, total] = now.match(/^(\d+) \/ (\d+)/) || [];
    const cue = Number((now.match(/cue (\d+) \//) || [])[1] || 0);
    const title = now.split(' · ')[1] || `step-${step}`;
    const name = `${String(n).padStart(2, '0')}-${stepIds[step - 1] || title.toLowerCase().replace(/[^a-z0-9]+/g, '-')}${cue ? `-cue${cue}` : ''}.png`;
    const stepId = stepIds[step - 1] || title;
    if (only.size && !only.has(stepId)) {
      previous = now;
      const skipCues = now.match(/cue (\d+) \/ (\d+)/);
      if (Number(step) === Number(total) && (!skipCues || skipCues[1] === skipCues[2])) break;
      await pause(600);
      await action('next');
      continue;
    }
    // Cues glide controls for up to about three seconds.
    await pause(cue ? 3500 : 1500);
    const figures = await figuresSettled(name.replace(/\.png$/, ''));
    // The window is never raised: raising it takes keyboard focus from whoever is at the machine,
    // and their next keystroke (Space, an arrow) would move this talk on. Launched with
    // --disable-backgrounding-occluded-windows, a covered window still draws its figure views and
    // screencapture -l still sees them.
    await pause(figures ? 1200 : 600);
    const window = audienceWindow();
    assert.equal(await status(), now, `the presenter moved on by itself (someone else is driving this talk) at ${now}`);
    assert.equal(await error(), '', `presenter error at ${now}`);
    execFileSync('screencapture', ['-x', '-o', '-l', window.id, path.join(output, name)]);
    const stillInFront = audienceWindow().inFront;
    views.push({file: name, status: now, figures, inFront: window.inFront && stillInFront});
    console.log(`${name}\t${now}\t${figures} figure(s)`);
    previous = now;
    const cues = now.match(/cue (\d+) \/ (\d+)/);
    if (Number(step) === Number(total) && (!cues || cues[1] === cues[2])) break;
    await action('next');
  }
  fs.writeFileSync(path.join(output, 'views.json'), JSON.stringify(views, null, 2) + '\n');
} finally { for (const socket of sockets) socket.close(); }
