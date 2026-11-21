import {send, growTextFrame, setTextFrameContents, flushTextFramePropertyEditor, syncTextFrameSettingsEditor, syncSelectedPropertyEditor, requestCustomMarkingMenu} from './afield.mjs';
import {mountChatAutocomplete} from './aiChatAutocomplete.js';
import {renderFieldMarkdown} from './markdownView.js';
import {markdownOffsetAtPoint, revealTextareaCaret} from './markdownCaret.js';
import {queryPassageLinks} from './textFrameLinks.js';
import {markdownRangeFromDOM, domRangeForMarkdown} from './markdownTextRuns.js';
import {mountRichText, placeRichCaret, pasteRichText, moveBetweenRichBlocks} from './textFrameRichEditor.js';
import {load, dump, CORE_SCHEMA} from './vendor/js-yaml/js-yaml.mjs';

// Text belongs to the box. DOM, editing state and uncommitted conflict drafts are local to a view.
const frames = new Map();
const normalizeMarkdown = value => value.replace(/\r\n?/g, '\n');
const runBoundary = (text, at) => at <= 0 || at >= text.length ||
    !(/[\ud800-\udbff]/.test(text[at - 1]) && /[\udc00-\udfff]/.test(text[at]));
function runExcerpt(text, start, end) {
    const stop = Math.min(end, start + 120);
    return text.slice(start, runBoundary(text, stop) ? stop : stop - 1);
}
const textRunCutType = 'application/x-field-text-run-cut';
const passageCopyType = 'application/x-field-passage';
const webPassageCopyType = 'web ' + passageCopyType;
const passageLinkPrefix = 'field://passage/';
let lastPassagePlain;
let shownPassage = null;

function updatePassageHighlight() {
    const highlights = window.CSS?.highlights;
    if (!highlights) return;
    highlights.delete('field-source-passage');
    const {source, child, id} = shownPassage || {};
    const anchor = child ? child.passageAnchor : source?.runSources.find(entry => entry.id === id);
    if (!source?.box.isConnected || (child && !child.box.isConnected) || !anchor || anchor.lost || anchor.sourceUid !== source.box.uid || source.preview.hidden ||
        source.box.classList.contains('tombstoned') || child?.box.classList.contains('tombstoned')) return;
    const text = normalizeMarkdown(source.previewSource || '');
    if (anchor.end > text.length || text.slice(anchor.start, anchor.start + anchor.excerpt.length) !== anchor.excerpt) return;
    const range = domRangeForMarkdown(source.preview, anchor.start, anchor.end);
    if (range) highlights.set('field-source-passage', new Highlight(range));
}

function updatePassageBadge(state) {
    let anchor;
    try { anchor = JSON.parse(state.box.textRunAnchor); } catch (_) { /* No passage on this box. */ }
    state.passageAnchor = anchor?.version === 1 ? anchor : null;
    state.passageBadge.hidden = !state.passageAnchor;
    if (state.passageAnchor) {
        state.passageBadge.textContent = anchor.lost ? 'Passage lost' : '↖ Source passage';
        state.passageBadge.classList.toggle('text-frame-passage-lost', anchor.lost === true);
        state.passageBadge.title = (anchor.cut ? 'Passage cut; paste it to move this note' :
            anchor.lost ? 'Original passage deleted; go to parent box' : 'Show the passage on its parent box') +
            (anchor.excerpt ? ': ' + anchor.excerpt : '');
    }
    if (shownPassage?.child === state) updatePassageHighlight();
}

function showPassage(child) {
    const anchor = child.passageAnchor;
    if (!anchor) return false;
    const source = [...frames.values()].find(state => state.box.uid === anchor.sourceUid);
    if (!source || !window.frameBoxFromGuide?.(anchor.sourceUid)) return false;
    finish(source);
    shownPassage = {source, child};
    updatePassageHighlight();
    return true;
}

function findRunSource(id) {
    for (const state of frames.values()) {
        if (state.box.classList.contains('tombstoned')) continue;
        const anchor = state.runSources.find(entry => entry.id === id);
        if (anchor) return {state, anchor};
    }
    return null;
}

window.openTextFramePassage = id => {
    const found = findRunSource(id);
    if (!found || !window.frameBoxFromGuide?.(found.state.box.uid)) return false;
    finish(found.state);
    shownPassage = {source: found.state, id};
    updatePassageHighlight();
    return true;
};

window.queryTextFrameLinks = options => queryPassageLinks(frames.values(), options);
window.followTextFrameLink = (item, direction) => {
    const destination = [...frames.values()].find(state => state.box.uid === item.destinationUid);
    if (direction === 'source') {
        if (item.kind === 'transclusion') return window.openTextFramePassage(item.passageId);
        if (item.kind === 'note') return !!destination && showPassage(destination);
        return !!window.navigateToBoxFromLink?.(item.sourceUid, item.property, item.line, item.line);
    }
    if (!destination || !window.frameBoxFromGuide?.(destination.box.uid)) return false;
    finish(destination);
    const element = item.kind === 'note' ? destination.passageBadge :
        [...destination.preview.querySelectorAll('a[href]')].filter(a => a.getAttribute('href') === item.href)[item.occurrence];
    if (element) {
        element.classList.add('text-frame-link-target');
        setTimeout(() => element.classList.remove('text-frame-link-target'), 2200);
        if (element.closest('.text-frame-preview')) {
            const rect = element.getBoundingClientRect(), preview = destination.preview;
            const area = preview.getBoundingClientRect();
            if (area.height > 0 && (rect.top < area.top || rect.bottom > area.bottom)) {
                preview.scrollTop += (rect.top - area.top) * preview.clientHeight / area.height;
            }
        }
    }
    return true;
};

