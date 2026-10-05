// Optional, document-scoped camera transport. Annotation and display state never travel here.
const vector = v => Array.isArray(v) && v.length === 3 && v.every(Number.isFinite);
const units = new Map([['mm', .001], ['cm', .01], ['m', 1], ['in', .0254], ['ft', .3048]]);
const toModel = (v, f, point) => {
    const p = f.upAxis === 'Z' ? [v[0], -v[2], v[1]] : [...v];
    return p.map((n, i) => point ? (n + f.origin[i]) * f.scale : n);
};
const toDisplay = (v, f, point) => {
    const p = v.map((n, i) => point ? n / f.scale - f.origin[i] : n);
    return f.upAxis === 'Z' ? [p[0], p[2], -p[1]] : p;
};
// The default group keeps the channel name it had before groups existed.
const busName = f => f.group === 'default' ? `field-three-camera-v1:${f.scope}:${f.space}`
    : `field-three-camera-v1:${JSON.stringify([f.scope, f.space, f.group])}`;
export function cameraLinkFrame(spec) {
    if (!spec) return null;
    const {scope, origin = [0, 0, 0], upAxis = 'Y', units: unit = '', group = 'default'} = spec;
    if (typeof scope !== 'string' || !scope || scope.length > 160 || !vector(origin) ||
        !['Y', 'Z'].includes(upAxis) || typeof unit !== 'string' || unit.length > 40 ||
        typeof group !== 'string' || !group || group.length > 64) return null;
    const name = unit.trim().toLowerCase(), scale = units.get(name) ?? 1;
    return {scope, group, origin: [...origin], upAxis, scale, space: units.has(name) ? 'length' : `units:${name}`};
}
function valid(p) {
    return p && vector(p.eye) && vector(p.target) && vector(p.up) &&
        p.eye.some((x, i) => x !== p.target[i]) && Math.hypot(...p.up) > .0001 &&
        Number.isFinite(p.fov) && p.fov > 0 && p.fov < 180 && Number.isFinite(p.zoom) && p.zoom > 0 &&
        Number.isFinite(p.near) && p.near > 0 && Number.isFinite(p.far) && p.far > p.near;
}
export function cameraToModel(p, frame) {
    if (!valid(p)) throw Error('Invalid linked camera');
    return {eye: toModel(p.eye, frame, true), target: toModel(p.target, frame, true),
        up: toModel(p.up, frame, false), fov:p.fov, zoom:p.zoom,
        near: p.near * frame.scale, far: p.far * frame.scale};
}
export function cameraFromModel(p, frame) {
    if (!valid(p)) throw Error('Invalid linked camera');
    // Whitelist fields: no selection, clipping section, visibility, tools or X-ray state.
    return {eye: toDisplay(p.eye, frame, true), target: toDisplay(p.target, frame, true),
        up: toDisplay(p.up, frame, false), fov: p.fov, zoom: p.zoom,
        near: p.near / frame.scale, far: p.far / frame.scale};
}
// `tag()` adds fields to this view's pose broadcasts. `heard(m)` sees each peer broadcast first —
// `{drive}` for a pose (a turning peer's key, else null), `{busy}` for input that moved nothing —
// and returns false to leave the camera where it is.
export function createCameraLink({read, write, changed = () => {}, tag = () => null, heard = () => true,
    channel = name => new BroadcastChannel(name), id = crypto.randomUUID()}) {
    let frame = null, bus = null, enabled = false, applying = false, dragging = false, last = null;
    let joined = false;
    const status = () => ({enabled, available: !!frame, connected: joined});
    const notify = () => changed(status());
    const post = data => bus?.postMessage({...data, sender: id});
    function disconnect() { bus?.close(); bus = null; joined = false; }
    function apply(pose) {
        if (!valid(pose)) return;
        applying = true;
        try { write(cameraFromModel(pose, frame)); last = pose; joined = true; }
        finally { applying = false; }
        notify();
    }
    function connect() {
        disconnect();
        if (!enabled || !frame) return;
        bus = channel(busName(frame));
        bus.onmessage = ({data: m}) => {
            if (!m || m.sender === id || typeof m.sender !== 'string' || !enabled) return;
            if (m.type === 'hello') {
                post({type: 'pose', to: m.sender, pose: cameraToModel(read(), frame)});
            } else if (m.type === 'busy') {
                heard({busy: true});
            } else if (m.type === 'pose' && (!m.to || m.to === id)) {
                if (!m.to && heard({drive: typeof m.drive === 'string' ? m.drive : null}) === false) return;
                if (dragging || (m.to && joined)) return; // one initial alignment; subsequent user moves are broadcasts
                apply(m.pose);
            }
        };
        post({type: 'hello'});
    }
    return Object.freeze({
        status,
        configure(spec) {
            const next = cameraLinkFrame(spec), previous = frame;
            const shared = enabled && previous && next && busName(previous) === busName(next);
            const pose = shared ? last : null;
            frame = next;
            if (!shared) { last = null; connect(); }
            else if (pose && JSON.stringify(previous) !== JSON.stringify(next)) apply(pose);
            notify();
        },
        setEnabled(value) { enabled = !!value && !!frame; last = null; connect(); notify(); },
        localChanged() {
            if (!enabled || !frame || applying) return;
            const pose = cameraToModel(read(), frame);
            if (JSON.stringify(pose) === JSON.stringify(last)) return;
            last = pose; post({type: 'pose', pose, ...tag()});
        },
        // Input in this view that need not move the camera: a press, a wheel step, a key.
        busy() { if (enabled && frame) post({type: 'busy'}); },
        dragging(value) { dragging = !!value; },
        dispose() { enabled = false; disconnect(); notify(); }
    });
}
// A slow orbit about the view's up axis. Bookkeeping only: the viewer turns its camera by the
// angle step() returns and asks for another frame; null means "not turning, stop asking".
//
// Local input, or a peer's moves, pause it until `resumeAfterSeconds` after the last of them.
// Linked views share one turn: the view with the smallest key drives and the link carries its
// pose to the rest. A view that has heard a better driver waits `lease` ms of silence from it
// before taking over, so a driver that pauses, leaves the screen or closes hands over, and one
// that resumes is not fought. Two views that start together settle within a message: the worse
// one yields as soon as it hears the better.
export function createTurntable({key, now = () => performance.now(), wake = () => {}, visible = () => true,
    lease = 1000, settle = 500, maxStep = 100}) {
    let spec = null, holding = false, pausedUntil = -Infinity, leader = null, freeSince = null;
    let lastStep = null, driving = false, wakeAt = -Infinity;
    const halt = () => { driving = false; lastStep = null; return null; };
    const live = t => leader !== null && t - leader.at <= lease;
    function later(t, at) { // one pending wake, at the earliest moment the answer can change
        if (!(at > t) || (wakeAt > t && wakeAt <= at)) return;
        wakeAt = at; wake(at - t);
    }
    function pause(t, ms) {
        pausedUntil = Math.max(pausedUntil, t + ms); freeSince = null; halt(); later(t, pausedUntil);
    }
    return Object.freeze({
        key: () => key,
        driving: () => driving,
        status: () => ({enabled: !!spec, driving, held: holding || now() < pausedUntil, following: live(now())}),
        configure(next) {
            const rate = Number(next?.degreesPerSecond), resume = Number(next?.resumeAfterSeconds ?? 2);
            spec = next && Number.isFinite(rate) && rate !== 0 && Math.abs(rate) <= 720 &&
                Number.isFinite(resume) && resume >= 0 ? {rate: rate * Math.PI / 180, resume: resume * 1000} : null;
            if (!spec) halt();
        },
        hold(value) {
            holding = !!value;
            if (spec) pause(now(), holding ? 0 : spec.resume);
        },
        nudge() { if (spec) pause(now(), spec.resume); },
        // The link was switched on or off. On joining, let the alignment pose land before turning.
        linked(on) {
            leader = null;
            if (on && spec) pause(now(), settle);
        },
        heard({drive = null, busy = false} = {}) {
            if (!spec) return true;
            const t = now();
            if (busy || drive === null) { pause(t, spec.resume); return true; }
            if (drive < key) {
                if (!live(t) || drive <= leader.key) leader = {key: drive, at: t};
                if (driving) halt();
                return drive === leader.key;
            }
            // A worse-ranked driver yields once it hears us or our driver; until then, ignore it.
            return !driving && !live(t);
        },
        step() {
            const t = now();
            if (!spec || holding || !visible()) { freeSince = null; return halt(); }
            if (t < pausedUntil) { freeSince = null; later(t, pausedUntil); return halt(); }
            if (freeSince === null) freeSince = t;
            if (!driving && leader !== null) {
                const quiet = Math.max(leader.at, freeSince) + lease;
                if (t < quiet) { later(t, quiet); return halt(); }
                leader = null;
            }
            const dt = lastStep === null ? 0 : Math.min(t - lastStep, maxStep);
            lastStep = t; driving = true;
            return spec.rate * dt / 1000;
        }
    });
}
