/**
 * The film page's runtime for `film-page --timeline` (afield/tools/docs/pageFilm.kt): the stage, and each frame.
 *
 * `install(spec)` lays the stage out: each region is bound to one box output (a three.js view, a `lines` drawing,
 * a `display`, a control panel), found by the box's uid and the output's channel, and shown at the region's place
 * on the frame with everything else of the page hidden. The outputs stay where Field put them in the document, so
 * they keep updating as the box redraws them; the stage writes one stylesheet, keyed by `data-field-stage-*`
 * attributes, whose `!important` rules outrank the inline sizes Field's output code writes, and opens a channel
 * that was collapsed. A three.js view is sized to its region (it draws at that size); a drawing or a display keeps
 * its own layout and is scaled to fit its region, so nothing is stretched.
 *
 * `frame(state)` puts each view where the frame's keys ask (a camera pose, the parts hidden, a motion's value, the
 * slider values) and resolves once every view has drawn it: the camera reads back as asked, the parts and the motion
 * are as asked, each `waits` entry's view metadata shows the value the server's drag or control was given, and the
 * view has rendered since. Nothing reads a clock: a frame that takes longer only makes the shoot longer.
 * `status()` says what the last frame is still waiting for, which is what a timed-out frame reports.
 */

const STAGE_Z = 2147483000;
const OUTRANK = ':not(#field-stage-outrank):not(#field-stage-outrank-2)';
const state = {spec: null, regions: new Map(), views: new Map(), styles: new Map(), caption: null,
    applied: new Map(), sent: new Map(), waiting: [], frames: 0};

const nextFrame = (win = window) => new Promise((resolve) => win.requestAnimationFrame(() => resolve()));
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function boxElement(uid) {
    return [...document.querySelectorAll('.box')].find((b) => b.uid === uid) || null;
}

function disclosures(box) {
    return [...box.querySelectorAll('.outputArea .disclosure')].filter((d) => d.closest('.box') === box);
}

function viewerOf(frame) {
    try { return frame?.contentWindow?.FieldViewer ? frame.contentWindow : null; } catch (_) { return null; }
}

/** The output a region or view key names: `{box, channel}` → the disclosure, what kind it is, the element to show. */
function locate(boxUid, channel) {
    const box = boxElement(boxUid);
    if (!box) return {missing: `no box ${boxUid} on the page`};
    const all = disclosures(box);
    const disclosure = channel ? all.find((d) => d.__disclosureId === channel)
        : all.find((d) => viewerOf(d.querySelector('iframe'))) || all[0];
    if (!disclosure) {
        return {missing: `box ${box.name || boxUid} has no output ${channel ? `"${channel}"` : ''} yet` +
            (all.length ? ` (it has ${all.map((d) => d.__disclosureId).join(', ')})` : '')};
    }
    const content = disclosure.querySelector(':scope > .disclosure-content') || disclosure.querySelector('.disclosure-content');
    const frame = content?.querySelector('iframe');
    if (frame && /\/threejs\b/.test(frame.src || '')) {
        const win = viewerOf(frame);
        if (!win) return {missing: `the 3D view in ${disclosure.__disclosureId} has not loaded`};
        return {box, disclosure, content, kind: 'three', element: frame, win};
    }
    const kind = content?.querySelector('.afield-lines-output-shell') ? 'lines' : 'html';
    return {box, disclosure, content, kind, element: content};
}

function open(found) {
    const group = found.disclosure.closest('.disclosure-group');
    const id = found.disclosure.__disclosureId;
    const api = group && typeof window.disclosureInterface === 'function' ? window.disclosureInterface(group) : null;
    if (api && api.isOpen(id) !== true) api.open(id);
}

function cssText(selector, props) {
    return `${selector} {${Object.entries(props).map(([k, v]) => `${k}: ${v} !important;`).join(' ')}}`;
}

function setStyle(key, text) {
    let el = state.styles.get(key);
    if (!el) { el = document.createElement('style'); el.dataset.fieldStageStyle = key; document.head.append(el); state.styles.set(key, el); }
    if (el.textContent !== text) el.textContent = text;
}

/** Marks [element]'s ancestors so the stage stylesheet takes off whatever would make one of them its containing block. */
function markPath(element) {
    for (let p = element.parentElement; p && p !== document.body && p !== document.documentElement; p = p.parentElement)
        p.setAttribute('data-field-stage-path', '');
}

const px = (n) => `${Math.round(n * 1000) / 1000}px`;

