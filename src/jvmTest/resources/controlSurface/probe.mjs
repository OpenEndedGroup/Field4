// Runs the SHIPPED `controlLaws.js` and `sliders.js` against the HTML the Kotlin control builder
// actually emitted.
//
// Nothing here re-describes what the browser is supposed to do. Both files are staged verbatim off the
// classpath by `ControlSurfaceProbeTest`, loaded as the classic scripts they are, and driven through
// the entry points the real page uses: the delegated `pointerdown` that `addDelegatedListeners`
// installs, `window.applyFieldControlUpdate` for a value arriving from the server, and
// `window.sendSliderUpdate` / `sendViaAfield` for what goes back. The assertions are on the numbers
// that leave — which is what the JVM decodes — and on what the DOM ends up showing, which is what a
// person reads.
//
// The DOM is a stub, and deliberately a small one: an HTML parser for the markup the builder emits,
// class/id/attribute selectors, `dataset`, `classList`, `textContent`, and a `getBoundingClientRect`
// with a caller-chosen scale so the pointer→view transform is exercised rather than assumed. What it
// leaves out is browser hit-testing and layout: each case says which element the pointer went down on.
//
// Usage: node probe.mjs <cases.json> <out-observed.json>

import fs from 'node:fs';

const CASES = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
const OUT = process.argv[3];

// ---------------------------------------------------------------- a very small HTML DOM

const ENTITIES = { '&amp;': '&', '&lt;': '<', '&gt;': '>', '&quot;': '"', '&#39;': "'" };
// Only tags the builder really leaves unclosed. SVG shapes are NOT here: the emitted markup writes
// `<circle …></circle>`, and treating those as void made the closing tag pop the <svg> instead, which
// silently hid every child from `querySelector` — the first thing this probe caught.
const VOID_TAGS = new Set(['input', 'br', 'hr', 'img', 'meta', 'link']);

