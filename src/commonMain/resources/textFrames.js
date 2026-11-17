import {send, growTextFrame, setTextFrameContents, flushTextFramePropertyEditor, syncTextFrameSettingsEditor, syncSelectedPropertyEditor} from './afield.mjs';
import {mountChatAutocomplete} from './aiChatAutocomplete.js';
import {renderFieldMarkdown} from './markdownView.js';
import {markdownOffsetAtPoint, revealTextareaCaret} from './markdownCaret.js';
import {load, dump, CORE_SCHEMA} from './vendor/js-yaml/js-yaml.mjs';

// Text belongs to the box. DOM, editing state and uncommitted conflict drafts are local to a view.
const frames = new Map();
const defaults = {fontFamily: '"Field Etoile", serif', fontSize: 11, fontWeight: 400, lineHeight: 1.5, padding: 16, sizing: 'grow', connectionBorderLength: 100, autocomplete: false, minHeight: 80, reflow: true};
const node = (tag, cls, text) => {
    const result = document.createElement(tag);
    result.className = cls;
    if (text !== undefined) result.textContent = text;
    return result;
};
const button = (label, action, cls = '') => {
    const b = node('button', cls, label);
    b.type = 'button';
    b.addEventListener('click', action);
    return b;
};
function readSettings(source) {
    const raw = load(source || '{}', {schema: CORE_SCHEMA}) ?? {};
    if (typeof raw !== 'object' || Array.isArray(raw)) throw new Error('Text frame settings must be a YAML mapping.');
    return raw;
}
let diagnosticsEditor, diagnosticsSubscription;
function installSettingsDiagnostics() {
    const editor = window.globalEditor, monaco = window.globalMonaco;
    if (!editor || !monaco || diagnosticsEditor === editor) return;
    diagnosticsSubscription?.dispose();
    diagnosticsEditor = editor;
    const update = () => {
        const model = editor.getModel();
        if (!model) return;
        let markers = [];
        if (window.currentSelectedProperty === 'textFrame') {
            try { readSettings(model.getValue()); } catch (error) {
                const line = (error.mark?.line ?? 0) + 1, column = (error.mark?.column ?? 0) + 1;
                markers = [{severity: monaco.MarkerSeverity.Error, message: error.reason || error.message,
                    startLineNumber: line, endLineNumber: line, startColumn: column, endColumn: column + 1}];
            }
        }
        monaco.editor.setModelMarkers(model, 'text-frame-settings', markers);
    };
    diagnosticsSubscription = editor.onDidChangeModelContent(() => queueMicrotask(update));
    update();
}
function openSettings(uid) {
    const box = [...frames.keys()].find(box => box.uid === uid);
    if (!box || window.currentDocumentReadOnly) return;
    const state = frames.get(box);
    flushTextFramePropertyEditor(uid);
    if (box.classList.contains('text-frame-editing')) finish(state);
    if (state.incoming !== null) return;
    const source = box.textFrame || '';
    try {
        const raw = readSettings(source);
        const missing = Object.fromEntries(Object.entries(defaults).filter(([key]) => !Object.hasOwn(raw, key)));
        // JSON is valid YAML: keep old documents readable, and turn the settings page into YAML on open.
        // Append missing defaults to existing YAML so the person's comments and ordering survive.
        const next = source.trim().startsWith('{')
            ? dump({...defaults, ...raw}, {schema: CORE_SCHEMA, lineWidth: 100, noRefs: true})
            : source.trimEnd() + (source.trim() && Object.keys(missing).length ? '\n' : '') +
                (Object.keys(missing).length ? dump(missing, {schema: CORE_SCHEMA, lineWidth: 100, noRefs: true}) : '');
        if (next !== source) write(box, 'textFrame', next);
    } catch (_) { /* Open invalid YAML intact for repair in the normal editor. */ }
    window.switchToProperty(box.uid, 'textFrame');
    installSettingsDiagnostics();
    window.globalEditor?.focus();
}
window.openTextFrameSettings = openSettings;
installSettingsDiagnostics();
window.addEventListener('field-editor-font-changed', () => {
    installSettingsDiagnostics();
    for (const state of frames.values()) layout(state);
});
function settingsFor(box) {
    let raw;
    try { raw = readSettings(box.textFrame); } catch (_) { return frames.get(box)?.config || defaults; }
    const number = (key, low, high) => Number.isFinite(Number(raw[key])) && raw[key] !== null
        ? Math.max(low, Math.min(high, Number(raw[key]))) : defaults[key];
    const config = {...raw,
        fontFamily: typeof raw.fontFamily === 'string' && raw.fontFamily.trim() ? raw.fontFamily : defaults.fontFamily,
        fontSize: number('fontSize', 8, 120), fontWeight: number('fontWeight', 100, 900), lineHeight: number('lineHeight', 1, 3), padding: number('padding', 0, 96),
        connectionBorderLength: number('connectionBorderLength', 0, 200),
        minHeight: number('minHeight', 40, 2000), reflow: raw.reflow !== false,
        sizing: raw.sizing === 'fixed' ? 'fixed' : 'grow'};
    if (frames.has(box)) frames.get(box).config = config;
    return config;
}
function write(box, property, value) {
    if (window.currentDocumentReadOnly) return;
    if (property === 'contents') {
        if (frames.has(box)) frames.get(box).seenContents = value;
        setTextFrameContents(box.uid, value);
    } else {
        box[property] = value;
        if (property === 'textFrame') syncTextFrameSettingsEditor(box.uid, value);
        send('set-string', {uid: box.uid, property, value});
    }
}
function flush(state) {
    clearTimeout(state.saveTimer);
    if (state.incoming !== null || !state.dirty) return;
    state.dirty = false;
    write(state.box, 'contents', state.editor.value);
}
function markdownOptions(state, sourcePositions = false) {
    return {sourcePositions, resolveImage: src => {
        if (/^(https?:|data:)/i.test(src)) return src;
        if (/^[a-z][a-z0-9+.-]*:/i.test(src)) return '';
        const params = new URLSearchParams({document: window.currentDocumentSessionKey || window.currentDocumentFilename || '', box: state.box.uid, path: src});
        return '/api/text-frame-asset?' + params;
    }};
}
function render(state) {
    const text = state.box.contents || '';
    state.previewSource = text;
    renderFieldMarkdown(state.preview, text, markdownOptions(state, true));
    state.preview.classList.toggle('text-frame-empty', !text.trim());
    if (!text.trim()) state.preview.textContent = 'Double-click to write Markdown';
    layout(state);
}
function layout(state) {
    cancelAnimationFrame(state.layoutFrame);
    state.layoutFrame = requestAnimationFrame(() => {
        if (!state.box.isConnected || state.box.classList.contains('tombstoned') || !(state.box.data_w > 0 && state.box.data_h > 0)) return;
        const config = settingsFor(state.box);
        if (state.box.getAttribute('data-connection-border-length') !== String(config.connectionBorderLength)) {
            state.box.setAttribute('data-connection-border-length', config.connectionBorderLength);
            window.requestConnectionGroupRebuild?.();
        }
        state.box.style.setProperty('--text-frame-font', config.fontFamily);
        state.box.style.setProperty('--text-frame-size', `${config.fontSize}px`);
        state.box.style.setProperty('--text-frame-weight', config.fontWeight);
        state.box.style.setProperty('--text-frame-leading', config.lineHeight);
        state.box.style.setProperty('--text-frame-padding', `${config.padding}px`);
        const editing = state.box.classList.contains('text-frame-editing');
        // Formatted content owns the persistent geometry. A taller Markdown source scrolls
        // inside the same frame; measuring it would make every edit/release change the graph.
        if (editing && state.measuredDraft !== state.editor.value) {
            state.measuredDraft = state.editor.value;
            state.previewSource = state.measuredDraft;
            renderFieldMarkdown(state.preview, state.measuredDraft, markdownOptions(state));
            state.preview.classList.remove('text-frame-empty');
        }
        if (config.sizing === 'grow' && !window.currentDocumentReadOnly) {
            // The hidden preview still lays out at the real frame width, including in split panes.
            const height = state.preview.style.height, bottom = state.preview.style.bottom;
            state.preview.style.height = 'auto'; state.preview.style.bottom = 'auto';
            const measured = Math.max(config.minHeight, Math.ceil(state.preview.scrollHeight + 2));
            state.preview.style.height = height; state.preview.style.bottom = bottom;
            growTextFrame(state.box.uid, measured, config.reflow);
        }
        const overflow = state.preview.scrollHeight > state.preview.clientHeight + 1 ||
            state.preview.scrollWidth > state.preview.clientWidth + 1;
        state.overflow.hidden = editing || !overflow;
        state.box.classList.toggle('text-frame-has-overflow', overflow && !editing);
    });
}
function begin(state, clickedOffset, clickedSource) {
    if (window.currentDocumentReadOnly) return;
    if (state.box.classList.contains('text-frame-editing')) { state.editor.focus(); return; }
    // The main property editor polls its model. Drain a final keystroke before switching surfaces.
    flushTextFramePropertyEditor(state.box.uid);
    state.measuredDraft = null;
    state.editor.value = state.incoming !== null ? state.editor.value : (state.box.contents || '');
    state.autocomplete?.clear();
    state.box.classList.add('text-frame-editing');
    state.editor.hidden = false;
    state.preview.hidden = true;
    state.editor.focus({preventScroll: true});
    const saved = state.sourceSelection;
    if (Number.isFinite(clickedOffset) && clickedSource?.replace(/\r\n/g, '\n') === state.editor.value) {
        const offset = Math.max(0, Math.min(clickedOffset, state.editor.value.length));
        state.editor.setSelectionRange(offset, offset);
    } else if (saved?.value === state.editor.value) {
        state.editor.setSelectionRange(saved.start, saved.end, saved.direction);
        state.editor.scrollTop = saved.scrollTop;
    } else state.editor.setSelectionRange(0, 0);
    layout(state);
    requestAnimationFrame(() => {
        if (!state.editor.hidden && document.activeElement === state.editor) revealTextareaCaret(state.editor);
    });
}
function finish(state) {
    if (state.composing || !state.box.classList.contains('text-frame-editing')) return;
    flush(state);
    // A concurrent edit never silently discards this view's draft. Resolve it before closing.
    if (state.incoming !== null) return;
    state.sourceSelection = {value: state.editor.value, start: state.editor.selectionStart,
        end: state.editor.selectionEnd, direction: state.editor.selectionDirection, scrollTop: state.editor.scrollTop};
    state.autocomplete?.clear();
    state.box.classList.remove('text-frame-editing');
    state.editor.hidden = true;
    state.preview.hidden = false;
    render(state);
}
function showConflict(state, incoming) {
    state.incoming = incoming;
    state.autocomplete?.clear();
    clearTimeout(state.saveTimer);
    state.conflict.hidden = false;
}
function resolveConflict(state, useMine) {
    if (!useMine) state.editor.value = state.incoming;
    state.incoming = null;
    state.conflict.hidden = true;
    state.dirty = useMine;
    flush(state);
    state.editor.focus({preventScroll: true});
    layout(state);
}
function mount(box) {
    const body = node('div', 'text-frame-body');
    const preview = node('div', 'text-frame-preview');
    preview.title = 'Double-click to edit Markdown';
    preview.tabIndex = -1;
    preview.dataset.fieldCanvasKeyboard = 'true';
    preview.setAttribute('aria-label', 'Text frame; press Enter to edit Markdown');
    const editor = node('textarea', 'text-frame-editor');
    editor.setAttribute('aria-label', 'Markdown contents');
    editor.placeholder = 'Write Markdown…'; editor.spellcheck = true; editor.hidden = true;
    const conflict = node('div', 'text-frame-conflict'); conflict.hidden = true; conflict.setAttribute('role', 'alert');
    const state = {box, body, preview, editor, conflict, seenContents: box.contents || '', incoming: null, dirty: false, composing: false};
    state.overflow = button('+', () => begin(state), 'text-frame-overflow');
    state.overflow.title = 'Text overflows this frame. Click to edit all text.';
    state.overflow.setAttribute('aria-label', 'Overflowed text; edit all text'); state.overflow.hidden = true;
    conflict.append(node('span', '', 'Contents changed elsewhere. Your draft is kept here.'),
        button('Keep my version', () => resolveConflict(state, true)), button('Use incoming', () => resolveConflict(state, false)));
    body.append(preview, editor); box.append(body, state.overflow, conflict);
    box.classList.add('text-frame-box');
    // Let a double-click edit a link label without following its first click. Modified clicks
    // keep normal browser navigation; an ordinary single click follows after the double-click window.
    preview.addEventListener('click', e => {
        const anchor = e.target.closest('a[href]');
        if (!anchor || state.followingLink || window.currentDocumentReadOnly || e.ctrlKey || e.metaKey || e.shiftKey || e.altKey) return;
        e.preventDefault(); e.stopImmediatePropagation();
        clearTimeout(state.linkTimer);
        if (e.detail < 2) state.linkTimer = setTimeout(() => {
            if (!anchor.isConnected || !state.editor.hidden) return;
            state.followingLink = true;
            try { anchor.click(); } finally { state.followingLink = false; }
        }, 500);
    }, true);
    preview.addEventListener('dblclick', e => {
        if (window.currentDocumentReadOnly || e.target.closest('button')) return;
        clearTimeout(state.linkTimer);
        e.preventDefault(); e.stopPropagation();
        const offset = markdownOffsetAtPoint(preview, e.clientX, e.clientY, e.target);
        begin(state, offset, state.previewSource);
    });
    editor.addEventListener('input', () => {
        state.dirty = true;
        if (!state.composing) { clearTimeout(state.saveTimer); state.saveTimer = setTimeout(() => flush(state), 150); }
        layout(state);
    });
    editor.addEventListener('compositionstart', () => { state.composing = true; clearTimeout(state.saveTimer); });
    editor.addEventListener('compositionend', () => { state.composing = false; state.dirty = true; flush(state); layout(state); });
    state.autocomplete = mountChatAutocomplete(body, editor, {
        chatUid: () => box.uid, chatProperty: () => 'contents', requestName: 'ai-text-frame-suggest',
        isEnabled: () => settingsFor(box).autocomplete === true && state.incoming === null && !state.composing
    });
    editor.addEventListener('keydown', e => {
        e.stopPropagation();
        if (e.defaultPrevented) return;
        if (!e.isComposing && (e.key === 'Escape' || ((e.metaKey || e.ctrlKey) && e.key === 'Enter'))) {
            e.preventDefault(); finish(state); if (state.incoming === null) preview.focus({preventScroll: true});
        }
    });
    editor.addEventListener('blur', () => queueMicrotask(() => {
        // Monaco can briefly focus its own input while syncing a peer value, then restore us.
        if (document.activeElement !== editor) finish(state);
    }));
    for (const element of [editor, conflict, state.overflow]) {
        for (const name of ['pointerdown', 'mousedown', 'dblclick', 'wheel', 'keyup', 'contextmenu']) element.addEventListener(name, e => e.stopPropagation());
    }
    // Loaded images, math typesetting and a width resize can all change the amount of text that fits.
    body.addEventListener('load', () => layout(state), true);
    state.observer = new ResizeObserver(() => layout(state));
    state.observer.observe(box); state.observer.observe(preview);
    frames.set(box, state);
    render(state);
    return state;
}
export function syncTextFrame(box, property) {
    let state = frames.get(box);
    if (box.box_kind !== 'text-frame') {
        if (state) {
            flush(state); state.observer.disconnect(); state.autocomplete.dispose(); cancelAnimationFrame(state.layoutFrame);
            for (const part of [state.body, state.overflow, state.conflict]) part.remove();
            box.classList.remove('text-frame-box', 'text-frame-editing', 'text-frame-has-overflow'); frames.delete(box);
        }
        return;
    }
    if (!state) { mount(box); return; }
    if (box.classList.contains('text-frame-editing')) {
        const incoming = box.contents || '';
        if (property === 'contents' && incoming !== state.seenContents) {
            if (incoming !== state.editor.value) showConflict(state, incoming);
            else { state.incoming = null; state.conflict.hidden = true; state.dirty = false; }
        }
        if (property === 'contents') state.seenContents = incoming;
        layout(state);
    } else render(state);
}
window.syncTextFrame = syncTextFrame;
window.editTextFrame = box => { syncTextFrame(box); const state = frames.get(box); if (state) begin(state); };
// Module loading and the server's initial resync can arrive in either order.
document.querySelectorAll('.box').forEach(syncTextFrame);
new MutationObserver(() => {
    for (const [box, state] of frames) if (!box.isConnected) {
        clearTimeout(state.saveTimer); cancelAnimationFrame(state.layoutFrame); state.observer.disconnect(); state.autocomplete.dispose(); frames.delete(box);
    }
}).observe(document.body, {childList: true, subtree: true});
window.addEventListener('pagehide', () => frames.forEach(flush));