/** Places region [r] for its output's current size: a three.js view sized to the rect, anything else scaled to fit it. */
function place(r) {
    const [left, top, width, height] = r.rect;
    // `:not(#…)` lends the rule an id's specificity: Field's own `!important` output rules (`.disclosure-content
    // iframe`, say) are class-and-element strong, and a stage rule has to outrank them.
    const sel = `html.field-stage [data-field-stage-region="${CSS.escape(r.id)}"]${OUTRANK}`;
    const base = {position: 'fixed', margin: '0', 'z-index': String(STAGE_Z), 'box-sizing': 'border-box',
        'max-width': 'none', 'max-height': 'none', 'min-width': '0', 'min-height': '0', resize: 'none',
        transition: 'none', animation: 'none', 'transform-origin': '0 0'};
    if (r.background) base.background = r.background;
    const fit = r.fit || (r.kind === 'three' ? 'size' : 'contain');
    if (fit === 'size') {
        setStyle(r.id, cssText(sel, {...base, left: px(left), top: px(top), width: px(width), height: px(height), transform: 'none', overflow: 'hidden'}));
        r.scale = 1;
        return;
    }
    // Its own layout size, as Field sized it: scaled, so a drawing keeps its proportions and text its layout.
    const el = r.element;
    const w = Math.max(el.offsetWidth, el.scrollWidth, 1), h = Math.max(el.offsetHeight, el.scrollHeight, 1);
    const s = r.spec.scale ?? (fit === 'width' ? width / w : Math.min(width / w, height / h));
    const dx = r.spec.align === 'center' ? (width - w * s) / 2 : 0, dy = r.spec.align === 'center' && fit !== 'width' ? (height - h * s) / 2 : 0;
    setStyle(r.id, cssText(sel, {...base, left: px(left + dx), top: px(top + dy), transform: `scale(${s})`, overflow: 'visible'}));
    r.scale = s;
}

function stageSheet(background) {
    const path = {transform: 'none', filter: 'none', 'backdrop-filter': 'none', '-webkit-backdrop-filter': 'none',
        perspective: 'none', contain: 'none', 'will-change': 'auto', 'container-type': 'normal', 'content-visibility': 'visible',
        'clip-path': 'none', mask: 'none', opacity: '1', 'z-index': 'auto', isolation: 'auto', 'mix-blend-mode': 'normal',
        transition: 'none', animation: 'none'};
    return [
        cssText('html.field-stage, html.field-stage body', {background: background || '#111', overflow: 'hidden'}),
        cssText('html.field-stage body *', {visibility: 'hidden'}),
        cssText(`html.field-stage [data-field-stage-path]${OUTRANK}`, path),
        cssText(`html.field-stage [data-field-stage-region]${OUTRANK}, html.field-stage [data-field-stage-region] *${OUTRANK}`, {visibility: 'visible'}),
    ].join('\n');
}

function captionElement(spec) {
    const el = document.createElement('div');
    el.id = 'field-stage-caption';
    el.setAttribute('data-field-stage-region', 'caption');
    const [left, top, width, height] = spec.rect;
    Object.assign(el.style, {position: 'fixed', left: px(left), top: px(top), width: px(width), height: px(height),
        zIndex: String(STAGE_Z + 1), boxSizing: 'border-box', display: 'flex', alignItems: 'flex-end', whiteSpace: 'pre-wrap',
        font: '500 26px/1.3 system-ui, sans-serif', color: 'rgba(255,255,255,0.92)', pointerEvents: 'none', opacity: '0'});
    for (const [k, v] of Object.entries(spec.style || {})) {
        if (k.includes('-')) el.style.setProperty(k, String(v)); else el.style[k] = String(v);
    }
    document.body.append(el);
    return el;
}

function sliderOf(boxUid, label) {
    const box = boxElement(boxUid);
    const slider = box && [...box.querySelectorAll('.field-slider')].find((s) => s.getAttribute('aria-label') === label);
    if (!slider) throw new Error(`no slider "${label}" in box ${box?.name || boxUid}`);
    return slider;
}

function viewKey(key) {
    if (state.regions.has(key)) { const r = state.regions.get(key); return {box: r.spec.box, channel: r.spec.channel}; }
    const i = key.lastIndexOf(':');
    return {box: key.slice(0, i), channel: key.slice(i + 1) || null};
}

function resolveView(key) {
    const r = state.regions.get(key);
    if (r && r.kind === 'three' && r.win === viewerOf(r.element)) return r;
    const {box, channel} = viewKey(key);
    const found = locate(box, channel);
    if (found.missing) throw new Error(`view ${key}: ${found.missing}`);
    if (found.kind !== 'three') throw new Error(`view ${key} is a ${found.kind} output, not a three.js view`);
    if (r) { r.element = found.element; r.win = found.win; }
    return found;
}

/**
 * Lays out [spec]'s stage (when it has one) and readies every view [views] names; resolves with each view's camera,
 * which an orbit with no eye of its own starts from. Waits for outputs that have not arrived yet: the setup budget
 * is the film's load timeout.
 */
