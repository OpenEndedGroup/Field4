const {newBox, send} = await import('./afield.mjs');
const wait = async (test, detail) => {
    for (let i = 0; i < 200; i++) {
        if (test()) return;
        await new Promise(resolve => setTimeout(resolve, 30));
    }
    throw new Error(detail);
};
const check = (ok, detail) => { if (!ok) throw new Error(detail); };
const select = box => {
    for (const previous of [...window.selectedList]) if (previous !== box) window.deselectBox(previous);
    window.selectBox(box);
};
const raw = async box => {
    select(box); window.editTextFrame(box);
    const editor = box.querySelector('textarea.text-frame-editor');
    await wait(() => document.activeElement === editor && !editor.hidden, 'Inline Markdown did not focus');
    return editor;
};
const escape = editor => editor.dispatchEvent(new KeyboardEvent('keydown', {key:'Escape', bubbles:true,cancelable:true}));
const event = (target, type, data) => {
    const e = new ClipboardEvent(type, {bubbles:true, cancelable:true, clipboardData:data});
    target.dispatchEvent(e);
    return e.defaultPrevented;
};
const frame = async (name, x, y, contents) => {
    newBox({kind:'text-frame', name, contents, frame:{x,y,w:300,h:170}});
    const find = () => [...document.querySelectorAll('.text-frame-box')].find(b => b.name === name);
    await wait(() => find()?.contents === contents, name + ' did not sync');
    return find();
};

await wait(() => window.globalEditor?.getModel(), 'Editor is not ready');
window.setAutoDisplayEditor(false);
window.moveCanvasCamera(0, 0, 1);
const source = await frame('Source passage', 180, 160, 'From here: tracked phrase. Retain the rest.');
const target = await frame('Reader', 560, 160, 'Live excerpt: ');
const move = await frame('Moved source', 180, 400, 'New home: ');
const editor = await raw(source);
const a = editor.value.indexOf('tracked phrase');
editor.setSelectionRange(a, a + 'tracked phrase'.length);
const clip = new DataTransfer();
check(event(editor, 'copy', clip), 'Field copy did not expose passage data');
const meta = JSON.parse(clip.getData('application/x-field-passage'));
check(meta.quote === 'tracked phrase' && clip.getData('text/plain') === 'tracked phrase', 'Copy changed the ordinary clipboard text');
escape(editor);

const writer = await raw(target);
writer.setSelectionRange(writer.value.length, writer.value.length);
writer.dispatchEvent(new KeyboardEvent('keydown', {key:'V',code:'KeyV',metaKey:true,shiftKey:true,bubbles:true,cancelable:true}));
check(event(writer, 'paste', clip), 'Paste as transclusion did not claim the copied passage');
escape(writer);
const liveLink = () => target.querySelector('.text-frame-preview:not(.text-frame-rich) .text-frame-transclusion');
await wait(() => liveLink()?.textContent === 'tracked phrase', 'The live excerpt did not render');
check(target.contents.includes('field://passage/' + meta.token) && !target.contents.includes('tracked phrase'), 'Pasted Markdown should contain a reference, not copied words');
check(source.contents === 'From here: tracked phrase. Retain the rest.', 'Transclusion altered the source');
select(target);
window.writeTextFrame(target);
await wait(() => target.querySelector('.text-frame-rich .text-frame-transclusion')?.contentEditable === 'false',
    'The excerpt is not an atomic read-only element in the visual editor');
check(target.querySelector('.text-frame-rich-block p')?.textContent.startsWith('Live excerpt: '),
    'Transclusion made the surrounding prose uneditable');
document.activeElement.dispatchEvent(new KeyboardEvent('keydown', {key:'Escape',bubbles:true,cancelable:true}));
await wait(() => liveLink()?.textContent === 'tracked phrase', 'The excerpt did not return to the reader');
liveLink().click();
await wait(() => [...(CSS.highlights.get('field-source-passage') || [])][0]?.toString() === 'tracked phrase',
    'Clicking the excerpt did not highlight the source');
const write = (box, value) => send('set-string', {uid:box.uid, property:'contents', value});
const query = options => window.queryTextFrameLinks(options);
const passageUrl = 'field://passage/' + meta.token;
const mentionUrl = 'field://box/' + source.uid + '?property=contents&line=1';
const references = await frame('Extra references', 750, 400, [
    '[One](' + passageUrl + ')',
    '[Two](' + passageUrl + ')',
    '`[Code example](' + passageUrl + ')`',
    '[Ordinary source link](' + mentionUrl + ')',
].join('\n\n'));
const persisted = JSON.parse(source.textRunSources).find(anchor => anchor.id === meta.token);
newBox({kind:'text-frame', name:'Passage commentary', contents:'A linked note.',
    parentUid:source.uid, textRunAnchor:JSON.stringify(persisted), frame:{x:540,y:420,w:180,h:150}});
const note = () => [...document.querySelectorAll('.text-frame-box')].find(b => b.name === 'Passage commentary');
await wait(() => note()?.textRunAnchor && source.box_children?.includes(note().uid), 'Linked note did not connect');
await wait(() => query({boxUid:source.uid, direction:'backlink'}).length === 5, 'Expected the note, three excerpts and one ordinary link');
const duplicates = query({boxUid:references.uid, direction:'source', kind:'transclusion'});
check(duplicates.map(link => link.occurrence).join(',') === '0,1', 'Backlinks collapsed repeated occurrences or indexed the code example');
check(query({boxUid:references.uid, direction:'source', kind:'box link'})[0]?.line === 1, 'The ordinary box link lost its property or line');
check(query({passageId:meta.token, query:'commentary tracked'}).map(link => link.kind).join(',') === 'note',
    'Link query should find a note by title and attached source text');

