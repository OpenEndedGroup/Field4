// Drags `lines-handles.kt`'s handles. A hand's press on two nearly coincident handles takes the higher priority one;
// a drag by name (FieldLines) takes the named one, at the exact value given, whatever lies under it.
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const checks = [];
const expect = (ok, label, detail) => { checks.push({label, passed: !!ok}); if (!ok) throw new Error(label + (detail ? ': ' + JSON.stringify(detail) : '')); };
let view = null;
for (let i = 0; i < 200 && !view; i++) {
    view = window.FieldLines?.all().find((v) => v.handles().length === 3);
    if (!view) await sleep(100);
}
expect(view, 'the drawing with three handles appears');
const state = () => JSON.parse(document.getElementById('lines-handles-state').textContent);
async function until(test, label) {
    for (let i = 0; i < 100; i++) { const s = state(); if (test(s)) return s; await sleep(50); }
    expect(false, label, state());
}
const names = view.handles().map((h) => h.name).sort();
expect(JSON.stringify(names) === '["high","low","pt"]', 'handles() lists every handle', names);
const vp = view.root.querySelector('.afield-lines-output-viewport');
const pointer = (type, x, y) => vp.dispatchEvent(new PointerEvent(type, {bubbles: true, cancelable: true, pointerId: 1,
    pointerType: 'mouse', isPrimary: true, button: 0, buttons: type === 'pointerup' ? 0 : 1, clientX: x, clientY: y}));
// 1. By hand, exactly on "low": "high" is a fifth of a pixel away and outranks it, so the press takes "high".
const low = view.screen('low'), high = view.screen('high'), mid = view.screen('pt');
expect(Math.abs(high.x - low.x) < 1, 'the two line handles are under a pixel apart on screen', {low, high});
const y = mid.y + 40;
pointer('pointerdown', low.x, y);
await sleep(50);
pointer('pointermove', low.x + 40, y);
await sleep(100);
pointer('pointerup', low.x + 40, y);
let s = await until((s) => s.log.includes('end:high'), 'a press on both line handles takes the higher priority one');
expect(s.low === 30 && s.high > 33, 'only "high" moved, by the pointer\'s travel', s);
// 2. By name: "low" is taken even though "high" (moved off) and nothing else is near; the value is exact.
view.drag('low', 12.345);
s = await until((s) => s.low === 12.345, 'a drag by name puts "low" at exactly 12.345');
expect(s.log.slice(-3).join() === 'start:low,move:low,end:low', 'a drag by name is a press, a move and a release of that handle', s.log);
// 3. By name onto the other: drag "high" to sit 0.001 from "low"; then "low" by name is still "low".
view.drag('high', 12.346);
await until((s) => s.high === 12.346, 'high moves to 12.346');
view.press('low'); view.move('low', 20.5); view.move('low', 20.25); view.release('low');
s = await until((s) => s.low === 20.25 && s.log.at(-1) === 'end:low', 'a stepwise drag by name ends where its last move put it');
expect(s.high === 12.346, 'the nearly coincident handle never moved', s);
// 4. A point handle, by name, to a sub-pixel place.
view.drag('pt', {x: 10.5, y: 5.25});
s = await until((s) => s.pt[0] === 10.5 && s.pt[1] === 5.25, 'a point handle goes to (10.5, 5.25)');
// 5. A press on no handle is still a drag the box hears, holding no handle.
pointer('pointerdown', mid.x + 120, y + 30); await sleep(50); pointer('pointerup', mid.x + 120, y + 30);
s = await until((s) => s.log.at(-1) === 'end:null', 'a press away from every handle reaches onDrag holding none');
return {ok: true, checks, state: s};
