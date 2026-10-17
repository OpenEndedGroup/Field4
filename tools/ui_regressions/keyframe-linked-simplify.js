// The curve menu's Simplify slider, moved in lane a: b, which shares a's key times, is simplified
// with it to exactly the same times; c, whose times differ, is left alone.
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const lanes = () => [...document.querySelectorAll('iframe')].filter((f) => (f.getAttribute('src') || '').includes('/keyframe'));
for (let i = 0; i < 80 && lanes().length < 3; i++) await sleep(250);
await sleep(2500);
const byName = (name) => lanes().find((f) => f.getAttribute('src').includes('_' + name + '_') || f.getAttribute('src').includes('_' + name + '&'));
const [fa, fb, fc] = ['a', 'b', 'c'].map(byName);
if (!fa || !fb || !fc) throw new Error('expected lanes a, b and c, found ' + lanes().map((f) => f.getAttribute('src')).join(', '));
const keysOf = (f) => [...f.contentDocument.querySelectorAll('.kf-keyframes > g')].map((g) => Math.round(g.getBoundingClientRect().x));
const before = [fa, fb, fc].map((f) => keysOf(f).length);
const d = fa.contentDocument, w = fa.contentWindow;
const hit = [...d.querySelectorAll('rect')].find((r) => r.getAttribute('pointer-events') === 'all');
const box = hit.getBoundingClientRect();
const x = box.x + box.width * 0.5, y = box.y + 5;
hit.dispatchEvent(new w.PointerEvent('pointerdown', { button: 2, clientX: x, clientY: y, bubbles: true }));
w.dispatchEvent(new w.PointerEvent('pointerup', { button: 2, clientX: x, clientY: y, bubbles: true }));
await sleep(300);
const row = [...document.querySelectorAll('.kfm-tool'), ...d.querySelectorAll('.kfm-tool')].find((r) => r.textContent.startsWith('Simplify'));
if (!row) throw new Error('the curve menu has no Simplify slider');
const input = row.querySelector('input');
for (const v of [100, 250, 400]) { input.value = String(v); input.dispatchEvent(new Event('input', { bubbles: true })); await sleep(400); }
const readout = row.querySelector('.kfm-hint').textContent;
input.dispatchEvent(new Event('change', { bubbles: true }));
await sleep(800);
const [ka, kb, kc] = [fa, fb, fc].map(keysOf);
const result = { before, after: [ka.length, kb.length, kc.length], readout };
if (!(ka.length < before[0])) throw new Error('lane a was not simplified: ' + JSON.stringify(result));
if (JSON.stringify(ka) !== JSON.stringify(kb)) throw new Error('lanes a and b no longer share key times: ' + JSON.stringify(result));
if (kc.length !== before[2]) throw new Error('lane c, which shares no times, was simplified: ' + JSON.stringify(result));
if (!readout.includes('2 lanes')) throw new Error('the readout does not say the lanes moved together: ' + readout);
return { ok: true, ...result };
