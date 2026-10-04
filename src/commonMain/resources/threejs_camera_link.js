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
export function cameraLinkFrame(spec) {
    if (!spec) return null;
    const {scope, origin = [0, 0, 0], upAxis = 'Y', units: unit = ''} = spec;
    if (typeof scope !== 'string' || !scope || scope.length > 160 || !vector(origin) ||
        !['Y', 'Z'].includes(upAxis) || typeof unit !== 'string' || unit.length > 40) return null;
    const name = unit.trim().toLowerCase(), scale = units.get(name) ?? 1;
    return {scope, origin: [...origin], upAxis, scale, space: units.has(name) ? 'length' : `units:${name}`};
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
export function createCameraLink({read, write, changed = () => {},
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
        bus = channel(`field-three-camera-v1:${frame.scope}:${frame.space}`);
        bus.onmessage = ({data: m}) => {
            if (!m || m.sender === id || typeof m.sender !== 'string' || !enabled) return;
            if (m.type === 'hello') {
                post({type: 'pose', to: m.sender, pose: cameraToModel(read(), frame)});
            } else if (m.type === 'pose' && (!m.to || m.to === id) && !dragging) {
                if (m.to && joined) return; // one initial alignment; subsequent user moves are broadcasts
                apply(m.pose);
            }
        };
        post({type: 'hello'});
    }
    return Object.freeze({
        status,
        configure(spec) {
            const next = cameraLinkFrame(spec), previous = frame;
            const shared = enabled && previous && next && previous.scope === next.scope && previous.space === next.space;
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
            last = pose; post({type: 'pose', pose});
        },
        dragging(value) { dragging = !!value; },
        dispose() { enabled = false; disconnect(); notify(); }
    });
}