// Webfont arrival changes line breaks even when the frame's fixed border box has not resized.
document.fonts?.addEventListener('loadingdone', () => frames.forEach(layout));

// Canvas gestures can prevent the browser's default focus change. Release the editor explicitly.
document.addEventListener('pointerdown', event => {
    for (const state of frames.values()) {
        if (state.box.classList.contains('text-frame-editing') && !state.box.contains(event.target)) finish(state);
    }
}, true);

// DOM focus can outlive a canvas selection (notably after spatial navigation). The selected
// box owns Return; a previously focused reader must never reopen itself instead.
const canvasSelection = () => Array.from(window.selectedList || []).filter(box =>
    box.isConnected && !box.classList.contains('tombstoned') &&
    getComputedStyle(box).display !== 'none' && getComputedStyle(box).visibility !== 'hidden');
window.addEventListener('field-selection-changed', () => {
    const selection = canvasSelection();
    for (const state of frames.values()) if (!selection.includes(state.box)) {
        if (state.box.classList.contains('text-frame-editing')) finish(state);
        const focused = document.activeElement;
        if (focused === state.editor || state.preview.contains(focused)) focused.blur();
    }
    const focused = document.activeElement;
    const mainEditor = focused?.closest?.('.monaco-editor-container');
    if (mainEditor) {
        const style = getComputedStyle(mainEditor);
        if (style.visibility === 'hidden' || style.display === 'none' || mainEditor.style.opacity === '0' || style.opacity === '0') focused.blur();
    }
});
function editSelectedCanvasBox() {
    const selection = canvasSelection();
    if (selection.length !== 1) return;
    const box = selection[0];
    if (box.box_kind === 'text-frame') window.editTextFrame(box);
    else {
        // The normal editor polls selection. Synchronize before focusing it so an immediate
        // keystroke cannot reach the preceding box's model.
        syncSelectedPropertyEditor();
        window.switchToProperty(box.uid, window.selectedProperty || box.default_property || 'code');
    }
}
window.editSelectedCanvasBox = editSelectedCanvasBox;
window.allActions.textFrames = [{
    id: 'edit-selected-box', label: 'Edit Selected Box', keys: 'enter', keyScope: 'canvas',
    detail: 'Edit the selected text frame, or open the selected box’s property editor',
    canvasGuard: event => !event.isComposing && canvasSelection().length === 1,
    run: editSelectedCanvasBox
}];
window.updateActions?.();
