// How a Field control's travel maps onto its value — the browser half of `afield.ControlLaw`.
//
// One module, loaded by BOTH control surfaces: `index.html` (before `sliders.js`, which draws the
// output-area controls) and `controllers.html` (before `controllers.js`, which draws the remote tablet
// surface). That is deliberate. A control group is a single `ControllerControl` record served to two
// renderers, so a law belongs in one place they both read rather than in two places that agree today.
//
// The Kotlin side of every function here is in `src/jvmMain/kotlin/afield/ControlLaw.kt`, and
// `ControlSurfaceProbeTest` runs this exact file under node against the HTML the Kotlin builder emits,
// on a shared matrix of laws and values. So the two implementations cannot drift apart quietly — which
// they otherwise would, because the failure looks like "the number under the handle is slightly wrong"
// and nobody files that.
//
// The invariants worth knowing before editing:
//
//   * Both endpoints are short-circuited, in both directions. `min * (max/min)**1` is exact for
//     20..20000 and NOT exact for e.g. 7..29, where it gives 29.000000000000004 — a value above the
//     declared maximum. A slider at the end of its track must send the maximum.
//   * Nothing here may return a non-finite number. `data-value="NaN"` renders as a wedged handle and
//     a readout that says "NaN". Kotlin refuses an impossible range at declaration time, so a NaN
//     arriving here means a stale cached script or hand-written markup — still not an excuse to emit
//     one, so every entry point falls back to the minimum.

