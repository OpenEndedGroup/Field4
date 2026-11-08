// The file chooser control (`fileChooser(...)`, afield/ui/output/FileChooser.kt): a text field that
// completes against the filesystem as you type, in place of a native file dialog.
//
// Each keystroke asks the server (`file-complete`) what the text could complete to, and a single
// drop-down under the field lists the answer. Arrow keys move, Tab or Enter take an entry (a
// directory opens, a file is chosen), Escape closes, Alt+ArrowDown or the ▾ button lists the folder.
// Choosing a file, Enter on a typed path, or leaving the field sends `string-update`, the same
// message a text control sends; the server only hands the box paths the chooser accepts.

import { send, sendWithReply } from "./afield.mjs";

const REQUEST_DELAY_MS = 40;
const state = new WeakMap();
let menu = null;
let openFor = null;
let followFrame = 0;

function inputState(input) {
    let s = state.get(input);
    if (!s) {
        s = { seq: 0, entries: [], index: -1, timer: 0, reply: null };
        if (input.dataset.committed === undefined) input.dataset.committed = input.value;
        state.set(input, s);
    }
    return s;
}

function isChooserInput(el) {
    return !!(el && el.matches && el.matches('.field-file-input'));
}

function menuElement() {
    if (menu && menu.isConnected) return menu;
    menu = document.createElement('div');
    menu.className = 'field-file-menu';
    menu.id = 'field-file-menu';
    menu.setAttribute('role', 'listbox');
    // Keep focus in the field while the list is clicked.
    menu.addEventListener('pointerdown', (event) => event.preventDefault());
    menu.addEventListener('mousedown', (event) => event.preventDefault());
    menu.addEventListener('click', (event) => {
        const item = event.target.closest('.field-file-item');
        if (!item || !openFor) return;
        const s = inputState(openFor);
        const entry = s.entries[Number(item.dataset.index)];
        if (entry) accept(openFor, entry);
    });
    document.body.appendChild(menu);
    return menu;
}

function formatSize(bytes) {
    if (!bytes) return '';
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    let v = bytes;
    let u = 0;
    while (v >= 1024 && u < units.length - 1) { v /= 1024; u++; }
    return `${v < 10 && u > 0 ? v.toFixed(1) : Math.round(v)} ${units[u]}`;
}

function formatDate(ms) {
    if (!ms) return '';
    const d = new Date(ms);
    const now = new Date();
    return d.getFullYear() === now.getFullYear()
        ? d.toLocaleDateString(undefined, { month: 'short', day: 'numeric' })
        : d.toLocaleDateString(undefined, { year: 'numeric', month: 'short' });
}

function follow() {
    cancelAnimationFrame(followFrame);
    if (!openFor || !openFor.isConnected) { close(); return; }
    const r = openFor.getBoundingClientRect();
    const m = menuElement();
    const width = Math.max(r.width, 280);
    m.style.minWidth = `${Math.round(width)}px`;
    m.style.left = `${Math.round(Math.max(4, Math.min(r.left, window.innerWidth - m.offsetWidth - 4)))}px`;
    const below = window.innerHeight - r.bottom;
    if (below < 180 && r.top > below) {
        m.style.top = '';
        m.style.bottom = `${Math.round(window.innerHeight - r.top + 2)}px`;
    } else {
        m.style.bottom = '';
        m.style.top = `${Math.round(r.bottom + 2)}px`;
    }
    // The canvas pans and zooms under the field; keep the list on it.
    followFrame = requestAnimationFrame(follow);
}

function render(input) {
    const s = inputState(input);
    const reply = s.reply || {};
    const m = menuElement();
    m.replaceChildren();
    const header = document.createElement('div');
    header.className = 'field-file-header';
    const where = document.createElement('span');
    where.className = 'field-file-where';
    // The box clips from the left (direction: rtl) so the folder's own name stays visible; the
    // text inside is isolated left-to-right, or a leading "~" would be reordered to the end.
    const path = document.createElement('bdi');
    path.textContent = reply.directory || '';
    where.appendChild(path);
    header.appendChild(where);
    if (reply.mask) {
        const mask = document.createElement('span');
        mask.className = 'field-file-mask';
        mask.textContent = reply.mask;
        header.appendChild(mask);
    }
    m.appendChild(header);
    if (reply.error) {
        const err = document.createElement('div');
        err.className = 'field-file-empty field-file-error';
        err.textContent = reply.error;
        m.appendChild(err);
    } else if (!s.entries.length) {
        const empty = document.createElement('div');
        empty.className = 'field-file-empty';
        empty.textContent = reply.choosable ? 'no other matches' : 'nothing matches';
        m.appendChild(empty);
    }
    s.entries.forEach((entry, i) => {
        const item = document.createElement('div');
        item.className = `field-file-item${entry.directory ? ' directory' : ''}${i === s.index ? ' selected' : ''}`;
        item.dataset.index = String(i);
        item.setAttribute('role', 'option');
        item.id = `field-file-option-${i}`;
        if (i === s.index) item.setAttribute('aria-selected', 'true');
        const name = document.createElement('span');
        name.className = 'field-file-name';
        name.textContent = entry.directory ? `${entry.name}/` : entry.name;
        const meta = document.createElement('span');
        meta.className = 'field-file-meta';
        meta.textContent = entry.directory ? '' : [formatSize(entry.size), formatDate(entry.modified)].filter(Boolean).join('  ');
        item.append(name, meta);
        m.appendChild(item);
    });
    if (reply.truncated) {
        const more = document.createElement('div');
        more.className = 'field-file-empty';
        more.textContent = 'more… keep typing';
        m.appendChild(more);
    }
    m.classList.add('visible');
    input.setAttribute('aria-expanded', 'true');
    input.setAttribute('aria-controls', m.id);
    if (s.index >= 0) input.setAttribute('aria-activedescendant', `field-file-option-${s.index}`);
    else input.removeAttribute('aria-activedescendant');
    openFor = input;
    follow();
    m.querySelector('.field-file-item.selected')?.scrollIntoView({ block: 'nearest' });
}

