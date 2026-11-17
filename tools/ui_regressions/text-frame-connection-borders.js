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
    await wait(() => box.getAttribute('data-connection-border-length') === String(length ?? 100), 'Border setting did not sync');
    await delay(100);
};
const geometry = path => {
    const [length] = path.getAttribute('stroke-dasharray').split(/\s+/).map(Number);
    const center = length / 2 - Number(path.getAttribute('stroke-dashoffset'));
    const perimeter = path.getTotalLength();
    const at = distance => path.getPointAtLength(((center + distance) % perimeter + perimeter) % perimeter);
    return {length, at};
};
const screenAttachment = path => {
    const p = geometry(path).at(0);
    return new DOMPoint(p.x, p.y).matrixTransform(path.getScreenCTM());
};
const assertCornerRouting = () => {
    const a = source.getBoundingClientRect(), b = child.getBoundingClientRect();
    const fromText = source.classList.contains('text-frame-box'), toText = child.classList.contains('text-frame-box');
    const start = fromText ? {x: a.right - Math.min(10, a.width / 2), y: a.bottom - Math.min(10, a.height / 2)} : {x:a.x+a.width/2,y:a.y+a.height/2};
    const end = toText ? {x: b.left + Math.min(10, b.width / 2), y: b.top + Math.min(10, b.height / 2)} : {x:b.x+b.width/2,y:b.y+b.height/2};
    const trim = (rect, origin, target) => {
        const dx = target.x - origin.x, dy = target.y - origin.y;
        const tx = dx > 0 ? (rect.right - origin.x) / dx : dx < 0 ? (rect.left - origin.x) / dx : Infinity;
        const ty = dy > 0 ? (rect.bottom - origin.y) / dy : dy < 0 ? (rect.top - origin.y) / dy : Infinity;
        const t = Math.min(tx, ty);
        return {x: origin.x + t * dx, y: origin.y + t * dy};
    };
    const checkEndpoint = (box, rect, expected) => {
        const radius = 3 * window.canvasZoom();
        const nearCorner = Math.min(Math.abs(expected.x - rect.left), Math.abs(expected.x - rect.right)) < radius &&
            Math.min(Math.abs(expected.y - rect.top), Math.abs(expected.y - rect.bottom)) < radius;
        const error = Math.min(...borders(box).map(path => {
            const actual = screenAttachment(path);
            return Math.hypot(actual.x - expected.x, actual.y - expected.y);
        }));
        check(error < (nearCorner ? radius + .1 : .1), 'Connection did not trim the 10px inset-corner ray: ' + JSON.stringify({expected, error}));
    };
    if (fromText) checkEndpoint(source, a, trim(a, start, end));
    if (toText) checkEndpoint(child, b, trim(b, end, start));
};
await config(child);
await wait(() => borders(child).length === 1, 'Default attachment border missing');
check(Math.abs(geometry(borders(child)[0]).length - 200) < .01, 'Default attachment is not 100px on either side');
// Use a shorter custom arm to exercise wrapping on the small corner-routing fixtures below.
await config(source, 24);
await config(child, 24);
await resize(child, {x: 450, y: 350, w: 300, h: 200});
let wrapped = false;
for (const [x, y] of [[70, 90], [1030, 90], [1030, 730], [70, 730]]) {
    await resize(source, {x, y, w: 100, h: 80});
    await wait(() => borders(child).length === 1, 'Child should have one attachment border');
    const path = borders(child)[0];
    const {length, at} = geometry(path);
    check(Math.abs(length - 48) < .01, 'Configured attachment is not 24px on either side');
    for (const distance of [-21, 0, 21]) check(path.isPointInStroke(at(distance)), 'Visible border dash has a gap');
    for (const distance of [-28, 28]) check(!path.isPointInStroke(at(distance)), 'Border extends beyond its configured length');
    assertCornerRouting();
    const a = at(-23.5), b = at(23.5);
    wrapped ||= Math.abs(a.x - b.x) > 4 && Math.abs(a.y - b.y) > 4;
    const boxRect = child.getBoundingClientRect(), matrix = path.getScreenCTM(), bounds = path.getBBox();
    const topLeft = new DOMPoint(bounds.x, bounds.y).matrixTransform(matrix);
    check(Math.abs(topLeft.x - boxRect.x) < 1 && Math.abs(topLeft.y - boxRect.y) < 1, 'Border does not follow the real frame');
}
check(wrapped, 'Border failed to wrap around the inset corner attachment');
for (const zoom of [.5, 2]) {
    window.setCanvasZoom(zoom, 600, 450);
    await delay(250);
    const path = borders(child)[0], {length} = geometry(path), matrix = path.getScreenCTM();
    check(Math.abs(length * Math.hypot(matrix.a, matrix.b) - 48) < .1, 'Border length changes with canvas zoom');
    check(Math.abs(Number(path.getAttribute('stroke-width')) * Math.hypot(matrix.a, matrix.b) - 1) < .01, 'Border stroke changes with canvas zoom');
    assertCornerRouting();
}
window.setCanvasZoom(1, 600, 450);
await config(child, 40);
check(Math.abs(geometry(borders(child)[0]).length - 80) < .01, 'Custom border length did not render');
await config(child, 0);
check(borders(child).length === 0 && borders(source).length > 0, 'Zero length did not hide just this frame’s border');
await config(child, 24);
set(child, 'box_kind', '');
await wait(() => !child.classList.contains('text-frame-box') && borders(child).length === 0, 'Ordinary box received attachment borders');
assertCornerRouting();
set(child, 'box_kind', 'text-frame');
await wait(() => child.classList.contains('text-frame-box') && borders(child).length === 1, 'Text frame kind did not restore');
set(source, 'box_kind', '');
await wait(() => !source.classList.contains('text-frame-box') && borders(source).length === 0, 'Ordinary source retained attachment borders');
assertCornerRouting();
set(source, 'box_kind', 'text-frame');
await wait(() => source.classList.contains('text-frame-box') && borders(source).length > 0, 'Source frame kind did not restore');
for (const {box, config, frame} of saved) {
    set(box, 'textFrame', config || '{}');
    await resize(box, frame);
}
// Editing the source earlier in the gesture test can grow it over its original child. Keep the
// final writing example at the same 32-unit standoff that connected creation uses.
await resize(child, {x: source.data_x, y: source.data_y + source.data_h + 32, w: source.data_w, h: child.data_h});
// The connection filter intentionally trails layout changes. Capture the settled live curves.
let previous = '', stable = 0;
for (let i = 0; i < 250 && stable < 3; i++) {
    await delay(100);
    const signature = [...document.querySelectorAll('#connectionsplinelayer path')].map(p => p.getAttribute('d')).join('|');
    stable = signature && signature === previous ? stable + 1 : 0;
    previous = signature;
}
check(stable >= 3, 'Connection animation did not settle after restoring frames');
const routes = [...document.querySelectorAll('#connectionsplinelayer #connections path[fill="none"]')];
check(routes.length >= 3, 'Expected the three connected writing routes');
for (const path of routes) {
    const length = path.getTotalLength(), start = path.getPointAtLength(0), end = path.getPointAtLength(length);
    const straight = Math.hypot(end.x - start.x, end.y - start.y);
    check(length < 2 * straight + 100, 'Corner route takes a large looping detour');
}
return {ok: true, insetCornerRoutingAllDirections: true, roundedCornerWrap: true, closedPathSeam: true, screenPixelLength: true,
    screenPixelStroke: true, configurable: true, ordinaryBoxesUnchanged: true};
