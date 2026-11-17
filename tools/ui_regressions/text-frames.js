// Real boxes, property RPC and canvas editor. No model request: completion is opt-in.
const {newBox, newTextFrameBelow, send, sendWithReply} = await import('./afield.mjs');
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const check = (condition, message) => { if (!condition) throw new Error(message); };
const wait = async (fn, message) => {
    for (let i = 0; i < 160; i++) { const result = fn(); if (result) return result; await delay(50); }
    throw new Error(message);
};
// Use the existing preference for a canvas-only regression; text frames do not override it.
await wait(() => typeof window.setAutoDisplayEditor === 'function' && window.globalEditor?.getModel(), 'Main editor and its preference are not ready');
window.setAutoDisplayEditor(false);
const action = window.allActions.builtin.find(a => a.id === 'new-text-frame');
check(action && action.keys === 'shift+n', 'Shift+N is not registered for text frames');
await wait(() => typeof action.canvasKeybinding === 'function', 'Text frame binding was not installed');
check(action.canvasKeybinding(new KeyboardEvent('keydown', {key:'N',code:'KeyN',keyCode:78,which:78,shiftKey:true})), 'Installed text-frame binding does not match Shift+N: ' + JSON.stringify({keys:action.keys, labels:window.fieldKeybindings?.labelsFor?.('new-text-frame')}));
newBox({kind: 'text-frame', name: 'Canvas writing', frame: {x: 80, y: 90, w: 380, h: 180}});
const box = await wait(() => [...document.querySelectorAll('.text-frame-box')].find(b => b.name === 'Canvas writing'), 'Frame missing');
const editor = box.querySelector('.text-frame-editor');
const preview = box.querySelector('.text-frame-preview');
await wait(() => document.activeElement === editor, 'New frame did not focus its Markdown editor');
check(editor.spellcheck && editor.tagName === 'TEXTAREA', 'Editor lost native spellcheck');
await Promise.all(['300 18px', 'italic 300 18px', '18px', 'italic 18px', 'bold 18px', 'bold italic 18px'].map(style => document.fonts.load(`${style} "Field Etoile"`)));
check(document.fonts.check('18px "Field Etoile"') && getComputedStyle(editor).fontFamily.includes('Field Etoile'), 'Customized Etoile webfont is not loaded as the default: ' + JSON.stringify({family: getComputedStyle(editor).fontFamily, variable: getComputedStyle(box).getPropertyValue('--text-frame-font'), loaded: document.fonts.check('18px "Field Etoile"'), faces: [...document.fonts].filter(f => f.family.includes('Etoile')).map(f => ({family:f.family,status:f.status,weight:f.weight,style:f.style}))}));
const source = '# Canvas notes\n\n2026: 0 is for text.\n\nWrite **together**, with [a safe link](https://example.org).\n\n- Ideas\n- Decisions';
editor.value = source;
editor.dispatchEvent(new Event('input', {bubbles: true}));
editor.dispatchEvent(new KeyboardEvent('keydown', {key: 'Escape', bubbles: true, cancelable: true}));
await wait(() => preview.querySelector('h1') && preview.querySelector('strong'), 'Markdown was not formatted');
await delay(600);
check(box.contents === source && editor.hidden, 'Local contents were lost to another editor or failed to commit');
check(!box.textFrame || !JSON.parse(box.textFrame).autocomplete, 'Completion must be opt-in');
// Overset indicator is based on real rendered content and keeps all source available.
send('set-string', {uid: box.uid, property: 'textFrame', value: JSON.stringify({sizing: 'fixed', fontSize: 22})});
await wait(() => box.textFrame?.includes('fixed'), 'Settings did not sync');
const long = '# Fixed text\n\n' + ('A paragraph on the canvas.\n\n').repeat(30);
send('set-string', {uid: box.uid, property: 'contents', value: long});
await wait(() => !box.querySelector('.text-frame-overflow').hidden, 'Overset text lacks an indicator');
check(getComputedStyle(preview).overflow === 'hidden', 'Released source is not clipped');
box.querySelector('.text-frame-overflow').click();
check(editor.value === long && !editor.hidden, 'Overflow editing did not reveal the whole source');
check(getComputedStyle(editor).overflow === 'auto', 'Fixed editor must scroll through all text');
editor.dispatchEvent(new CompositionEvent('compositionstart', {bubbles: true}));
editor.value = 'A local draft still being written.';
editor.dispatchEvent(new Event('input', {bubbles: true}));
// An authoritative agent edit traverses real property sync, without this client's own-echo stamp.
// Hold an IME composition so a pending debounce cannot race the deliberate incoming revision.
const incoming = value => new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('Incoming property edit timed out')), 15000);
    sendWithReply('agent-set-property', {uid: box.uid, property: 'contents', value}, `text-frame-peer-${Math.random()}`, (_message, result) => {
        clearTimeout(timer); if (!result.ok) reject(new Error(JSON.stringify(result))); else resolve(result); return 'remove';
    });
});
await incoming('An incoming shared revision.');
await wait(() => !box.querySelector('.text-frame-conflict').hidden, 'Concurrent edit did not retain a draft');
check(editor.value === 'A local draft still being written.', 'Incoming text destroyed the local draft');
editor.dispatchEvent(new CompositionEvent('compositionend', {bubbles: true}));
[...box.querySelectorAll('.text-frame-conflict button')].find(b => b.textContent === 'Use incoming').click();
check(editor.value === 'An incoming shared revision.', 'Incoming conflict resolution failed');
editor.value = 'A local version deliberately kept.';
editor.dispatchEvent(new Event('input', {bubbles: true}));
await wait(() => box.contents === editor.value, 'Local draft did not synchronize');
await incoming('Another incoming version.');
await wait(() => !box.querySelector('.text-frame-conflict').hidden, 'Second incoming edit did not retain the local version');
[...box.querySelectorAll('.text-frame-conflict button')].find(b => b.textContent === 'Keep my version').click();
check(box.contents === 'A local version deliberately kept.', 'Explicit keep-mine resolution failed');
editor.dispatchEvent(new KeyboardEvent('keydown', {key: 'Escape', bubbles: true, cancelable: true}));
await wait(() => editor.hidden, 'Resolved editor did not close');
// Settings UI writes JSON; growing uses world units and never changes the width.
box.querySelector('.text-frame-controls button:nth-child(2)').click();
const weight = box.querySelector('[name=fontWeight]');
weight.value = '300'; weight.dispatchEvent(new Event('change', {bubbles: true}));
await wait(() => getComputedStyle(preview).fontWeight === '300', 'Light weight did not apply');
const sizing = box.querySelector('[name=sizing]');
sizing.value = 'grow'; sizing.dispatchEvent(new Event('change', {bubbles: true}));
box.querySelector('.text-frame-settings > button').click();
const oldHeight = box.data_h, width = box.data_w;
send('set-string', {uid: box.uid, property: 'contents', value: long});
await wait(() => box.data_h > oldHeight + 200, 'Frame failed to grow for rendered text');
check(box.data_w === width, 'Typing changed the frame width');
// Restore compact copy for the screenshot and create a real graph child at a world-space standoff.
send('set-string', {uid: box.uid, property: 'contents', value: source});
await delay(300);
send('set-rect', {uid: box.uid, property: 'frame', value: {x: 80, y: 90, w: 380, h: 480}});
await wait(() => box.data_h === 480, 'Frame resize did not sync');
newTextFrameBelow(box.uid);
const child = await wait(() => [...document.querySelectorAll('.text-frame-box')].find(b => b !== box), 'Connected frame missing');
await wait(() => child.box_parents?.includes?.(box.uid) || box.box_children?.includes?.(child.uid), 'New frame was not g-connected');
check(child.data_w === box.data_w && child.data_x === box.data_x && child.data_y === box.data_y + box.data_h + 32, 'Below frame does not match source width and standoff');
child.querySelector('.text-frame-editor').value = '## Next thought\n\nConnected notes remain ordinary boxes.';
child.querySelector('.text-frame-editor').dispatchEvent(new Event('input', {bubbles: true}));
child.querySelector('.text-frame-editor').dispatchEvent(new KeyboardEvent('keydown', {key: 'Escape', bubbles: true, cancelable: true}));
await delay(300);
check(getComputedStyle(box).borderTopColor === 'rgba(0, 0, 0, 0)' && getComputedStyle(box, '::before').borderTopWidth === '0px', 'Unselected frame outline is visible');
window.__textFrameTest = {source: box.uid, child: child.uid};
return {ok: true, markdown: true, spellcheck: true, fixedOverflow: true, conflictDraftPreserved: true,
    growth: true, widthPreserved: true, connectedBelow: true, shiftNRegistered: true, noModelRequest: true};
