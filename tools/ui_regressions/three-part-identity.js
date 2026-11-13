const sleep = (ms) => new Promise((r) => setTimeout(r, ms)), checks = [];
const expect = (value, label) => { checks.push({label, passed: !!value}); if (!value) throw new Error(label); };
const frameOf = (suffix, n) => [...document.querySelectorAll('iframe')].find((f) => {
    try { return new URL(f.src, location.href).searchParams.get('uid')?.endsWith(suffix) && f.contentDocument.querySelector('.scene-note')?.textContent === 'frame ' + n && f.contentWindow.FieldViewer?.captureStatus().ready; }
    catch (e) { return false; }
});
async function waitFor(suffix, n) { for (let i = 0; i < 200; i++) { const f = frameOf(suffix, n); if (f) return f; await sleep(100); } throw new Error('no ' + suffix + ' at frame ' + n); }
try {
    const views = await waitFor('_parts', 0), sheet = await waitFor('_sheet', 0);
    const w = views.contentWindow, viewer = w.FieldViewer, api = w.FieldInspector;
    const part = (id) => viewer.parts().find((p) => p.id === id);
    expect(viewer.parts().map((p) => p.id).sort().join(',') === 'a,b,door,roof', 'Parts are found by their stable ids');
    expect(part('door').metadata?.kind === 'swing' && part('door').metadata.pivot[0] === 3.8, 'A part carries its metadata to the browser');
    expect(JSON.stringify(part('door').layer) === '["Walls","Doors"]', 'and its layer path');
    expect(viewer.metadata()?.shift === 0, 'Scene metadata reaches the browser');
    expect(!part('roof').visible && part('a').visible, 'A part that starts hidden is hidden');
    const tree = api.parts();
    const walls = tree.children.find((n) => n.label === 'Walls');
    expect(walls && walls.children.some((n) => n.label === 'Doors'), 'The Parts list folds by layer path');
    viewer.hidePart('b'); viewer.hidePart('roof', false);
    const door = part('door').sources[0];
    api.restore({...api.snapshot(), selectedPart: door});
    const before = api.snapshot().artifactDigest;
    window.sendSliderUpdate(document.querySelector('[data-slider-id]').dataset.sliderId, 0.5);
    await waitFor('_parts', 1);
    expect(api.snapshot().artifactDigest !== before, 'The redraw is a new artifact');
    expect(!part('b').visible && part('roof').visible && part('a').visible, 'Visibility is kept by part id across the new artifact');
    expect(part('door').sources.includes(api.snapshot().selectedPart), 'The selected part is kept by part id');
    expect(viewer.metadata()?.shift === -0.5, 'Metadata follows the redraw');
    // Per-panel styles: part a is red in panel 0 and muted in panel 1.
    const sw = sheet.contentWindow;
    await waitFor('_sheet', 1);
    expect(sw.FieldViewer.partStyle('a', 0)?.emphasis === 'HIGHLIGHT' && sw.FieldViewer.partStyle('a', 1)?.emphasis === 'MUTED', 'Each panel has its own style for a part');
    const src = sw.FieldViewer.parts().find((p) => p.id === 'a').sources[0];
    const image = await new Promise((resolve) => { const i = new sw.Image(); i.onload = () => resolve(i); i.src = sw.FieldViewer.snapshotImage(); });
    const canvas = document.createElement('canvas'); canvas.width = image.width; canvas.height = image.height;
    const g = canvas.getContext('2d'); g.drawImage(image, 0, 0);
    // project() answers in fractions of the canvas.
    const pixel = (panel) => { const p = sw.FieldInspector.project(src, [0, 0, 0.5], panel); return [...g.getImageData(Math.round(p.x * image.width), Math.round(p.y * image.height), 1, 1).data]; };
    const red = pixel(0), muted = pixel(1);
    expect(red[0] > 120 && red[0] > red[1] * 2 && red[0] > red[2] * 2, 'The highlighted part is red in its own panel: ' + red);
    expect(!(muted[0] > muted[1] * 1.5) && muted[3] < 160, 'and muted (grey, see-through) in the other: ' + muted);
    return {ok: true, checks, red, muted};
} catch (e) { return {ok: false, reason: String(e), checks}; }
