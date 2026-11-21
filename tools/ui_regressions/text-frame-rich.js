const {newBox, send} = await import('./afield.mjs');
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const check = (ok, message) => { if (!ok) throw Error(message); };
async function wait(predicate, message) {
    for (let i = 0; i < 160; i++) {
        if (predicate()) return;
        await delay(50);
    }
    throw Error(message);
}
await wait(() => window.globalEditor?.getModel(), 'Property editor is not ready');
window.setAutoDisplayEditor(false);
window.moveCanvasCamera(0, 0, 1);
newBox({kind: 'text-frame', name: 'Visual notes', frame: {x: 140, y: 140, w: 470, h: 360}});
const fixture = () => [...document.querySelectorAll('.text-frame-box')].find(box => box.name === 'Visual notes');
await wait(() => fixture()?.querySelector('.text-frame-rich-block:focus'), 'New frame did not open the visual editor');
const box = fixture();
const close = () => document.activeElement.dispatchEvent(new KeyboardEvent('keydown', {key: 'Escape', bubbles: true, cancelable: true}));
close();
const table = '| Name  | Value |\n| :---  | ---:  |\n| Alpha | 42    |';
const fence = '```kotlin\nval x = 10\n```';
const source = 'Research notes\n\nA visible sentence.\n\n' + table + '\n\n' + fence;
send('set-string', {uid: box.uid, property: 'contents', value: source});
const preview = box.querySelector('.text-frame-preview');
const checkCopyIcon = root => {
    const button = root.querySelector('.ai-chat-copy');
    const icon = button?.querySelector('svg');
    check(button?.textContent.trim() === '' && button.getAttribute('aria-label') === 'Copy' && button.title === 'Copy' &&
        icon && getComputedStyle(icon).width === '14px' && getComputedStyle(icon).display !== 'none',
        `Code copy control should show an icon and keep its accessible name: ${button?.outerHTML}`);
};
await wait(() => preview.querySelector('table') && box.contents === source, 'Markdown fixture did not sync');
checkCopyIcon(preview);
newBox({kind: 'text-frame', name: 'Unstyled notes', frame: {x: 650, y: 140, w: 300, h: 190}});
const otherFrame = () => [...document.querySelectorAll('.text-frame-box')].find(box => box.name === 'Unstyled notes');
await wait(() => otherFrame()?.querySelector('.text-frame-rich-block:focus'), 'Second frame did not mount');
close();
send('set-string', {uid: otherFrame().uid, property: 'contents', value: '# Unstyled heading'});
await wait(() => otherFrame()?.querySelector('h1'), 'Second heading did not sync');
const otherHeading = otherFrame().querySelector('h1');
const initialColors = [otherHeading, document.body].map(node => getComputedStyle(node).color);
const headingColor = 'rgb(182, 227, 197)';
send('set-string', {uid: box.uid, property: 'textFrame', value: `css: |
  h1, h2 { color: #b6e3c5; letter-spacing: 2px; }
  body { color: red !important; }
  h2 { position: fixed; }
`});
await wait(() => box.textFrame.includes('letter-spacing'), 'Frame CSS did not sync');
for (const selected of [...window.selectedList]) window.deselectBox(selected);
window.selectBox(box);
const position = preview.querySelector('p').firstElementChild.getBoundingClientRect();
preview.querySelector('p').dispatchEvent(new MouseEvent('dblclick', {
    clientX: position.left + 3, clientY: position.top + position.height / 2, bubbles: true, cancelable: true
}));
const rich = box.querySelector('.text-frame-rich');
const toolbar = document.querySelector(`.text-frame-rich-toolbar[data-text-frame-uid="${box.uid}"]`);
checkCopyIcon(rich);
const [heading, paragraph] = rich.querySelectorAll('.text-frame-rich-block');
check(document.activeElement === heading && !toolbar.hidden, 'Double-click did not focus the clicked prose');
const activeStyle = label => toolbar.querySelector(`[aria-label="${label}"]`).getAttribute('aria-pressed') === 'true';
check(activeStyle('Paragraph'), 'The toolbar did not highlight the current paragraph style');
check(rich.querySelector('table') && rich.querySelector('pre') && !rich.querySelector('.text-frame-rich-block table'), 'Advanced Markdown became editable');
toolbar.querySelector('[aria-label="Heading 2"]').click();
check(heading.querySelector('h2')?.textContent === 'Research notes', 'Heading toolbar did not format the block');
check(activeStyle('Heading 2') && !activeStyle('Paragraph'), 'Heading highlight did not follow the formatting command');
const styledHeading = getComputedStyle(heading.querySelector('h2'));
await wait(() => styledHeading.color === headingColor && styledHeading.letterSpacing === '2px',
    'Frame CSS did not style the visual heading');
