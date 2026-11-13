// Times a datum drag on `three-drag-bench.kt`'s 200-part scene, end to end: from the slider update a drag
// sends to the first animation frame after the view has drawn the geometry it produced. Steps run one at a
// time, so each is the latency of one drag frame with nothing queued behind it.
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const steps = Number(window.__fieldDragBenchSteps || 40), warmup = 5;
let slider = null, frame = null;
for (let i = 0; i < 200 && !(slider && frame); i++) {
    slider = document.querySelector('[data-slider-id]');
    frame = [...document.querySelectorAll('iframe')].find((f) => {
        try { return new URL(f.src).searchParams.get('uid')?.endsWith('_bench') && f.contentDocument.querySelector('.scene-note')?.textContent === 'frame 0'; }
        catch (e) { return false; }
    });
    if (!(slider && frame)) await sleep(100);
}
if (!slider || !frame) throw new Error('drag bench fixture did not appear');
const w = frame.contentWindow, d = frame.contentDocument, id = slider.dataset.sliderId;
const now = (win) => win.performance.timeOrigin + win.performance.now();
const note = () => d.querySelector('.scene-note');
// The first frame after the note reads `frame n`: the viewer requested its redraw before setting the note,
// so this callback runs after that draw, in the same animation frame.
function drawn(n) {
    return new Promise((resolve, reject) => {
        const want = 'frame ' + n;
        const timer = setTimeout(() => { observer.disconnect(); reject(new Error('no ' + want + ' after 10 s')); }, 10000);
        const check = () => {
            if (note()?.textContent !== want) return false;
            observer.disconnect(); clearTimeout(timer);
            w.requestAnimationFrame(() => resolve(now(w)));
            return true;
        };
        const observer = new w.MutationObserver(check);
        observer.observe(d.body, {subtree: true, childList: true, characterData: true});
        check();
    });
}
const samples = [], stats = [];
for (let n = 1; n <= steps + warmup; n++) {
    const began = now(window);
    window.sendSliderUpdate(id, n / (steps + warmup + 1));
    const at = await drawn(n);
    if (n > warmup) { samples.push(at - began); stats.push({...(w.fieldThreeWireStats || {}), began, at}); }
    await sleep(30);
}
const sorted = samples.slice().sort((a, b) => a - b);
const q = (p) => sorted[Math.min(sorted.length - 1, Math.floor(p * sorted.length))];
const result = {
    ok: true, steps, parts: 200,
    p50: q(0.5), p90: q(0.9), max: sorted[sorted.length - 1], mean: samples.reduce((a, b) => a + b, 0) / samples.length,
    samples: samples.map((s) => Math.round(s * 10) / 10),
    fetchedBytes: stats.map((s) => s.fetchedBytes), applyMs: stats.map((s) => Math.round((s.applyMs || 0) * 10) / 10),
    builtSlots: stats.map((s) => s.builtSlots), reusedSlots: stats.map((s) => s.reusedSlots),
    // Where a step's time goes: to the description's arrival (box, server, websocket), to its buffers, to the draw.
    toArrivalMs: stats.map((s) => Math.round((s.receivedAt - s.began) * 10) / 10),
    toBuffersMs: stats.map((s) => Math.round(((s.fetchedAt || s.receivedAt) - s.receivedAt) * 10) / 10),
    toDrawnMs: stats.map((s) => Math.round((s.at - (s.fetchedAt || s.receivedAt)) * 10) / 10),
    parseMs: stats.map((s) => Math.round(s.parseMs * 10) / 10), descriptionBytes: stats[0]?.descriptionBytes,
};
// The acceptance bar: a drag frame on this scene draws within 50 ms, median. `__fieldDragBenchLimitMs = 0` only reports.
const limit = Number(window.__fieldDragBenchLimitMs ?? 50);
if (limit > 0 && result.p50 > limit) return {...result, ok: false, reason: `median ${result.p50.toFixed(1)} ms is over ${limit} ms`};
return result;