let transclusionFrame;
function refreshTransclusions() {
    if (transclusionFrame != null) return;
    transclusionFrame = requestAnimationFrame(() => {
        transclusionFrame = null;
        const byId = new Map();
        for (const state of frames.values()) {
            if (!state.box.classList.contains('tombstoned')) {
                for (const anchor of state.runSources) byId.set(anchor.id, {state, anchor});
            }
        }
        for (const state of frames.values()) {
            let changed = false;
            for (const surface of [state.preview, state.rich]) {
                for (const link of surface.querySelectorAll(`a[href^="${passageLinkPrefix}"]`)) {
                    const id = link.getAttribute('href').slice(passageLinkPrefix.length);
                    const found = byId.get(id);
                    const anchor = found?.anchor;
                    const source = found?.state;
                    const text = normalizeMarkdown(source?.box.contents || '');
                    const live = anchor && !anchor.lost && anchor.end <= text.length &&
                        text.slice(anchor.start, anchor.start + anchor.excerpt.length) === anchor.excerpt;
                    const excerpt = live ? text.slice(anchor.start, anchor.end) : 'Passage lost';
                    const signature = JSON.stringify([live, source?.box.uid, excerpt]);
                    if (link.fieldPassageSignature === signature) continue;
                    link.fieldPassageSignature = signature;
                    link.classList.add('text-frame-transclusion');
                    link.classList.toggle('text-frame-transclusion-lost', !live);
                    link.title = live ? 'Live passage · click to show its source' : 'The source passage is no longer available';
                    link.replaceChildren();
                    if (live) {
                        const rendered = document.createElement('span');
                        renderFieldMarkdown(rendered, excerpt, markdownOptions(source));
                        for (const nested of rendered.querySelectorAll('a')) nested.replaceWith(...nested.childNodes);
                        rendered.querySelectorAll('button').forEach(button => button.remove());
                        const blocks = rendered.querySelector('.ai-chat-markdown')?.children || [];
                        for (const block of [...blocks]) {
                            if (link.childNodes.length) link.append(document.createElement('br'));
                            if (block.matches('p,h1,h2,h3,h4,h5,h6,blockquote')) link.append(...block.childNodes);
                            else link.append(document.createTextNode(block.textContent));
                        }
                        if (!link.childNodes.length) link.textContent = excerpt;
                    } else link.textContent = excerpt;
                    changed = true;
                }
            }
            if (changed) layout(state);
        }
    });
}

function syncRunSources(state) {
    let sources;
    try { sources = JSON.parse(state.box.textRunSources); } catch (_) { /* No transclusions yet. */ }
    state.runSources = Array.isArray(sources) ? sources : [];
    refreshTransclusions();
    if (shownPassage?.source === state) updatePassageHighlight();
}

