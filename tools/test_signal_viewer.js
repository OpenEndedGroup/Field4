// Run with Field's page-eval after displaying a three-channel, 0..3600 SignalViewer.
// Use screenshot-window --uid <box> --open-outputs first to open the disclosure.
(async () => {
  const assert = (condition, message) => { if (!condition) throw new Error(message); };
  const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
  const documents = [document, ...[...document.querySelectorAll('iframe')].map(frame => frame.contentDocument).filter(Boolean)];
  const root = documents.map(doc => doc.querySelector('.field-signal-viewer')).find(Boolean);
  assert(root, 'No signal viewer output found');
  assert(root.dataset.renderer === 'plotly', 'Not using the house Plotly renderer');
  const win = root.ownerDocument.defaultView, canvas = root.querySelector('.fsv-plot');
  const config = JSON.parse(root.querySelector('.fsv-config').textContent);
  assert(canvas.getBoundingClientRect().width > 100, 'Open the signal output before testing gestures');
  const ready = async () => {
    for (let i = 0; i < 200 && root.getAttribute('aria-busy') !== 'false'; i++) await sleep(50);
    assert(root.getAttribute('aria-busy') === 'false', 'Sampling did not finish');
    assert(!root.querySelector('.fsv-status').textContent.startsWith('Signal'), root.querySelector('.fsv-status').textContent);
  };
  const view = () => ({start: Number(root.dataset.start), end: Number(root.dataset.end)});
  const key = name => canvas.dispatchEvent(new win.KeyboardEvent('keydown', {key: name, bubbles: true, cancelable: true}));
  key('Home'); await ready();
  assert(root.dataset.mode === 'envelope', 'Full hour should use extrema envelopes');
  assert(canvas.data.some(t=>t.fill==='tonexty'), 'Overview must use filled extrema bands');
  assert(canvas.querySelectorAll('.main-svg').length > 0, 'No Plotly SVG rendered');
  const rect = canvas.getBoundingClientRect(), x = Math.floor(rect.left + 52 + (rect.width - 64) * .35);
  const anchor = (x - rect.left - 52) / (rect.width - 64);
  const before = view(), at = before.start + (before.end - before.start) * anchor;
  canvas.dispatchEvent(new win.WheelEvent('wheel', {clientX: x, clientY: rect.top + 40, deltaY: -600, bubbles: true, cancelable: true}));
  const after = view();
  assert(after.end - after.start < before.end - before.start, 'Wheel did not zoom');
  assert(Math.abs(after.start + (after.end - after.start) * anchor - at) < 1e-6, 'Wheel lost pointer anchor');
  for (let i = 0; i < 6; i++) key('+');
  await ready();
  assert(root.dataset.mode === 'waveform', 'Zoom did not resolve waveforms');
  const detail = view(), firstSpacing = Number(root.dataset.spacing);
  key('+'); await ready();
  assert(Number(root.dataset.spacing) < firstSpacing, 'Zoom did not request finer evaluations');
  const panBefore = view();
  canvas.dispatchEvent(new win.WheelEvent('wheel', {deltaY: 100, shiftKey: true, bubbles: true, cancelable: true}));
  assert(view().start > panBefore.start, 'Shift-wheel did not pan');
  key('ArrowLeft'); key('ArrowRight'); await ready();
  // Simulate a late response that ignores cancellation, as an already-buffered network reply can.
  const originalFetch = win.fetch; let count = 0;
  win.fetch = async (...args) => {
    const response = await originalFetch(...args);
    if (++count === 1) await sleep(500);
    return response;
  };
  try {
    key('+'); await sleep(150); key('-'); await ready(); await sleep(600);
    const current = view();
    const points = Math.max(16, Math.min(4096, Math.round(rect.width - 64)));
    assert(Math.abs(Number(root.dataset.spacing) - (current.end - current.start) / (points * 2)) < 1e-8, 'Late response replaced the current window');
  } finally { win.fetch = originalFetch; }
  const endpoint = new URL(config.url, win.document.baseURI);
  endpoint.search = new URLSearchParams({start: 'NaN', end: '10', points: '100'});
  assert((await originalFetch(endpoint)).status === 400, 'Invalid interval was accepted');
  key('Home'); await ready(); key('ArrowLeft'); await ready();
  assert(view().start === config.start && view().end === config.end, 'Full-domain clamp failed');
  // Leave the output at a useful local scale for inspection and a subsequent replacement test.
  for (let i = 0; i < 7; i++) key('+');
  await ready();
  // Hover is the viewer's own readout plus a DOM cursor line, not Plotly hover labels.
  const r2 = canvas.getBoundingClientRect();
  canvas.dispatchEvent(new win.PointerEvent('pointermove', {clientX: r2.left + 52 + (r2.width - 64) / 2, clientY: r2.top + 40, bubbles: true}));
  const readout = root.querySelector('.fsv-readout').textContent;
  assert(/ s /.test(readout) && readout.includes(config.lanes[0].name), 'Hover readout missing: ' + readout);
  assert(root.querySelector('.fsv-cursor').style.display === 'block', 'Hover cursor line missing');
  return {ok: true, pointerAnchoredZoom: true, pan: true, fineResampling: true, staleReplyRejected: true,
    bounds: true, invalidQueryRejected: true, view: view(), detail, mode: root.dataset.mode,
    status: root.querySelector('.fsv-status').textContent};
})()