function unescapeAttribute(value) {
    return value.replace(/&(amp|lt|gt|quot|#39);/g, (m) => ENTITIES[m]);
}

function camel(name) {
    return name.replace(/-([a-z0-9])/g, (_, c) => c.toUpperCase());
}

function dashed(name) {
    return name.replace(/[A-Z]/g, (c) => `-${c.toLowerCase()}`);
}

class StubElement {
    constructor(tagName, attributes, doc) {
        this.tagName = tagName;
        this.nodeType = 1;
        this.attributes = attributes || {};
        this.children = [];
        this.parentNode = null;
        this.ownerDocument = doc || null;
        this.text = '';
        this.listeners = {};
        // `dataset` is a live view of the data-* attributes, both ways, because the code under test
        // both reads (`container.dataset.min`) and writes (`container.dataset.value = v`) through it.
        const element = this;
        this.dataset = new Proxy({}, {
            get(_, key) {
                const found = Object.keys(element.attributes)
                    .find((name) => name.startsWith('data-') && camel(name.slice(5)) === key);
                return found === undefined ? undefined : element.attributes[found];
            },
            set(_, key, value) {
                element.attributes[`data-${dashed(String(key))}`] = String(value);
                return true;
            },
            has(_, key) {
                return Object.keys(element.attributes)
                    .some((name) => name.startsWith('data-') && camel(name.slice(5)) === key);
            }
        });
    }

    get id() { return this.getAttribute('id') || ''; }

    get className() { return this.getAttribute('class') || ''; }

    get classList() {
        const element = this;
        const list = () => (element.getAttribute('class') || '').split(/\s+/).filter(Boolean);
        return {
            contains: (name) => list().includes(name),
            add: (name) => {
                const now = list();
                if (!now.includes(name)) element.setAttribute('class', now.concat(name).join(' '));
            },
            remove: (name) => element.setAttribute('class', list().filter((n) => n !== name).join(' ')),
            toggle: (name, on) => (on ? element.classList.add(name) : element.classList.remove(name))
        };
    }

    get textContent() {
        if (this.children.length === 0) return this.text;
        return this.text + this.children.map((child) => child.textContent).join('');
    }

    set textContent(value) {
        this.children = [];
        this.text = String(value);
    }

    getAttribute(name) {
        return Object.prototype.hasOwnProperty.call(this.attributes, name) ? this.attributes[name] : null;
    }

    setAttribute(name, value) { this.attributes[name] = String(value); }

    removeAttribute(name) { delete this.attributes[name]; }

    hasAttribute(name) { return Object.prototype.hasOwnProperty.call(this.attributes, name); }

    appendChild(child) {
        child.parentNode = this;
        this.children.push(child);
        return child;
    }

    descendants() {
        const out = [];
        const walk = (node) => node.children.forEach((child) => { out.push(child); walk(child); });
        walk(this);
        return out;
    }

    /** One compound selector: `tag`, `.class`, `#id`, `[attr]`, `[attr="value"]`, and combinations. */
    matchesOne(selector) {
        const s = selector.trim();
        if (!s) return false;
        let rest = s;
        const tagMatch = rest.match(/^([a-zA-Z][a-zA-Z0-9-]*)/);
        if (tagMatch) {
            if (this.tagName !== tagMatch[1]) return false;
            rest = rest.slice(tagMatch[1].length);
        }
        const tokens = rest.match(/(\.[A-Za-z0-9_-]+|#[A-Za-z0-9_:.-]+|\[[^\]]+\])/g) || [];
        if (tokens.join('').length !== rest.length) {
            throw new Error(`the stub DOM cannot parse the selector ${JSON.stringify(selector)}`);
        }
        return tokens.every((token) => {
            if (token.startsWith('.')) return this.classList.contains(token.slice(1));
            if (token.startsWith('#')) return this.id === token.slice(1).replace(/\\(.)/g, '$1');
            const body = token.slice(1, -1);
            const eq = body.indexOf('=');
            if (eq < 0) return this.getAttribute(body) !== null;
            const name = body.slice(0, eq);
            const wanted = body.slice(eq + 1).replace(/^["']|["']$/g, '').replace(/\\(.)/g, '$1');
            return this.getAttribute(name) === wanted;
        });
    }

    matches(selector) {
        return String(selector).split(',').some((part) => this.matchesOne(part));
    }

    querySelectorAll(selector) {
        return this.descendants().filter((node) => node.matches(selector));
    }

    querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }

    closest(selector) {
        let node = this;
        while (node) {
            if (node.nodeType === 1 && node.matches(selector)) return node;
            node = node.parentNode;
        }
        return null;
    }

    /**
     * Where this element is on screen.
     *
     * The scale comes from the case, not from a guess: the code under test divides by `rect.width`, so
     * a case that renders a 320-unit viewBox into 640 CSS pixels proves the pointer→view transform
     * rather than letting a 1:1 layout hide it.
     */
    getBoundingClientRect() {
        const doc = this.ownerDocument || {};
        const scale = doc.__scale === undefined ? 1 : doc.__scale;
        const viewWidth = parseFloat((this.getAttribute('viewBox') || '0 0 320 44').split(/\s+/)[2]);
        const height = parseFloat((this.getAttribute('viewBox') || '0 0 320 44').split(/\s+/)[3]);
        return {
            left: doc.__originX === undefined ? 0 : doc.__originX,
            top: doc.__originY === undefined ? 0 : doc.__originY,
            width: viewWidth * scale,
            height: height * scale,
            right: (doc.__originX || 0) + viewWidth * scale,
            bottom: (doc.__originY || 0) + height * scale
        };
    }

    setPointerCapture() {}
    releasePointerCapture() {}
    focus() { if (this.ownerDocument) this.ownerDocument.activeElement = this; }

    addEventListener(type, fn) {
        (this.listeners[type] = this.listeners[type] || []).push(fn);
    }

    removeEventListener(type, fn) {
        this.listeners[type] = (this.listeners[type] || []).filter((f) => f !== fn);
    }

    /**
     * Bubbles to the root, capture-phase listeners included, which is how the delegation is wired.
     *
     * The document is reached through `parentNode` when the element is mounted, and appended explicitly
     * when it is not — either way it is visited exactly once, because a delegated handler that ran twice
     * would make a double-send look like correct behaviour.
     */
    dispatchEvent(event) {
        event.target = event.target || this;
        let node = this;
        const chain = [];
        while (node) { chain.push(node); node = node.parentNode; }
        const doc = this.ownerDocument;
        if (doc && !chain.includes(doc)) chain.push(doc);
        chain.forEach((n) => (n.listeners[event.type] || []).slice().forEach((fn) => fn.call(n, event)));
        return true;
    }
}

function parseHtml(html, doc) {
    const root = new StubElement('#root', {}, doc);
    const stack = [root];
    const tokenizer = /<\/([a-zA-Z][a-zA-Z0-9-]*)\s*>|<([a-zA-Z][a-zA-Z0-9-]*)((?:\s+[^\s=/>]+(?:\s*=\s*(?:"[^"]*"|'[^']*'|[^\s>]+))?)*)\s*(\/?)>|([^<]+)/g;
    let match;
    while ((match = tokenizer.exec(html)) !== null) {
        const [, closing, opening, attrText, selfClose, text] = match;
        if (closing) {
            // Pop to the nearest matching open element, and ignore a stray closer outright, so one
            // unbalanced tag cannot silently reparent the rest of the document.
            const at = stack.map((n) => n.tagName).lastIndexOf(closing);
            if (at > 0) stack.length = at;
        } else if (opening) {
            const attributes = {};
            const attrRe = /([^\s=/>]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+)))?/g;
            let a;
            while ((a = attrRe.exec(attrText || '')) !== null) {
                const raw = a[2] !== undefined ? a[2] : a[3] !== undefined ? a[3] : a[4] !== undefined ? a[4] : '';
                attributes[a[1]] = unescapeAttribute(raw);
            }
            const element = new StubElement(opening, attributes, doc);
            stack[stack.length - 1].appendChild(element);
            if (!selfClose && !VOID_TAGS.has(opening)) stack.push(element);
        } else if (text && text.trim()) {
            const parent = stack[stack.length - 1];
            parent.text += text.trim();
        }
    }
    return root;
}

// ---------------------------------------------------------------- the globals the scripts expect

const posted = [];

function makeDocument() {
    const doc = new StubElement('#document', {}, null);
    doc.ownerDocument = doc;
    doc.__scale = 1;
    doc.activeElement = null;
    doc.body = new StubElement('body', {}, doc);
    doc.appendChild(doc.body);
    doc.getElementById = (id) => doc.descendants().find((node) => node.id === id) || null;
    doc.createElement = (tag) => new StubElement(tag, {}, doc);
    return doc;
}

const document = makeDocument();
const window = {
    document,
    CSS: { escape: (v) => String(v).replace(/(["\\\]])/g, '\\$1') },
    listeners: {},
    addEventListener(type, fn) { (this.listeners[type] = this.listeners[type] || []).push(fn); },
    removeEventListener(type, fn) { this.listeners[type] = (this.listeners[type] || []).filter((f) => f !== fn); },
    dispatch(type, event) { (this.listeners[type] || []).slice().forEach((fn) => fn({ type, ...event })); },
    // Left undefined on purpose: `sliders.js` guards on it, and leaving it out keeps the probe from
    // depending on a mutation-observer implementation that would only ever re-run `refreshControls`.
    MutationObserver: undefined
};
window.window = window;

globalThis.window = window;
globalThis.document = document;
globalThis.requestAnimationFrame = (fn) => { fn(0); return 0; };
globalThis.setTimeout = (fn) => { fn(); return 0; };
globalThis.clearTimeout = () => {};

/**
 * Lets the pending microtasks run.
 *
 * This matters more than it looks: `sendViaAfield` in `sliders.js` routes every outbound message
 * through a promise (it lazily imports `afield.mjs`), so nothing has been "posted" at the instant the
 * synchronous handler returns. A probe that read the transport immediately would observe an empty queue
 * and could only conclude that the control was broken — which is exactly what the first run of this file
 * concluded.
 */
const flush = () => new Promise((resolve) => setImmediate(resolve));
globalThis.getComputedStyle = () => ({ getPropertyValue: () => '' });
// `navigator` is a getter-only global from node 20 on, so it is patched rather than assigned.
try { Object.defineProperty(globalThis, 'navigator', { value: { userAgent: 'field-control-probe' }, configurable: true }); } catch (_) {}

// The transport. `sliders.js` funnels every outbound control message through the lazy `afield.mjs`
// import; giving it a synchronous `afield` short-circuits that and records what would have gone out.
globalThis.afield = {
    send(type, payload) { posted.push({ type, payload }); }
};

function loadScript(path) {
    const source = fs.readFileSync(path, 'utf8');
    // Indirect eval so the scripts land in the global scope the way a <script> tag puts them there.
    // eslint-disable-next-line no-eval
    (0, eval)(source);
}

loadScript(CASES.controlLawsPath);
loadScript(CASES.slidersPath);

if (!window.FieldControlLaw) throw new Error('controlLaws.js did not publish window.FieldControlLaw');
if (typeof window.applyFieldControlUpdate !== 'function') {
    throw new Error('sliders.js did not publish applyFieldControlUpdate');
}

// `DOMContentLoaded` is what installs the delegated pointer listeners and does the first refresh.
function domReady() {
    (document.listeners.DOMContentLoaded || []).slice().forEach((fn) => fn({ type: 'DOMContentLoaded' }));
}

// ---------------------------------------------------------------- driving a control

function mount(html) {
    const parsed = parseHtml(html, document);
    parsed.children.slice().forEach((child) => document.body.appendChild(child));
}

/** A pointer press at a fraction of the way along an element's on-screen box, then a release. */
function pressAt(element, fraction, { release = true } = {}) {
    const rect = element.getBoundingClientRect();
    const clientX = rect.left + rect.width * fraction;
    const clientY = rect.top + rect.height / 2;
    element.dispatchEvent({
        type: 'pointerdown', target: element, clientX, clientY, pointerId: 1,
        preventDefault() {}, stopPropagation() {}
    });
    if (release) window.dispatch('pointerup', { clientX, clientY, pointerId: 1 });
    return clientX;
}

/**
 * A key going down and coming back up on a focused element.
 *
 * The `repeat` case is the one that matters: holding space on a real button fires `keydown` over and
 * over, and a momentary control that posted a `true` for each of them would flood the socket. So the
 * probe repeats the keydown deliberately.
 */
function keyPress(element, key, { repeats = 0, release = true } = {}) {
    const send = (type) => element.dispatchEvent({
        type, target: element, key, code: key === ' ' ? 'Space' : key,
        preventDefault() {}, stopPropagation() {}
    });
    send('keydown');
    for (let i = 0; i < repeats; i++) send('keydown');
    if (release) send('keyup');
}

function dragTo(element, fraction) {
    const rect = element.getBoundingClientRect();
    window.dispatch('pointermove', {
        clientX: rect.left + rect.width * fraction,
        clientY: rect.top + rect.height / 2,
        pointerId: 1
    });
}

const observed = { laws: [], buttons: [], meters: [], decimals: [] };
const failures = [];

function check(condition, message) {
    if (!condition) failures.push(message);
}

function near(actual, expected, tolerance, message) {
    const ok = Number.isFinite(actual) && Number.isFinite(expected) && Math.abs(actual - expected) <= tolerance;
    if (!ok) failures.push(`${message}: got ${actual}, wanted ${expected} ± ${tolerance}`);
    return ok;
}

// ---------------------------------------------------------------- 1. the law, through the real markup

domReady();

for (const testCase of CASES.sliders) {
    document.__scale = testCase.scale === undefined ? 1 : testCase.scale;
    document.__originX = testCase.originX === undefined ? 0 : testCase.originX;
    mount(testCase.html);

    const container = document.querySelector(`.field-slider[data-slider-id="${testCase.id}"]`);
    if (!container) { failures.push(`${testCase.name}: the emitted HTML has no .field-slider`); continue; }
    const svg = container.querySelector('.field-slider-visual');
    if (!svg) { failures.push(`${testCase.name}: the emitted HTML has no .field-slider-visual`); continue; }

    // (a) The handle position Kotlin baked into the markup is where the JS puts it too. Both are read
    // AFTER a refresh, so this is the JS's own answer against the server's.
    const bakedCx = parseFloat(svg.querySelector('.slider-thumb').getAttribute('cx'));
    const spec = window.FieldControlLaw.specFromDataset(container.dataset);
    const jsCx = 14 + (320 - 28) * window.FieldControlLaw.normalizedFor(spec, parseFloat(container.dataset.value));
    near(jsCx, bakedCx, 1e-6, `${testCase.name}: the baked thumb position disagrees with the shipped law`);

    // (b) Drive the delegated pointer path at each stated fraction of the track and record what the
    // browser would have posted.
    const values = [];
    for (const fraction of testCase.fractions) {
        posted.length = 0;
        // The fractions are of the TRACK, so convert to a fraction of the whole box (padding included).
        const padding = 14 / 320;
        const boxFraction = padding + fraction * (1 - 2 * padding);
        pressAt(svg, boxFraction);
        await flush();
        const sent = posted.filter((m) => m.type === 'slider-update').pop();
        values.push({
            fraction,
            sent: sent ? sent.payload.value : null,
            dataset: parseFloat(container.dataset.value),
            shown: document.getElementById(`${testCase.id}-value`)?.textContent ?? null
        });
    }
    observed.laws.push({ name: testCase.name, law: spec.law, values });

    // (c) A value arriving from the server puts the handle in the law's place, not a linear one.
    for (const arriving of testCase.arriving || []) {
        window.applyFieldControlUpdate({ id: testCase.id, type: 'slider', value: arriving.value });
        await flush();
        const cx = parseFloat(svg.querySelector('.slider-thumb').getAttribute('cx'));
        near(cx, arriving.expectCx, 1e-6, `${testCase.name}: a value of ${arriving.value} lands at the wrong x`);
        const shown = document.getElementById(`${testCase.id}-value`)?.textContent ?? '';
        if (arriving.expectText !== undefined) {
            check(shown === arriving.expectText,
                `${testCase.name}: ${arriving.value} shows as ${JSON.stringify(shown)}, wanted ${JSON.stringify(arriving.expectText)}`);
        }
    }

    document.body.children = [];
}

// ---------------------------------------------------------------- 2. the decimal rule, both spellings

for (const row of CASES.decimals) {
    const got = window.FieldControlLaw.decimalsFor(row.value, row.significantDigits, row.stepDecimals);
    observed.decimals.push({ ...row, got });
    check(got === row.expect,
        `decimalsFor(${row.value}, ${row.significantDigits}, ${row.stepDecimals}) = ${got}, Kotlin says ${row.expect}`);
}

// ---------------------------------------------------------------- 3. the momentary button

for (const testCase of CASES.buttons || []) {
    mount(testCase.html);
    const button = document.querySelector(`.field-button[data-control-id="${testCase.id}"]`);
    if (!button) { failures.push(`${testCase.name}: the emitted HTML has no .field-button`); continue; }

    const sequence = [];
    const record = async (label) => {
        await flush();
        const sent = posted.filter((m) => m.type === 'button-update').map((m) => m.payload.pressed);
        sequence.push({ step: label, sent, held: button.classList.contains('held') });
        posted.length = 0;
    };

    // down, then a clean up on the element
    posted.length = 0;
    pressAt(button, 0.5);
    await record('press and release');

    // down, then the pointer released OUTSIDE the element — the classic stuck-on defect. The window
    // listener is the only thing that can save this, so it is driven exactly the way a browser would.
    posted.length = 0;
    pressAt(button, 0.5, { release: false });
    await record('press only');
    window.dispatch('pointerup', { clientX: 10000, clientY: 10000, pointerId: 1 });
    await record('release far outside');


    // down, then pointercancel (a touch turning into a scroll on the tablet surface)
    posted.length = 0;
    pressAt(button, 0.5, { release: false });
    await flush();
    posted.length = 0;
    window.dispatch('pointercancel', { pointerId: 1 });
    await record('pointercancel');

    // down, then the window loses focus entirely (cmd-tab away mid-press)
    posted.length = 0;
    pressAt(button, 0.5, { release: false });
    await flush();
    posted.length = 0;
    window.dispatch('blur', {});
    await record('window blur');

    // a stray release with nothing held must post nothing at all
    posted.length = 0;
    window.dispatch('pointerup', { clientX: 0, clientY: 0, pointerId: 1 });
    await record('stray release');

    // --- keyboard. A momentary control that a keyboard cannot reach is unreachable to some people, and
    // "held" has no obvious keyboard analogue, so the chosen answer is asserted rather than assumed:
    // space/enter down is a press, up is the release, and an auto-repeating keydown is NOT a new press.
    const inner = document.getElementById(testCase.id) || button;
    posted.length = 0;
    keyPress(inner, ' ');
    await record('space down and up');

    posted.length = 0;
    keyPress(inner, ' ', { repeats: 12, release: false });
    await record('space held, 13 keydowns');
    window.dispatch('blur', {});
    await record('space then focus lost');

    posted.length = 0;
    keyPress(inner, 'Enter');
    await record('enter down and up');

    posted.length = 0;
    keyPress(inner, 'a');
    await record('an unrelated key');

    observed.buttons.push({ name: testCase.name, sequence });
    document.body.children = [];
}

// ---------------------------------------------------------------- 4. the meter

for (const testCase of CASES.meters || []) {
    mount(testCase.html);
    const meter = document.querySelector(`.field-meter[data-control-id="${testCase.id}"]`);
    if (!meter) { failures.push(`${testCase.name}: the emitted HTML has no .field-meter`); continue; }

    const readings = [];
    for (const level of testCase.levels) {
        posted.length = 0;
        window.applyFieldControlUpdate({ id: testCase.id, type: 'meter', value: level.value });
        await flush();
        const bar = meter.querySelector('.meter-fill');
        readings.push({
            value: level.value,
            width: bar ? parseFloat(bar.getAttribute('width')) : null,
            shown: document.getElementById(`${testCase.id}-value`)?.textContent ?? null,
            postedAnything: posted.length
        });
        if (level.expectWidth !== undefined) {
            near(bar ? parseFloat(bar.getAttribute('width')) : NaN, level.expectWidth, 1e-6,
                `${testCase.name}: a level of ${level.value} draws the wrong bar width`);
        }
        if (level.expectText !== undefined) {
            const shown = document.getElementById(`${testCase.id}-value`)?.textContent ?? '';
            check(shown === level.expectText,
                `${testCase.name}: ${level.value} reads ${JSON.stringify(shown)}, wanted ${JSON.stringify(level.expectText)}`);
        }
        // A meter is an output. Touching it must never post anything back.
        check(posted.length === 0, `${testCase.name}: a meter update posted ${posted.length} message(s) back`);
    }

    // A pointer press on a meter is inert — it is read-only, and must not become a slider.
    posted.length = 0;
    const visual = meter.querySelector('.field-meter-visual');
    if (visual) pressAt(visual, 0.9);
    await flush();
    check(posted.length === 0, `${testCase.name}: pressing a meter posted ${posted.length} message(s)`);

    observed.meters.push({ name: testCase.name, readings });
    document.body.children = [];
}

// ---------------------------------------------------------------- report

fs.writeFileSync(OUT, JSON.stringify(observed, null, 2));
observed.laws.forEach((law) => {
    console.log(`probe: ${law.name} [${law.law}] ` +
        law.values.map((v) => `${v.fraction}→${v.sent}`).join(' '));
});
observed.buttons.forEach((b) => {
    console.log(`probe: ${b.name} ` + b.sequence.map((s) => `${s.step}=[${s.sent}]`).join(' '));
});
observed.meters.forEach((m) => {
    console.log(`probe: ${m.name} ` + m.readings.map((r) => `${r.value}→${r.width}px "${r.shown}"`).join(' '));
});

if (failures.length) {
    failures.forEach((f) => console.log(`probe FAILURE: ${f}`));
    throw new Error(`${failures.length} control-surface probe failure(s)`);
}
console.log('control surface probe passed');
