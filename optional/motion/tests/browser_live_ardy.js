(async () => {
// Run with Field's page-eval after launching live_ardy.kt and starting its optional worker.
// Uses the existing viewer's render counter/capture status, never treats pose receipt as a draw.
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
function documents() {
    const found = [document];
    for (let i = 0; i < found.length; i++) {
        for (const frame of found[i].querySelectorAll('iframe')) {
            try { if (frame.contentDocument && !found.includes(frame.contentDocument)) found.push(frame.contentDocument); } catch (_) {}
        }
    }
    return found;
}
function view() {
    for (const doc of documents()) {
        const title = [...doc.querySelectorAll('.panel-title')].map(e => e.textContent).join(' ');
        const revision = title.match(/ardy\.core27.*revision (\d+)/);
        const api = doc.defaultView?.FieldViewer;
        if (revision && api) return {doc, api, revision: Number(revision[1]), title, frames: api.renderStats().frames};
    }
    return null;
}
function button(label) {
    for (const doc of documents()) {
        for (const text of doc.querySelectorAll('.field-label')) {
            if (text.textContent.trim() === label) return text.closest('.field-control')?.querySelector('button');
        }
    }
    throw new Error('Missing control: ' + label);
}
const deadline = performance.now() + 60000;
while (!view() && performance.now() < deadline) await delay(100);
if (!view()) throw new Error('ARDY never produced a view with command revisions');
const first = view();
first.doc.defaultView.frameElement?.scrollIntoView({block: 'center'});
const results = [];
for (const label of ['Run', 'Stop', 'Recoil', 'Run', 'Stop', 'Apply timed route from current root', 'Restrict joint at first editable frame', 'Remove joint targets']) {
    const before = view();
    const control = button(label);
    const started = performance.now();
    const w = control.ownerDocument.defaultView;
    control.dispatchEvent(new w.PointerEvent('pointerdown', {bubbles: true, button: 0, isPrimary: true}));
    control.dispatchEvent(new w.PointerEvent('pointerup', {bubbles: true, button: 0, isPrimary: true}));
    let current;
    while (performance.now() - started < 15000) {
        await delay(10);
        current = view();
        if (current && current.revision > before.revision && current.frames > before.frames && current.api.captureStatus().ready) {
            // Geometry has arrived and a viewer draw has occurred; wait through another paint boundary.
            await new Promise(resolve => current.doc.defaultView.requestAnimationFrame(() => resolve()));
            break;
        }
    }
    if (!current || current.revision <= before.revision || performance.now() - started >= 15000) throw new Error('No rendered command effect: ' + label);
    results.push({control: label, delayMs: performance.now() - started, revision: current.revision});
    await delay(100);
}
const sorted = results.map(r => r.delayMs).sort((a,b) => a-b);
return {ok: true, method: 'browser pointer event to first viewer draw carrying a newer command revision, 10 ms polling plus one animation frame',
    nativeVulkanMeasured: false, results, percentileMethod: "nearest rank", p50Ms: (sorted[Math.floor((sorted.length-1)/2)] + sorted[Math.floor(sorted.length/2)]) / 2, p95Ms: sorted[Math.ceil(sorted.length*.95)-1]};

})()