function markValidity(input, reply) {
    const container = input.closest('.field-file');
    if (!container || !reply || reply.text !== input.value) return;
    container.classList.toggle('field-file-invalid', !!input.value.trim() && !reply.choosable);
}

function close() {
    cancelAnimationFrame(followFrame);
    if (menu) menu.classList.remove('visible');
    if (openFor) {
        openFor.setAttribute('aria-expanded', 'false');
        openFor.removeAttribute('aria-activedescendant');
    }
    openFor = null;
}

function request(input, text = input.value, { open = true } = {}) {
    const s = inputState(input);
    const seq = ++s.seq;
    sendWithReply('file-complete', { controlId: input.id, text, seq },
        `file-complete-${input.id}-${seq}-${Date.now()}`, (_message, reply) => {
            if (seq !== s.seq || !reply) return 'remove';
            s.reply = reply;
            s.entries = reply.entries || [];
            s.index = -1;
            // Red only for what was committed: a half-typed name is not a mistake yet.
            if (!open) markValidity(input, reply);
            if (open && document.activeElement === input) render(input);
            return 'remove';
        });
}

function scheduleRequest(input) {
    const s = inputState(input);
    clearTimeout(s.timer);
    s.timer = setTimeout(() => request(input), REQUEST_DELAY_MS);
}

function commit(input) {
    const value = input.value;
    if (value === input.dataset.committed) return;
    input.dataset.committed = value;
    send('string-update', { controlId: input.id, value });
    request(input, value, { open: false });
}

function setValue(input, value) {
    input.value = value;
    input.setSelectionRange(value.length, value.length);
}

function accept(input, entry) {
    setValue(input, entry.insert);
    if (entry.directory) {
        request(input);
    } else {
        close();
        commit(input);
    }
}

function directoryPart(text) {
    const slash = text.lastIndexOf('/');
    return slash >= 0 ? text.slice(0, slash + 1) : '';
}

function browse(input) {
    input.focus();
    request(input, directoryPart(input.value));
}

function move(input, delta) {
    const s = inputState(input);
    if (!s.entries.length) return;
    s.index = s.index < 0 ? (delta > 0 ? 0 : s.entries.length - 1)
        : (s.index + delta + s.entries.length) % s.entries.length;
    render(input);
}

document.addEventListener('input', (event) => {
    if (!isChooserInput(event.target)) return;
    inputState(event.target);
    event.target.closest('.field-file')?.classList.remove('field-file-invalid');
    scheduleRequest(event.target);
}, true);

document.addEventListener('focusin', (event) => {
    if (isChooserInput(event.target)) inputState(event.target);
}, true);

document.addEventListener('keydown', (event) => {
    const input = event.target;
    if (!isChooserInput(input)) return;
    const s = inputState(input);
    const isOpen = openFor === input;
    let handled = true;
    switch (event.key) {
        case 'ArrowDown':
            if (event.altKey || !isOpen) browse(input); else move(input, 1);
            break;
        case 'ArrowUp':
            if (isOpen) move(input, -1); else handled = false;
            break;
        case 'Tab':
            if (isOpen && s.entries.length && !event.shiftKey) {
                // Tab takes the highlighted entry, or the only/first one: the shell's completion.
                accept(input, s.entries[Math.max(0, s.index)]);
            } else {
                handled = false;
            }
            break;
        case 'Enter':
            if (isOpen && s.index >= 0 && s.entries[s.index]) {
                accept(input, s.entries[s.index]);
            } else {
                close();
                commit(input);
            }
            break;
        case 'Escape':
            if (isOpen) close();
            else setValue(input, input.dataset.committed || '');
            break;
        default:
            handled = false;
    }
    if (handled) {
        event.preventDefault();
    }
    // The canvas has its own keys; none of them belong to a focused text field.
    event.stopPropagation();
}, true);

document.addEventListener('focusout', (event) => {
    const input = event.target;
    if (!isChooserInput(input)) return;
    if (openFor === input) close();
    commit(input);
}, true);

document.addEventListener('click', (event) => {
    const button = event.target.closest?.('.field-file-browse');
    if (!button) return;
    const input = button.closest('.field-file')?.querySelector('.field-file-input');
    if (!input) return;
    event.preventDefault();
    event.stopPropagation();
    if (openFor === input) close(); else browse(input);
}, true);

// The browse button must not take focus from the field (which would commit and close).
document.addEventListener('pointerdown', (event) => {
    if (event.target.closest?.('.field-file-browse')) event.preventDefault();
}, true);

// For probes and tests.
window.fieldFileChooserState = () => {
    if (!openFor) return null;
    const s = inputState(openFor);
    return { controlId: openFor.id, value: openFor.value, index: s.index, reply: s.reply,
        entries: s.entries.map((e) => e.insert) };
};