window.captureTextFrameRun = uid => {
    if (window.currentDocumentReadOnly) return null;
    const state = [...frames.values()].find(item => item.box.uid === uid);
    if (!state || state.composing || state.incoming !== null) return null;
    const selection = window.getSelection();
    const range = selection?.rangeCount ? selection.getRangeAt(0) : null;
    let text, offsets;
    if (state.richEdit && range) {
        text = normalizeMarkdown(state.editor.value);
        offsets = state.richEdit.sourceRange(range);
    } else if (!state.editor.hidden && document.activeElement === state.editor) {
        text = normalizeMarkdown(state.editor.value);
        offsets = {start: state.editor.selectionStart, end: state.editor.selectionEnd};
    } else if (!state.preview.hidden) {
        text = normalizeMarkdown(state.previewSource || '');
        offsets = markdownRangeFromDOM(state.preview, range);
    }
    if (!offsets || offsets.start >= offsets.end || offsets.end > text.length ||
        !runBoundary(text, offsets.start) || !runBoundary(text, offsets.end) ||
        !text.slice(offsets.start, offsets.end).trim()) return null;
    flush(state);
    return JSON.stringify({version: 1, id: crypto.randomUUID(), sourceUid: uid, property: 'contents',
        ...offsets, excerpt: runExcerpt(text, offsets.start, offsets.end)});
};
const defaults = {fontFamily: '"Field Etoile", serif', fontSize: 11, fontWeight: 400, fontStyle: 'normal', color: 'inherit', backgroundColor: 'transparent', textAlign: 'left', letterSpacing: 'normal', lineHeight: 1.5, padding: 16, sizing: 'grow', connectionBorderLength: 100, autocomplete: false, minHeight: 80, reflow: true, css: ''};
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
let nextStyleId = 0;
const localStyleProperty = /^(?:color|background-color|opacity|font(?:-[a-z-]+)?|line-height|letter-spacing|word-spacing|text-(?:align|indent|transform|decoration(?:-[a-z-]+)?)|(?:margin|padding)(?:-[a-z-]+)?|border(?:-(?:top|right|bottom|left|block|inline|start|end|width|style|color|radius))*|white-space|overflow-wrap|list-style-type)$/;
function applyLocalStyle(state, sources) {
    const signature = JSON.stringify(sources);
    if (signature === state.localStyleSource) return;
    state.localStyleSource = signature;
    state.localStyle?.remove();
    state.localStyle = null;
    if (!sources.length) return;

    // Parse off-document. Rebuild individual style rules so document settings cannot introduce
    // global rules, fetch resources, or select anything outside the two formatted surfaces.
    const parsed = new CSSStyleSheet();
    const style = state.localStyle = document.createElement('style');
    document.head.append(style);
    const selector = `.text-frame-preview[data-text-frame-style="${state.styleId}"]`;
    const declarations = document.createElement('span').style;
    for (const source of sources) {
        parsed.replaceSync(source);
        for (const rule of parsed.cssRules) {
            if (!(rule instanceof CSSStyleRule)) continue;
            declarations.cssText = '';
            for (const property of rule.style) if (localStyleProperty.test(property)) {
                declarations.setProperty(property, rule.style.getPropertyValue(property), rule.style.getPropertyPriority(property));
            }
            if (!declarations.length) continue;
            try {
                style.sheet.insertRule(`${selector} :is(${rule.selectorText}) { ${declarations.cssText} }`, style.sheet.cssRules.length);
            } catch (_) { /* Skip selectors that cannot be expressed inside the frame. */ }
        }
    }
}
function readSettings(source) {
    const raw = load(source || '{}', {schema: CORE_SCHEMA}) ?? {};
    if (typeof raw !== 'object' || Array.isArray(raw)) throw new Error('Text frame settings must be a YAML mapping.');
    if (raw.css != null && typeof raw.css !== 'string') throw new Error('Text frame css must be a string. Use css: | for multiple lines.');
    return raw;
}
const styleSettings = new WeakMap();
function frameCss(box) {
    const yaml = box.textFrame;
    const last = styleSettings.get(box);
    if (last?.yaml === yaml) return last?.css || '';
    let css = last?.css || '';
    try { css = readSettings(yaml).css || ''; } catch (_) { /* Keep the last valid style while typing. */ }
    styleSettings.set(box, {yaml, css});
    return css;
}
let inheritanceFrame;
function refreshStyleInheritance() {
    if (inheritanceFrame != null) return;
    inheritanceFrame = requestAnimationFrame(() => {
        inheritanceFrame = null;
        if (window.fieldPropertySyncPending?.()) { refreshStyleInheritance(); return; }
        const boxes = new Map([...document.querySelectorAll('.box:not(.tombstoned)')].map(box => [box.uid, box]));
        for (const state of frames.values()) {
            if (!boxes.has(state.box.uid)) continue;
            // Walk breadth first in Field's parent order. CSS sources are applied in reverse so
            // local rules, then nearer and earlier parents, win when selectors are equally specific.
            const ordered = [state.box];
            const seen = new Set([state.box.uid]);
            for (let i = 0; i < ordered.length; i++) {
                for (const uid of ordered[i].box_parents || []) {
                    if (seen.has(uid) || !boxes.has(uid)) continue;
                    seen.add(uid);
                    ordered.push(boxes.get(uid));
                }
            }
            const sources = ordered.reverse().map(frameCss).filter(Boolean);
            if (state.localStyleSource !== JSON.stringify(sources)) {
                applyLocalStyle(state, sources);
                layout(state);
            }
        }
    });
}
window.refreshTextFrameStyleInheritance = refreshStyleInheritance;
window.textFrameSettingsForChild = source => {
    try {
        const config = readSettings(source);
        if (!Object.hasOwn(config, 'css')) return source;
        delete config.css;
        return dump(config, {schema: CORE_SCHEMA, noRefs: true});
    } catch (_) { return '{}'; }
};
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
    const css = (key, property) => {
        const value = key === 'letterSpacing' && typeof raw[key] === 'number' && Number.isFinite(raw[key])
            ? `${raw[key]}px` : typeof raw[key] === 'string' ? raw[key].trim() : '';
        return value && CSS.supports(property, value) ? value : defaults[key];
    };
    const config = {...raw,
        fontFamily: typeof raw.fontFamily === 'string' && raw.fontFamily.trim() ? raw.fontFamily : defaults.fontFamily,
        fontSize: number('fontSize', 8, 120), fontWeight: number('fontWeight', 100, 900), lineHeight: number('lineHeight', 1, 3), padding: number('padding', 0, 96),
        color: css('color', 'color'), backgroundColor: css('backgroundColor', 'background-color'),
        textAlign: css('textAlign', 'text-align'), fontStyle: css('fontStyle', 'font-style'),
        letterSpacing: css('letterSpacing', 'letter-spacing'),
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
        refreshTransclusions();
    } else {
        box[property] = value;
        if (property === 'textFrame') { syncTextFrameSettingsEditor(box.uid, value); refreshStyleInheritance(); }
        send('set-string', {uid: box.uid, property, value});
    }
}
function flush(state) {
    clearTimeout(state.saveTimer);
    if (state.incoming !== null || !state.dirty) return;
    state.dirty = false;
    write(state.box, 'contents', state.editor.value);
}
function clipboardRange(state, cut = false) {
    if (state.richEdit) {
        const selection = window.getSelection();
        if (!selection?.rangeCount) return null;
        const range = selection.getRangeAt(0);
        const offsets = state.richEdit.sourceRange(range, true);
        if (!cut || !offsets || range.collapsed) return offsets;
        // Carry the Markdown delimiters when cutting an entire formatted span. A cut through
        // only part of that span leaves the existing delimiters around the remaining text.
        const element = range.startContainer.nodeType === Node.TEXT_NODE
            ? range.startContainer.parentElement : range.startContainer;
        const mark = element.closest?.('b,strong,em,i');
        if (mark?.contains(range.endContainer) && range.toString() === mark.textContent) {
            const raw = state.editor.value;
            const bold = mark.matches('b,strong') || mark.querySelector('b,strong');
            const italic = mark.matches('em,i') || mark.querySelector('em,i');
            const delimiter = '*'.repeat((bold ? 2 : 0) + (italic ? 1 : 0));
            if (raw.slice(offsets.start - delimiter.length, offsets.start) === delimiter &&
                raw.slice(offsets.end, offsets.end + delimiter.length) === delimiter) {
                return {start: offsets.start - delimiter.length, end: offsets.end + delimiter.length};
            }
        }
        return offsets;
    }
    return {start: state.editor.selectionStart, end: state.editor.selectionEnd};
}

function applyClipboardRange(state, start, end, inserted, proof = null) {
    const before = normalizeMarkdown(state.editor.value);
    flush(state);
    const after = before.slice(0, start) + inserted + before.slice(end);
    const caret = start + inserted.length;
    if (state.richEdit) {
        state.editor.value = after;
        renderRich(state);
        state.richEdit.placeAtSourceOffset(caret);
        state.captureRichSelection();
    } else {
        state.editor.setRangeText(inserted, start, end, 'end');
        revealTextareaCaret(state.editor);
    }
    state.seenContents = after;
    setTextFrameContents(state.box.uid, after, proof ? {...proof, start, end} : null);
    refreshTransclusions();
    layout(state);
}