export async function install({stage = null, views = [], timeoutMs = 120000} = {}) {
    state.spec = stage;
    const started = performance.now();
    const until = async (what, test) => {
        for (;;) {
            const r = test();
            if (r && !r.missing) return r;
            if (performance.now() - started > timeoutMs) throw new Error(`film stage: ${r?.missing || what}`);
            await sleep(100);
        }
    };
    if (stage) {
        document.documentElement.classList.add('field-stage');
        setStyle('stage', stageSheet(stage.background));
        for (const spec of stage.regions || []) {
            const found = await until(`region ${spec.id}`, () => {
                const f = locate(spec.box, spec.channel);
                if (!f.missing) open(f);
                return f;
            });
            const r = {id: spec.id, spec, rect: spec.rect, fit: spec.fit, background: spec.background, ...found};
            found.element.setAttribute('data-field-stage-region', spec.id);
            markPath(found.element);
            state.regions.set(spec.id, r);
            place(r);
        }
        if (stage.caption) state.caption = captionElement(stage.caption);
    }
    const poses = {};
    for (const key of views) {
        const view = await until(`view ${key}`, () => {
            try { const v = resolveView(key); return v.win.FieldViewer.captureStatus().ready ? v : {missing: `view ${key} is not ready`}; }
            catch (e) { return {missing: e.message}; }
        });
        // Frame-locked from here: no damping between frames, no turntable or camera link, shots on the film's clock.
        const film = view.win.FieldViewer.film.begin({degreesPerSecond: 0});
        poses[key] = {eye: film.eye, target: film.target, up: film.up};
        state.views.set(key, view);
    }
    // A view resized by its region draws again at its new size before the first frame.
    for (const r of state.regions.values()) place(r);
    for (let k = 0; k < 3; k++) await nextFrame();
    window.FieldFilm = api;
    return {ok: true, regions: Object.fromEntries([...state.regions.values()].map((r) => [r.id,
        {kind: r.kind, rect: r.rect, scale: r.scale, channel: r.disclosure.__disclosureId}])), poses};
}

const near = (a, b, tol) => Math.abs(a - b) <= tol;

function poseNear(a, b) {
    const d = Math.hypot(...a.eye.map((v, j) => v - a.target[j]));
    const tol = 1e-6 * Math.max(1, d);
    return a.eye.every((v, j) => near(v, b.eye[j], tol)) && a.target.every((v, j) => near(v, b.target[j], tol));
}

function setCamera(win, pose) {
    const V = win.FieldViewer;
    if (typeof V.setCamera === 'function') {
        V.setCamera({eye: pose.eye, target: pose.target, ...(pose.up ? {up: pose.up} : {}), ...(pose.fov ? {fov: pose.fov} : {})});
        if (poseNear(V.cameraPose(), pose)) return;
    }
    const I = win.FieldInspector, snap = I.snapshot();
    I.restore({...snap, camera: pose.eye, target: pose.target, cameraUp: pose.up || snap.cameraUp,
        cameraFov: pose.fov ?? snap.cameraFov});
}

function metadataAt(meta, path) {
    let v = meta;
    for (const part of String(path).split('.')) { if (v == null) return undefined; v = v[part]; }
    return v;
}

function matches(actual, expected, tol) {
    if (Array.isArray(expected)) return Array.isArray(actual) && actual.length === expected.length && expected.every((e, j) => matches(actual[j], e, tol));
    return typeof actual === 'number' && near(actual, expected, tol);
}

function hiddenWanted(win, ops) {
    const parts = win.FieldViewer.parts();
    const wanted = new Map();
    for (const op of ops) {
        for (const id of op.ids || []) wanted.set(id, op.hidden);
        for (const layer of op.layers || [])
            for (const p of parts) if (layer.every((name, j) => (p.layer || [])[j] === name)) wanted.set(p.id, op.hidden);
    }
    return {parts, wanted};
}

