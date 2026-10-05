// node --test tools/test_keycast.cjs
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../src/commonMain/resources/keycast.js'), 'utf8');

function overlay(platform = 'Linux', pointer = true) {
    const listeners = new Map();
    const elements = [];
    const document = {
        readyState: 'complete',
        createElement: () => {
            const element = { style: {}, textContent: '', appendChild() {}, offsetWidth: 1 };
            elements.push(element);
            return element;
        },
        body: { appendChild() {} },
        querySelector: () => null,
        addEventListener() { throw new Error('Mouse events must not also attach to document'); }
    };
    const window = {
        innerWidth: 1200,
        PointerEvent: pointer ? function PointerEvent() {} : undefined,
        addEventListener(name, callback) { listeners.set(name, callback); },
        clearTimeout() {}, setTimeout() { return 1; }
    };
    vm.runInNewContext(source, { window, document, navigator: { platform }, Date });
    window.fieldKeycast.enable({ quiet: true });
    const emit = (type, event) => listeners.get(type)?.({ key: '', ...event });
    return { emit, text: () => elements.find(e => e.className === 'keycast-text').textContent, listeners };
}

test('click then Alt+Up shows one launch chord, without a standalone modifier', () => {
    const view = overlay();
    view.emit('pointerdown', { button: 0 });
    assert.equal(view.text(), 'LMB');
    view.emit('keydown', { key: 'Alt', altKey: true });
    view.emit('keydown', { key: 'ArrowUp', altKey: true });
    assert.equal(view.text(), 'Alt+↑');
    view.emit('keydown', { key: 'ArrowUp', altKey: true, repeat: true });
    assert.equal(view.text(), 'Alt+↑');
});

test('Alt-click includes its modifier before the button and counts only the press', () => {
    const view = overlay();
    view.emit('keydown', { key: 'Alt', altKey: true });
    view.emit('pointerdown', { button: 0, altKey: true });
    view.emit('mousedown', { button: 0, altKey: true });
    view.emit('pointerup', { button: 0, altKey: true });
    view.emit('click', { button: 0, altKey: true });
    assert.equal(view.text(), 'Alt+LMB');
    assert.equal(view.listeners.has('mousedown'), false);
});

test('typing accumulates, while a long shortcut stays whole', () => {
    const view = overlay('Win32', false);
    view.emit('keydown', { key: 'a' });
    view.emit('keydown', { key: 'b' });
    assert.equal(view.text(), 'AB');
    view.emit('keydown', { key: 'Control', ctrlKey: true });
    view.emit('keydown', { key: 'Shift', ctrlKey: true, shiftKey: true });
    view.emit('keydown', { key: 'p', ctrlKey: true, shiftKey: true });
    assert.equal(view.text(), 'Ctrl+Shift+P');
    view.emit('mousedown', { button: 0 });
    assert.equal(view.text(), 'LMB');
});

test('Mac uses one Option chord and Shift-click keeps its modifier', () => {
    const view = overlay('MacIntel');
    view.emit('keydown', { key: 'Alt', altKey: true });
    view.emit('keydown', { key: 'ArrowUp', altKey: true });
    assert.equal(view.text(), '⌥↑');
    view.emit('pointerdown', { button: 0, shiftKey: true });
    assert.equal(view.text(), '⇧LMB');
});
