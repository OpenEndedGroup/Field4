const {newBox, send} = await import('./afield.mjs');
const wait = async (test, detail) => {
    for (let i = 0; i < 200; i++) {
        if (test()) return;
        await new Promise(resolve => setTimeout(resolve, 30));
    }
    throw new Error(detail);
};
await wait(() => window.globalEditor?.getModel(), 'Editor is not ready');
window.setAutoDisplayEditor(false);
window.moveCanvasCamera(0, 0, 1);
const original = 'Lead before.\n\nA **selected words** remain.\n\nTail.';
newBox({kind: 'text-frame', name: 'Passage source', contents: original, frame: {x: 180, y: 180, w: 460, h: 180}});
const parent = () => [...document.querySelectorAll('.text-frame-box')].find(b => b.name === 'Passage source');
await wait(() => parent()?.querySelector('.text-frame-rich-block:focus'), 'Source frame did not open');
document.activeElement.dispatchEvent(new KeyboardEvent('keydown', {key:'Escape', bubbles:true, cancelable:true}));
const write = value => send('set-string', {uid: parent().uid, property:'contents', value});
await wait(() => parent().contents === original &&
    parent().querySelector('.text-frame-preview:not(.text-frame-rich) strong')?.textContent === 'selected words', 'Fixture did not sync');
const select = element => {
    const range = document.createRange();
    range.selectNodeContents(element);
    getSelection().removeAllRanges();
    getSelection().addRange(range);
};
select(parent().querySelector('.text-frame-preview strong'));
const exact = surface => {
    const anchor = JSON.parse(window.captureTextFrameRun(parent().uid));
    if (anchor?.excerpt !== 'selected words') throw new Error('Wrong ' + surface + ' range: ' + JSON.stringify(anchor));
};
exact('reader');
window.editTextFrame(parent());
const textarea = parent().querySelector('textarea');
await wait(() => document.activeElement === textarea && textarea.value === original, 'Inline Markdown did not focus with the fixture');
const at = textarea.value.indexOf('selected words');
textarea.setSelectionRange(at, at + 'selected words'.length);
exact('inline Markdown');
textarea.dispatchEvent(new KeyboardEvent('keydown', {key:'Escape', bubbles:true, cancelable:true}));
window.writeTextFrame(parent());
const strong = parent().querySelector('.text-frame-rich strong');
strong.closest('.text-frame-rich-block').focus();
select(strong);
exact('visual editor');
window.__passageTest = {
    parent,
    write,
    child: () => [...document.querySelectorAll('.text-frame-box')].find(b => b.name === 'Note'),
    get anchor() { return JSON.parse(this.child()?.textRunAnchor || 'null'); },
};
return {ok:true};
