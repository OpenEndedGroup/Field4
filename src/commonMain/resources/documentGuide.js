import { sendWithReply, clearReplyHandler } from './afield.mjs';
import { renderFieldMarkdown } from './markdownView.js';
import { boxGuideLink, guideAssetUrl, noteFilePath, noteUid } from './documentGuideModel.js';

let serial = 0;
function rpc(payload) {
    return new Promise((resolve, reject) => {
        const reply = `document-guide-${++serial}`;
        const timeout = setTimeout(() => { clearReplyHandler(reply); reject(new Error('Guide request timed out')); }, 8000);
        sendWithReply('document-guide', payload, reply, (_message, response) => {
            clearTimeout(timeout); resolve({ok:true,readOnly:false,entries:[],...response}); return 'remove';
        });
    });
}
const el = (tag, cls, text) => { const n = document.createElement(tag); if (cls) n.className = cls; if (text !== undefined) n.textContent = text; return n; };
const button = (text, id, action) => { const b = el('button', '', text); b.type = 'button'; if (id) b.id = id; b.addEventListener('click', action); return b; };
let panel, reader, source, editor, fileList, noteList, noteHeading, outline, status, title, conflict, controls, launcher;
let entries = [], current = null, documentKey = '', readOnly = false, visible = false, sourceMode = false;
let epoch = 0, refreshTimer, refreshPending = false, navigation = [], headings = [], suppressEdit = false;
const drafts = new Map();
let saved = {path: '', positions: {}};
function storageKey() { return `field.documentGuide.${documentKey || window.currentDocumentSessionKey || window.currentDocumentFilename || ''}`; }
function message(text) { if (status) status.textContent = text; }
function report(error) { message(error.message || String(error)); }
function dirty(file = current) { return file && file.draft !== file.contents; }
/** A box's `notes`: readable here, editable on the box. */
function isNote(file = current) { return !!file && !!noteUid(file.path); }
function writable(file = current) { return !!file && !readOnly && !isNote(file); }
/** A note's own heading, from the list; the read reply carries the text, not the title. */
function noteTitle(file) { return entries.find(e => e.path === file.path)?.title || noteUid(file.path); }
function remember() {
    if (current && reader && !sourceMode) saved.positions[current.path] = reader.scrollTop;
    if (current) saved.path = current.path;
    if (panel) {
        const r = panel.getBoundingClientRect();
        if (r.width) saved.rect = {left:r.left,top:r.top,width:r.width,height:r.height};
    }
    try { localStorage.setItem(storageKey(), JSON.stringify(saved)); } catch (_) {}
}
function build() {
    if (panel) return;
    const css = el('link'); css.rel = 'stylesheet'; css.href = 'documentGuide.css'; document.head.append(css);
    panel = el('section', 'document-guide'); panel.id = 'document-guide'; panel.hidden = true;
    panel.setAttribute('aria-label', 'Document guide');
    const header = el('header', 'guide-header');
    header.append(el('strong', '', 'Document Guide'));
    title = el('span', 'guide-title'); title.id = 'guide-title'; header.append(title);
    const closeButton = button('×', 'guide-close', close); closeButton.className = 'macro-recorder-icon';
    closeButton.title = 'Close'; closeButton.setAttribute('aria-label', 'Close document guide'); header.append(closeButton);
    const toolbar = el('div', 'guide-toolbar');
    controls = {
        back: button('←', 'guide-back', back),
        read: button('Read', 'guide-read', () => setMode(false)),
        source: button('Source', 'guide-source', () => setMode(true)),
        save: button('Save', 'guide-save', save),
        add: button('Add note', 'guide-add', addNote),
        box: button('Open box', 'guide-open-box', openBox),
        pin: button('Place on canvas', 'guide-pin', pin),
        copy: button('Copy section link', 'guide-copy-link', copySection)
    };
    controls.back.title = 'Previous guide'; controls.back.setAttribute('aria-label', 'Previous guide');
    controls.add.title = 'Create a new Markdown note in this document';
    controls.box.title = 'Select the box these notes belong to';
    toolbar.append(controls.back, controls.read, controls.source, controls.save, controls.add,
        controls.box, controls.copy, controls.pin);
    const body = el('div', 'guide-body');
    const nav = el('nav', 'guide-nav'); nav.setAttribute('aria-label', 'Guides and headings');
    fileList = el('div', 'guide-files'); fileList.id = 'guide-files';
    noteList = el('div', 'guide-files guide-notes'); noteList.id = 'guide-notes';
    noteHeading = el('h2', '', 'Box notes');
    outline = el('div', 'guide-outline'); outline.id = 'guide-outline';
    nav.append(el('h2', '', 'Guides'), fileList, noteHeading, noteList, el('h2', '', 'On this page'), outline);
    const content = el('div', 'guide-content');
    conflict = el('div', 'guide-conflict'); conflict.id = 'guide-conflict'; conflict.hidden = true;
    conflict.append(el('span', '', 'Changed on disk. Your draft is kept.'),
        button('Copy draft', 'guide-copy-draft', copyDraft), button('Reload disk', 'guide-reload', reload));
    reader = el('article', 'guide-reader'); reader.id = 'guide-reader'; reader.tabIndex = 0;
    reader.addEventListener('scroll', () => { remember(); markHeading(); }, {passive:true});
    source = el('div', 'guide-source-editor'); source.id = 'guide-source-editor'; source.hidden = true;
    content.append(conflict, reader, source); body.append(nav, content);
    status = el('footer', 'guide-status'); status.id = 'guide-status'; status.setAttribute('role', 'status');
    panel.append(header, toolbar, body, status); document.body.append(panel);
    panel.addEventListener('keydown', e => {
        if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 's') { e.preventDefault(); save(); }
        if (e.key === 'Escape' && !sourceMode) { e.preventDefault(); close(); }
        e.stopPropagation(); // Panel keystrokes must not become canvas actions.
    });
    installGestures(header);
    updateControls();
}
function updateControls() {
    if (!controls) return;
    controls.back.disabled = !navigation.length;
    controls.read.classList.toggle('guide-active', !sourceMode);
    controls.source.classList.toggle('guide-active', sourceMode);
    controls.save.disabled = !dirty() || !writable() || current?.saving;
    controls.add.disabled = readOnly;
    controls.box.hidden = !isNote();
    controls.pin.disabled = !writable();
    controls.copy.disabled = !current;
    controls.source.disabled = !current;
    conflict.hidden = !current?.conflict;
    if (current) title.textContent = (isNote() ? `${noteTitle(current)} · notes` : current.path) + (dirty() ? ' •' : '');
}
function renderEntries() {
    const label = entry => entry.kind === 'note' ? `${entry.title} · box notes` : entry.path;
    for (const [list, kind] of [[fileList, 'guide'], [noteList, 'note']]) {
        list.replaceChildren();
        for (const entry of entries.filter(e => (e.kind || 'guide') === kind)) {
            const b = button(entry.title, null, () => navigate(entry.path)); b.dataset.path = entry.path;
            b.title = label(entry); b.classList.toggle('guide-active', entry.path === current?.path); list.append(b);
        }
    }
    noteHeading.hidden = noteList.hidden = !noteList.childElementCount;
    if (!fileList.childElementCount) fileList.append(el('p', '', 'Add note writes a .md file here; docs/ and guides/ are read too.'));
}
function markHeading() {
    const top = reader.getBoundingClientRect().top + 24;
    const active = headings.filter(h => h.node.getBoundingClientRect().top <= top).at(-1) || headings[0];
    outline.querySelectorAll('button').forEach(b => b.classList.toggle('guide-active', b.dataset.heading === active?.id));
    return active;
}
function jump(hash) {
    const heading = headings.find(h => h.id === hash);
    if (heading) reader.scrollTop += heading.node.getBoundingClientRect().top - reader.getBoundingClientRect().top - 16;
    else if (hash) message(`Heading “${hash}” is not in this guide`);
    markHeading();
}
function renderReader(hash = null) {
    if (!current) return;
    const position = saved.positions[current.path] || 0;
    // A note lives on a box, not in a directory, so its relative links and images resolve from the
    // document root — the same base `classifyGuideLink` assumes when no path is given.
    const base = isNote() ? 'README.md' : current.path;
    headings = renderFieldMarkdown(reader, current.draft, {documentKey, path:base, onLink:follow});
    outline.replaceChildren();
    for (const h of headings) {
        const b = button(h.title, null, () => { setMode(false); jump(h.id); });
        b.dataset.heading = h.id; b.style.paddingLeft = `${8 + Math.max(0,h.level-1)*10}px`; outline.append(b);
    }
    reader.scrollTop = position;
    if (hash) jump(hash);
    markHeading();
}
function setEditorValue() {
    if (!editor || !current) return;
    suppressEdit = true;
    if (editor.getValue() !== current.draft) editor.setValue(current.draft);
    // Source stays available for a note — reading the Markdown is useful — but not editable here.
    editor.updateOptions({readOnly: !writable()}); suppressEdit = false;
}
function setMode(toSource) {
    if (!current) return;
    remember(); sourceMode = toSource;
    reader.hidden = toSource; source.hidden = !toSource;
    if (toSource && !editor) {
        const monaco = window.globalMonaco;
        if (!monaco) { message('The editor is still loading'); sourceMode = false; source.hidden = true; reader.hidden = false; return; }
        editor = monaco.editor.create(source, {
            value:current.draft, language:'markdown', theme:'vs-dark', automaticLayout:true,
            minimap:{enabled:false}, fontFamily:'Iosevka Web, Iosevka, monospace', fontSize:14,
            wordWrap:'on', scrollBeyondLastLine:false, readOnly:!writable()
        });
        editor.onDidChangeModelContent(() => {
            if (suppressEdit || !current) return;
            current.draft = editor.getValue(); updateControls();
            message(dirty() ? 'Unsaved draft · Save writes to the Markdown file' : 'Saved');
        });
    }
    if (toSource) { setEditorValue(); editor.layout(); editor.focus(); }
    else { renderReader(); reader.focus(); }
    updateControls();
}
async function navigate(path, hash = '', push = true) {
    if (!path) return;
    remember();
    if (current?.path === path) { setMode(false); jump(hash); return; }
    const token = ++epoch; message('Opening guide…');
    try {
        const response = await rpc({action:'read', path});
        if (token !== epoch) return;
        if (!response.ok) throw new Error(response.error);
        if (push && current) navigation.push({path:current.path, top:saved.positions[current.path] || 0});
        documentKey = response.document; readOnly = response.readOnly;
        const cached = drafts.get(path);
        if (cached && dirty(cached)) {
            current = cached;
            current.conflict = response.file.revision !== current.revision;
        } else {
            current = {...response.file, draft:response.file.contents, conflict:false}; drafts.set(path, current);
        }
        saved.path = path; panel.dataset.path = path;
        sourceMode = false; source.hidden = true; reader.hidden = false;
        renderEntries(); renderReader(hash); updateControls();
        message(isNote() ? 'Box notes · Open box to edit them' :
            readOnly ? 'Read-only document' : dirty() ? 'Unsaved draft restored' : 'Markdown file · Changes on disk refresh here');
        remember();
    } catch (error) { if (token === epoch) report(error); }
}
async function back() {
    const previous = navigation.pop(); if (!previous) return;
    saved.positions[previous.path] = previous.top;
    await navigate(previous.path, '', false); updateControls();
}
function follow(link) {
    if (link.kind === 'guide') navigate(link.path, link.hash);
    else if (link.kind === 'box') {
        if (!window.navigateToBoxFromLink?.(link.uid, link.property, link.line, link.line)) message(`Box “${link.uid}” is not in this document`);
        else message(`Opened ${link.property}${link.line ? ` · line ${link.line}` : ''}`);
    } else if (link.kind === 'asset') window.open(guideAssetUrl(documentKey, link.path), '_blank', 'noopener');
}
async function save() {
    if (!dirty() || !writable() || current.saving) return;
    const file = current, contents = file.draft; file.saving = true; updateControls();
    try {
        const response = await rpc({action:'save', path:file.path, revision:file.revision, contents});
        if (!response.ok) { file.conflict = !!response.conflict; throw new Error(response.error); }
        file.contents = response.file.contents; file.revision = response.file.revision; file.conflict = false;
        if (current === file) { message(dirty() ? 'Saved · newer edits are still in your draft' : 'Saved to disk'); if (!sourceMode) renderReader(); }
    } catch (error) { if (current === file) report(error); }
    finally { file.saving = false; updateControls(); }
}
async function reload() {
    if (!current) return;
    // An explicit destructive action, only offered when the user's draft is visibly in conflict.
    if (dirty() && !window.confirm('Replace your unsaved draft with the disk version? Copy draft first if you want to keep it.')) return;
    const file = current;
    try {
        const response = await rpc({action:'read', path:file.path});
        if (!response.ok) throw new Error(response.error);
        if (current !== file) return;
        Object.assign(file, response.file, {draft:response.file.contents, conflict:false});
        setEditorValue(); renderReader(); updateControls(); message('Reloaded disk version');
    } catch (error) { report(error); }
}
async function copyDraft() { try { await navigator.clipboard.writeText(current.draft); message('Copied draft'); } catch (error) { report(error); } }
async function copySection() {
    const h = markHeading();
    // A note has no guide path to link to; its box does, and that link opens the notes themselves.
    const label = h?.title || (isNote() ? noteTitle(current) : current.path);
    const href = isNote() ? boxGuideLink(noteUid(current.path), 'notes')
        : `field://guide/${current.path.split('/').map(encodeURIComponent).join('/')}${h ? '#'+h.id : ''}`;
    try { await navigator.clipboard.writeText(`[${String(label).replace(/[\[\]]/g, '')}](${href})`); message('Copied Markdown link to this section'); }
    catch (error) { report(error); }
}
/** Editing notes belongs to the box, so hand the selection over rather than growing a second writer. */
function openBox() {
    const uid = noteUid(current?.path || '');
    if (!uid) return;
    if (window.navigateToBoxFromLink?.(uid, 'notes', null, null)) message('Opened these notes on their box');
    else message(`Box “${uid}” is no longer in this document`);
}
async function addNote() {
    if (readOnly) { message('Read-only document'); return; }
    const name = window.prompt('New note — a title, or docs/a-title', 'Note');
    if (name === null) return;
    const heading = name.trim().replace(/\.(md|markdown)$/i, '').split('/').pop().trim();
    const path = noteFilePath(name);
    try {
        const response = await rpc({action:'create', path, contents:`# ${heading || 'Note'}\n\n`});
        if (!response.ok) throw new Error(response.conflict ? `${path} already exists — open it from the list` : response.error);
        entries = response.entries || entries;
        await navigate(path);
        if (current?.path === path) { setMode(true); message(`New note · Save writes to ${path}`); }
    } catch (error) { report(error); }
}
async function pin() {
    if (!writable()) return;
    try {
        const response = await rpc({action:'pin', path:current.path, x:60, y:60});
        if (!response.ok) throw new Error(response.error);
        // Wait briefly for the normal box resync; no duplicate content is pushed by this panel.
        for (let i=0; i<20; i++) {
            if (window.navigateToBoxProperty?.(response.uid, 'contents', null, null)) break;
            await new Promise(resolve => setTimeout(resolve, 100));
        }
        message('Markdown file placed on the canvas');
    } catch (error) { report(error); }
}
async function refresh() {
    if (!visible || refreshPending) return;
    refreshPending = true; const file = current, token = epoch;
    try {
        const response = await rpc({action:'list'});
        if (!visible || token !== epoch) return;
        if (!response.ok) throw new Error(response.error);
        entries = response.entries || []; renderEntries();
        if (file && !file.saving) {
            const response = await rpc({action:'read', path:file.path});
            if (!visible || file !== current || token !== epoch || file.saving) return;
            if (!response.ok) { message(response.error + (dirty() ? ' · Your draft is kept' : '')); return; }
            readOnly = response.readOnly;
            if (response.file.revision !== file.revision) {
                if (dirty(file)) { file.conflict = true; message('Changed on disk · Your draft is kept'); }
                else {
                    remember(); Object.assign(file, response.file, {draft:response.file.contents, conflict:false});
                    setEditorValue(); renderReader(); message('Updated from disk');
                }
                updateControls();
            }
        }
    } catch (error) { if (visible) report(error); }
    finally { refreshPending = false; }
}
window.addEventListener('beforeunload', event => {
    if ([...drafts.values()].some(file => dirty(file))) { event.preventDefault(); event.returnValue = ''; }
});
function close() {
    remember(); visible = false; epoch++; panel.hidden = true; clearInterval(refreshTimer);
    launcher?.focus();
}
async function show(path = '') {
    build(); visible = true; panel.hidden = false;
    try {
        const response = await rpc({action:'list'});
        if (!visible) return;
        if (!response.ok) throw new Error(response.error);
        if (documentKey !== response.document) {
            documentKey = response.document; drafts.clear(); current = null; navigation = [];
            try { saved = {path:'',positions:{},...JSON.parse(localStorage.getItem(storageKey()) || '{}')}; } catch (_) { saved = {path:'',positions:{}}; }
            if (saved.rect) setRect(saved.rect);
        }
        readOnly = response.readOnly; entries = response.entries || []; renderEntries();
        const target = path || current?.path || (entries.some(e=>e.path===saved.path) ? saved.path : entries[0]?.path);
        if (target) { if (target !== current?.path) await navigate(target); else { renderReader(); updateControls(); } }
        else {
            reader.textContent = 'This document has no guides or notes yet. Use Add note, put a Markdown file beside '
                + 'metadata.edn or inside docs/ or guides/, or write notes on a box.';
            message('No guides or notes found');
        }
        clearInterval(refreshTimer); refreshTimer = setInterval(refresh, 2500); refresh();
    } catch (error) { report(error); }
}
function setRect(rect) {
    const width = Math.min(innerWidth-20, Math.max(Math.min(480,innerWidth-20),rect.width));
    const height = Math.min(innerHeight-20, Math.max(Math.min(320,innerHeight-20),rect.height));
    Object.assign(panel.style, {width:`${width}px`,height:`${height}px`,left:`${Math.max(10,Math.min(innerWidth-width-10,rect.left))}px`,top:`${Math.max(10,Math.min(innerHeight-height-10,rect.top))}px`});
}
function installGestures(header) {
    for (const corner of ['nw','ne','sw','se']) panel.append(el('span', `guide-resize guide-resize-${corner}`));
    window.interact(panel).resizable({edges:{top:true,bottom:true,left:true,right:true},margin:10}).on('resizemove', event => {
        const r = panel.getBoundingClientRect(); setRect({left:r.left+event.deltaRect.left,top:r.top+event.deltaRect.top,width:event.rect.width,height:event.rect.height}); remember();
    }).draggable({allowFrom:'.guide-header',ignoreFrom:'button',listeners:{move:event=>{
        const r=panel.getBoundingClientRect(); setRect({left:r.left+event.dx,top:r.top+event.dy,width:r.width,height:r.height}); remember();
    }}});
    header.style.touchAction='none';
    window.addEventListener('resize',()=>{if(visible)setRect(panel.getBoundingClientRect());});
}
window.showDocumentGuide = show;
window.closeDocumentGuide = close;
// The panel has to be open for a new note to land somewhere visible, so open it first.
window.addDocumentGuideNote = async () => { await show(); await addNote(); };
// Discovery for Open Anything does not build or open the viewer, or fetch guide bodies.
let paletteCatalogue = null, paletteCatalogueAt = 0, paletteCatalogueKey = '', paletteCataloguePending = null;
window.documentGuidePaletteList = () => {
    const key = window.currentDocumentFilename || '';
    if (paletteCatalogueKey !== key) {
        paletteCatalogueKey = key; paletteCatalogue = null; paletteCataloguePending = null;
    }
    if (paletteCatalogue && Date.now() - paletteCatalogueAt < 750) return Promise.resolve(paletteCatalogue);
    if (paletteCataloguePending) return paletteCataloguePending;
    const pending = rpc({action:'list'}).then(response => {
        if (!response.ok) throw new Error(response.error);
        if (paletteCatalogueKey === key) { paletteCatalogue = response.entries || []; paletteCatalogueAt = Date.now(); }
        return response.entries || [];
    }).finally(() => { if (paletteCataloguePending === pending) paletteCataloguePending = null; });
    paletteCataloguePending = pending;
    return pending;
};
window.openDocumentGuideLink = async (link, fromPath) => { await show(fromPath); follow(link); };
window.copyDocumentGuideBoxLink = async () => {
    const selected = window.currentSelectedCode?.length ? window.currentSelectedCode : window.selectedList;
    const box = selected?.length === 1 ? selected[0] : null;
    if (!box?.uid) { window.notify?.('Select a box first', 'guide'); return; }
    const property = window.currentSelectedProperty || 'code';
    const line = window.globalEditor?.getPosition?.()?.lineNumber;
    const label = String(box.name || box.uid).replace(/[\[\]]/g,'');
    try { await navigator.clipboard.writeText(`[${label}](${boxGuideLink(box.uid,property,line)})`); window.notify?.('Copied Markdown link to box', 'guide'); }
    catch (_) { window.notify?.('Could not copy the link', 'error'); }
};
// Small document-level entry point. Check occasionally so externally added guides become discoverable.
let discovered = '', discoveryAt = 0, discoveryPending = false, deepLinkOpened = false;
setInterval(async () => {
    const name = window.currentDocumentFilename;
    if (!name || discoveryPending || (name === discovered && Date.now()-discoveryAt < 15000)) return;
    discovered = name; discoveryAt = Date.now(); discoveryPending = true;
    try {
        const response = await rpc({action:'list'});
        if (!response.ok) { discovered=''; return; }
        if (window.documentGuideSessionKey !== response.document) {
            window.documentGuideSessionKey = response.document; window.syncNotesMarkdownPreview?.();
        }
        build();
        if (!launcher) { launcher=button('Guides', 'document-guide-launcher',()=>show()); launcher.title='Open this document’s guides and notes'; document.body.append(launcher); }
        const found = response.entries || [];
        const noteCount = found.filter(e => e.kind === 'note').length;
        launcher.hidden = !found.length;
        // Name what is actually there: a document can carry notes and no Markdown files, or the reverse.
        launcher.textContent = noteCount === found.length ? `Notes · ${noteCount}`
            : noteCount ? `Guides · ${found.length - noteCount} · Notes · ${noteCount}`
            : `Guides · ${found.length}`;
        const initial = new URL(location.href).searchParams.get('guide');
        if (initial && !deepLinkOpened) { deepLinkOpened = true; await show(initial); }
    } catch (_) { discovered=''; }
    finally { discoveryPending = false; }
}, 2000);
