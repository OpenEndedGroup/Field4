// Runs the SHIPPED `sliders.js` against SVG the Kotlin renderer actually emitted.
//
// Nothing here re-describes what the browser is supposed to do: the file under test is staged
// verbatim off the classpath, loaded as the classic script it is, and driven through the two entry
// points the generated `onclick` attribute names — `window.__flineIntersectionFromSvg` and
// `window.sendFLineClick`. The assertions are on the payload `sendFLineClick` posts, which is the
// thing the server decodes.
//
// The DOM here is a stub, and deliberately a small one: enough SVG surface for the code under test
// (attributes, `ownerSVGElement`, `createSVGPoint`/`getScreenCTM` with a real 2x3 matrix inverse,
// `querySelectorAll('path, circle')`), and no more. What that leaves out is stated in the Kotlin
// test's KDoc — above all, browser hit-testing: each case names the `<g>` the browser would have
// hit, because deciding that is the browser's job, not this file's.
//
// Usage: node probe.mjs <cases.json> <out-posted.json>

import fs from 'node:fs';

const CASES = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
const POSTED_OUT = process.argv[3];
// The `fline-hit-picking` region lifted verbatim out of `threejs_viewer.html`, when the caller staged
// it: the face-coordinate rule is written twice — once for a click in a plane, once for a raycast hit in
// a face's plane — and the differential at the bottom of this file requires the two to agree.
const VIEWER_REGION = process.argv[4] ? fs.readFileSync(process.argv[4], 'utf8') : null;

// ---------------------------------------------------------------- a very small SVG DOM

const ENTITIES = { '&amp;': '&', '&lt;': '<', '&gt;': '>', '&quot;': '"', '&#39;': "'" };