function clipboardDocument() {
    return window.currentDocumentSessionKey || window.currentDocumentFilename || '';
}

function copyPassage(event) {
    if (event.defaultPrevented || !event.clipboardData || !clipboardDocument()) return;
    const selection = window.getSelection();
    const range = selection?.rangeCount ? selection.getRangeAt(0) : null;
    const target = event.target.closest?.('.text-frame-box');
    const anchor = range?.startContainer;
    const anchorBox = (anchor?.nodeType === Node.ELEMENT_NODE ? anchor : anchor?.parentElement)?.closest('.text-frame-box');
    const state = frames.get(target || anchorBox);
    if (!state || state.composing || state.incoming !== null) return;
    const editing = !state.editor.hidden || state.richEdit;
    const source = normalizeMarkdown(editing ? state.editor.value : state.previewSource || '');
    const offsets = editing ? clipboardRange(state, true) : markdownRangeFromDOM(state.preview, range);
    if (!offsets || offsets.start >= offsets.end) return;
    if (!runBoundary(source, offsets.start) || !runBoundary(source, offsets.end)) return;
    const quote = source.slice(offsets.start, offsets.end);
    if (!quote.trim() || quote.length > 65536) return;
    const sameRange = a => a && !a.lost && a.sourceUid === state.box.uid && a.start === offsets.start && a.end === offsets.end;
    const known = state.runSources.find(sameRange) ||
        [...frames.values()].map(frame => frame.passageAnchor).find(sameRange);
    const clip = {version:1, token:known?.id || crypto.randomUUID(), sourceUid:state.box.uid,
        sourceStart:offsets.start, sourceEnd:offsets.end, quote, document:clipboardDocument()};
    const data = JSON.stringify(clip);
    const raw = !state.editor.hidden;
    const plain = raw ? quote : range?.toString();
    if (!plain) return;
    const html = document.createElement('div');
    if (!raw) html.append(range.cloneContents());
    try {
        event.clipboardData.setData('text/plain', plain);
        if (!raw) event.clipboardData.setData('text/html', html.innerHTML);
        event.clipboardData.setData(passageCopyType, data);
        event.preventDefault();
        lastPassagePlain = normalizeMarkdown(plain);
        flush(state);
        // Native "paste as plain text" may strip event formats. Also store a web custom
        // format where the platform's async Clipboard API supports it.
        if (event.isTrusted && window.ClipboardItem && navigator.clipboard?.write) {
            const formats = {
                'text/plain': new Blob([plain], {type:'text/plain'}),
                [webPassageCopyType]: new Blob([data], {type:passageCopyType}),
            };
            if (!raw) formats['text/html'] = new Blob([html.innerHTML], {type:'text/html'});
            navigator.clipboard.write([new ClipboardItem(formats)]).catch(() => {});
        }
    } catch (_) { /* The native copy still works when a clipboard format is unavailable. */ }
}
document.addEventListener('copy', copyPassage);

function noteTransclusionShortcut(event, state) {
    if ((event.metaKey || event.ctrlKey) && event.shiftKey && !event.altKey && event.code === 'KeyV') {
        state.transclusionUntil = performance.now() + 1500;
    }
}

function insertTransclusion(state, offsets, serialized) {
    let clip;
    try { clip = JSON.parse(serialized); } catch (_) { return false; }
    if (clip?.version !== 1 || clip.document !== clipboardDocument() || !/^[a-f0-9-]{36}$/.test(clip.token) ||
        typeof clip.quote !== 'string' || !clip.quote.trim() || clip.quote.length > 65536 ||
        !Number.isInteger(clip.sourceStart) || !Number.isInteger(clip.sourceEnd) ||
        ![...frames.values()].some(source => source.box.uid === clip.sourceUid && !source.box.classList.contains('tombstoned'))) return false;
    applyClipboardRange(state, offsets.start, offsets.end, `[Source passage](${passageLinkPrefix}${clip.token})`,
        {action:'transclude', token:clip.token, sourceUid:clip.sourceUid, sourceStart:clip.sourceStart, sourceEnd:clip.sourceEnd, quote:clip.quote});
    return true;
}

function pasteTransclusion(event, state) {
    if (!(state.transclusionUntil > performance.now())) return false;
    state.transclusionUntil = 0;
    if (event.defaultPrevented || !event.clipboardData || state.composing || state.incoming !== null ||
        window.currentDocumentReadOnly) return false;
    const offsets = clipboardRange(state);
    if (!offsets) return false;
    const direct = event.clipboardData.getData(passageCopyType);
    if (direct && insertTransclusion(state, offsets, direct)) {
        event.preventDefault();
        return true;
    }
    const fallback = normalizeMarkdown(event.clipboardData.getData('text/plain'));
    if (!event.isTrusted || !navigator.clipboard?.read || !fallback || fallback !== lastPassagePlain) return false;
    event.preventDefault();
    const value = state.editor.value, rich = state.richEdit;
    (async () => {
        let data;
        try {
            const items = await navigator.clipboard.read();
            const item = items.find(entry => entry.types.includes(webPassageCopyType));
            if (item && item.types.includes('text/plain')) {
                const plain = await (await item.getType('text/plain')).text();
                if (normalizeMarkdown(plain) === fallback) data = await (await item.getType(webPassageCopyType)).text();
            }
        } catch (_) { /* Permission denied: use the ordinary text from the paste event. */ }
        if (state.editor.value !== value || state.richEdit !== rich || state.incoming !== null ||
            (state.editor.hidden && !state.richEdit)) return;
        if (!insertTransclusion(state, offsets, data)) applyClipboardRange(state, offsets.start, offsets.end, fallback);
    })();
    return true;
}