/** Each check is [description, test]; apply() runs once now, and again whenever its test fails (a redraw undid it). */
function viewChecks(key, v) {
    const view = resolveView(key), win = view.win, V = win.FieldViewer, checks = [];
    const renders0 = V.renderStats().frames;
    let changed = false;
    if (v.camera) {
        const want = v.camera;
        let readback = null;
        const apply = () => { setCamera(win, want); readback = V.cameraPose(); changed = true; };
        const last = state.applied.get(key + '/camera');
        if (!last || JSON.stringify(last) !== JSON.stringify(want) || !poseNear(V.cameraPose(), want)) apply();
        state.applied.set(key + '/camera', want);
        // A pose the controls clamp (a distance or polar limit) reads back as the clamped pose; that is what it draws.
        checks.push([`${key} camera`, () => {
            const now = V.cameraPose();
            if (poseNear(now, want) || (readback && poseNear(now, readback))) return true;
            apply(); return false;
        }]);
    }
    if (v.parts) {
        const reconcile = () => {
            const {parts, wanted} = hiddenWanted(win, v.parts);
            let ok = true;
            for (const p of parts) if (wanted.has(p.id) && p.visible === wanted.get(p.id)) { V.hidePart(p.id, wanted.get(p.id)); ok = false; changed = true; }
            return ok;
        };
        reconcile();
        checks.push([`${key} parts`, reconcile]);
    }
    if (v.motion) {
        const m = v.motion;
        const reconcile = () => {
            const s = V.motion.status();
            if (m.rest ? s.mode === 'rest' : s.mode === 'play' && !s.playing && near(s.t, Math.max(0, Math.min(1, m.value)), 1e-9)) return true;
            if (m.rest) V.motion.rest(); else V.motion.at(m.value);
            changed = true;
            return false;
        };
        reconcile();
        checks.push([`${key} motion ${m.rest ? 'at rest' : m.value.toFixed(4)}`, reconcile]);
    }
    if (v.tracks != null) {
        const reconcile = () => {
            if (V.motion.status().tracks === v.tracks) return true;
            V.motion.tracks(v.tracks); changed = true; return false;
        };
        reconcile();
        checks.push([`${key} tracks ${v.tracks ? 'on' : 'off'}`, reconcile]);
    }
    checks.push([`${key} drawn`, () => {
        const s = V.captureStatus(), r = V.renderStats();
        return s.ready && !r.pending && (!changed || r.frames > renders0);
    }]);
    return {win, checks};
}

/**
 * Puts the page where frame [frameState] (FilmTimeline.frame on the server) asks, and resolves once every view shows it.
 * Returns what it did: the frame, the animation frames it waited, and the views it touched.
 */
export async function frame(frameState) {
    const fs = typeof frameState === 'string' ? JSON.parse(frameState) : frameState;
    for (const r of state.regions.values()) {
        // A redraw may replace an output's element: follow it to the new one.
        if (!r.element.isConnected || (r.kind === 'three' && !viewerOf(r.element))) {
            const f = locate(r.spec.box, r.spec.channel);
            if (!f.missing) { Object.assign(r, f); f.element.setAttribute('data-field-stage-region', r.id); markPath(f.element); }
        }
        place(r);
    }
    const checks = [], wins = new Set();
    for (const [key, v] of Object.entries(fs.views || {})) {
        const c = viewChecks(key, v);
        checks.push(...c.checks); wins.add(c.win);
    }
    for (const c of fs.controls || []) {
        const slider = sliderOf(c.box, c.label);
        // Snapped to the slider's step first, as a hand's drag is; sent only when that moves it.
        const before = slider.dataset.value;
        window.updateFieldSlider(slider, c.value, {send: false});
        if (slider.dataset.value !== before || !state.sent.has(c.box + '/' + c.label)) window.sendSliderUpdate(slider.dataset.sliderId, Number(slider.dataset.value));
        state.sent.set(c.box + '/' + c.label, slider.dataset.value);
    }
    for (const w of fs.waits || []) {
        const win = resolveView(w.view).win;
        const value = w.control ? (w.scale ?? 1) * Number(sliderOf(w.control.box, w.control.label).dataset.value) : w.value;
        wins.add(win);
        checks.push([`${w.view} metadata ${w.metadata} = ${JSON.stringify(value)}`,
            () => matches(metadataAt(win.FieldViewer.metadata(), w.metadata), value, w.tol)]);
    }
    if (state.caption) {
        const c = fs.caption || {text: '', opacity: 0};
        if (state.caption.textContent !== c.text) state.caption.textContent = c.text;
        const o = String(c.text ? c.opacity : 0);
        if (state.caption.style.opacity !== o) state.caption.style.opacity = o;
    }
    let waited = 0;
    for (;;) {
        state.waiting = checks.filter(([, test]) => !test()).map(([what]) => what);
        if (!state.waiting.length) break;
        waited++;
        await nextFrame();
    }
    // Two animation frames in each view and in the page: what was drawn is on screen before the capture.
    for (const win of wins) { await nextFrame(win); await nextFrame(win); }
    state.frames++;
    return {i: fs.i, t: fs.t, waitedFrames: waited, views: Object.keys(fs.views || {}).length};
}

/** What the frame in progress is waiting for (empty when it is not waiting), and how many frames have been put. */
export function status() {
    return {waiting: state.waiting.slice(), frames: state.frames, regions: [...state.regions.keys()]};
}

const api = Object.freeze({install, frame, status});
window.FieldFilm = api;
