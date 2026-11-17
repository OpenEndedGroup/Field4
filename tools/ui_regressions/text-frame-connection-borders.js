// Exercise actual SVG dashes against live graph geometry, including the closed-path seam.
const {send} = await import('./afield.mjs');
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const check = (condition, message) => { if (!condition) throw new Error(message); };
const wait = async (fn, message) => {
    for (let i = 0; i < 160; i++) { if (fn()) return; await delay(50); }
    throw new Error(message);
};
const frames = [...document.querySelectorAll('.text-frame-box')];
const source = frames.find(b => b.uid === window.__textFrameTest.source);
const child = frames.find(b => b.uid === window.__textFrameTest.child);
const saved = [source, child].map(box => ({box, config: box.textFrame,
    frame: {x: box.data_x, y: box.data_y, w: box.data_w, h: box.data_h}}));
const set = (box, property, value) => send('set-string', {uid: box.uid, property, value});
const resize = async (box, frame) => {
    send('set-rect', {uid: box.uid, property: 'frame', value: frame});
    await wait(() => box.data_x === frame.x && box.data_y === frame.y && box.data_w === frame.w && box.data_h === frame.h, 'Geometry did not sync');
    await delay(100);
};
const borders = box => [...document.querySelectorAll('.text-frame-connection-border')].filter(p => p.dataset.boxUid === box.uid);
const config = async (box, length) => {
    set(box, 'textFrame', JSON.stringify({sizing: 'fixed', connectionBorderLength: length}));
    await wait(() => box.getAttribute('data-connection-border-length') === String(length), 'Border setting did not sync');
    await delay(100);
};
const geometry = path => {
    const [length] = path.getAttribute('stroke-dasharray').split(/\s+/).map(Number);
    const center = length / 2 - Number(path.getAttribute('stroke-dashoffset'));
    const perimeter = path.getTotalLength();
    const at = distance => path.getPointAtLength(((center + distance) % perimeter + perimeter) % perimeter);
    return {length, at};
};
await config(source, 24);
await config(child, 24);
await resize(child, {x: 450, y: 350, w: 300, h: 200});
for (const [x, y] of [[70, 90], [1030, 90], [1030, 730], [70, 730]]) {
    await resize(source, {x, y, w: 100, h: 80});
    await wait(() => borders(child).length === 1, 'Child should have one attachment border');
    const path = borders(child)[0];
    const {length, at} = geometry(path);
    check(Math.abs(length - 48) < .01, 'Default attachment is not 24px on either side');
    for (const distance of [-21, 0, 21]) check(path.isPointInStroke(at(distance)), 'Visible border dash has a gap');
    for (const distance of [-28, 28]) check(!path.isPointInStroke(at(distance)), 'Border extends beyond its configured length');
    // Connections keep 20 world units clear of a corner; a 24px arm reaches just past its arc.
    const a = at(-23.5), b = at(23.5);
    check(Math.abs(a.x - b.x) > 4 && Math.abs(a.y - b.y) > 4,
        'Border failed to wrap around a corner: ' + JSON.stringify({x, y, a: {x:a.x,y:a.y}, b: {x:b.x,y:b.y}}));
    const boxRect = child.getBoundingClientRect(), matrix = path.getScreenCTM(), bounds = path.getBBox();
    const topLeft = new DOMPoint(bounds.x, bounds.y).matrixTransform(matrix);
    check(Math.abs(topLeft.x - boxRect.x) < 1 && Math.abs(topLeft.y - boxRect.y) < 1, 'Border does not follow the real frame');
}
for (const zoom of [.5, 2]) {
    window.setCanvasZoom(zoom, 600, 450);
    await delay(250);
    const path = borders(child)[0], {length} = geometry(path), matrix = path.getScreenCTM();
    check(Math.abs(length * Math.hypot(matrix.a, matrix.b) - 48) < .1, 'Border length changes with canvas zoom');
    check(Math.abs(Number(path.getAttribute('stroke-width')) * Math.hypot(matrix.a, matrix.b) - 1) < .01, 'Border stroke changes with canvas zoom');
}
window.setCanvasZoom(1, 600, 450);
await config(child, 40);
check(Math.abs(geometry(borders(child)[0]).length - 80) < .01, 'Custom border length did not render');
await config(child, 0);
check(borders(child).length === 0 && borders(source).length > 0, 'Zero length did not hide just this frame’s border');
await config(child, 24);
set(child, 'box_kind', '');
await wait(() => !child.classList.contains('text-frame-box') && borders(child).length === 0, 'Ordinary box received attachment borders');
set(child, 'box_kind', 'text-frame');
await wait(() => child.classList.contains('text-frame-box'), 'Text frame kind did not restore');
for (const {box, config, frame} of saved) {
    set(box, 'textFrame', config || '{}');
    await resize(box, frame);
}
await delay(300);
return {ok: true, fourRoundedCorners: true, closedPathSeam: true, screenPixelLength: true,
    screenPixelStroke: true, configurable: true, ordinaryBoxesUnchanged: true};
