const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
let frame;
for (let i = 0; i < 100; i++) {
    frame = [...document.querySelectorAll('iframe')].find(f => {
        try { return f.src.includes('idle-animation') && f.contentWindow.FieldViewer; }
        catch (_) { return false; }
    });
    if (frame) break;
    await delay(100);
}
if (!frame) throw new Error('Missing live animation');
const w = frame.contentWindow, stats = () => w.FieldViewer.renderStats();
// Count every animation-frame callback the viewer schedules, not only the renders it reports.
let rafCallbacks = 0;
const raf = w.requestAnimationFrame.bind(w);
w.requestAnimationFrame = cb => raf(t => { rafCallbacks++; cb(t); });
const before = stats().frames;
await delay(600);
const after = stats().frames;
if (after <= before + 3) throw new Error('Incoming geometry did not redraw: ' + JSON.stringify({before, after}));
// The box stops sending after a bounded run; the viewer must then render nothing at all.
const deadline = Date.now() + 20000;
let last = -1, stable = 0;
while (Date.now() < deadline) {
    await delay(100);
    const s = stats();
    if (s.frames === last && !s.pending && !s.dirty) stable++; else stable = 0;
    if (stable >= 10) break;
    last = s.frames;
}
if (stable < 10) throw new Error('Viewer never went idle after the stream stopped: ' + JSON.stringify(stats()));
const quietFrames = stats().frames, quietRaf = rafCallbacks;
await delay(1500);
if (stats().frames !== quietFrames) throw new Error('Idle viewer rendered after geometry stopped: ' + (stats().frames - quietFrames));
if (rafCallbacks !== quietRaf) throw new Error('Idle viewer ran requestAnimationFrame callbacks: ' + (rafCallbacks - quietRaf));
return {ok: true, framesDuringAnimation: after - before, idleFrames: 0, idleRafCallbacks: 0};