check(styledHeading.position !== 'fixed', 'Frame CSS accepted a layout override');
check([otherHeading, document.body].every((node, index) => getComputedStyle(node).color === initialColors[index]),
    'Frame CSS leaked to another frame or the document');
const select = (node, start, end) => {
    const range = document.createRange();
    range.setStart(node, start); range.setEnd(node, end);
    const selection = window.getSelection();
    selection.removeAllRanges(); selection.addRange(range);
};
const arrow = key => document.activeElement.dispatchEvent(new KeyboardEvent('keydown', {
    key, bubbles: true, cancelable: true
}));
const headingText = heading.querySelector('h2').firstChild;
const proseText = paragraph.querySelector('p').firstChild;
paragraph.focus();
select(proseText, 0, 0);
arrow('ArrowLeft');
check(document.activeElement === heading && getSelection().anchorNode === headingText &&
    getSelection().anchorOffset === headingText.length, 'Left did not enter the end of the preceding paragraph');
arrow('ArrowRight');
check(document.activeElement === paragraph && getSelection().anchorNode === proseText &&
    getSelection().anchorOffset === 0, 'Right did not enter the start of the following paragraph');
select(proseText, 5, 5);
arrow('ArrowUp');
check(document.activeElement === heading && heading.contains(getSelection().anchorNode), 'Up did not move to the preceding paragraph');
arrow('ArrowDown');
check(document.activeElement === paragraph && paragraph.contains(getSelection().anchorNode), 'Down did not move to the following paragraph');
await wait(() => activeStyle('Paragraph') && !activeStyle('Heading 2'), 'Heading highlight did not follow the caret');
select(proseText, 5, 5);
check(arrow('ArrowLeft'), 'The cross-paragraph handler swallowed a normal within-paragraph arrow');
const toolbarStyle = getComputedStyle(toolbar);
check(toolbarStyle.borderTopWidth === '0px' && toolbarStyle.borderRadius === '0px' &&
    toolbarStyle.backgroundColor === 'rgb(0, 0, 0)' && toolbarStyle.flexDirection === 'column' &&
    toolbar.offsetWidth <= 75, 'Toolbar is not a thin vertical black strip');
const front = document.querySelector('.front-connection-layer');
check(toolbar.parentElement === document.body && front?.parentElement === document.body &&
    Number(toolbarStyle.zIndex) > Number(getComputedStyle(front).zIndex), 'Toolbar is behind the connection chrome');
