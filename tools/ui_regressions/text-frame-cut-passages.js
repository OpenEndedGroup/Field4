const {newBox, send} = await import('./afield.mjs');
const t = window.__passageTest;
const wait = async (test, detail) => {
    for (let i = 0; i < 200; i++) {
        if (test()) return;
        await new Promise(resolve => setTimeout(resolve, 30));
    }
    throw new Error(detail + ': ' + JSON.stringify({anchor: t.anchor, children: t.parent().box_children}));
};
const check = (ok, detail) => { if (!ok) throw new Error(detail); };
const connect = (a, b) => send('perform-action', {uid_action:'-connect', uid_currentSelection:[a.uid,b.uid]});
const select = box => {
    for (const previous of [...window.selectedList]) if (previous !== box) window.deselectBox(previous);
    window.selectBox(box);
};
const raw = async box => {
    select(box);
    window.editTextFrame(box);
    const editor = box.querySelector('textarea.text-frame-editor');
    await wait(() => document.activeElement === editor && !editor.hidden, 'Inline Markdown did not focus');
    return editor;
};
const event = (target, type, data) => {
    const e = new ClipboardEvent(type, {bubbles:true, cancelable:true, clipboardData:data});
    target.dispatchEvent(e);
    return e.defaultPrevented;
};
const clipboardType = 'application/x-field-text-run-cut';
const cut = (target) => {
    const data = new DataTransfer();
    check(event(target, 'cut', data) && data.getData(clipboardType), 'Cut did not publish passage identity');
    return data;
};
const richRange = (host, start, end = start) => {
    const node = host.querySelector('p').firstChild;
    const range = document.createRange();
    range.setStart(node, start);
    range.setEnd(node, end);
    host.focus();
    getSelection().removeAllRanges();
    getSelection().addRange(range);
};
await wait(() => t.anchor && t.parent().box_children?.includes(t.child().uid), 'Initial linked note is missing');
const originalId = t.anchor.id;
const fragment = '\n\nA **selected words** remain.';
const editor = await raw(t.parent());
const at = editor.value.indexOf(fragment);
editor.setSelectionRange(at, at + fragment.length);
const sameFrame = cut(editor);
await wait(() => t.anchor.lost && t.anchor.cut && t.parent().box_children.includes(t.child().uid), 'Cut should leave a pending lost passage');
check(sameFrame.getData('text/plain') === fragment, 'The clipboard should retain the Markdown fragment');
editor.setSelectionRange(editor.value.length, editor.value.length);
check(event(editor, 'paste', sameFrame), 'The same-frame paste did not use the cut');
await wait(() => !t.anchor.lost && t.anchor.id === originalId && t.anchor.sourceUid === t.parent().uid &&
    t.anchor.start === editor.value.lastIndexOf('selected words') && !t.anchor.cut,
    'The passage did not follow the first same-frame paste');

const frame = (name, x, y, contents) => {
    newBox({kind:'text-frame', name, contents, frame:{x,y,w:290,h:170}});
    return () => [...document.querySelectorAll('.text-frame-box')].find(b => b.name === name);
};
const other = frame('Unrelated parent', 700, 380, 'Keep this edge.');
await wait(() => other()?.box_children, 'Other parent did not sync');
connect(other(), t.child());
await wait(() => other().box_children?.includes(t.child().uid), 'Other parent was not connected');
const destination = frame('Destination', 700, 135, 'Home: here.');
await wait(() => destination()?.contents === 'Home: here.', 'Destination did not sync');

select(t.parent());
window.writeTextFrame(t.parent());
const strong = t.parent().querySelector('.text-frame-rich strong');
const host = strong.closest('.text-frame-rich-block');
host.focus();
const selection = document.createRange();
selection.selectNodeContents(strong);
getSelection().removeAllRanges();
getSelection().addRange(selection);
const crossFrame = cut(host);
check(crossFrame.getData('text/plain') === '**selected words**', 'A whole bold span should retain its Markdown');
await wait(() => t.anchor.lost && t.anchor.cut, 'Cut in the visual editor did not mark the passage lost');

select(destination());
window.writeTextFrame(destination());
const dstHost = destination().querySelector('.text-frame-rich-block');
richRange(dstHost, 'Home: '.length, 'Home: here'.length);
check(event(dstHost, 'paste', crossFrame), 'Rich cross-frame paste did not use the cut');
await wait(() => !t.anchor.lost && t.anchor.id === originalId && t.anchor.sourceUid === destination().uid &&
    t.anchor.start === 'Home: **'.length && !t.parent().box_children.includes(t.child().uid) &&
    destination().box_children?.includes(t.child().uid) && other().box_children?.includes(t.child().uid),
    'Cross-frame paste did not move just the passage parent edge');

const replay = await raw(t.parent());
replay.setSelectionRange(replay.value.length, replay.value.length);
check(event(replay, 'paste', crossFrame), 'A replayed paste did not still insert text');
await wait(() => t.parent().contents.endsWith('**selected words**'), 'A replayed paste did not save the text');
check(t.anchor.sourceUid === destination().uid && destination().box_children.includes(t.child().uid) &&
    !t.parent().box_children.includes(t.child().uid), 'Replaying the clipboard moved the note a second time');
t.child().querySelector('.text-frame-passage').click();
await wait(() => [...(CSS.highlights.get('field-source-passage') || [])][0]?.toString() === 'selected words',
    'Jump to source no longer highlighted the moved passage');
return {ok:true};
