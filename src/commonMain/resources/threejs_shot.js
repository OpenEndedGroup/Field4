// A looping presentation move for a three.js view: `shot(…)` in a box (ThreeSceneBuilder.shot).
//
// One clock drives three things in every panel of the view: the camera's distance (a multiple of where it
// would otherwise stand), how far the model is exploded (a multiple of each vertex's own explode offset),
// and how far it is x-rayed (each surface's opacity, from its solid value at 0 to its authored one at 1).
// The clock is the wall clock, so separate views of one document stay in step without being linked. A
// press, wheel, key or drag holds it; it resumes the payload's pause after the last of those, from where
// it stopped.
//
// No DOM, no THREE: tools/test_three_shot.mjs runs this file as it is.

const smooth = (t) => t * t * (3 - 2 * t);

/**
 * The look at [phase] (wrapped into [0, 1)): `{distance, explode, xray}`, eased (smoothstep) between the
 * two keys either side. Keys are the payload's, validated by the server: first at 0, last at 1, increasing.
 */
export function shotLook(keys, phase) {
    if (!Array.isArray(keys) || keys.length === 0) return null;
    const p = ((phase % 1) + 1) % 1;
    let i = 0;
    while (i < keys.length - 2 && keys[i + 1].at <= p) i += 1;
    const a = keys[i], b = keys[Math.min(i + 1, keys.length - 1)];
    const span = b.at - a.at;
    const t = span > 1e-9 ? smooth(Math.min(1, Math.max(0, (p - a.at) / span))) : 0;
    const mix = (x, y) => x + (y - x) * t;
    return { distance: mix(a.distance, b.distance), explode: mix(a.explode, b.explode), xray: mix(a.xray, b.xray) };
}

/**
 * `base + offsets * amount` for every float, written into `out`. At `amount = 0` this copies `base`
 * exactly, so an assembled model is the geometry the server sent, bit for bit.
 */
export function explodePositions(base, offsets, amount, out) {
    const target = out || new Float32Array(base.length);
    if (amount === 0) { target.set(base); return target; }
    for (let i = 0; i < base.length; i += 1) target[i] = base[i] + offsets[i] * amount;
    return target;
}

/** A surface's opacity at x-ray amount [xray]: [solid] at 0, its [authored] opacity at 1. */
export function xrayOpacity(solid, authored, xray) {
    return solid + (authored - solid) * Math.min(1, Math.max(0, xray));
}

/**
 * The shot's clock. `step()` returns this frame's look (with its `phase`), or `null` when the payload has
 * no shot. While held the look is frozen; when the hold ends the loop carries on from the frozen phase.
 * `wake(ms)` asks for a frame once a hold's pause has run out, as the turntable's does.
 */
export function createShot({ now = () => Date.now(), wake = () => {} } = {}) {
    let spec = null;
    let holds = 0;          // drags in progress
    let lastTouch = -Infinity;
    let paused = 0;         // ms the clock has spent held
    let frozenAt = null;    // wall time the current hold began
    let lastLook = null;

    const resumeMs = () => Math.max(0, (spec?.resumeAfterSeconds ?? 2) * 1000);
    const held = (t) => holds > 0 || t - lastTouch < resumeMs();
    // The clock stops when the hold starts, not at the next frame drawn, and starts again when the pause
    // runs out, not whenever a frame next happens to be drawn (the view may have been off screen).
    const freeze = (t) => { if (frozenAt === null) frozenAt = t; };

    return {
        configure(next) {
            const keys = next && Array.isArray(next.keys) ? next.keys : null;
            spec = keys && keys.length >= 2 && Number.isFinite(next.periodSeconds) && next.periodSeconds > 0 ? next : null;
            if (!spec) lastLook = null;
        },
        active: () => !!spec,
        hold(on) {
            const t = now();
            if (on) { freeze(t); holds += 1; return; }
            holds = Math.max(0, holds - 1);
            lastTouch = t;
            if (spec) wake(resumeMs() + 16);
        },
        nudge() { const t = now(); freeze(t); lastTouch = t; if (spec) wake(resumeMs() + 16); },
        step() {
            if (!spec) return null;
            const t = now();
            const resumeAt = lastTouch + resumeMs();
            if (frozenAt !== null && holds === 0 && t >= resumeAt) { paused += Math.max(0, resumeAt - frozenAt); frozenAt = null; }
            const clock = (frozenAt ?? t) - paused;
            const phase = ((clock / 1000) / spec.periodSeconds) % 1;
            const look = shotLook(spec.keys, phase);
            lastLook = look && { ...look, phase };
            return lastLook;
        },
        held: () => !!spec && held(now()),
        status: () => ({ active: !!spec, held: !!spec && held(now()), look: lastLook,
            periodSeconds: spec?.periodSeconds ?? null, keys: spec?.keys?.length ?? 0 })
    };
}