const openLinkResult = async (search, label, description) => {
    window.openAnywherePalette();
    const input = document.querySelector('.afield-palette-input');
    await wait(() => document.activeElement === input, 'Open Anything did not focus');
    input.value = search;
    input.dispatchEvent(new Event('input', {bubbles:true}));
    const row = () => [...document.querySelectorAll('.afield-palette-row')].find(r =>
        r.querySelector('.afield-palette-label')?.textContent === label && (!description || r.textContent.includes(description)));
    await wait(row, 'Missing link search result: ' + label);
    row().click();
};
await openLinkResult('backlinks: tracked phrase reader', 'Backlink: Reader');
await wait(() => [...window.selectedList].includes(target) && liveLink()?.classList.contains('text-frame-link-target'),
    'Backlink search did not frame and highlight the referencing box');
await openLinkResult('sources: tracked phrase reader', 'Source: Source passage', 'transclusion');
await wait(() => [...window.selectedList].includes(source) &&
    [...(CSS.highlights.get('field-source-passage') || [])][0]?.toString() === 'tracked phrase',
    'Source search did not frame and highlight the tracked passage');
const noteLink = query({boxUid:note().uid, direction:'source', kind:'note'})[0];
check(window.followTextFrameLink(noteLink, 'backlink'), 'Could not navigate to the linked note');
await wait(() => [...window.selectedList].includes(note()) &&
    note().querySelector('.text-frame-passage.text-frame-link-target'), 'Note backlink did not identify the child');
check(window.followTextFrameLink(noteLink, 'source'), 'Could not navigate from the linked note');
await wait(() => [...window.selectedList].includes(source) &&
    [...(CSS.highlights.get('field-source-passage') || [])][0]?.toString() === 'tracked phrase',
    'The linked note did not navigate back to its passage');

check(window.followTextFrameLink(duplicates[1], 'backlink'), 'Could not follow the second occurrence');
await wait(() => references.querySelectorAll('a.text-frame-transclusion')[1]?.classList.contains('text-frame-link-target'),
    'Backlink navigation did not highlight the second occurrence');

write(source, 'New text before: tracked living phrase. Retain the rest.');
await wait(() => liveLink()?.textContent === 'tracked living phrase', 'The excerpt did not update after source edits');

const cutEditor = await raw(source);
const start = cutEditor.value.indexOf('tracked living phrase');
cutEditor.setSelectionRange(start, start + 'tracked living phrase'.length);
const cut = new DataTransfer();
check(event(cutEditor, 'cut', cut) && cut.getData('application/x-field-text-run-cut'), 'A transclusion-only range was not carried by Cut');
await wait(() => liveLink()?.textContent === 'Passage lost', 'The excerpt should show its unavailable state while cut');
escape(cutEditor);
const newEditor = await raw(move);
newEditor.setSelectionRange(newEditor.value.length, newEditor.value.length);
check(event(newEditor, 'paste', cut), 'The cut passage did not paste');
await wait(() => liveLink()?.textContent === 'tracked living phrase' &&
    JSON.parse(move.textRunSources || '[]').some(anchor => anchor.id === meta.token && !anchor.lost),
    'The same live excerpt did not follow the move');
escape(newEditor);
write(move, 'New home: tracked changed phrase');
await wait(() => liveLink()?.textContent === 'tracked changed phrase', 'Edits at the moved source did not update the excerpt');
liveLink().click();
await wait(() => [...(CSS.highlights.get('field-source-passage') || [])][0]?.toString() === 'tracked changed phrase',
    'Clicking the moved excerpt did not highlight its new source');
await wait(() => query({boxUid:move.uid, direction:'backlink', passageId:meta.token}).length === 4 &&
    !query({boxUid:source.uid, direction:'backlink', passageId:meta.token}).length &&
    move.box_children?.includes(note().uid), 'Moving the passage did not update both note and excerpt backlinks');
write(move, 'New home: ');
await wait(() => liveLink()?.textContent === 'Passage lost' &&
    JSON.parse(move.textRunSources || '[]').some(anchor => anchor.id === meta.token && anchor.lost),
    'Permanently deleting the source did not mark the excerpt lost');
check(query({passageId:meta.token, query:'lost changed'}).length === 4, 'Lost links should remain searchable by their last excerpt');
const mention = query({boxUid:references.uid, direction:'source', kind:'box link', query:'ordinary'})[0];
check(window.followTextFrameLink(mention, 'source'), 'Could not follow the ordinary box link');
await wait(() => window.currentSelectedCode?.[0]?.uid === source.uid && window.currentSelectedProperty === 'contents' &&
    window.globalEditor?.getPosition()?.lineNumber === 1, 'The ordinary source link did not open the linked property and line');
check(window.followTextFrameLink(mention, 'backlink'), 'Could not navigate to the box containing the ordinary link');
await wait(() => [...window.selectedList].includes(references) &&
    references.querySelector('a.text-frame-link-target')?.textContent === 'Ordinary source link',
    'The ordinary backlink did not highlight its Markdown occurrence');
return {ok:true};
