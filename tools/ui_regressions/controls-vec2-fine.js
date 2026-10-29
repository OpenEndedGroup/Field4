// A shift-drag on a vec2 pad does not jump to the pointer and moves ten times finer than the pointer;
// a press on a collapsed pad sends nothing; the value reaches the Kotlin callback.
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let pad = null;
for (let i = 0; i < 80; i++) {
    pad = [...document.querySelectorAll('.field-vec2')].find((p) => p.getBoundingClientRect().width > 0);
    if (pad) break;
    await sleep(250);
}
if (!pad) throw new Error('no laid-out vec2 pad');
const svg = pad.querySelector('.vec2-visual');
const r = svg.getBoundingClientRect();
const xy = () => [parseFloat(pad.dataset.x), parseFloat(pad.dataset.y)];
const down = (x, y, shift) => svg.dispatchEvent(new PointerEvent('pointerdown', { clientX: x, clientY: y, shiftKey: shift, bubbles: true, cancelable: true, pointerId: 7 }));
const move = (x, y, shift) => window.dispatchEvent(new PointerEvent('pointermove', { clientX: x, clientY: y, shiftKey: shift, bubbles: true, pointerId: 7 }));
const up = () => window.dispatchEvent(new PointerEvent('pointerup', { bubbles: true, pointerId: 7 }));
// Shift-press near the top-left corner: the handle must stay at the centre.
const px = r.x + r.width * 0.2, py = r.y + r.height * 0.2;
down(px, py, true);
const afterPress = xy();
const fine = pad.classList.contains('fine');
// Move a quarter of the pad right: a plain drag would travel 0.5 in x; a fine one 0.05.
for (let i = 1; i <= 10; i++) move(px + r.width * 0.025 * i, py, true);
const afterFine = xy();
up();
await sleep(1500);
const seen = document.getElementById('vec2-fine-seen')?.textContent;
// A collapsed pad: clone hidden from layout must not take a press.
const ghost = pad.cloneNode(true);
ghost.style.display = 'none';
pad.parentElement.appendChild(ghost);
const before = [ghost.dataset.x, ghost.dataset.y];
ghost.querySelector('.vec2-visual').dispatchEvent(new PointerEvent('pointerdown', { clientX: 0, clientY: 0, bubbles: true, cancelable: true }));
up();
const ghostAfter = [ghost.dataset.x, ghost.dataset.y];
ghost.remove();
const result = { afterPress, fine, afterFine, seen, before, ghostAfter };
if (Math.abs(afterPress[0]) > 1e-9 || Math.abs(afterPress[1]) > 1e-9) throw new Error('shift-press jumped the handle: ' + JSON.stringify(result));
if (!fine) throw new Error('the pad did not show fine mode');
// The pad's drawn area is inside the SVG padding, so travel is a little over 0.05.
if (!(afterFine[0] > 0.03 && afterFine[0] < 0.09)) throw new Error('fine drag did not move a tenth: ' + JSON.stringify(result));
if (Math.abs(afterFine[1]) > 1e-9) throw new Error('a horizontal fine drag moved y: ' + JSON.stringify(result));
const xs = (seen || '').trim().split(/\s+/).map(Number);
if (!xs.length || Math.abs(xs[xs.length - 1] - afterFine[0]) > 0.002) throw new Error('Kotlin did not end on the released value: ' + JSON.stringify(result));
if (xs.some((v, i) => i > 0 && v < xs[i - 1])) throw new Error('Kotlin received the drag out of order: ' + JSON.stringify(result));
if (JSON.stringify(before) !== JSON.stringify(ghostAfter)) throw new Error('a collapsed pad took a press: ' + JSON.stringify(result));
return { ok: true, ...result };