const caretCenter = () => {
    const range = getSelection().getRangeAt(0).cloneRange();
    range.collapse(false);
    const caret = range.getBoundingClientRect();
    return caret.top + caret.height / 2;
};
const aligned = () => {
    const frame = box.getBoundingClientRect(), strip = toolbar.getBoundingClientRect();
    return Math.abs(frame.left - strip.right - 4) < 1 && Math.abs(strip.top + strip.height / 2 - caretCenter()) < 1;
};
// Put the frame well inside the viewport so the toolbar need not clamp to a pane edge.
window.moveCanvasCamera(0, 80, 1);
await wait(aligned, 'Toolbar did not follow the caret along the left edge');
const paragraphToolbarTop = toolbar.getBoundingClientRect().top;
heading.focus();
select(headingText, 2, 2);
await wait(() => aligned() && toolbar.getBoundingClientRect().top < paragraphToolbarTop - 5, 'Toolbar did not follow a caret move');
paragraph.focus();
const gap = (before, after) => after.getBoundingClientRect().top - before.getBoundingClientRect().bottom;
const [, , , h3, b, i, markdown] = toolbar.querySelectorAll('button');
check(gap(h3, b) >= 6 && gap(i, markdown) >= 6, 'Toolbar groups need visible gaps');
select(paragraph.querySelector('p').firstChild, 2, 9);
toolbar.querySelector('[aria-label="Bold"]').click();
const bold = paragraph.querySelector('b,strong');
check(bold?.textContent === 'visible', 'Bold toolbar did not preserve the selection');
select(bold.firstChild, 0, 7);
paragraph.dispatchEvent(new KeyboardEvent('keydown', {key: 'i', metaKey: true, bubbles: true, cancelable: true}));
await wait(() => box.contents.includes('***visible***'), 'Combined formatting did not save as Markdown');
const canvas = document.createElement('canvas');
canvas.width = 100; canvas.height = 36;
const ctx = canvas.getContext('2d');
ctx.fillStyle = '#444'; ctx.fillRect(0, 0, 100, 36);
ctx.fillStyle = '#eee'; ctx.fillText('Field notes', 16, 22);
const blob = await new Promise(resolve => canvas.toBlob(resolve, 'image/png'));
const clipboard = new DataTransfer();
clipboard.items.add(new File([blob], 'notes.png', {type: 'image/png'}));
const last = paragraph.querySelector('p').lastChild;
select(last, last.length, last.length);
paragraph.dispatchEvent(new ClipboardEvent('paste', {clipboardData: clipboard, bubbles: true, cancelable: true}));
await wait(() => box.contents.includes('![Pasted image](data:image/png;base64,'), 'Image paste did not persist');
check(box.contents.startsWith('## Research notes\n\nA ***visible*** sentence.') && box.contents.endsWith(table + '\n\n' + fence), 'Prose editing rewrote the original table or code');
window.toggleNotesMarkdownPreview(window.globalEditor, 'preview');
toolbar.querySelector('button:last-child').click();
await wait(() => window.currentSelectedProperty === 'contents' && window.globalEditor.getModel()?.getValue() === box.contents &&
    window.globalEditor.hasTextFocus() && !window.isNotesMarkdownPreviewActive(),
    'Markdown toolbar did not open the saved source');
check(preview.querySelector('strong em')?.textContent === 'visible' && preview.querySelector('img'), 'Reader lost combined marks or pasted image');
check(getComputedStyle(preview.querySelector('h2')).color === headingColor, 'Reader did not use the same frame CSS');
send('set-string', {uid: box.uid, property: 'textFrame', value: 'css: ""'});
await wait(() => getComputedStyle(preview.querySelector('h2')).color !== headingColor, 'Clearing CSS did not restore the default styling');
const edge = action => send('perform-action', {uid_currentSelection: [box.uid, otherFrame().uid], uid_action: action});
const color = () => getComputedStyle(otherHeading).color;
edge('-connect');
send('set-string', {uid: box.uid, property: 'textFrame', value: 'css: |\n  h1 { color: #b6e3c5; }'});
await wait(() => color() === headingColor, 'Connected frame did not inherit its parent CSS');
send('set-string', {uid: otherFrame().uid, property: 'textFrame', value: 'css: |\n  h1 { color: #aaccff; }'});
await wait(() => color() === 'rgb(170, 204, 255)', 'Local CSS lost an equally specific tie');
send('set-string', {uid: otherFrame().uid, property: 'textFrame', value: 'css: ""'});
await wait(() => color() === headingColor, 'Clearing the local override did not restore the inherited value');
edge('-disconnect');
await wait(() => color() === initialColors[0], 'Disconnected frame retained its parent CSS');
window.writeTextFrame(box);
await wait(() => !toolbar.hidden && toolbar.getBoundingClientRect().width > 0, 'Visual toolbar did not reopen');
return {ok: true};
