// Arrow keys nudge a focused vec2 pad by one step per axis (up is +y), shift ×10; Backspace and
// double-click reset to the declared value; the SVG angle label agrees in sign with the θ readout.
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let pad = null;
for (let i = 0; i < 80; i++) {
    pad = [...document.querySelectorAll('.field-vec2')].find((p) => p.getBoundingClientRect().width > 0);
    if (pad) break;
    await sleep(250);
}
if (!pad) throw new Error('no laid-out vec2 pad');
const xy = () => [parseFloat(pad.dataset.x), parseFloat(pad.dataset.y)].map((v) => Math.round(v * 1000) / 1000);
const key = (k, o = {}) => pad.dispatchEvent(new KeyboardEvent('keydown', { key: k, bubbles: true, cancelable: true, ...o }));
if (pad.tabIndex !== 0) throw new Error('the pad is not focusable: tabIndex ' + pad.tabIndex);
pad.focus();
if (document.activeElement !== pad) throw new Error('the pad did not take focus');
const start = xy();
key('ArrowRight'); key('ArrowUp');
const nudged = xy();
key('ArrowLeft', { shiftKey: true });
const coarse = xy();
const label = pad.querySelector('.vec2-angle-label').textContent;
const theta = document.getElementById(pad.dataset.controlId + '-angle').textContent;
key('Backspace');
const reset = xy();
key('ArrowDown');
const svg = pad.querySelector('.vec2-visual');
const r = svg.getBoundingClientRect();
svg.dispatchEvent(new MouseEvent('dblclick', { bubbles: true, cancelable: true, clientX: r.x + 4, clientY: r.y + 4 }));
const dbl = xy();
const result = { start, nudged, coarse, reset, dbl, label, theta };
const near = (a, b) => Math.abs(a[0] - b[0]) < 1e-6 && Math.abs(a[1] - b[1]) < 1e-6;
if (!near(start, [0.2, -0.3])) throw new Error('unexpected start ' + JSON.stringify(result));
if (!near(nudged, [0.25, -0.25])) throw new Error('arrow keys did not nudge one step: ' + JSON.stringify(result));
if (!near(coarse, [-0.25, -0.25])) throw new Error('shift-arrow did not move ten steps: ' + JSON.stringify(result));
if (!near(reset, start) || !near(dbl, start)) throw new Error('reset did not return to the declared value: ' + JSON.stringify(result));
const sign = (t) => Math.sign(parseFloat(String(t).replace(/[^-\d.]/g, '')));
if (sign(label) !== sign(theta)) throw new Error('the angle label and θ readout disagree in sign: ' + JSON.stringify(result));
await sleep(300);
return { ok: true, ...result };
