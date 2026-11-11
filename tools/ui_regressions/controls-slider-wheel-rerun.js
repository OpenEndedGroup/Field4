// The wheel turns a focused slider one step per notch (an unfocused one is left alone, so the canvas
// keeps its scroll), and a drag survives its control being re-rendered mid-gesture.
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const find = () => [...document.querySelectorAll('.field-slider')].find((s) => s.getBoundingClientRect().width > 0);
let s = null;
for (let i = 0; i < 80 && !(s = find()); i++) await sleep(250);
if (!s) throw new Error('no laid-out slider');
const val = (el) => parseFloat((el || find()).dataset.value);
const wheel = (el, dy) => el.querySelector('.field-slider-visual').dispatchEvent(new WheelEvent('wheel', { deltaY: dy, bubbles: true, cancelable: true }));
s.blur();
wheel(s, -100);
const unfocused = val(s);
s.focus();
wheel(s, -100); wheel(s, -100);
const focused = val(s);
wheel(s, 100);
const back = val(s);
const wheelX = (dx) => s.querySelector('.field-slider-visual').dispatchEvent(new WheelEvent('wheel', { deltaX: dx, bubbles: true, cancelable: true }));
for (let i = 0; i < 20; i++) wheelX(1);
const right = val(s);
for (let i = 0; i < 20; i++) wheelX(-1);
const left = val(s);
const unitsBefore = s.querySelector('.field-value').textContent;
s.querySelector('.field-slider-visual').dispatchEvent(new CustomEvent('field-controller-reset', { bubbles: true }));
const reset = val(s);
const unitsAfter = s.querySelector('.field-value').textContent;
const heldRect = s.querySelector('.field-slider-visual').getBoundingClientRect();
const heldX = heldRect.x + heldRect.width * 0.3, heldY = heldRect.y + heldRect.height / 2;
s.querySelector('.field-slider-visual').dispatchEvent(new PointerEvent('pointerdown', { clientX: heldX, clientY: heldY, bubbles: true, cancelable: true, pointerId: 8 }));
s.querySelector('.field-slider-visual').dispatchEvent(new CustomEvent('field-controller-reset', { bubbles: true }));
window.dispatchEvent(new PointerEvent('pointermove', { clientX: heldX, clientY: heldY, bubbles: true, pointerId: 8 }));
const heldReset = val(s);
window.dispatchEvent(new PointerEvent('pointerup', { bubbles: true, pointerId: 8 }));
s.blur();
// Drag from 0.3 to 0.9; at 0.5 the box re-displays the group, replacing the slider's DOM.
const svg = s.querySelector('.field-slider-visual');
const r = svg.getBoundingClientRect();
const at = (t) => r.x + r.width * t;
const y = r.y + r.height / 2;
const original = s;
svg.dispatchEvent(new PointerEvent('pointerdown', { clientX: at(0.3), clientY: y, bubbles: true, cancelable: true, pointerId: 3 }));
for (let t = 0.32; t <= 0.6; t += 0.04) { window.dispatchEvent(new PointerEvent('pointermove', { clientX: at(t), clientY: y, bubbles: true, pointerId: 3 })); await sleep(60); }
for (let i = 0; i < 40 && original.isConnected; i++) await sleep(100);
const replaced = !original.isConnected;
for (let t = 0.64; t <= 0.9; t += 0.04) { window.dispatchEvent(new PointerEvent('pointermove', { clientX: at(t), clientY: y, bubbles: true, pointerId: 3 })); await sleep(60); }
window.dispatchEvent(new PointerEvent('pointerup', { bubbles: true, pointerId: 3 }));
await sleep(800);
const live = find();
const seen = parseFloat(document.getElementById('slider-rerun-seen')?.textContent);
const result = { unfocused, focused, back, right, left, reset, heldReset, unitsBefore, unitsAfter, replaced, liveValue: val(live), seen };
if (unfocused !== 0.2) throw new Error('an unfocused slider took the wheel: ' + JSON.stringify(result));
if (Math.abs(focused - 0.22) > 1e-9 || Math.abs(back - 0.21) > 1e-9) throw new Error('the wheel did not step a focused slider: ' + JSON.stringify(result));
if (!(right > back) || Math.abs(left - back) > 0.011) throw new Error('horizontal motion did not accumulate in the right direction: ' + JSON.stringify(result));
if (reset !== 0.2 || heldReset !== 0.2 || !unitsBefore.endsWith(' m/s²') || unitsAfter !== '0.20 m/s²') throw new Error('reset or unit display failed: ' + JSON.stringify(result));
if (!replaced) throw new Error('the box did not re-render the slider mid-drag (test precondition): ' + JSON.stringify(result));
if (!(val(live) > 0.8)) throw new Error('the drag died when the slider was re-rendered: ' + JSON.stringify(result));
if (Math.abs(seen - val(live)) > 0.011) throw new Error('Kotlin did not receive the post-re-render drag: ' + JSON.stringify(result));
return { ok: true, ...result };
