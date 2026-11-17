import {send, growTextFrame, setTextFrameContents} from './afield.mjs';
import {mountChatAutocomplete} from './aiChatAutocomplete.js';
import {renderFieldMarkdown} from './markdownView.js';

// Text belongs to the box. DOM, editing state and uncommitted conflict drafts are local to a view.
const frames = new Map();
const defaults = {fontFamily: '"Field Etoile", serif', fontSize: 18, fontWeight: 400, lineHeight: 1.5, padding: 16, sizing: 'grow', connectionBorderLength: 24};
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
function settingsFor(box) {
    let raw = {};
    try { raw = JSON.parse(box.textFrame || '{}'); } catch (_) { /* Keep malformed settings editable in the property editor. */ }
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) raw = {};
    const number = (key, low, high) => Number.isFinite(Number(raw[key])) && raw[key] !== null
        ? Math.max(low, Math.min(high, Number(raw[key]))) : defaults[key];
    return {...raw,
        fontFamily: typeof raw.fontFamily === 'string' && raw.fontFamily.trim() ? raw.fontFamily : defaults.fontFamily,
        fontSize: number('fontSize', 8, 120), fontWeight: number('fontWeight', 100, 900), lineHeight: number('lineHeight', 1, 3), padding: number('padding', 0, 96),
        connectionBorderLength: number('connectionBorderLength', 0, 200),
        sizing: raw.sizing === 'fixed' ? 'fixed' : 'grow'};
}
function write(box, property, value) {
    if (window.currentDocumentReadOnly) return;
    if (property === 'contents') {
        if (frames.has(box)) frames.get(box).seenContents = value;
        setTextFrameContents(box.uid, value);
    } else {
        box[property] = value;
        send('set-string', {uid: box.uid, property, value});
    }
}
function flush(state) {
    clearTimeout(state.saveTimer);
    if (state.incoming !== null || !state.dirty) return;
    state.dirty = false;
    write(state.box, 'contents', state.editor.value);
}
function render(state) {
    const text = state.box.contents || '';
    renderFieldMarkdown(state.preview, text);
    state.preview.classList.toggle('text-frame-empty', !text.trim());
    if (!text.trim()) state.preview.textContent = 'Double-click to write Markdown';
    layout(state);
}
function layout(state) {
    cancelAnimationFrame(state.layoutFrame);
    state.layoutFrame = requestAnimationFrame(() => {
        if (!state.box.isConnected || !(state.box.data_w > 0 && state.box.data_h > 0)) return;
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
        const surface = editing ? state.editor : state.preview;
        if (config.sizing === 'grow' && !window.currentDocumentReadOnly) {
            // scrollHeight and data_h are both unscaled world/CSS units, including in split panes.
            growTextFrame(state.box.uid, Math.ceil(surface.scrollHeight + 32));
        }
        const overflow = state.preview.scrollHeight > state.preview.clientHeight + 1 ||
            state.preview.scrollWidth > state.preview.clientWidth + 1;
        state.overflow.hidden = editing || !overflow;
        state.box.classList.toggle('text-frame-has-overflow', overflow && !editing);
    });
}
function begin(state) {
    if (window.currentDocumentReadOnly) return;
    if (state.box.classList.contains('text-frame-editing')) { state.editor.focus(); return; }
    // The main property editor polls its model. Drain a final keystroke before switching surfaces.
    const selection = window.currentSelectedCode;
    if (selection?.length === 1 && selection[0].uid === state.box.uid && window.currentSelectedProperty === 'contents') {
        const model = window.globalEditor?.getModel();
        if (model && model.getValue() !== (state.box.contents || '')) write(state.box, 'contents', model.getValue());
    }
    state.editor.value = state.incoming !== null ? state.editor.value : (state.box.contents || '');
    state.autocomplete?.clear();
    state.box.classList.add('text-frame-editing');
    state.editor.hidden = false;
    state.preview.hidden = true;
    state.editor.focus({preventScroll: true});
    layout(state);
}
function finish(state) {
    if (state.composing) return;
    flush(state);
    // A concurrent edit never silently discards this view's draft. Resolve it before closing.
    if (state.incoming !== null) return;
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
function makeSettings(state) {
    const panel = node('div', 'text-frame-settings');
    panel.hidden = true;
    panel.setAttribute('role', 'group');
    panel.setAttribute('aria-label', 'Text frame settings');
    const config = settingsFor(state.box);
    for (const [key, label, type, min, max, step] of [
        ['fontFamily', 'Font family', 'text'], ['fontSize', 'Font size', 'number', 8, 120, 1],
        ['fontWeight', 'Font weight', 'weight'], ['lineHeight', 'Line height', 'number', 1, 3, 0.1], ['padding', 'Padding', 'number', 0, 96, 1],
        ['sizing', 'Frame sizing', 'select'], ['autocomplete', 'AI4 autocomplete', 'checkbox']
    ]) {
        const row = node('label', '', label);
        const input = node(type === 'select' || type === 'weight' ? 'select' : 'input', '');
        input.name = key;
        if (type === 'select' || type === 'weight') for (const [value, text] of (type === 'weight'
            ? [['300', 'Light'], ['400', 'Regular'], ['700', 'Bold']]
            : [['grow', 'Grow vertically'], ['fixed', 'Fixed / clip overflow']])) {
            const option = node('option', '', text); option.value = value; input.append(option);
        } else {
            input.type = type;
            if (type === 'number') { input.min = min; input.max = max; input.step = step; }
        }
        if (type === 'checkbox') input.checked = config[key] === true;
        else input.value = config[key];
        input.addEventListener('change', () => {
            if (!input.checkValidity()) return;
            const next = {...settingsFor(state.box), [key]: type === 'checkbox' ? input.checked : type === 'number' || type === 'weight' ? Number(input.value) : input.value};
            write(state.box, 'textFrame', JSON.stringify(next));
            layout(state);
        });
        row.append(input); panel.append(row);
    }
    panel.append(button('Done', () => { panel.hidden = true; state.settingsButton.setAttribute('aria-expanded', 'false'); }));
    panel.addEventListener('keydown', e => {
        e.stopPropagation();
        if (e.key === 'Escape') { panel.hidden = true; state.settingsButton.setAttribute('aria-expanded', 'false'); state.settingsButton.focus(); }
    });
    return panel;
}
function mount(box) {
    const body = node('div', 'text-frame-body');
    const preview = node('div', 'text-frame-preview');
    preview.title = 'Double-click to edit Markdown';
    preview.tabIndex = 0;
    preview.dataset.fieldCanvasKeyboard = 'true';
    preview.setAttribute('aria-label', 'Text frame; press Enter to edit Markdown');
    const editor = node('textarea', 'text-frame-editor');
    editor.setAttribute('aria-label', 'Markdown contents');
    editor.placeholder = 'Write Markdown…'; editor.spellcheck = true; editor.hidden = true;
    const controls = node('div', 'text-frame-controls');
    const conflict = node('div', 'text-frame-conflict'); conflict.hidden = true; conflict.setAttribute('role', 'alert');
    const state = {box, body, preview, editor, controls, conflict, seenContents: box.contents || '', incoming: null, dirty: false, composing: false};
    state.overflow = button('+', () => begin(state), 'text-frame-overflow');
    state.overflow.title = 'Text overflows this frame. Click to edit all text.';
    state.overflow.setAttribute('aria-label', 'Overflowed text; edit all text'); state.overflow.hidden = true;
    controls.append(button('Edit', () => begin(state)));
    state.settingsButton = button('Settings', () => {
        const open = state.settings.hidden;
        state.settings.hidden = !open;
        state.settingsButton.setAttribute('aria-expanded', String(open));
        if (open) {
            const config = settingsFor(box);
            state.settings.querySelectorAll('[name]').forEach(input => { if (input.type === 'checkbox') input.checked = config[input.name] === true; else input.value = config[input.name]; });
            state.settings.querySelector('input').focus();
        }
    });
    state.settingsButton.setAttribute('aria-expanded', 'false');
    controls.append(state.settingsButton);
    state.settings = makeSettings(state);
    conflict.append(node('span', '', 'Contents changed elsewhere. Your draft is kept here.'),
        button('Keep my version', () => resolveConflict(state, true)), button('Use incoming', () => resolveConflict(state, false)));
    body.append(preview, editor); box.append(body, controls, state.overflow, state.settings, conflict);
    box.classList.add('text-frame-box');
    preview.addEventListener('dblclick', e => { if (!e.target.closest('a,button')) { e.stopPropagation(); begin(state); } });
    preview.addEventListener('keydown', e => { if (e.target === preview && e.key === 'Enter') { e.preventDefault(); e.stopPropagation(); begin(state); } });
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
    editor.addEventListener('blur', () => finish(state));
    for (const element of [editor, controls, state.settings, conflict, state.overflow]) {
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
            for (const part of [state.body, state.controls, state.overflow, state.settings, state.conflict]) part.remove();
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