function cutTextRuns(event, state) {
    if (event.defaultPrevented || !event.clipboardData || state.composing || state.incoming !== null ||
        window.currentDocumentReadOnly || !clipboardDocument()) return;
    const offsets = clipboardRange(state, true);
    if (!offsets || offsets.start >= offsets.end) return;
    // Leave ordinary cuts to the editor. A dirty draft may have shifted a live range before its next sync.
    const encloses = a => a && !a.lost && a.sourceUid === state.box.uid &&
        (state.dirty || (a.start >= offsets.start && a.end <= offsets.end));
    const hasRun = state.runSources.some(encloses) || [...frames.values()].some(({passageAnchor}) => encloses(passageAnchor));
    if (!hasRun) return;
    const fragment = normalizeMarkdown(state.editor.value).slice(offsets.start, offsets.end);
    const proof = {version: 1, token: crypto.randomUUID(), sourceUid: state.box.uid, document: clipboardDocument()};
    try {
        event.clipboardData.setData('text/plain', fragment);
        event.clipboardData.setData(textRunCutType, JSON.stringify(proof));
    } catch (_) { return; }
    event.preventDefault();
    event.stopPropagation();
    applyClipboardRange(state, offsets.start, offsets.end, '', {action: 'cut', token: proof.token, sourceUid: proof.sourceUid});
}

function pasteTextRuns(event, state) {
    if (event.defaultPrevented || !event.clipboardData || state.composing || state.incoming !== null ||
        window.currentDocumentReadOnly) return false;
    let proof;
    try { proof = JSON.parse(event.clipboardData.getData(textRunCutType)); } catch (_) { return false; }
    if (proof?.version !== 1 || proof.document !== clipboardDocument() ||
        typeof proof.sourceUid !== 'string' || typeof proof.token !== 'string') return false;
    const offsets = clipboardRange(state);
    const fragment = normalizeMarkdown(event.clipboardData.getData('text/plain'));
    if (!offsets || !fragment) return false;
    event.preventDefault();
    event.stopPropagation();
    applyClipboardRange(state, offsets.start, offsets.end, fragment,
        {action: 'paste', token: proof.token, sourceUid: proof.sourceUid});
    return true;
}

