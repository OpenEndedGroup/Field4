// On the canvas a three viewer keeps its grey background and HUD. Moved into a presentation
// figure's host (what index.html?figure= does), it sits on the deck's background with no HUD.
const delay = ms => new Promise(r => setTimeout(r, ms));
const find = () => [...document.querySelectorAll('iframe')].find(f => {
  try { return new URL(f.src).searchParams.get('uid')?.endsWith('_figuremode') && f.contentDocument?.getElementById('hud'); } catch (e) { return false; }
});
async function viewer() { for (let i = 0; i < 100; i++) { const f = find(); if (f && f.contentDocument.readyState === 'complete') return f; await delay(100); } throw new Error('Missing figure-mode fixture'); }
const look = f => { const d = f.contentDocument, w = f.contentWindow;
  return {figure: d.body.classList.contains('in-figure'), bg: w.getComputedStyle(d.body).backgroundColor,
    wrap: w.getComputedStyle(d.getElementById('canvas-wrap')).backgroundColor,
    hud: w.getComputedStyle(d.getElementById('hud')).display}; };
let frame = await viewer();
const canvas = look(frame);
try {
  if (canvas.figure) throw new Error('A viewer on the canvas is not a figure');
  if (canvas.hud === 'none') throw new Error('The canvas viewer shows its HUD');
  if (canvas.bg === 'rgba(0, 0, 0, 0)') throw new Error('The canvas viewer keeps its own background');
  const box = frame.closest('.box'), home = box.parentElement, next = box.nextSibling;
  const host = document.createElement('div'); host.id = 'field-figure-host';
  document.body.appendChild(host); host.appendChild(box);
  let inFigure;
  for (let i = 0; i < 100; i++) { await delay(100); const f = find(); if (f && f.contentDocument.readyState === 'complete' && (inFigure = look(f)).figure) break; }
  home.insertBefore(box, next); host.remove();
  if (!inFigure?.figure) throw new Error('A viewer inside #field-figure-host knows it is a figure');
  if (inFigure.bg !== 'rgba(0, 0, 0, 0)' || inFigure.wrap !== 'rgba(0, 0, 0, 0)') throw new Error(`Figure background is transparent, got ${inFigure.bg} / ${inFigure.wrap}`);
  if (inFigure.hud !== 'none') throw new Error('Figure hides the HUD');
  for (let i = 0; i < 100; i++) { await delay(100); const f = find(); if (f && f.contentDocument.readyState === 'complete' && !look(f).figure) { frame = f; break; } }
  const back = look(frame);
  if (back.figure || back.bg !== canvas.bg || back.hud !== canvas.hud) throw new Error('Back on the canvas, the viewer looks as it did');
  return {ok: true, canvas, inFigure, back};
} catch (e) { return {ok: false, reason: String(e.message || e), canvas}; }