(function () {
    'use strict';

    const LINEAR = 'linear';
    const LOG = 'log';
    const EXP = 'exp';
    const BIPOLAR = 'bipolar';
    const LAWS = [LINEAR, LOG, EXP, BIPOLAR];

    const finite = (value, fallback) => {
        const n = Number(value);
        return Number.isFinite(n) ? n : fallback;
    };

    /** Normalises whatever a dataset or a JSON record offers into the four numbers a law needs. */
    function spec(source) {
        const src = source || {};
        const min = finite(src.min, 0);
        const max = finite(src.max, 1);
        const name = String(src.law == null ? LINEAR : src.law).trim().toLowerCase();
        return {
            law: LAWS.indexOf(name) >= 0 ? name : LINEAR,
            min,
            max,
            // A non-positive exponent would make the taper diverge; 2 is the documented default.
            exponent: Math.max(finite(src.lawExponent, 2), 1e-6),
            center: finite(src.lawCenter, (min + max) / 2),
            significantDigits: Math.max(0, Math.round(finite(src.significantDigits, 0))),
            step: Math.max(0, finite(src.step, 0)),
            decimalPlaces: Math.max(0, Math.round(finite(src.decimalPlaces, 0)))
        };
    }

    /** Reads a law spec out of a `.field-slider` / `.field-meter` element's `data-*` attributes. */
    function specFromDataset(dataset) {
        const d = dataset || {};
        return spec({
            law: d.law,
            min: d.min,
            max: d.max,
            lawExponent: d.lawExponent,
            lawCenter: d.lawCenter,
            significantDigits: d.significantDigits,
            step: d.step,
            decimalPlaces: d.decimalPlaces
        });
    }

    /** The value at travel `t`, a fraction of the track. Exact at both ends by short-circuit. */
    function valueAt(s, t) {
        const spec_ = s.law === undefined ? spec(s) : s;
        const travel = Number(t);
        if (!Number.isFinite(travel) || travel <= 0) return spec_.min;
        if (travel >= 1) return spec_.max;
        const { law, min, max, exponent, center } = spec_;
        let out;
        if (law === LOG) {
            // Kotlin refuses this range, so reaching it means stale markup; degrade to linear rather
            // than hand the DOM a NaN.
            out = min > 0 && max > min ? min * Math.pow(max / min, travel) : min + travel * (max - min);
        } else if (law === EXP) {
            out = min + (max - min) * Math.pow(travel, exponent);
        } else if (law === BIPOLAR) {
            if (travel === 0.5) return center;
            out = travel > 0.5
                ? center + (max - center) * Math.pow((travel - 0.5) * 2, exponent)
                : center - (center - min) * Math.pow((0.5 - travel) * 2, exponent);
        } else {
            out = min + travel * (max - min);
        }
        return Number.isFinite(out) ? out : min;
    }

    /** The travel that shows `value` — the inverse of `valueAt`, clamped to 0..1. */
    function normalizedFor(s, value) {
        const spec_ = s.law === undefined ? spec(s) : s;
        const v = Number(value);
        const { law, min, max, exponent, center } = spec_;
        if (!Number.isFinite(v) || v <= min) return 0;
        if (v >= max) return 1;
        let t;
        if (law === LOG) {
            t = min > 0 && max > min ? Math.log(v / min) / Math.log(max / min) : (v - min) / (max - min);
        } else if (law === EXP) {
            t = Math.pow((v - min) / (max - min), 1 / exponent);
        } else if (law === BIPOLAR) {
            if (v === center) return 0.5;
            t = v > center
                ? 0.5 + 0.5 * Math.pow((v - center) / (max - center), 1 / exponent)
                : 0.5 - 0.5 * Math.pow((center - v) / (center - min), 1 / exponent);
        } else {
            t = max === min ? 0 : (v - min) / (max - min);
        }
        if (!Number.isFinite(t)) return 0;
        return Math.min(Math.max(t, 0), 1);
    }

    /**
     * How many decimals to show for `value`, given a significant-digit budget.
     *
     * A budget of 0 returns `stepDecimals` untouched, which is how every linear control keeps its
     * historical formatting to the digit. See `controlLawDecimals` in ControlLaw.kt for why a
     * nonlinear control cannot have one fixed decimal count.
     */
    function decimalsFor(value, significantDigits, stepDecimals) {
        const budget = Math.round(Number(significantDigits) || 0);
        const fallback = Math.round(Number(stepDecimals) || 0);
        if (budget <= 0) return fallback;
        const magnitude = Math.abs(Number(value));
        const exponent = magnitude > 0 && Number.isFinite(magnitude) ? Math.floor(Math.log10(magnitude)) : 0;
        return Math.min(Math.max(budget - 1 - exponent, 0), 8);
    }

    /** `value` as text, with the law's own idea of how many decimals that deserves. */
    function format(s, value) {
        const spec_ = s.law === undefined ? spec(s) : s;
        const v = Number(value);
        if (!Number.isFinite(v)) return '—';
        return v.toFixed(decimalsFor(v, spec_.significantDigits, spec_.decimalPlaces));
    }

    /**
     * `value` snapped to the control's step and then clamped back inside its range.
     *
     * Snapping is in **value** space, not travel space, so a declared step means the same thing
     * everywhere on the track. Clamping happens *after* the snap because it has to: snapping 20000 to
     * a 0.03 step from a minimum of 20 lands on 20000.0000000000036, which is above the maximum the
     * box declared, and that number is what the callback would receive.
     */
    function quantize(s, value) {
        const spec_ = s.law === undefined ? spec(s) : s;
        const v = Number(value);
        if (!Number.isFinite(v)) return spec_.min;
        const lo = Math.min(spec_.min, spec_.max);
        const hi = Math.max(spec_.min, spec_.max);
        let out = v;
        if (spec_.step > 0) {
            out = Math.round((v - spec_.min) / spec_.step) * spec_.step + spec_.min;
        }
        return Math.min(Math.max(out, lo), hi);
    }

    const api = { LAWS, spec, specFromDataset, valueAt, normalizedFor, decimalsFor, format, quantize };

    // `window` in a browser; `globalThis` under node, where the probe drives this directly.
    const host = typeof window !== 'undefined' ? window : globalThis;
    host.FieldControlLaw = host.FieldControlLaw || api;
    if (typeof module !== 'undefined' && module.exports) module.exports = api;
})();