function markdownOptions(state, sourcePositions = false) {
    return {sourcePositions, copyIcon: true, eagerImages: true, resolveImage: src => {
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
    if (!text.trim()) state.preview.textContent = 'Double-click to write';
    if (shownPassage?.source === state) updatePassageHighlight();
    refreshTransclusions();
    layout(state);
}
function layout(state) {
    cancelAnimationFrame(state.layoutFrame);
    state.layoutFrame = requestAnimationFrame(() => {
        if (!state.box.isConnected || state.box.classList.contains('tombstoned') || !(state.box.data_w > 0 && state.box.data_h > 0)) return;
        // A resync drains large property batches over several turns. Never persist layout from
        // a partial box (empty contents or default reflow), or move its neighbours during load.
        if (window.fieldPropertySyncPending?.()) { layout(state); return; }
        const config = settingsFor(state.box);
        if (state.box.getAttribute('data-connection-border-length') !== String(config.connectionBorderLength)) {
            state.box.setAttribute('data-connection-border-length', config.connectionBorderLength);
            window.requestConnectionGroupRebuild?.();
        }
        state.box.style.setProperty('--text-frame-font', config.fontFamily);
        state.box.style.setProperty('--text-frame-size', `${config.fontSize}px`);
        state.box.style.setProperty('--text-frame-weight', config.fontWeight);
        state.box.style.setProperty('--text-frame-color', config.color);
        state.box.style.setProperty('--text-frame-fill', config.backgroundColor);
        state.box.style.setProperty('--text-frame-align', config.textAlign);
        state.box.style.setProperty('--text-frame-style', config.fontStyle);
        state.box.style.setProperty('--text-frame-tracking', config.letterSpacing);
        state.box.style.setProperty('--text-frame-leading', config.lineHeight);
        state.box.style.setProperty('--text-frame-padding', `${config.padding}px`);
        const editing = state.box.classList.contains('text-frame-editing');
        // Formatted content owns the persistent geometry. A taller Markdown source scrolls
        // inside the same frame; measuring it would make every edit/release change the graph.
        if (editing && !state.richEdit && state.measuredDraft !== state.editor.value) {
            state.measuredDraft = state.editor.value;
            state.previewSource = state.measuredDraft;
            renderFieldMarkdown(state.preview, state.measuredDraft, markdownOptions(state));
            state.preview.classList.remove('text-frame-empty');
        }
        const surface = state.richEdit ? state.rich : state.preview;
        const face = getComputedStyle(surface);
        const fontReady = !document.fonts || document.fonts.check(`${face.fontStyle} ${face.fontWeight} ${face.fontSize} ${face.fontFamily}`);
        if (config.sizing === 'grow' && !window.currentDocumentReadOnly && fontReady && document.fonts?.status !== 'loading' &&
            [...surface.querySelectorAll('img')].every(img => img.complete)) {
            // The hidden preview still lays out at the real frame width, including in split panes.
            const height = surface.style.height, bottom = surface.style.bottom;
            surface.style.height = 'auto'; surface.style.bottom = 'auto';
            const measured = Math.max(config.minHeight, Math.ceil(surface.scrollHeight + 2));
            surface.style.height = height; surface.style.bottom = bottom;
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
    if (state.box.classList.contains('text-frame-editing')) {
        (state.richEdit?.first || state.editor).focus();
        return;
    }
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
function renderRich(state) {
    renderFieldMarkdown(state.rich, state.editor.value, markdownOptions(state, true));
    state.richEdit = mountRichText(state.rich, state.editor.value);
    refreshTransclusions();
}
function positionRichToolbar(state) {
    const {box, toolbar} = state;
    if (toolbar.hidden || !box.isConnected) return;
    const rect = box.getBoundingClientRect();
    const pane = box.closest('.field-pane, .field-main-viewport')?.getBoundingClientRect();
    const paneLeft = Math.max(0, pane?.left ?? 0), paneRight = Math.min(innerWidth, pane?.right ?? innerWidth);
    const paneTop = Math.max(0, pane?.top ?? 0), paneBottom = Math.min(innerHeight, pane?.bottom ?? innerHeight);
    const height = toolbar.offsetHeight, width = toolbar.offsetWidth;
    const caret = state.richSelection?.caret.getBoundingClientRect();
    const scroll = state.rich.getBoundingClientRect();
    const anchor = caret?.height ? Math.max(scroll.top, Math.min(caret.top + caret.height / 2, scroll.bottom))
        : Math.max(rect.top, paneTop) + height / 2;
    const left = Math.max(paneLeft, Math.min(rect.left - width - 4, paneRight - width));
    const top = Math.max(paneTop, Math.min(anchor - height / 2, paneBottom - height));
    toolbar.style.transform = `translate(${left}px, ${top}px)`;
    toolbar.style.visibility = rect.width && rect.right > paneLeft && rect.left < paneRight &&
        rect.bottom > paneTop && rect.top < paneBottom ? 'visible' : 'hidden';
    state.toolbarFrame = requestAnimationFrame(() => positionRichToolbar(state));
}
function beginRich(state, point) {
    if (window.currentDocumentReadOnly) return;
    if (state.richEdit) { state.richEdit.first.focus(); return; }
    if (state.box.classList.contains('text-frame-editing')) { state.editor.focus(); return; }
    flushTextFramePropertyEditor(state.box.uid);
    state.editor.value = state.box.contents || '';
    renderRich(state);
    state.box.classList.add('text-frame-editing');
    state.preview.hidden = true;
    state.rich.hidden = state.toolbar.hidden = false;
    placeRichCaret(state.rich, point, state.richEdit.first);
    state.captureRichSelection();
    positionRichToolbar(state);
    layout(state);
}
function draftChanged(state) {
    state.dirty = true;
    if (!state.composing) {
        clearTimeout(state.saveTimer);
        state.saveTimer = setTimeout(() => flush(state), 150);
    }
    layout(state);
}
function richChanged(state) {
    if (!state.richEdit) return;
    const markdown = state.richEdit.markdown();
    if (markdown === state.editor.value) return;
    state.editor.value = markdown;
    draftChanged(state);
}
function openSource(state) {
    if (state.composing || state.incoming !== null || window.currentDocumentReadOnly) return;
    if (state.box.classList.contains('text-frame-editing')) finish(state);
    window.switchToProperty(state.box.uid, 'contents');
    window.toggleNotesMarkdownPreview(window.globalEditor, 'source');
}
function finish(state) {
    if (state.composing || !state.box.classList.contains('text-frame-editing')) return;
    flush(state);
    // A concurrent edit never silently discards this view's draft. Resolve it before closing.
    if (state.incoming !== null) return;
    if (!state.richEdit) state.sourceSelection = {value: state.editor.value, start: state.editor.selectionStart,
        end: state.editor.selectionEnd, direction: state.editor.selectionDirection, scrollTop: state.editor.scrollTop};
    state.autocomplete?.clear();
    state.richEdit = null;
    state.richSelection = null;
    cancelAnimationFrame(state.toolbarFrame);
    state.rich.hidden = state.toolbar.hidden = true;
    state.rich.replaceChildren();
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
    if (!useMine) {
        state.editor.value = state.incoming;
        if (state.richEdit) renderRich(state);
    }
    state.incoming = null;
    state.conflict.hidden = true;
    state.dirty = useMine;
    flush(state);
    (state.richEdit?.first || state.editor).focus({preventScroll: true});
    layout(state);
}
function mount(box) {
    const body = node('div', 'text-frame-body');
    const preview = node('div', 'text-frame-preview');
    preview.title = 'Double-click to write';
    preview.tabIndex = -1;
    preview.dataset.fieldCanvasKeyboard = 'true';
    preview.setAttribute('aria-label', 'Text frame; press Enter to write');
    const editor = node('textarea', 'text-frame-editor');
    editor.setAttribute('aria-label', 'Markdown contents');
    editor.placeholder = 'Write Markdown…'; editor.spellcheck = true; editor.hidden = true;
    const conflict = node('div', 'text-frame-conflict'); conflict.hidden = true; conflict.setAttribute('role', 'alert');
    const state = {box, body, preview, editor, conflict, seenContents: box.contents || '', incoming: null, dirty: false, composing: false};
    state.passageBadge = button('', () => showPassage(state), 'text-frame-passage');
    state.passageBadge.hidden = true;
    state.overflow = button('+', () => beginRich(state), 'text-frame-overflow');
    state.overflow.title = 'Text overflows this frame. Click to edit all text.';
    state.overflow.setAttribute('aria-label', 'Overflowed text; edit all text'); state.overflow.hidden = true;
    conflict.append(node('span', '', 'Contents changed elsewhere. Your draft is kept here.'),
        button('Keep my version', () => resolveConflict(state, true)), button('Use incoming', () => resolveConflict(state, false)));
    const rich = state.rich = node('div', 'text-frame-preview text-frame-rich');
    state.styleId = ++nextStyleId;
    preview.dataset.textFrameStyle = rich.dataset.textFrameStyle = state.styleId;
    const toolbar = state.toolbar = node('div', 'text-frame-rich-toolbar');
    toolbar.dataset.textFrameUid = box.uid;
    toolbar.setAttribute('role', 'group');
    toolbar.setAttribute('aria-label', 'Text formatting');
    rich.hidden = toolbar.hidden = true;
    const format = (command, value) => {
        state.captureRichSelection();
        const selection = window.getSelection();
        const saved = state.richSelection;
        if (!saved || !rich.contains(saved.host)) return;
        if (document.activeElement !== saved.host) {
            saved.host.focus({preventScroll: true});
            selection.removeAllRanges();
            selection.addRange(saved.range);
        }
        document.execCommand('styleWithCSS', false, 'false');
        document.execCommand(command, false, value);
        richChanged(state);
        state.captureRichSelection();
    };
    const headings = [['Text', 'p'], ['H1', 'h1'], ['H2', 'h2'], ['H3', 'h3']];
    const headingControls = new Map();
    for (const [label, tag] of headings) {
        const control = button(label, () => format('formatBlock', tag));
        control.title = tag === 'p' ? 'Paragraph' : 'Heading ' + tag[1];
        control.setAttribute('aria-label', control.title);
        control.setAttribute('aria-pressed', 'false');
        headingControls.set(tag, control);
        toolbar.append(control);
    }
    for (const [label, command] of [['B', 'bold'], ['I', 'italic']]) {
        const control = button(label, () => format(command), 'text-frame-format-' + command);
        control.setAttribute('aria-label', command === 'bold' ? 'Bold' : 'Italic');
        toolbar.append(control);
    }
    const source = button('', () => openSource(state), 'text-frame-format-source');
    source.title = 'Edit Markdown source';
    source.setAttribute('aria-label', source.title);
    source.innerHTML = `<svg width="13" height="13" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><path d="M3 8h10m-4-4 4 4-4 4"/></svg>`;
    toolbar.append(source);
    toolbar.addEventListener('mousedown', e => e.preventDefault());
    state.captureRichSelection = () => {
        const selection = window.getSelection(), host = document.activeElement;
        if (host?.matches('.text-frame-rich-block') && rich.contains(host) && selection?.rangeCount &&
            host.contains(selection.anchorNode) && host.contains(selection.focusNode)) {
            const caret = document.createRange();
            caret.setStart(selection.focusNode, selection.focusOffset);
            caret.collapse(true);
            state.richSelection = {host, range: selection.getRangeAt(0).cloneRange(), caret};
            const anchor = selection.focusNode;
            const element = anchor.nodeType === Node.ELEMENT_NODE ? anchor : anchor.parentElement;
            const target = anchor === host
                ? host.childNodes[selection.focusOffset] || host.lastChild : element;
            const line = target?.closest?.('p,h1,h2,h3,h4,h5,h6');
            const tag = line?.tagName.toLowerCase() ?? 'p';
            for (const [style, control] of headingControls) control.setAttribute('aria-pressed', String(style === tag));
        }
    };
    rich.addEventListener('pointerup', state.captureRichSelection);
    rich.addEventListener('keyup', state.captureRichSelection);
    rich.addEventListener('focusout', state.captureRichSelection);
    body.append(preview, editor, rich); box.append(body, state.overflow, conflict, state.passageBadge);
    // The foreground connections sit outside the canvas's stacking context.
    document.body.append(toolbar);
    box.classList.add('text-frame-box');
    // Let a double-click edit a link label without following its first click. Modified clicks
    // keep normal browser navigation; an ordinary single click follows after the double-click window.
    preview.addEventListener('click', e => {
        const anchor = e.target.closest('a[href]');
        if (!anchor || anchor.classList.contains('text-frame-transclusion') || state.followingLink || window.currentDocumentReadOnly || e.ctrlKey || e.metaKey || e.altKey) return;
        e.preventDefault(); e.stopImmediatePropagation();
        clearTimeout(state.linkTimer);
        if (e.detail < 2) state.linkTimer = setTimeout(() => {
            if (e.shiftKey || !anchor.isConnected || box.classList.contains('text-frame-editing')) return;
            state.followingLink = true;
            try { anchor.click(); } finally { state.followingLink = false; }
        }, 500);
    }, true);
    preview.addEventListener('mousedown', e => {
        // Shift-click belongs to box selection here; prevent the browser from extending a text range.
        if (e.button === 0 && (e.shiftKey || e.altKey)) e.preventDefault();
    });
    preview.addEventListener('dblclick', e => {
        if (window.currentDocumentReadOnly || e.target.closest('button')) return;
        clearTimeout(state.linkTimer);
        e.preventDefault(); e.stopPropagation();
        if (e.shiftKey) {
            const offset = markdownOffsetAtPoint(preview, e.clientX, e.clientY, e.target);
            begin(state, offset, state.previewSource);
        } else beginRich(state, e);
    });
    rich.addEventListener('input', () => richChanged(state));
    rich.addEventListener('compositionstart', () => { state.composing = true; clearTimeout(state.saveTimer); });
    rich.addEventListener('compositionend', () => { state.composing = false; richChanged(state); flush(state); });
    rich.addEventListener('cut', e => cutTextRuns(e, state));
    rich.addEventListener('paste', e => {
        if (pasteTransclusion(e, state) || pasteTextRuns(e, state)) return;
        const session = state.richEdit;
        pasteRichText(e, rich, () => state.richEdit === session, () => richChanged(state));
    });
    rich.addEventListener('drop', e => e.preventDefault());
    rich.addEventListener('click', e => {
        if (e.target.closest('a:not(.text-frame-transclusion)')) { e.preventDefault(); e.stopImmediatePropagation(); }
    }, true);
    rich.addEventListener('keydown', e => {
        noteTransclusionShortcut(e, state);
        e.stopPropagation();
        if (e.isComposing) return;
        const shortcut = e.metaKey || e.ctrlKey;
        const command = {b: 'bold', i: 'italic'}[e.key.toLowerCase()];
        if (shortcut && command) { e.preventDefault(); format(command); }
        else if (e.key === 'Escape' || (shortcut && e.key === 'Enter')) {
            e.preventDefault();
            finish(state);
            if (state.incoming === null) preview.focus({preventScroll: true});
        } else moveBetweenRichBlocks(e, rich);
    });
    editor.addEventListener('cut', e => cutTextRuns(e, state));
    editor.addEventListener('paste', e => pasteTransclusion(e, state) || pasteTextRuns(e, state));
    editor.addEventListener('input', () => draftChanged(state));
    editor.addEventListener('compositionstart', () => { state.composing = true; clearTimeout(state.saveTimer); });
    editor.addEventListener('compositionend', () => { state.composing = false; state.dirty = true; flush(state); layout(state); });
    state.autocomplete = mountChatAutocomplete(body, editor, {
        chatUid: () => box.uid, chatProperty: () => 'contents', requestName: 'ai-text-frame-suggest',
        isEnabled: () => settingsFor(box).autocomplete === true && state.incoming === null && !state.composing
    });
    editor.addEventListener('keydown', e => {
        noteTransclusionShortcut(e, state);
        e.stopPropagation();
        if (e.defaultPrevented) return;
        if (!e.isComposing && (e.key === 'Escape' || ((e.metaKey || e.ctrlKey) && e.key === 'Enter'))) {
            e.preventDefault(); finish(state); if (state.incoming === null) preview.focus({preventScroll: true});
        }
    });
    editor.addEventListener('blur', () => queueMicrotask(() => {
        // Monaco can briefly focus its own input while syncing a peer value, then restore us.
        if (!editor.hidden && document.activeElement !== editor) finish(state);
    }));
    for (const element of [editor, rich, toolbar, conflict, state.overflow, state.passageBadge]) {
        for (const name of ['pointerdown', 'mousedown', 'dblclick', 'wheel', 'keyup', 'contextmenu']) {
            element.addEventListener(name, e => {
                e.stopPropagation();
                if (name === 'mousedown' && e.altKey && e.button === 0 && (element === editor || element === rich)) {
                    e.preventDefault();
                    requestCustomMarkingMenu(box.uid, e.clientX, e.clientY, e);
                }
            });
        }
    }
    // Loaded images, math typesetting and a width resize can all change the amount of text that fits.
    body.addEventListener('load', () => layout(state), true);
    body.addEventListener('error', () => layout(state), true);
    state.observer = new ResizeObserver(() => layout(state));
    state.observer.observe(box); state.observer.observe(preview);
    frames.set(box, state);
    updatePassageBadge(state);
    syncRunSources(state);
    refreshStyleInheritance();
    render(state);
    return state;
}
export function syncTextFrame(box, property) {
    if (property === 'textFrame') refreshStyleInheritance();
    let state = frames.get(box);
    if (box.box_kind !== 'text-frame') {
        if (state) {
            flush(state); state.observer.disconnect(); state.autocomplete.dispose(); cancelAnimationFrame(state.layoutFrame);
            cancelAnimationFrame(state.toolbarFrame); state.localStyle?.remove();
            for (const part of [state.body, state.overflow, state.conflict, state.toolbar, state.passageBadge]) part.remove();
            box.classList.remove('text-frame-box', 'text-frame-editing', 'text-frame-has-overflow'); frames.delete(box);
        }
        return;
    }
    if (!state) { mount(box); return; }
    if (property === 'textRunAnchor') { updatePassageBadge(state); return; }
    if (property === 'textRunSources') { syncRunSources(state); return; }
    if (property === 'contents') refreshTransclusions();
    if (property === 'textFrame') { layout(state); return; }
    if (box.classList.contains('text-frame-editing')) {
        const incoming = box.contents || '';
        if (property === 'contents' && incoming !== state.seenContents) {
            if (incoming !== state.editor.value) showConflict(state, incoming);
            else { state.incoming = null; state.conflict.hidden = true; state.dirty = false; }
        }
        if (property === 'contents') state.seenContents = incoming;
        layout(state);
    } else if (state.previewSource !== (box.contents || '')) render(state);
}
window.syncTextFrame = syncTextFrame;
window.editTextFrame = box => {
    syncTextFrame(box);
    const state = frames.get(box);
    if (!state) return;
    if (state.richEdit) finish(state);
    begin(state);
};
window.writeTextFrame = box => { syncTextFrame(box); const state = frames.get(box); if (state) beginRich(state); };
window.openTextFrameMarkdown = uid => {
    const state = [...frames.values()].find(state => state.box.uid === uid);
    if (state) openSource(state);
};
// Module loading and the server's initial resync can arrive in either order.
document.querySelectorAll('.box').forEach(syncTextFrame);
new MutationObserver(() => {
    for (const [box, state] of frames) if (!box.isConnected) {
        clearTimeout(state.saveTimer); cancelAnimationFrame(state.layoutFrame); state.observer.disconnect(); state.autocomplete.dispose(); frames.delete(box);
        cancelAnimationFrame(state.toolbarFrame); state.toolbar.remove(); state.localStyle?.remove();
        refreshTransclusions();
    }
}).observe(document.body, {childList: true, subtree: true});
window.addEventListener('pagehide', () => frames.forEach(flush));

// Webfont arrival changes line breaks even when the frame's fixed border box has not resized.
document.fonts?.addEventListener('loadingdone', () => frames.forEach(layout));

// Canvas gestures can prevent the browser's default focus change. Release the editor explicitly.
document.addEventListener('pointerdown', event => {
    for (const state of frames.values()) {
        if (state.box.classList.contains('text-frame-editing') &&
            !state.box.contains(event.target) && !state.toolbar.contains(event.target)) finish(state);
    }
}, true);
document.addEventListener('selectionchange', () => {
    const host = document.activeElement?.closest('.text-frame-rich-block');
    if (host) frames.get(host.closest('.text-frame-box'))?.captureRichSelection();
});

// DOM focus can outlive a canvas selection (notably after spatial navigation). The selected
// box owns Return; a previously focused reader must never reopen itself instead.
const canvasSelection = () => Array.from(window.selectedList || []).filter(box =>
    box.isConnected && !box.classList.contains('tombstoned') &&
    getComputedStyle(box).display !== 'none' && getComputedStyle(box).visibility !== 'hidden');
window.addEventListener('field-selection-changed', () => {
    const selection = canvasSelection();
    if (shownPassage && !selection.includes(shownPassage.source.box)) { shownPassage = null; updatePassageHighlight(); }
    for (const state of frames.values()) if (!selection.includes(state.box)) {
        if (state.box.classList.contains('text-frame-editing')) finish(state);
        const focused = document.activeElement;
        if (focused === state.editor || state.preview.contains(focused) || state.rich.contains(focused) || state.toolbar.contains(focused)) focused.blur();
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
    if (box.box_kind === 'text-frame') window.writeTextFrame(box);
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