function unescapeAttribute(value) {
    return value.replace(/&(amp|lt|gt|quot|#39);/g, (m) => ENTITIES[m]);
}

class StubElement {
    constructor(tagName, attributes) {
        this.tagName = tagName;
        this.attributes = attributes;
        this.children = [];
        this.parentNode = null;
    }

    getAttribute(name) {
        return Object.prototype.hasOwnProperty.call(this.attributes, name) ? this.attributes[name] : null;
    }

    setAttribute(name, value) {
        this.attributes[name] = String(value);
    }

    removeAttribute(name) {
        delete this.attributes[name];
    }

    get ownerSVGElement() {
        let node = this.parentNode;
        while (node && node.tagName !== 'svg') node = node.parentNode;
        return node;
    }

    descendants() {
        const out = [];
        const walk = (node) => node.children.forEach((child) => { out.push(child); walk(child); });
        walk(this);
        return out;
    }

    querySelectorAll(selector) {
        // Two forms, which is all the code under test asks for: a tag list, and one `[data-attr]`.
        if (selector.startsWith('[')) {
            const name = selector.replace(/^\[|\]$/g, '');
            return this.descendants().filter((node) => node.getAttribute(name) !== null);
        }
        const wanted = selector.split(',').map((part) => part.trim());
        return this.descendants().filter((node) => wanted.includes(node.tagName));
    }

    querySelector(selector) {
        return this.querySelectorAll(selector)[0] || null;
    }

    /**
     * Where this element is on screen, under the owning <svg>'s CTM.
     *
     * Only rectangles need it — the calibration rectangle the renderer emits, which is how the code
     * under test measures the page's scaling. Computing it from the same CTM the fallback path uses
     * means both routes have to agree, which is the property worth holding.
     */
    getBoundingClientRect() {
        const svg = this.tagName === 'svg' ? this : this.ownerSVGElement;
        const m = svg && svg.screenCTM;
        const x = parseFloat(this.getAttribute('x') || '0');
        const y = parseFloat(this.getAttribute('y') || '0');
        const width = parseFloat(this.getAttribute('width') || '0');
        const height = parseFloat(this.getAttribute('height') || '0');
        if (!m) return { left: x, top: y, width, height };
        return {
            left: m.a * x + m.c * y + m.e,
            top: m.b * x + m.d * y + m.f,
            width: width * m.a,
            height: height * m.d
        };
    }

    closest(selector) {
        // Only the `[data-attr]` form the editor helpers use.
        const name = selector.replace(/^\[|\]$/g, '');
        let node = this;
        while (node) {
            if (node.getAttribute(name) !== null) return node;
            node = node.parentNode;
        }
        return null;
    }

    // The matrix pieces `__flineIntersectionFromSvg` asks an <svg> for.
    createSVGPoint() {
        return {
            x: 0,
            y: 0,
            matrixTransform(matrix) {
                return {
                    x: matrix.a * this.x + matrix.c * this.y + matrix.e,
                    y: matrix.b * this.x + matrix.d * this.y + matrix.f
                };
            }
        };
    }

    getScreenCTM() {
        return this.screenCTM;
    }
}

function matrix(a, b, c, d, e, f) {
    return {
        a, b, c, d, e, f,
        inverse() {
            const det = a * d - b * c;
            return matrix(d / det, -b / det, -c / det, a / det, (c * f - d * e) / det, (b * e - a * f) / det);
        }
    };
}

const TAG = /<(\/?)([A-Za-z][A-Za-z0-9]*)((?:\s+[A-Za-z][-A-Za-z0-9]*=(?:"[^"]*"|'[^']*'))*)\s*(\/?)>/g;
const ATTR = /([A-Za-z][-A-Za-z0-9]*)=(?:"([^"]*)"|'([^']*)')/g;

function parseSvg(text) {
    let root = null;
    const stack = [];
    let match;
    TAG.lastIndex = 0;
    while ((match = TAG.exec(text)) !== null) {
        const [, closing, tagName, attributeText, selfClosing] = match;
        if (closing) {
            stack.pop();
            continue;
        }
        const attributes = {};
        ATTR.lastIndex = 0;
        let attribute;
        while ((attribute = ATTR.exec(attributeText)) !== null) {
            attributes[attribute[1]] = unescapeAttribute(attribute[2] !== undefined ? attribute[2] : attribute[3]);
        }
        const element = new StubElement(tagName, attributes);
        const parent = stack[stack.length - 1];
        if (parent) {
            element.parentNode = parent;
            parent.children.push(element);
        } else {
            root = element;
        }
        if (!selfClosing) stack.push(element);
    }
    if (!root) throw new Error('no elements parsed out of the SVG');
    return root;
}

// ---------------------------------------------------------------- load the real sliders.js

const posted = [];

globalThis.afield = {
    send(type, payload) {
        posted.push({ type, payload });
    }
};

const documentStub = {
    listeners: {},
    addEventListener(type, callback) {
        (this.listeners[type] = this.listeners[type] || []).push(callback);
    },
    querySelectorAll() { return []; },
    createElementNS(_ns, tagName) { return new StubElement(tagName, {}); },
    body: new StubElement('body', {})
};

const windowStub = {
    addEventListener() {},
    removeEventListener() {},
    CSS: { escape: (value) => String(value) }
};

globalThis.window = windowStub;
globalThis.document = documentStub;

const slidersSource = fs.readFileSync('sliders.js', 'utf8');
// A classic script, not a module: evaluate it with `window`/`document` in scope, exactly as a page
// would, so nothing in it has to be edited to be testable. The one epilogue line is the harness's,
// not the file's — `flineFaceCoords` is module-private in the shipped script and the differential
// below has to be able to call it directly on the same inputs the viewer's copy gets.
const EXPORT_INTERNALS = '\nwindow.__flineFaceCoords = flineFaceCoords;\n';
new Function('window', 'document', slidersSource + EXPORT_INTERNALS)(windowStub, documentStub);

for (const name of ['sendFLineClick', '__flineIntersectionFromSvg', '__flineHoverIn', '__flineHoverOut']) {
    if (typeof windowStub[name] !== 'function') {
        throw new Error(`sliders.js did not define window.${name} — the staged file is not the one under test`);
    }
}

// ---------------------------------------------------------------- run the cases

const documents = new Map();
function figure(name) {
    if (!documents.has(name)) {
        const root = parseSvg(fs.readFileSync(name, 'utf8'));
        documents.set(name, root);
    }
    return documents.get(name);
}

function groupFor(svgName, lineIndex) {
    const root = figure(svgName);
    const found = root.descendants().filter((node) =>
        node.tagName === 'g' && node.getAttribute('data-fline-index') === String(lineIndex));
    if (found.length !== 1) {
        throw new Error(`${svgName}: expected exactly one <g data-fline-index="${lineIndex}">, found ${found.length}`);
    }
    return found[0];
}

const failures = [];
function check(caseName, what, condition, detail) {
    if (!condition) failures.push(`${caseName}: ${what} — ${detail}`);
}

function closeTo(caseName, what, actual, expected, tolerance) {
    const ok = Number.isFinite(actual) && Math.abs(actual - expected) <= tolerance;
    check(caseName, what, ok, `expected ${expected} +- ${tolerance}, got ${actual}`);
}

const tick = () => new Promise((resolve) => setTimeout(resolve, 0));

const CTM = CASES.ctm || { scale: 2, tx: 30, ty: 17 };

const record = [];

for (const testCase of CASES.cases) {
    const group = groupFor(testCase.svg, testCase.lineIndex);
    const svg = group.ownerSVGElement;
    svg.screenCTM = matrix(CTM.scale, 0, 0, CTM.scale, CTM.tx, CTM.ty);

    const event = { clientX: testCase.clientX, clientY: testCase.clientY, target: group };
    posted.length = 0;
    const hit = windowStub.__flineIntersectionFromSvg(event, group);
    windowStub.sendFLineClick(group.getAttribute('data-fline-callback-id'), hit);
    await tick();

    check(testCase.name, 'exactly one message posted', posted.length === 1, `posted ${posted.length}`);
    if (posted.length !== 1) continue;
    check(testCase.name, 'message type', posted[0].type === 'fline-click', `got ${posted[0].type}`);
    const payload = posted[0].payload;
    record.push({ name: testCase.name, payload });

    const expected = testCase.expect || {};
    const tolerance = expected.tol === undefined ? 1e-4 : expected.tol;
    for (const [key, value] of Object.entries(expected)) {
        if (key === 'tol') continue;
        if (typeof value === 'number' && !Number.isInteger(value)) {
            closeTo(testCase.name, key, payload[key], value, tolerance);
        } else if (value === null) {
            check(testCase.name, key, payload[key] === null, `expected null, got ${JSON.stringify(payload[key])}`);
        } else {
            check(testCase.name, key, payload[key] === value,
                `expected ${JSON.stringify(value)}, got ${JSON.stringify(payload[key])}`);
        }
    }
    check(testCase.name, 'callbackId round-trips',
        payload.callbackId === group.getAttribute('data-fline-callback-id'),
        `got ${payload.callbackId}`);
    console.log(`  ${testCase.name}: kind=${payload.kind} line=${payload.lineIndex}/${payload.label} ` +
        `node=${payload.nodeIndex} sub=${payload.subPathIndex} el=${payload.elementIndex} ` +
        `u=${fmt(payload.u)} v=${fmt(payload.v)} on=(${fmt(payload.ex)}, ${fmt(payload.ey)}, ${fmt(payload.ez)}) ` +
        `closestVertex=${payload.closestVertexIndex}`);
}

function fmt(value) {
    if (value === null || value === undefined) return '-';
    return Number.isNaN(value) ? 'NaN' : Number(value).toFixed(4);
}

// ---------------------------------------------------------------- the hover pair, same file

for (const hoverCase of CASES.hover || []) {
    const group = groupFor(hoverCase.svg, hoverCase.lineIndex);
    const hoverColor = group.getAttribute('data-hover-color');
    check(hoverCase.name, 'group carries a hover colour', !!hoverColor, 'data-hover-color missing');
    const painted = group.querySelectorAll('path, circle');
    check(hoverCase.name, 'group has painted children', painted.length > 0, 'no path/circle children');
    const before = painted.map((child) => [child.getAttribute('stroke'), child.getAttribute('fill')]);

    windowStub.__flineHoverIn(group);
    painted.forEach((child, index) => {
        const [stroke, fill] = before[index];
        if (stroke && stroke !== 'none') {
            check(hoverCase.name, `child ${index} stroke hovers`, child.getAttribute('stroke') === hoverColor,
                `got ${child.getAttribute('stroke')}`);
        }
        if (fill && fill !== 'none') {
            check(hoverCase.name, `child ${index} fill hovers`, child.getAttribute('fill') === hoverColor,
                `got ${child.getAttribute('fill')}`);
        }
    });

    windowStub.__flineHoverOut(group);
    painted.forEach((child, index) => {
        const [stroke, fill] = before[index];
        check(hoverCase.name, `child ${index} stroke restored`, child.getAttribute('stroke') === stroke,
            `got ${child.getAttribute('stroke')}, was ${stroke}`);
        check(hoverCase.name, `child ${index} fill restored`, child.getAttribute('fill') === fill,
            `got ${child.getAttribute('fill')}, was ${fill}`);
    });
    console.log(`  ${hoverCase.name}: ${painted.length} painted children hovered to ${hoverColor} and back`);
}

// ---------------------------------------------------------------- the two implementations, compared
//
// `flineFaceCoords` (sliders.js, a click in the plane of a 2D figure) and `flinePickFaceCoords` (the
// `fline-hit-picking` region of threejs_viewer.html, a raycast hit projected into a face's own plane)
// are the same rule written twice. For a face in the z = 0 plane both take the same numbers, so they
// have to return the same `{ u, v, mode }` — including on the awkward faces, where "the same" is the
// only way to know that a fallback in one is a fallback in the other.
if (VIEWER_REGION && (CASES.differential || []).length) {
    const region = new Function(
        VIEWER_REGION + '\nreturn { flinePickFaceCoords: flinePickFaceCoords };'
    )();
    for (const face of CASES.differential) {
        const svgSide = windowStub.__flineFaceCoords(face.pts, face.at[0], face.at[1]);
        const viewerSide = region.flinePickFaceCoords(face.pts, face.at[0], face.at[1]);
        check(face.name, 'both implementations report the same mode',
            svgSide.mode === viewerSide.mode,
            `sliders.js says ${svgSide.mode}, the viewer region says ${viewerSide.mode}`);
        if (face.expectMode) {
            check(face.name, 'mode', svgSide.mode === face.expectMode,
                `expected ${face.expectMode}, got ${svgSide.mode}`);
        }
        const same = (a, b) => (Number.isNaN(a) && Number.isNaN(b)) || Math.abs(a - b) < 1e-9;
        check(face.name, 'both implementations report the same u', same(svgSide.u, viewerSide.u),
            `${svgSide.u} vs ${viewerSide.u}`);
        check(face.name, 'both implementations report the same v', same(svgSide.v, viewerSide.v),
            `${svgSide.v} vs ${viewerSide.v}`);
        if (face.expectU !== undefined) closeTo(face.name, 'u', svgSide.u, face.expectU, face.tol || 1e-6);
        if (face.expectV !== undefined) closeTo(face.name, 'v', svgSide.v, face.expectV, face.tol || 1e-6);
        console.log(`  ${face.name}: ${svgSide.mode} u=${fmt(svgSide.u)} v=${fmt(svgSide.v)} ` +
            `(viewer region agrees: ${viewerSide.mode} ${fmt(viewerSide.u)} ${fmt(viewerSide.v)})`);
    }
}

fs.writeFileSync(POSTED_OUT, JSON.stringify(record, null, 1));

if (failures.length) {
    console.log(`fline hit probe FAILED with ${failures.length} problem(s):`);
    failures.forEach((failure) => console.log(`  - ${failure}`));
    process.exit(1);
}
console.log(`fline hit record probe passed (${CASES.cases.length} clicks, ${(CASES.hover || []).length} hovers, ` +
    `${(VIEWER_REGION ? (CASES.differential || []).length : 0)} faces compared against the viewer region)`);
