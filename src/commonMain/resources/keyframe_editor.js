const SVG_NS = "http://www.w3.org/2000/svg";
const STYLE_ID = "kf-editor-style";
const DEFAULT_KEYFRAMES = [
        { time: 0, value: 0.2, type: "linear" },
        { time: 0.35, value: 0.8, type: "bezierFree" },
        { time: 0.75, value: 0.4, type: "hold" },
];

const HANDLE_MODES = Object.freeze({
        FREE: "free",
        FLAT: "flat",
        CONTINUOUS: "continuous",
});

const DEFAULT_TYPES = {
        linear: { label: "Linear" },
        hold: {
                label: "Hold",
                decorate(group) {
                        const path = createSVGElement("path", {
                                d: "M -8 0 H 8",
                                class: "kf-keyframe-hold",
                        });
                        group.appendChild(path);
                },
        },
        bezierFree: {
                label: "Bezier (Free)",
                handleMode: HANDLE_MODES.FREE,
                decorate(group) {
                        const path = createSVGElement("path", {
                                d: "M -7 -7 L 7 7 M -7 7 L 7 -7",
                                class: "kf-keyframe-bezier",
                        });
                        group.appendChild(path);
                },
        },
        bezierFlat: {
                label: "Bezier (Flat)",
                handleMode: HANDLE_MODES.FLAT,
                decorate(group) {
                        const path = createSVGElement("path", {
                                d: "M -8 0 H 8",
                                class: "kf-keyframe-hold",
                        });
                        group.appendChild(path);
                },
        },
        bezierContinuous: {
                label: "Bezier (Continuous)",
                handleMode: HANDLE_MODES.CONTINUOUS,
                decorate(group) {
                        const path = createSVGElement("path", {
                                d: "M -7 0 L 0 -7 L 7 0",
                                class: "kf-keyframe-bezier",
                        });
                        group.appendChild(path);
                },
        },
};

const EPSILON = 1e-6;

const STYLE_BLOCK = `
.kf-editor {
  --kf-bg: #07090f;
  --kf-border: rgba(255, 255, 255, 0.08);
  --kf-panel: rgba(255, 255, 255, 0.04);
  --kf-text: rgba(255, 255, 255, 0.92);
  --kf-muted: rgba(255, 255, 255, 0.5);
  --kf-accent: #6ae3ff;
  --kf-selected: #ffd166;
  font-family: 'Inter', 'SF Pro Display', system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
  background: var(--kf-bg);
  color: var(--kf-text);
  border: 1px solid var(--kf-border);
  padding: 0.5rem;
  width: 100%;
  box-sizing: border-box;
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.45);
  position: relative;
}

.kf-editor svg {
  display: block;
  width: 100%;
  height: auto;
}

.kf-editor svg .kf-graph-bg {
  fill: var(--kf-panel);
  stroke: var(--kf-border);
}

.kf-grid line {
  stroke: rgba(255, 255, 255, 0.08);
  stroke-width: 1;
}

.kf-axis line {
  stroke: rgba(255, 255, 255, 0.2);
  stroke-width: 1.5;
}

.kf-axis-origin {
  stroke: rgba(255, 255, 255, 0.5);
  stroke-width: 2.5;
}

.kf-grid,
.kf-axis {
  pointer-events: none;
}

.kf-playhead {
  stroke: #ffab47;
  stroke-width: 2;
  pointer-events: none;
}

.kf-playhead-dot {
  fill: #ffab47;
  stroke: var(--kf-bg);
  stroke-width: 2;
  pointer-events: none;
}

.kf-snap-line {
  stroke: #ffd166;
  stroke-width: 2;
  stroke-dasharray: 4 4;
  pointer-events: none;
  opacity: 0.4;
}

.kf-drag-label {
  pointer-events: none;
}

.kf-drag-label rect {
  fill: rgba(5, 6, 10, 0.92);
  stroke: rgba(255, 255, 255, 0.18);
  stroke-width: 1;
}

.kf-drag-label text {
  fill: var(--kf-text);
  font-size: 12px;
  font-weight: 500;
  letter-spacing: 0.01em;
}

.kf-graph-path {
  fill: none;
  stroke: var(--kf-accent);
  stroke-width: 2;
  stroke-linecap: round;
  stroke-linejoin: round;
  pointer-events: none;
  transition: opacity 0.2s ease;
}

.kf-keyframes {
  transition: opacity 0.2s ease;
}

.kf-keyframe circle {
  fill: var(--kf-bg);
  stroke: var(--kf-accent);
  stroke-width: 2;
  pointer-events: visible;
}

.kf-keyframe.is-hovered circle {
  filter: drop-shadow(0 0 6px rgba(255, 255, 255, 0.4));
  stroke-width: 2.6;
}

.kf-keyframe--flat circle {
  stroke: #ffb347;
}

.kf-keyframe--continuous circle {
  stroke: #9c8df0;
}

.kf-keyframe--free circle {
  stroke: #7de2d1;
}

.kf-keyframe--hold circle,
.kf-keyframe--linear circle {
  stroke: #8f9bb3;
}

.kf-handle {
  fill: var(--kf-accent);
  stroke: none;
  opacity: 0.7;
  cursor: pointer;
  pointer-events: all;
  transition: opacity 0.15s ease, r 0.15s ease, stroke-width 0.15s ease;
}

.kf-handle:hover {
  opacity: 1;
  stroke: rgba(255, 255, 255, 0.5);
  stroke-width: 2;
}

.kf-keyframe.is-selected .kf-handle {
  fill: var(--kf-selected);
  opacity: 1;
}

.kf-keyframe.is-selected .kf-handle:hover {
  stroke: rgba(255, 255, 255, 0.7);
  stroke-width: 2;
}

.kf-handle-line {
  stroke: rgba(255, 255, 255, 0.25);
  stroke-width: 1;
  pointer-events: none;
}
.kf-handle-line--default {
  stroke: rgba(255, 255, 255, 0.25);
}

.kf-handle-line--flat {
  stroke: #ffb347;
}

.kf-handle-line--continuous {
  stroke: #9c8df0;
}

.kf-handle-line--free {
  stroke: #7de2d1;
}

.kf-handle--default {
  fill: var(--kf-accent);
  cursor: pointer;
  pointer-events: all;
  transition: opacity 0.15s ease, stroke-width 0.15s ease, stroke 0.15s ease;
}

.kf-handle--default:hover {
  opacity: 1 !important;
  stroke: rgba(255, 255, 255, 0.8) !important;
  stroke-width: 2 !important;
}

.kf-handle--flat {
  fill: #ffb347;
  cursor: pointer;
  pointer-events: all;
  transition: opacity 0.15s ease, stroke-width 0.15s ease, stroke 0.15s ease;
}

.kf-handle--flat:hover {
  opacity: 1 !important;
  stroke: rgba(255, 255, 255, 0.8) !important;
  stroke-width: 2 !important;
}

.kf-handle--continuous {
  fill: #9c8df0;
  cursor: pointer;
  pointer-events: all;
  transition: opacity 0.15s ease, stroke-width 0.15s ease, stroke 0.15s ease;
}

.kf-handle--continuous:hover {
  opacity: 1 !important;
  stroke: rgba(255, 255, 255, 0.8) !important;
  stroke-width: 2 !important;
}

.kf-handle--free {
  fill: #7de2d1;
  cursor: pointer;
  pointer-events: all;
  transition: opacity 0.15s ease, stroke-width 0.15s ease, stroke 0.15s ease;
}

.kf-handle--free:hover {
  opacity: 1 !important;
  stroke: rgba(255, 255, 255, 0.8) !important;
  stroke-width: 2 !important;
}

.kf-keyframe.is-selected .kf-handle-line {
  stroke: var(--kf-accent);
}

.kf-keyframe.is-selected > circle {
  stroke: var(--kf-selected);
  fill: rgba(255, 209, 102, 0.15);
}

.kf-keyframe-hold,
.kf-keyframe-bezier {
  stroke: currentColor;
  stroke-width: 1.2;
  stroke-linecap: round;
  stroke-linejoin: round;
  color: var(--kf-muted);
}

.kf-keyframe.is-selected .kf-keyframe-hold,
.kf-keyframe.is-selected .kf-keyframe-bezier {
  color: var(--kf-selected);
}

.kf-keyframe.is-locked circle {
  stroke-dasharray: 2.5 2;
  opacity: 0.55;
}

.kf-keyframe.is-locked.is-selected circle {
  stroke-dasharray: 2.5 2;
  opacity: 0.7;
}

.kf-marquee {
  fill: rgba(255, 209, 102, 0.08);
  stroke: var(--kf-selected);
  stroke-width: 1;
  stroke-dasharray: 4 3;
  pointer-events: none;
}

.kf-overlay path {
  fill: none;
  stroke: rgba(255, 255, 255, 0.35);
  stroke-width: 1.2;
}

.kf-overlay {
  pointer-events: none;
}

.kf-path-break {
  stroke: #ffab47;
  stroke-width: 2;
  stroke-dasharray: 4 4;
  fill: none;
  pointer-events: none;
}

.kf-origin-marker {
  fill: #ffab47;
  stroke: #05060a;
  stroke-width: 1;
  pointer-events: none;
}

.kf-axis-label {
  fill: rgba(255, 255, 255, 0.4);
  font-size: 9px;
  font-family: 'Inter', system-ui, sans-serif;
  pointer-events: none;
  user-select: none;
}

.kf-axis-label--origin {
  fill: rgba(255, 255, 255, 0.6);
}

.kf-menu {
  position: absolute;
  min-width: 120px;
  background: #0a0d13;
  border: 1px solid rgba(255, 255, 255, 0.08);
  box-shadow: 0 10px 25px rgba(0, 0, 0, 0.5);
  padding: 0.25rem;
  display: flex;
  flex-direction: column;
  gap: 0.15rem;
  z-index: 20;
  font-size: 0.7rem;
}

.kf-menu.is-hidden {
  display: none;
}

.kf-menu-group {
  display: flex;
  flex-direction: row;
  gap: 0.15rem;
}

.kf-menu-divider {
  height: 1px;
  background: rgba(255, 255, 255, 0.08);
  margin: 0.1rem 0;
}

.kf-menu-label {
  font-size: 0.6rem;
  text-transform: uppercase;
  letter-spacing: 0.1em;
  color: var(--kf-muted);
  padding: 0 0.15rem;
}

.kf-menu button {
  font: inherit;
  text-align: left;
}

.kf-menu-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  border: 1px solid transparent;
  padding: 0.15rem 0.3rem;
  background: transparent;
  color: inherit;
  cursor: pointer;
  font-size: 0.7rem;
  line-height: 1.2;
}

.kf-menu-item:hover {
  border-color: rgba(255, 255, 255, 0.18);
  background: rgba(255, 255, 255, 0.04);
}

.kf-menu-item.is-active {
  border-color: var(--kf-accent);
  color: var(--kf-accent);
}

.kf-type-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 20px;
  border: 1px solid transparent;
  background: transparent;
  color: inherit;
  cursor: pointer;
  padding: 1px;
}

.kf-type-icon:hover {
  border-color: rgba(255, 255, 255, 0.25);
  background: rgba(255, 255, 255, 0.06);
}

.kf-type-icon.is-active {
  border-color: var(--kf-accent);
  color: var(--kf-accent);
}

.kf-type-icon svg {
  width: 16px;
  height: 14px;
}

`;

// Copied keys, shared by every editor on the page.
const KF_CLIPBOARD = "field.keyframes.clipboard.v1";

const KF_TYPE_ICONS = {
        linear: `<svg viewBox="0 0 16 14" xmlns="http://www.w3.org/2000/svg"><line x1="2" y1="12" x2="14" y2="2" stroke="currentColor" stroke-width="1.5"/></svg>`,
        hold: `<svg viewBox="0 0 16 14" xmlns="http://www.w3.org/2000/svg"><polyline points="2,4 9,4 9,10 14,10" fill="none" stroke="currentColor" stroke-width="1.5"/></svg>`,
        bezierFree: `<svg viewBox="0 0 16 14" xmlns="http://www.w3.org/2000/svg"><path d="M2,12 C6,12 10,2 14,2" fill="none" stroke="currentColor" stroke-width="1.5"/></svg>`,
        bezierFlat: `<svg viewBox="0 0 16 14" xmlns="http://www.w3.org/2000/svg"><path d="M2,10 C6,10 10,4 14,4" fill="none" stroke="currentColor" stroke-width="1.5"/><line x1="1" y1="10" x2="5" y2="10" stroke="currentColor" stroke-width="0.8" opacity="0.5"/></svg>`,
        bezierContinuous: `<svg viewBox="0 0 16 14" xmlns="http://www.w3.org/2000/svg"><path d="M2,12 C5,12 5,2 8,7 C11,12 11,2 14,2" fill="none" stroke="currentColor" stroke-width="1.5"/></svg>`,
};

// The page the menu is drawn in: the one holding this editor's iframe when it is reachable (same
// origin), else this document.
function kfMenuHost() {
        try {
                const frame = window.frameElement;
                if (frame && window.parent && window.parent.document) {
                        return { doc: window.parent.document, win: window.parent, frame };
                }
        } catch (_) {
                // Cross-origin parent: draw here.
        }
        return { doc: document, win: window, frame: null };
}

// The menu's own styles, self-contained because the host page has none of the editor's variables.
const KF_MENU_CSS = `
.kfm {
  position: fixed;
  z-index: 2147483600;
  min-width: 200px;
  max-width: 340px;
  max-height: calc(100vh - 16px);
  overflow-y: auto;
  background: #0a0d13;
  color: rgba(255, 255, 255, 0.92);
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 8px;
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.55);
  padding: 6px;
  display: flex;
  flex-direction: column;
  gap: 2px;
  font: 12px/1.3 "Iosevka Web", ui-monospace, Menlo, monospace;
}
.kfm-header {
  font-size: 10px;
  text-transform: uppercase;
  letter-spacing: 0.1em;
  color: rgba(255, 255, 255, 0.5);
  padding: 2px 6px 4px;
}
.kfm-divider { height: 1px; background: rgba(255, 255, 255, 0.08); margin: 3px 0; }
.kfm-item {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: center;
  border: 1px solid transparent;
  border-radius: 4px;
  padding: 4px 6px;
  background: transparent;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
}
.kfm-item:hover:not(:disabled) { background: rgba(106, 227, 255, 0.1); border-color: rgba(106, 227, 255, 0.35); }
.kfm-item:disabled { opacity: 0.35; cursor: default; }
.kfm-item.is-danger:hover:not(:disabled) { background: rgba(255, 90, 90, 0.12); border-color: rgba(255, 90, 90, 0.4); }
.kfm-hint { color: rgba(255, 255, 255, 0.4); font-size: 11px; }
.kfm-fields { display: flex; gap: 6px; padding: 2px 4px 6px; }
.kfm-field { display: flex; flex-direction: column; gap: 2px; flex: 1; font-size: 10px; color: rgba(255, 255, 255, 0.5); }
.kfm-field input {
  width: 100%;
  box-sizing: border-box;
  background: #05070b;
  color: #6ae3ff;
  border: 1px solid rgba(255, 255, 255, 0.14);
  border-radius: 4px;
  padding: 4px 6px;
  font: 13px/1.2 "Iosevka Web", ui-monospace, Menlo, monospace;
}
.kfm-field input:focus { outline: none; border-color: #6ae3ff; }
.kfm-field input:disabled { opacity: 0.4; }
.kfm-types { display: grid; grid-template-columns: repeat(5, 1fr); gap: 3px; padding: 0 2px 2px; }
.kfm-type {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  padding: 4px 2px 3px;
  border: 1px solid transparent;
  border-radius: 4px;
  background: transparent;
  color: rgba(255, 255, 255, 0.8);
  font: 9px/1.1 "Iosevka Web", ui-monospace, Menlo, monospace;
  cursor: pointer;
}
.kfm-type svg { width: 18px; height: 15px; }
.kfm-types-wide { grid-template-columns: repeat(4, 1fr); }
.kfm-section {
  font-size: 9px;
  text-transform: uppercase;
  letter-spacing: 0.1em;
  color: rgba(255, 255, 255, 0.4);
  padding: 6px 6px 2px;
}
.kfm-ease { display: grid; grid-template-columns: 1fr 1fr; gap: 1px 8px; padding: 0 4px 2px; }
.kfm-ease-row { display: flex; align-items: center; gap: 1px; }
.kfm-ease-row > span { flex: 1; font-size: 10px; color: rgba(255, 255, 255, 0.65); }
.kfm-ease-type {
  width: 24px;
  height: 20px;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  border: 1px solid transparent;
  border-radius: 3px;
  background: transparent;
  color: rgba(255, 255, 255, 0.8);
  cursor: pointer;
}
.kfm-ease-type svg { width: 16px; height: 14px; }
.kfm-ease-type:hover { background: rgba(255, 255, 255, 0.06); border-color: rgba(255, 255, 255, 0.2); }
.kfm-ease-type.is-active { color: #6ae3ff; border-color: #6ae3ff; }
.kfm-type:hover { background: rgba(255, 255, 255, 0.06); border-color: rgba(255, 255, 255, 0.2); }
.kfm-type.is-active { color: #6ae3ff; border-color: #6ae3ff; }
`;

function injectMenuStyles(doc) {
        if (doc.getElementById("kf-menu-style")) return;
        const style = doc.createElement("style");
        style.id = "kf-menu-style";
        style.textContent = KF_MENU_CSS;
        (doc.head || doc.documentElement).appendChild(style);
}

let stylesInjected = false;

function injectStyles() {
        if (stylesInjected) return;
        const tag = document.createElement("style");
        tag.id = STYLE_ID;
        tag.textContent = STYLE_BLOCK;
        document.head.appendChild(tag);
        stylesInjected = true;
}

function clamp(value, min, max) {
        return Math.min(Math.max(value, min), max);
}

function rangeSize([min, max]) {
        return Math.max(max - min, 0.0001);
}

function createSVGElement(tag, attrs = {}) {
        const el = document.createElementNS(SVG_NS, tag);
        Object.entries(attrs).forEach(([key, val]) => {
                el.setAttribute(key, val);
        });
        return el;
}

function defaultEvaluateGraph(keyframes, ctx) {
        const samples = ctx.samples ?? (ctx.sampleCurve ? ctx.sampleCurve({ stepsPerSegment: 48 }) : []);
        const path = ctx.defaultPath ?? buildPathFromSamples(samples, ctx);
        return { path };
}

function buildPathFromSamples(samples, ctx) {
        if (!samples.length) return "";
        return samples
            .map((sample, index) => {
                    const x = ctx.timeToX(sample.time);
                    const y = ctx.valueToY(sample.value);
                    return `${index === 0 ? "M" : "L"} ${x.toFixed(2)} ${y.toFixed(2)}`;
            })
            .join(" ");
}

function hasActiveHandle(handle) {
        if (!handle) return false;
        return Math.abs(handle.time ?? 0) > EPSILON || Math.abs(handle.value ?? 0) > EPSILON;
}

function controlWeightFromHandle(handle, span) {
        if (!handle) return 1;
        const length = Math.hypot(handle.time ?? 0, handle.value ?? 0);
        const baseline = Math.max(span, EPSILON);
        return clamp(1 + length / baseline, 0.2, 6);
}

function resolveControlPoint(node, other, key, span, delta) {
        const handle = node.handles?.[key];
        const fallbackRatio = 0.3;
        const defaultTimeOffset = span * (key === "out" ? fallbackRatio : -fallbackRatio);
        const defaultValueOffset = delta * (key === "out" ? fallbackRatio : -fallbackRatio);
        let timeOffset = Number.isFinite(handle?.time) ? handle.time : defaultTimeOffset;
        let valueOffset = Number.isFinite(handle?.value) ? handle.value : defaultValueOffset;
        const minTime = Math.min(node.time, other.time) + EPSILON;
        const maxTime = Math.max(node.time, other.time) - EPSILON;
        const x = clamp(node.time + timeOffset, minTime, maxTime);
        const y = node.value + valueOffset;
        return { x, y, w: controlWeightFromHandle(handle, span) };
}

function buildRationalControls(prev, next) {
        const span = Math.max(next.time - prev.time, EPSILON);
        const delta = next.value - prev.value;
        const controls = {
                p0: { x: prev.time, y: prev.value, w: 1 },
                p3: { x: next.time, y: next.value, w: 1 },
        };
        controls.p1 = resolveControlPoint(prev, next, "out", span, delta);
        controls.p2 = resolveControlPoint(next, prev, "in", span, delta);
        if (controls.p1.x > controls.p2.x - EPSILON) {
                const mid = (controls.p1.x + controls.p2.x) / 2;
                controls.p1.x = clamp(mid - EPSILON, prev.time + EPSILON, next.time - EPSILON);
                controls.p2.x = clamp(mid + EPSILON, prev.time + EPSILON, next.time - EPSILON);
        }
        return controls;
}

function rationalBezierPoint(ctrls, t) {
        const { p0, p1, p2, p3 } = ctrls;
        const mt = 1 - t;
        const b0 = mt * mt * mt;
        const b1 = 3 * mt * mt * t;
        const b2 = 3 * mt * t * t;
        const b3 = t * t * t;
        const w0 = p0.w ?? 1;
        const w1 = p1.w ?? 1;
        const w2 = p2.w ?? 1;
        const w3 = p3.w ?? 1;
        const weightSum = b0 * w0 + b1 * w1 + b2 * w2 + b3 * w3;
        const x = (b0 * w0 * p0.x + b1 * w1 * p1.x + b2 * w2 * p2.x + b3 * w3 * p3.x) / weightSum;
        const y = (b0 * w0 * p0.y + b1 * w1 * p1.y + b2 * w2 * p2.y + b3 * w3 * p3.y) / weightSum;
        return { x, y };
}

function groupSamplesByContinuity(samples, ctx, manualBreaks = []) {
        const groups = [];
        const breaks = [...manualBreaks];
        if (!samples.length) return { groups, breaks };
        const timeSpan = rangeSize(ctx?.viewTimeRange ?? [0, 1]);
        const valueSpan = rangeSize(ctx?.viewValueRange ?? [0, 1]);
        const timeGapThreshold = timeSpan / 18;
        const valueJumpThreshold = valueSpan * 0.18;
        const slopeThreshold = (valueSpan / Math.max(timeSpan, EPSILON)) * 8;
        const buildKey = (a, b) => `${a.time}:${a.value}:${b.time}:${b.value}`;
        const forcedKeys = new Set(manualBreaks.map(([start, end]) => buildKey(start, end)));
        let current = [samples[0]];
        for (let i = 1; i < samples.length; i += 1) {
                const prev = samples[i - 1];
                const sample = samples[i];
                const dt = sample.time - prev.time;
                const dv = Math.abs(sample.value - prev.value);
                const slope = dt > EPSILON ? dv / dt : 0;
                const manual = forcedKeys.has(buildKey(prev, sample));
                if (manual) {
                        forcedKeys.delete(buildKey(prev, sample));
                }
                const jump = manual || dt > timeGapThreshold || dv > valueJumpThreshold || slope > slopeThreshold;
                if (jump) {
                        groups.push(current);
                        if (!manual) {
                                breaks.push([prev, sample]);
                        }
                        current = [sample];
                } else {
                        current.push(sample);
                }
        }
        if (current.length) groups.push(current);
        return { groups, breaks };
}

function buildGroupedPath(groups, ctx) {
        if (!groups.length) return "";
        return groups
            .map((group) => buildPathFromSamples(group, ctx))
            .join(" ");
}

function buildBreakPath(breaks, ctx) {
        if (!breaks.length) return "";
        return breaks
            .map(([start, end]) => {
                    const sx = ctx.timeToX(start.time);
                    const sy = ctx.valueToY(start.value);
                    const ex = ctx.timeToX(end.time);
                    const ey = ctx.valueToY(end.value);
                    return `M ${sx.toFixed(2)} ${sy.toFixed(2)} L ${ex.toFixed(2)} ${ey.toFixed(2)}`;
            })
            .join(" ");
}



function computeValueSlope(kf, key, fallback) {
        const handle = kf.handles?.[key];
        if (!handle) return fallback;
        const dt = Number.isFinite(handle.time) ? handle.time : 0;
        if (Math.abs(dt) < EPSILON) {
                return fallback;
        }
        const dv = Number.isFinite(handle.value) ? handle.value : 0;
        return dv / dt;
}

function computeMonotoneSlopes(prev, next) {
        const span = Math.max(next.time - prev.time, EPSILON);
        const delta = next.value - prev.value;
        const defaultSlope = delta / span;
        const prevIsLinear = prev.type === "linear";
        const nextIsLinear = next.type === "linear";
        let m0 = prevIsLinear ? defaultSlope : computeValueSlope(prev, "out", defaultSlope);
        let m1 = nextIsLinear ? defaultSlope : computeValueSlope(next, "in", defaultSlope);
        if (Math.abs(delta) < EPSILON) {
                return { m0: 0, m1: 0 };
        }
        let alpha = (m0 * span) / delta;
        let beta = (m1 * span) / delta;
        if (alpha < 0) alpha = 0;
        if (beta < 0) beta = 0;
        const sum = alpha + beta;
        if (sum > 6) {
                const tau = 6 / sum;
                alpha *= tau;
                beta *= tau;
        }
        return {
                m0: alpha * delta / span,
                m1: beta * delta / span,
        };
}

export class KeyframeEditor {
        constructor(mount, options = {}) {
                const root = typeof mount === "string" ? document.querySelector(mount) : mount;
                if (!root) {
                        throw new Error("KeyframeEditor: mount element not found");
                }

                injectStyles();

                this.root = root;
                this.options = {
                        width: options.width ?? 640,
                        height: options.height ?? 320,
                        padding: options.padding ?? 36,
                        timeRange: options.timeRange ?? [0, 1],
                        valueRange: options.valueRange ?? [0, 1],
                        evaluateGraph: options.evaluateGraph ?? defaultEvaluateGraph,
                        onChange: options.onChange ?? null,
                        keyframes: options.keyframes ?? DEFAULT_KEYFRAMES,
                        types: { ...DEFAULT_TYPES, ...(options.types || {}) },
                        playhead: options.playhead ?? null,
                };

                this.initialTimeRange = [...this.options.timeRange];
                this.viewTimeRange = [...this.options.timeRange];
                this.domainTimeRange = [...this.options.timeRange];
                this.initialValueRange = [...this.options.valueRange];
                this.viewValueRange = [...this.options.valueRange];
                this.domainValueRange = [...this.options.valueRange];
                this.idCounter = 1;
                this.keyframes = this.options.keyframes.map((kf) => this.prepareKeyframe(kf));
                const initialSelection = this.keyframes[0]?.id ? [this.keyframes[0].id] : [];
                this.state = {
                        selectedIds: initialSelection,
                        anchorId: initialSelection[0] ?? null,
                };
                this.playhead =
                    typeof this.options.playhead === "number" && Number.isFinite(this.options.playhead)
                        ? this.options.playhead
                        : null;

                /** Map from overlay id → {samples, color, strokeWidth, dashArray} */
                this._overlays = new Map();
                this._isDragging = false;

                this.buildDOM();
                this.refresh({ notify: false });
        }

        get innerWidth() {
                return Math.max(this.options.width - this.options.padding * 2, 10);
        }

        get innerHeight() {
                return Math.max(this.options.height - this.options.padding * 2, 10);
        }

        /**
         * Resize the editor to a new width/height and rebuild the SVG
         */
        resize(width, height) {
                this.options.width = width;
                this.options.height = height;
                this.wrapper.style.width = `${width}px`;
                // Rebuild SVG with new dimensions
                if (this.svg) {
                        this.svg.remove();
                }
                this.buildSVG();
                this.refresh({ notify: false });
        }

        prepareKeyframe(kf) {
                const desiredTime =
                    typeof kf.time === "number" && Number.isFinite(kf.time) ? kf.time : this.rangeMid();
                if (Number.isFinite(desiredTime)) {
                        this.extendTimeDomainToInclude(desiredTime);
                }
                const desiredValue =
                    typeof kf.value === "number" && Number.isFinite(kf.value)
                        ? kf.value
                        : this.rangeMid(this.viewValueRange);
                if (Number.isFinite(desiredValue)) {
                        this.extendValueDomainToInclude(desiredValue);
                }
                const [timeMin, timeMax] = this.domainTimeRange;
                const [valueMin, valueMax] = this.domainValueRange;
                const prepared = {
                        id: kf.id ?? `kf-${Date.now().toString(36)}-${this.idCounter++}`,
                        time: clamp(Number.isFinite(desiredTime) ? desiredTime : timeMin, timeMin, timeMax),
                        value: clamp(Number.isFinite(desiredValue) ? desiredValue : valueMin, valueMin, valueMax),
                        type: this.options.types[kf.type] ? kf.type : "linear",
                        handles: this.createHandlePayload(kf.handles),
                        locked: !!kf.locked,
                };
                this.ensureHandles(prepared);
                this.applyHandleMode(prepared);
                return prepared;
        }

        buildDOM() {
                this.root.innerHTML = "";
                this.wrapper = document.createElement("div");
                this.wrapper.className = "kf-editor";
                this.root.appendChild(this.wrapper);

                this.buildSVG();
                this.buildContextMenu();
        }

        buildSVG() {
                const svg = createSVGElement("svg", {
                        width: this.options.width,
                        height: this.options.height,
                        viewBox: `0 0 ${this.options.width} ${this.options.height}`,
                });

                const bg = createSVGElement("rect", {
                        class: "kf-graph-bg",
                        x: this.options.padding,
                        y: this.options.padding,
                        width: this.innerWidth,
                        height: this.innerHeight,
                        rx: 12,
                        ry: 12,
                });

                const gridGroup = createSVGElement("g", { class: "kf-grid" });
                const axisGroup = createSVGElement("g", { class: "kf-axis" });
                const overlayGroup = createSVGElement("g", { class: "kf-overlay" });
                const keyframeLayer = createSVGElement("g", { class: "kf-keyframes" });

                const defs = createSVGElement("defs");
                const clipId = `kf-clip-${Math.random().toString(36).slice(2)}`;
                const clipPath = createSVGElement("clipPath", { id: clipId });
                const clipRect = createSVGElement("rect", {
                        x: this.options.padding,
                        y: this.options.padding,
                        width: this.innerWidth,
                        height: this.innerHeight,
                        rx: 12,
                        ry: 12,
                });
                clipPath.appendChild(clipRect);
                defs.appendChild(clipPath);
                svg.appendChild(defs);
                this.clipPathId = clipId;
                this.clipRect = clipRect;

                this.graphPath = createSVGElement("path", { class: "kf-graph-path" });
                this.graphOverlay = overlayGroup;
                this.graphOverlayExtras = createSVGElement("g", { class: "kf-overlay-extras" });
                overlayGroup.appendChild(this.graphOverlayExtras);
                // Persistent overlay curves sent from backend (not cleared on re-render)
                this.backendOverlayGroup = createSVGElement("g", { class: "kf-backend-overlays" });
                overlayGroup.appendChild(this.backendOverlayGroup);
                this.snapIndicatorTime = createSVGElement("line", {
                        class: "kf-snap-line",
                        visibility: "hidden",
                });
                this.snapIndicatorValue = createSVGElement("line", {
                        class: "kf-snap-line",
                        visibility: "hidden",
                });
                overlayGroup.appendChild(this.snapIndicatorTime);
                overlayGroup.appendChild(this.snapIndicatorValue);
                this.dragLabelGroup = createSVGElement("g", {
                        class: "kf-drag-label",
                        visibility: "hidden",
                });
                this.dragLabelRect = createSVGElement("rect", {
                        width: 0,
                        height: 0,
                        rx: 6,
                        ry: 6,
                });
                this.dragLabelText = createSVGElement("text", {
                        x: 6,
                        y: 6,
                        'dominant-baseline': 'hanging',
                });
                this.dragLabelGroup.appendChild(this.dragLabelRect);
                this.dragLabelGroup.appendChild(this.dragLabelText);
                overlayGroup.appendChild(this.dragLabelGroup);
                this.playheadLine = createSVGElement("line", {
                        class: "kf-playhead",
                        x1: this.options.padding,
                        x2: this.options.padding,
                        y1: 0,
                        y2: this.options.height,
                });
                this.playheadDot = createSVGElement("circle", {
                        class: "kf-playhead-dot",
                        r: 5,
                        cx: this.options.padding,
                        cy: this.options.padding + this.innerHeight,
                });
                this.keyframeLayer = keyframeLayer;
                this.gridGroup = gridGroup;
                const clipRef = `url(#${clipId})`;
                this.graphPath.setAttribute("clip-path", clipRef);
                overlayGroup.setAttribute("clip-path", clipRef);
                keyframeLayer.setAttribute("clip-path", clipRef);
                gridGroup.setAttribute("clip-path", clipRef);
                this.playheadDot.setAttribute("clip-path", clipRef);
                this.snapIndicatorTime.setAttribute("clip-path", clipRef);
                this.snapIndicatorValue.setAttribute("clip-path", clipRef);
                this.dragLabelGroup.setAttribute("clip-path", clipRef);

                const hitRegion = createSVGElement("rect", {
                        x: this.options.padding,
                        y: this.options.padding,
                        width: this.innerWidth,
                        height: this.innerHeight,
                        fill: "transparent",
                        style: "cursor: crosshair;",
                        'pointer-events': 'all',
                });

                hitRegion.addEventListener("pointerdown", (event) => {
                        if (event.button === 2) {
                                // A right-drag pans; a right-click that does not move opens the curve's menu.
                                const start = event;
                                const point = this.pointerToValues(event);
                                const up = (e) => {
                                        window.removeEventListener("pointerup", up);
                                        if (Math.hypot(e.clientX - start.clientX, e.clientY - start.clientY) < 4) {
                                                this.openMenu(start, { kind: "background", point });
                                        }
                                };
                                window.addEventListener("pointerup", up);
                                this.startPan(event);
                                return;
                        }
                        if (event.button === 1) {
                                this.startPan(event);
                                return;
                        }
                        if (event.button !== 0) return;
                        this.hideContextMenu();
                        this.startMarquee(event);
                });

                hitRegion.addEventListener("contextmenu", (event) => {
                        event.preventDefault();
                });

                // Where Paste puts keys when there is no playhead in view.
                hitRegion.addEventListener("pointermove", (event) => {
                        this._lastPointerTime = this.pointerToValues(event).time;
                });

                hitRegion.addEventListener("dblclick", (event) => {
                        event.preventDefault();
                        const point = this.pointerToValues(event);
                        this.addKeyframe(point);
                });

                // Two-finger trackpad pan & pinch-zoom via wheel events
                hitRegion.addEventListener("wheel", (event) => {
                        event.preventDefault();
                        const timeSpan = rangeSize(this.viewTimeRange);
                        const valueSpan = rangeSize(this.viewValueRange);
                        const width = Math.max(this.innerWidth, 1);
                        const height = Math.max(this.innerHeight, 1);

                        if (event.ctrlKey) {
                                // Pinch-to-zoom: ctrlKey is set by trackpad pinch gestures
                                const zoomFactor = 1 + event.deltaY * 0.005;
                                const rect = this.svg.getBoundingClientRect();
                                const localX = event.clientX - rect.left - this.options.padding;
                                const localY = event.clientY - rect.top - this.options.padding;
                                const anchorTimeRatio = clamp(localX / width, 0, 1);

                                const [tMin, tMax] = this.viewTimeRange;
                                const anchorTime = tMin + anchorTimeRatio * timeSpan;
                                const newTimeSpan = Math.max(timeSpan * zoomFactor, 0.1);
                                const newTMin = anchorTime - anchorTimeRatio * newTimeSpan;
                                const newTMax = anchorTime + (1 - anchorTimeRatio) * newTimeSpan;
                                this.setViewTimeRange([newTMin, newTMax], { render: false });

                                // Zoom value axis only when Shift is also held
                                if (event.shiftKey) {
                                        const anchorValueRatio = clamp(1 - localY / height, 0, 1);
                                        const [vMin, vMax] = this.viewValueRange;
                                        const anchorValue = vMin + anchorValueRatio * valueSpan;
                                        const newValueSpan = Math.max(valueSpan * zoomFactor, 0.01);
                                        const newVMin = anchorValue - anchorValueRatio * newValueSpan;
                                        const newVMax = anchorValue + (1 - anchorValueRatio) * newValueSpan;
                                        this.setViewValueRange([newVMin, newVMax], { render: false });
                                }
                        } else {
                                // Two-finger scroll → pan
                                const timeShift = (event.deltaX / width) * timeSpan;
                                const valueShift = -(event.deltaY / height) * valueSpan;
                                const [tMin, tMax] = this.viewTimeRange;
                                this.setViewTimeRange([tMin + timeShift, tMax + timeShift], { render: false });
                                const [vMin, vMax] = this.viewValueRange;
                                this.setViewValueRange([vMin + valueShift, vMax + valueShift], { render: false });
                        }
                        this.render();
                }, { passive: false });

                this.marqueeRect = createSVGElement("rect", {
                        class: "kf-marquee",
                        visibility: "hidden",
                });

                svg.appendChild(gridGroup);
                svg.appendChild(axisGroup);
                svg.appendChild(bg);
                svg.appendChild(hitRegion);
                svg.appendChild(this.graphPath);
                svg.appendChild(overlayGroup);
                svg.appendChild(this.marqueeRect);
                svg.appendChild(keyframeLayer);
                svg.appendChild(this.playheadLine);
                svg.appendChild(this.playheadDot);

                this.svg = svg;
                this.axisGroup = axisGroup;
                this.wrapper.appendChild(svg);

                this.renderGrid();
        }

        // ── Menu, keyboard, history and clipboard ───────────────────────
        //
        // The menu is drawn in the page that holds the editor's iframe when it can reach it (Field's
        // own page, same origin), so it is never clipped by the editor's 232 px frame and can carry
        // as much as it needs. Without that page it falls back to this document.

        buildContextMenu() {
                this.menuTargetId = null;
                this.menuEl = null;
                this._menuCleanup = [];
                this._lastPointerTime = null;
                this.resetHistory();

                this.handleGlobalPointerDown = (event) => {
                        if (this.menuEl && !this.menuEl.contains(event.target)) this.hideContextMenu();
                };
                this.handleKeyDown = (event) => this.onKeyDown(event);
                window.addEventListener("pointerdown", this.handleGlobalPointerDown, true);
                window.addEventListener("keydown", this.handleKeyDown);
        }

        onKeyDown(event) {
                const target = event.target;
                if (target && (target.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(target.tagName))) return;
                const key = event.key;
                const lower = typeof key === "string" ? key.toLowerCase() : key;
                const mod = event.metaKey || event.ctrlKey;
                const consume = () => { event.preventDefault(); event.stopPropagation(); };
                if (key === "Escape") {
                        if (this.menuEl) this.hideContextMenu();
                        else if (this.state.selectedIds.length) this.setSelection([], null);
                        return;
                }
                if (mod && lower === "z") { consume(); event.shiftKey ? this.redo() : this.undo(); return; }
                if (mod && lower === "y") { consume(); this.redo(); return; }
                if (mod && lower === "c") { consume(); this.copySelection(); return; }
                if (mod && lower === "x") { consume(); this.cutSelection(); return; }
                if (mod && lower === "v") { consume(); this.paste(); return; }
                if (mod && lower === "d") { consume(); this.duplicateSelection(); return; }
                if (mod && lower === "a") { consume(); this.setSelection(this.keyframes.map((kf) => kf.id), null); return; }
                if ((key === "Delete" || key === "Backspace") && this.state.selectedIds.length) {
                        consume();
                        this.removeKeyframes([...this.state.selectedIds]);
                        this.hideContextMenu();
                        return;
                }
                const arrows = { ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, 1], ArrowDown: [0, -1] };
                if (arrows[key] && this.state.selectedIds.length) {
                        consume();
                        const [dt, dv] = arrows[key];
                        const scale = event.shiftKey ? 1 : 0.1;
                        this.nudgeSelection(dt * this.timeNudgeStep() * scale, dv * this.valueNudgeStep() * scale);
                }
        }

        /** One grid step of time; arrow keys move a tenth of it, Shift-arrow all of it. */
        timeNudgeStep() {
                return this.computeTimeStep(rangeSize(this.viewTimeRange));
        }

        valueNudgeStep() {
                const ticks = this.getValueTicks(...this.viewValueRange);
                return ticks.length > 1 ? Math.abs(ticks[1] - ticks[0]) : rangeSize(this.viewValueRange) / 4;
        }

        nudgeSelection(dt, dv) {
                const moving = this.keyframes.filter((kf) => this.isSelected(kf.id) && !kf.locked);
                if (!moving.length) return;
                moving.forEach((kf) => this.updateKeyframe(kf.id, { time: kf.time + dt, value: kf.value + dv }));
                // A run of nudges is one undo step.
                this.refresh({ coalesce: "nudge" });
        }

        // History: a snapshot after every committed edit; refresh() records it.

        _snapshot() {
                return JSON.stringify(this.keyframes.map((kf) => ({
                        id: kf.id, time: kf.time, value: kf.value, type: kf.type, locked: !!kf.locked, handles: kf.handles,
                })));
        }

        /** Forget the history: what is showing now is where undo stops. */
        resetHistory() {
                this._history = [this._snapshot()];
                this._historyIndex = 0;
                this._coalesce = null;
        }

        _record(coalesce = null) {
                if (!this._history) return;
                const snap = this._snapshot();
                if (snap === this._history[this._historyIndex]) return;
                const now = performance.now();
                if (coalesce && this._coalesce?.key === coalesce && now - this._coalesce.at < 800 && this._historyIndex > 0) {
                        this._history[this._historyIndex] = snap;
                        this._coalesce.at = now;
                        return;
                }
                this._history.splice(this._historyIndex + 1);
                this._history.push(snap);
                if (this._history.length > 200) this._history.shift();
                this._historyIndex = this._history.length - 1;
                this._coalesce = coalesce ? { key: coalesce, at: now } : null;
        }

        _restore(snap) {
                this.keyframes = JSON.parse(snap).map((kf) => this.prepareKeyframe(kf));
                const present = new Set(this.keyframes.map((kf) => kf.id));
                const selection = this.state.selectedIds.filter((id) => present.has(id));
                this.setSelection(selection, selection[selection.length - 1] ?? null, { render: false });
                this._coalesce = null;
                this.refresh({ record: false });
        }

        canUndo() { return this._historyIndex > 0; }
        canRedo() { return this._historyIndex < this._history.length - 1; }

        undo() {
                if (!this.canUndo()) return;
                this._historyIndex -= 1;
                this._restore(this._history[this._historyIndex]);
        }

        redo() {
                if (!this.canRedo()) return;
                this._historyIndex += 1;
                this._restore(this._history[this._historyIndex]);
        }

        // Clipboard: shared by every editor on the page (localStorage), so keys copy between curves.

        selectedKeyframes() {
                return this.keyframes.filter((kf) => this.isSelected(kf.id)).sort((a, b) => a.time - b.time);
        }

        copySelection() {
                const keys = this.selectedKeyframes();
                if (!keys.length) return false;
                try {
                        localStorage.setItem(KF_CLIPBOARD, JSON.stringify(keys.map((kf) => ({
                                time: kf.time, value: kf.value, type: kf.type, handles: kf.handles,
                        }))));
                } catch (_) {
                        return false;
                }
                return true;
        }

        cutSelection() {
                if (this.copySelection()) this.removeKeyframes(this.selectedKeyframes().map((kf) => kf.id));
        }

        clipboardKeyframes() {
                try {
                        const keys = JSON.parse(localStorage.getItem(KF_CLIPBOARD) || "null");
                        return Array.isArray(keys) && keys.length ? keys : null;
                } catch (_) {
                        return null;
                }
        }

        /**
         * Pastes the copied keys, keeping their spacing, with the first at [atTime]: by default the
         * playhead when it is in view, else where the pointer last was over the graph.
         */
        paste(atTime = null) {
                const clip = this.clipboardKeyframes();
                if (!clip) return;
                const [viewMin, viewMax] = this.viewTimeRange;
                const playheadInView = Number.isFinite(this.playhead) && this.playhead >= viewMin && this.playhead <= viewMax;
                const base = atTime ?? (playheadInView ? this.playhead : this._lastPointerTime ?? clip[0].time);
                const ids = clip.map((kf) => {
                        const pasted = this.prepareKeyframe({ ...kf, id: undefined, locked: false, time: base + (kf.time - clip[0].time) });
                        this.keyframes.push(pasted);
                        return pasted.id;
                });
                this.setSelection(ids, ids[ids.length - 1], { render: false });
                this.refresh();
        }

        /** A copy of the selection, just after it. */
        duplicateSelection() {
                const keys = this.selectedKeyframes();
                if (!keys.length || !this.copySelection()) return;
                const span = keys[keys.length - 1].time - keys[0].time;
                this.paste(keys[0].time + span + this.timeNudgeStep() * 0.5);
        }

        // The menu itself.

        showContextMenu(event, id, options = {}) {
                if (!this.keyframes.find((kf) => kf.id === id)) return;
                this.openMenu(event, { kind: "key", id, ...options });
        }

        openMenu(event, context) {
                this.hideContextMenu();
                const host = kfMenuHost();
                injectMenuStyles(host.doc);
                const doc = host.doc;
                const menu = doc.createElement("div");
                menu.className = "kfm";
                const mac = /Mac|iPhone|iPad/.test(navigator.platform);
                const shortcut = (k, shift = false) => (mac ? (shift ? "⇧⌘" : "⌘") + k : "Ctrl+" + (shift ? "Shift+" : "") + k);
                const close = () => this.hideContextMenu();

                const header = (text) => {
                        const el = doc.createElement("div");
                        el.className = "kfm-header";
                        el.textContent = text;
                        menu.appendChild(el);
                };
                const divider = () => {
                        const el = doc.createElement("div");
                        el.className = "kfm-divider";
                        menu.appendChild(el);
                };
                const item = (label, hint, action, { disabled = false, danger = false } = {}) => {
                        const el = doc.createElement("button");
                        el.type = "button";
                        el.className = "kfm-item" + (danger ? " is-danger" : "");
                        el.disabled = disabled;
                        el.innerHTML = `<span></span><span class="kfm-hint"></span>`;
                        el.firstChild.textContent = label;
                        el.lastChild.textContent = hint || "";
                        el.addEventListener("click", () => { close(); action(); });
                        menu.appendChild(el);
                        return el;
                };

                if (context.kind === "key") {
                        const id = context.id;
                        this.menuTargetId = id;
                        const ids = this.state.selectedIds.includes(id) && this.state.selectedIds.length > 1
                            ? [...this.state.selectedIds] : [id];
                        const target = this.keyframes.find((kf) => kf.id === id);
                        const anyLocked = ids.some((kid) => this.keyframes.find((kf) => kf.id === kid)?.locked);
                        header(ids.length > 1 ? `${ids.length} keys` : "Key");

                        if (ids.length === 1) {
                                const row = doc.createElement("div");
                                row.className = "kfm-fields";
                                const field = (label, value) => {
                                        const wrap = doc.createElement("label");
                                        wrap.className = "kfm-field";
                                        const name = doc.createElement("span");
                                        name.textContent = label;
                                        const input = doc.createElement("input");
                                        input.type = "text";
                                        input.inputMode = "decimal";
                                        input.spellcheck = false;
                                        input.value = String(Number(value.toFixed(4)));
                                        input.disabled = !!target.locked;
                                        wrap.append(name, input);
                                        row.appendChild(wrap);
                                        return input;
                                };
                                const timeInput = field("time", target.time);
                                const valueInput = field("value", target.value);
                                const apply = () => {
                                        const t = parseFloat(timeInput.value);
                                        const v = parseFloat(valueInput.value);
                                        const kf = this.keyframes.find((item) => item.id === id);
                                        if (!kf || kf.locked || !Number.isFinite(t) || !Number.isFinite(v)) return;
                                        if (t === kf.time && v === kf.value) return;
                                        this.updateKeyframe(id, { time: t, value: v });
                                        this.refresh();
                                };
                                [timeInput, valueInput].forEach((input) => {
                                        input.addEventListener("keydown", (e) => {
                                                e.stopPropagation();
                                                if (e.key === "Enter") { apply(); close(); }
                                                if (e.key === "Escape") close();
                                        });
                                        input.addEventListener("change", apply);
                                });
                                menu.appendChild(row);
                                context.inputs = { time: timeInput, value: valueInput };
                        }

                        // Types: the built-in five, then the library's groups, then this box's own.
                        const typeButton = (type, meta, className, text) => {
                                const b = doc.createElement("button");
                                b.type = "button";
                                b.className = className + (target.type === type ? " is-active" : "");
                                b.title = meta.label ?? type;
                                b.innerHTML = (KF_TYPE_ICONS[type] || "") + (text != null ? `<span></span>` : "");
                                if (text != null) b.lastChild.textContent = text;
                                b.addEventListener("click", () => { close(); this.setKeyframeTypeBatch(ids, type); });
                                return b;
                        };
                        const section = (title) => {
                                const el = doc.createElement("div");
                                el.className = "kfm-section";
                                el.textContent = title;
                                menu.appendChild(el);
                        };
                        const entries = Object.entries(this.options.types);
                        const basic = doc.createElement("div");
                        basic.className = "kfm-types";
                        entries.filter(([, m]) => !m.custom).forEach(([type, meta]) => {
                                basic.appendChild(typeButton(type, meta, "kfm-type", (meta.label ?? type).replace("Bezier ", "").replace(/[()]/g, "")));
                        });
                        menu.appendChild(basic);

                        const groups = new Map();
                        entries.filter(([, m]) => m.custom).forEach(([type, meta]) => {
                                if (!groups.has(meta.group)) groups.set(meta.group, []);
                                groups.get(meta.group).push([type, meta]);
                        });
                        groups.forEach((members, title) => {
                                section(title);
                                if (members.every(([, m]) => m.family)) {
                                        // A table: one family per row, its in / out / in-out across.
                                        const table = doc.createElement("div");
                                        table.className = "kfm-ease";
                                        const families = [...new Set(members.map(([, m]) => m.family))];
                                        families.forEach((family) => {
                                                const cell = doc.createElement("div");
                                                cell.className = "kfm-ease-row";
                                                const name = doc.createElement("span");
                                                name.textContent = family.toLowerCase();
                                                cell.appendChild(name);
                                                members.filter(([, m]) => m.family === family).forEach(([type, meta]) => {
                                                        cell.appendChild(typeButton(type, meta, "kfm-ease-type", null));
                                                });
                                                table.appendChild(cell);
                                        });
                                        menu.appendChild(table);
                                } else {
                                        const grid = doc.createElement("div");
                                        grid.className = "kfm-types kfm-types-wide";
                                        members.forEach(([type, meta]) => grid.appendChild(typeButton(type, meta, "kfm-type", meta.label ?? type)));
                                        menu.appendChild(grid);
                                }
                        });

                        divider();
                        item("Copy", shortcut("C"), () => this.copySelection());
                        item("Cut", shortcut("X"), () => this.cutSelection());
                        item("Duplicate", shortcut("D"), () => this.duplicateSelection());
                        item("Paste here", shortcut("V"), () => this.paste(target.time), { disabled: !this.clipboardKeyframes() });
                        divider();
                        item(anyLocked ? "Unlock" : "Lock", "", () => this.toggleLock(ids));
                        item(ids.length > 1 ? `Delete ${ids.length} keys` : "Delete", "⌫", () => this.removeKeyframes(ids), { danger: true });
                } else {
                        const point = context.point;
                        header("Curve");
                        item("Add key here", "double-click", () => this.addKeyframe(point));
                        item("Paste here", shortcut("V"), () => this.paste(point.time), { disabled: !this.clipboardKeyframes() });
                        item("Select all", shortcut("A"), () => this.setSelection(this.keyframes.map((kf) => kf.id), null), { disabled: !this.keyframes.length });
                        divider();
                        item("Undo", shortcut("Z"), () => this.undo(), { disabled: !this.canUndo() });
                        item("Redo", shortcut("Z", true), () => this.redo(), { disabled: !this.canRedo() });
                }
                divider();
                item("Fit the curve", "", () => this.resetView());

                // Place it at the pointer, in the host page's coordinates, kept on screen.
                let x = event.clientX;
                let y = event.clientY;
                if (host.frame) {
                        const r = host.frame.getBoundingClientRect();
                        const sx = r.width / (host.frame.clientWidth || r.width || 1);
                        const sy = r.height / (host.frame.clientHeight || r.height || 1);
                        x = r.left + x * sx;
                        y = r.top + y * sy;
                }
                menu.style.left = "0px";
                menu.style.top = "0px";
                doc.body.appendChild(menu);
                const box = menu.getBoundingClientRect();
                const vw = host.win.innerWidth;
                const vh = host.win.innerHeight;
                menu.style.left = `${Math.max(8, Math.min(x + 4, vw - box.width - 8))}px`;
                menu.style.top = `${Math.max(8, Math.min(y + 4, vh - box.height - 8))}px`;
                this.menuEl = menu;

                if (host.doc !== document) {
                        const outside = (e) => { if (!menu.contains(e.target)) close(); };
                        const escape = (e) => { if (e.key === "Escape") close(); };
                        host.win.addEventListener("pointerdown", outside, true);
                        host.win.addEventListener("keydown", escape, true);
                        host.win.addEventListener("wheel", close, { capture: true, passive: true });
                        this._menuCleanup.push(() => {
                                host.win.removeEventListener("pointerdown", outside, true);
                                host.win.removeEventListener("keydown", escape, true);
                                host.win.removeEventListener("wheel", close, { capture: true });
                        });
                }
                if (context.focus && context.inputs?.[context.focus]) {
                        context.inputs[context.focus].focus();
                        context.inputs[context.focus].select();
                }
        }

        hideContextMenu() {
                this._menuCleanup.forEach((fn) => fn());
                this._menuCleanup = [];
                if (this.menuEl) {
                        this.menuEl.remove();
                        this.menuEl = null;
                        try { window.focus(); } catch (_) {}
                }
                this.menuTargetId = null;
        }

        renderGrid() {
                this.gridGroup.innerHTML = "";
                this.axisGroup.innerHTML = "";
                this.drawTimeGridLines();
                this.drawValueGridLines();
                this.drawAxisBounds();
                this.drawOriginMarker();
        }

        drawTimeGridLines() {
                const [min, max] = this.viewTimeRange;
                const span = rangeSize(this.viewTimeRange);
                if (span <= EPSILON) return;
                const step = this.computeTimeStep(span);
                // Only show labels at intervals that aren't too crowded
                const labelStep = step < 1 ? (step * 5 <= span * 0.5 ? step * 5 : step * 2) : step;
                const start = Math.floor(min / step) * step;
                const bottomY = this.options.padding + this.innerHeight;
                for (let t = start; t <= max + step; t += step) {
                        if (t < min - step) continue;
                        const x = this.timeToX(t);
                        const idx = Math.round(t / step);
                        const isMajor = idx % 10 === 0;
                        const isOrigin = Math.abs(t) < step * 0.35;
                        const lineStyle = isOrigin
                            ? { stroke: "rgba(255, 255, 255, 0.35)", width: 2 }
                            : isMajor
                                ? { stroke: "rgba(255, 255, 255, 0.18)", width: 1.2 }
                                : { stroke: "rgba(255, 255, 255, 0.08)", width: 0.6 };
                        const line = createSVGElement("line", {
                                x1: x,
                                y1: this.options.padding,
                                x2: x,
                                y2: bottomY,
                                stroke: lineStyle.stroke,
                                "stroke-width": lineStyle.width,
                        });
                        this.gridGroup.appendChild(line);

                        // Draw label at major or integer ticks
                        const isLabelTick = isOrigin || isMajor || Math.abs(t - Math.round(t / labelStep) * labelStep) < step * 0.35;
                        if (isLabelTick) {
                                const labelText = Number.isInteger(t) ? t.toFixed(0) : t.toFixed(step < 0.1 ? 2 : 1);
                                const label = createSVGElement("text", {
                                        x: x,
                                        y: bottomY - 3,
                                        'text-anchor': 'middle',
                                        'class': isOrigin ? 'kf-axis-label kf-axis-label--origin' : 'kf-axis-label',
                                });
                                label.textContent = labelText;
                                this.axisGroup.appendChild(label);
                        }
                }
        }

        drawValueGridLines() {
                const [min, max] = this.viewValueRange;
                const ticks = this.getValueTicks(min, max);
                const leftX = this.options.padding;
                ticks.forEach(({ value, level }) => {
                        const y = this.valueToY(value);
                        let stroke = "rgba(255, 255, 255, 0.08)";
                        let width = 0.6;
                        if (level === "origin") {
                                stroke = "rgba(255, 255, 255, 0.35)";
                                width = 2;
                        } else if (level === "hundred") {
                                stroke = "rgba(255, 255, 255, 0.22)";
                                width = 1.4;
                        } else if (level === "major") {
                                stroke = "rgba(255, 255, 255, 0.16)";
                                width = 1;
                        }
                        const line = createSVGElement("line", {
                                x1: leftX,
                                y1: y,
                                x2: leftX + this.innerWidth,
                                y2: y,
                                stroke,
                                "stroke-width": width,
                        });
                        this.gridGroup.appendChild(line);

                        // Draw label for origin, integer (hundred), and major (0.5) ticks
                        if (level === "origin" || level === "hundred" || level === "major") {
                                const labelText = Number.isInteger(value) ? value.toFixed(0) : value.toFixed(2).replace(/0$/, '');
                                const label = createSVGElement("text", {
                                        x: leftX + 3,
                                        y: y - 3,
                                        'class': level === 'origin' ? 'kf-axis-label kf-axis-label--origin' : 'kf-axis-label',
                                });
                                label.textContent = labelText;
                                this.axisGroup.appendChild(label);
                        }
                });
        }

        drawAxisBounds() {
                const left = this.options.padding;
                const top = this.options.padding;
                const right = left + this.innerWidth;
                const bottom = top + this.innerHeight;
        }

        drawOriginMarker() {
                const [timeMin, timeMax] = this.viewTimeRange;
                const [valueMin, valueMax] = this.viewValueRange;
                if (0 < timeMin - EPSILON || 0 > timeMax + EPSILON || 0 < valueMin - EPSILON || 0 > valueMax + EPSILON) {
                        return;
                }
                const x = this.timeToX(0);
                const y = this.valueToY(0);
                const size = 8;
                const marker = createSVGElement("rect", {
                        class: "kf-origin-marker",
                        x: x - size / 2,
                        y: y - size / 2,
                        width: size,
                        height: size,
                        rx: 2,
                });
                this.axisGroup.appendChild(marker);
        }

        computeTimeStep(span) {
                const steps = [0.01, 0.02, 0.05, 0.1, 0.2, 0.5, 1, 2, 5, 10, 20, 50, 100, 200, 500, 1000];
                for (const step of steps) {
                        if (span / step <= 10) {
                                return step;
                        }
                }
                return steps[steps.length - 1];
        }

        getValueTicks(min, max) {
                const pattern = [0, 0.2, 0.25, 0.4, 0.5, 0.6, 0.75, 0.8, 1];
                const ticks = [];
                const seen = new Set();
                const startBase = Math.floor(min);
                const endBase = Math.ceil(max);
                for (let base = startBase - 1; base <= endBase + 1; base += 1) {
                        pattern.forEach((p) => {
                                const value = base + p;
                                if (value < min - 0.0001 || value > max + 0.0001) return;
                                const key = value.toFixed(4);
                                if (seen.has(key)) return;
                                seen.add(key);
                                const remainder = ((value % 1) + 1) % 1;
                                let level = "minor";
                                if (Math.abs(value) < 0.0005) {
                                        level = "origin";
                                } else if (Math.abs(remainder) < 0.0005) {
                                        level = "hundred";
                                } else if (Math.abs(remainder - 0.5) < 0.0005) {
                                        level = "major";
                                }
                                ticks.push({ value, level });
                        });
                }
                return ticks;
        }

        refresh({ notify = true, record = true, coalesce = null } = {}) {
                this.keyframes.sort((a, b) => a.time - b.time);
                this.render();
                if (notify && typeof this.options.onChange === "function") {
                        this.options.onChange(this.getKeyframes());
                }
                // Every committed edit is an undo step.
                if (notify && record) this._record(coalesce);
        }

        render() {
                this.renderGrid();
                this.cachedSamples = this.sampleCurve({ stepsPerSegment: 96 });
                this.renderKeyframes();
                this.renderGraph();
                this._renderBackendOverlays();
                this._updateGraphDimming();
                this.renderPlayhead();
        }

        renderKeyframes() {
                this.keyframeLayer.innerHTML = "";
                const ctx = this.getGraphContext();
                this.keyframes.forEach((kf) => {
                        const { x, y } = ctx.project(kf);
                        const typeClass = this.getNodeStyleClass(kf);
                        let cls = `kf-keyframe ${typeClass}`;
                        if (this.isSelected(kf.id)) cls += ' is-selected';
                        if (kf.locked) cls += ' is-locked';
                        const group = createSVGElement("g", {
                                'class': cls,
                                transform: `translate(${x}, ${y})`,
                                'data-id': kf.id,
                        });

                        const circle = createSVGElement("circle", { r: 6 });
                        group.appendChild(circle);

                        const typeDef = this.options.types[kf.type];
                        if (typeDef?.decorate) {
                                typeDef.decorate(group, kf, ctx);
                        }

                        this.renderHandles(group, kf, { x, y });

                        group.addEventListener("pointerdown", (event) => {
                                if (event.button !== 0) return;
                                event.stopPropagation();
                                // Don't narrow selection on pointerdown — defer to click
                                // so multi-selection drag works from any selected node.
                                if (event.shiftKey) {
                                        // Shift: add to selection if not already there
                                        if (!this.isSelected(kf.id)) {
                                                const merged = [...this.state.selectedIds, kf.id];
                                                this.setSelection(merged, this.state.anchorId);
                                        }
                                } else if (!this.isSelected(kf.id)) {
                                        this.setSelection([kf.id], kf.id);
                                }
                                if (!kf.locked) {
                                        this.startDrag(event, kf.id);
                                }
                        });

                        group.addEventListener("pointerenter", () => {
                                group.classList.add("is-hovered");
                        });

                        group.addEventListener("pointerleave", () => {
                                group.classList.remove("is-hovered");
                        });

                        group.addEventListener("click", (event) => {
                                event.stopPropagation();
                                // After a drag, don't narrow the selection
                                if (this._didDrag) {
                                        this._didDrag = false;
                                        return;
                                }
                                this.handleSelectionClick(kf.id, event);
                        });

                        group.addEventListener("contextmenu", (event) => {
                                event.preventDefault();
                                event.stopPropagation();
                                if (!this.state.selectedIds.includes(kf.id)) {
                                        this.setSelection([kf.id], kf.id);
                                }
                                this.showContextMenu(event, kf.id);
                        });

                        // Double-click a key to type its value.
                        group.addEventListener("dblclick", (event) => {
                                event.preventDefault();
                                event.stopPropagation();
                                this.setSelection([kf.id], kf.id);
                                this.showContextMenu(event, kf.id, { focus: "value" });
                        });

                        this.keyframeLayer.appendChild(group);
                });
        }

        renderHandles(group, kf, origin) {
                const mode = this.getHandleModeForType(kf.type);
                if (!mode || kf.type === "hold" || kf.type === "linear") return;
                const neighbors = this.getNeighborKeyframes(kf);
                this.ensureHandles(kf);
                [
                        { key: "in", direction: -1 },
                        { key: "out", direction: 1 },
                ].forEach(({ key }) => {
                        const disallowIn =
                            key === "in" && neighbors.prev && (neighbors.prev.type === "hold" || neighbors.prev.type === "linear");
                        if (disallowIn) {
                                return;
                        }
                        const rel = this.getHandlePixelOffset(kf, key, origin, 4);
                        if (!rel) return;
                        const styleSuffix = this.getHandleStyleClass(mode);
                        const line = createSVGElement("line", {
                                class: `kf-handle-line kf-handle-line--${styleSuffix}`,
                                x1: 0,
                                y1: 0,
                                x2: rel.lineX,
                                y2: rel.lineY,
                        });
                        const handle = createSVGElement("circle", {
                                class: `kf-handle kf-handle--${styleSuffix}`,
                                cx: rel.handleX,
                                cy: rel.handleY,
                                r: 4,
                                'data-handle': key,
                                'data-id': kf.id,
                        });
                        handle.addEventListener("pointerdown", (event) => {
                                if (event.button !== 0) return;
                                event.stopPropagation();
                                this.handleSelectionClick(kf.id, event);
                                if (!kf.locked) {
                                        this.startHandleDrag(event, kf.id, key);
                                }
                        });
                        handle.addEventListener("click", (event) => {
                                event.stopPropagation();
                        });
                        handle.addEventListener("contextmenu", (event) => {
                                event.preventDefault();
                                event.stopPropagation();
                                this.setSelection([kf.id], kf.id);
                                this.showContextMenu(event, kf.id);
                        });
                        group.appendChild(line);
                        group.appendChild(handle);
                });
        }

        getHandlePixelOffset(kf, key, origin, radius = 0) {
                const absolute = this.getHandleAbsolutePosition(kf, key);
                if (!absolute) return null;
                const offsetX = this.timeToX(absolute.time) - origin.x;
                const offsetY = this.valueToY(absolute.value) - origin.y;
                const length = Math.hypot(offsetX, offsetY);
                if (length < EPSILON) {
                        return {
                                handleX: offsetX,
                                handleY: offsetY,
                                lineX: 0,
                                lineY: 0,
                        };
                }
                const shrink = Math.min(radius + 1, length);
                const factor = (length - shrink) / length;
                return {
                        handleX: offsetX,
                        handleY: offsetY,
                        lineX: offsetX * factor,
                        lineY: offsetY * factor,
                };
        }

        renderGraph() {
                const ctx = this.getGraphContext();
                const samples = this.cachedSamples ?? this.sampleCurve();
                const manualBreaks = this.currentBreakSegments || [];
                const groups = groupSamplesByContinuity(samples, ctx, manualBreaks);
                const defaultPath = buildGroupedPath(groups, ctx);
                const evaluation = this.options.evaluateGraph(this.getKeyframes(), {
                        ...ctx,
                        samples,
                        defaultPath,
                        groupedSamples: groups,
                        breakSegments: manualBreaks,
                });
                const pathValue = typeof evaluation === "string" ? evaluation : evaluation?.path;
                const resolvedPath = pathValue || defaultPath;
                this.graphPath.setAttribute("d", resolvedPath ?? "");

                if (this.graphOverlayExtras) {
                        this.graphOverlayExtras.innerHTML = "";
                }
                const extra = typeof evaluation === "object" && evaluation !== null ? evaluation.elements : undefined;
                if (Array.isArray(extra)) {
                        extra.forEach((node) => {
                                if (node && node.nodeType === Node.ELEMENT_NODE) {
                                        this.graphOverlayExtras.appendChild(node);
                                }
                        });
                }
                if (manualBreaks.length) {
                        const breakPath = buildBreakPath(manualBreaks, ctx);
                        if (breakPath) {
                                const breakElement = createSVGElement("path", {
                                        class: "kf-path-break",
                                        d: breakPath,
                                });
                                this.graphOverlayExtras.appendChild(breakElement);
                        }
                }
        }

        // ── Backend overlay management ────────────────────────────────

        /**
         * Set (or update) a named overlay curve from backend data.
         * @param {string} id - unique overlay identifier
         * @param {Array<{time:number,value:number}>} samples - the curve data
         * @param {string} color - CSS stroke color
         * @param {number} strokeWidth - SVG stroke width
         * @param {string|null} dashArray - SVG dash pattern or null for solid
         */
        setOverlay(id, samples, color = '#ff9800', strokeWidth = 1.5, dashArray = '6 3') {
                this._overlays.set(id, { samples, color, strokeWidth, dashArray });
                this._renderBackendOverlays();
                this._updateGraphDimming();
        }

        /** Remove a specific backend overlay by id. */
        removeOverlay(id) {
                this._overlays.delete(id);
                this._renderBackendOverlays();
                this._updateGraphDimming();
        }

        /** Remove all backend overlays. */
        clearAllOverlays() {
                this._overlays.clear();
                this._renderBackendOverlays();
                this._updateGraphDimming();
        }

        /**
         * @private Dim the main graph path + keyframe nodes to 50% when overlays
         * are present and the user is NOT dragging.  Restores full opacity otherwise.
         */
        _updateGraphDimming() {
                const hasOverlays = this._overlays.size > 0;
                const dim = hasOverlays && !this._isDragging;
                const opacity = dim ? '0.5' : '1';
                if (this.graphPath) this.graphPath.style.opacity = opacity;
                if (this.keyframeLayer) this.keyframeLayer.style.opacity = opacity;
        }

        /** @private Re-render all backend overlay curves into the dedicated group. */
        _renderBackendOverlays() {
                if (!this.backendOverlayGroup) return;
                this.backendOverlayGroup.innerHTML = '';
                for (const [id, ov] of this._overlays) {
                        if (!ov.samples || !ov.samples.length) continue;
                        const commands = [];
                        for (let i = 0; i < ov.samples.length; i++) {
                                const s = ov.samples[i];
                                const x = this.timeToX(s.time);
                                const y = this.valueToY(s.value);
                                commands.push(`${i === 0 ? 'M' : 'L'} ${x.toFixed(2)} ${y.toFixed(2)}`);
                        }
                        const attrs = {
                                d: commands.join(' '),
                                stroke: ov.color,
                                'stroke-width': String(ov.strokeWidth),
                                fill: 'none',
                                'pointer-events': 'none',
                                'data-overlay-id': id,
                        };
                        if (ov.dashArray) {
                                attrs['stroke-dasharray'] = ov.dashArray;
                        }
                        const path = createSVGElement('path', attrs);
                        // Inline, because the group sits inside .kf-overlay, whose stylesheet stroke
                        // would otherwise win over the attribute and turn every overlay grey.
                        path.style.stroke = ov.color;
                        path.style.strokeWidth = String(ov.strokeWidth);
                        this.backendOverlayGroup.appendChild(path);
                }
        }

        renderPlayhead() {
                if (!this.playheadLine) return;
                if (typeof this.playhead !== "number" || !Number.isFinite(this.playhead)) {
                        this.playheadLine.setAttribute("visibility", "hidden");
                        if (this.playheadDot) this.playheadDot.setAttribute("visibility", "hidden");
                        return;
                }
                const [viewStart, viewEnd] = this.viewTimeRange;
                if (this.playhead < viewStart - EPSILON || this.playhead > viewEnd + EPSILON) {
                        this.playheadLine.setAttribute("visibility", "hidden");
                        if (this.playheadDot) this.playheadDot.setAttribute("visibility", "hidden");
                        return;
                }
                const x = this.timeToX(this.playhead);
                this.playheadLine.setAttribute("visibility", "visible");
                this.playheadLine.setAttribute("x1", x);
                this.playheadLine.setAttribute("x2", x);
                this.playheadLine.setAttribute("y1", 0);
                this.playheadLine.setAttribute("y2", this.options.height);
                if (this.playheadDot) {
                        const y = this.valueToY(this.evaluateValueAt(this.playhead));
                        this.playheadDot.setAttribute("visibility", "visible");
                        this.playheadDot.setAttribute("cx", x);
                        this.playheadDot.setAttribute("cy", y);
                }
        }

        getOrderedKeyframes() {
                return [...this.keyframes].sort((a, b) => a.time - b.time);
        }

        isSelected(id) {
                return this.state.selectedIds.includes(id);
        }

        setSelection(ids, anchorId = null, { render = true } = {}) {
                const unique = Array.from(new Set(ids.filter(Boolean)));
                this.state.selectedIds = unique;
                this.state.anchorId = anchorId ?? unique[unique.length - 1] ?? null;
                if (render) {
                        this.render();
                }
        }

        clearSelection() {
                this.setSelection([], null);
        }

        selectKeyframe(id, { range = false } = {}) {
                if (!id) {
                        this.clearSelection();
                        return;
                }
                if (range) {
                        this.selectRange(id);
                        return;
                }
                this.setSelection([id], id);
        }

        selectRange(targetId) {
                const anchorId = this.state.anchorId ?? this.state.selectedIds[this.state.selectedIds.length - 1] ?? targetId;
                if (!anchorId) {
                        this.setSelection([targetId], targetId);
                        return;
                }
                const order = this.getOrderedKeyframes();
                const anchorIndex = order.findIndex((kf) => kf.id === anchorId);
                const targetIndex = order.findIndex((kf) => kf.id === targetId);
                if (anchorIndex === -1 || targetIndex === -1) {
                        this.setSelection([targetId], targetId);
                        return;
                }
                const [start, end] = anchorIndex <= targetIndex ? [anchorIndex, targetIndex] : [targetIndex, anchorIndex];
                const ids = order.slice(start, end + 1).map((kf) => kf.id);
                this.setSelection(ids, anchorId);
        }

        handleSelectionClick(id, event) {
                if (event.shiftKey) {
                        this.selectRange(id);
                } else {
                        this.selectKeyframe(id);
                }
        }

        /**
         * The key types a box defined with interpolant(...): each becomes a type in the menu, drawn
         * with a small picture of its shape.
         */
        setCustomTypes(list) {
                if (!Array.isArray(list)) return;
                list.forEach(({ name, icon, label, group, family, variant }) => {
                        if (!name) return;
                        this.options.types[name] = { label: label || name, custom: true, group: group || "This box", family, variant };
                        if (Array.isArray(icon) && icon.length > 1) {
                                const points = icon
                                    .map(([u, v]) => `${(2 + u * 12).toFixed(2)},${(12 - Math.max(-0.2, Math.min(1.2, v)) * 10).toFixed(2)}`)
                                    .join(" ");
                                KF_TYPE_ICONS[name] = `<svg viewBox="0 0 16 14" xmlns="http://www.w3.org/2000/svg"><polyline points="${points}" fill="none" stroke="currentColor" stroke-width="1.5"/></svg>`;
                        }
                });
        }

        /** Sampled shapes of the custom segments, as the box last computed them. */
        setCustomSegments(list) {
                this._customSegments = Array.isArray(list) ? list : [];
                this.render();
        }

        customSegmentFor(prev, next) {
                const close = (a, b) => Math.abs(a - b) <= 1e-6 * Math.max(1, Math.abs(a), Math.abs(b));
                const found = (this._customSegments || []).find((s) =>
                        s.type === prev.type && close(s.t0, prev.time) && close(s.v0, prev.value) &&
                        close(s.t1, next.time) && close(s.v1, next.value));
                return found ? found.samples : null;
        }

        getHandleModeForType(type) {
                return this.options.types[type]?.handleMode ?? null;
        }

        getHandleStyleClass(mode) {
                switch (mode) {
                        case HANDLE_MODES.FLAT:
                                return "flat";
                        case HANDLE_MODES.CONTINUOUS:
                                return "continuous";
                        case HANDLE_MODES.FREE:
                                return "free";
                        default:
                                return "default";
                }
        }

        getNodeStyleClass(kf) {
                const mode = this.getHandleModeForType(kf.type);
                if (!mode) {
                        return kf.type === "hold" ? "kf-keyframe--hold" : "kf-keyframe--linear";
                }
                switch (mode) {
                        case HANDLE_MODES.FLAT:
                                return "kf-keyframe--flat";
                        case HANDLE_MODES.CONTINUOUS:
                                return "kf-keyframe--continuous";
                        case HANDLE_MODES.FREE:
                                return "kf-keyframe--free";
                        default:
                                return "";
                }
        }

        ensureHandles(kf) {
                if (!kf.handles) {
                        kf.handles = this.createHandlePayload();
                }
                kf.handles.in = kf.handles.in || { time: 0, value: 0 };
                kf.handles.out = kf.handles.out || { time: 0, value: 0 };
                const defaults = this.getDefaultHandleOffsets();
                if (!Number.isFinite(kf.handles.in.time) && !Number.isFinite(kf.handles.out.time)) {
                        kf.handles.in.time = defaults.in.time;
                        kf.handles.out.time = defaults.out.time;
                }
                if (!Number.isFinite(kf.handles.in.time)) {
                        kf.handles.in.time = defaults.in.time;
                }
                if (!Number.isFinite(kf.handles.out.time)) {
                        kf.handles.out.time = defaults.out.time;
                }
                if (!Number.isFinite(kf.handles.in.value)) {
                        kf.handles.in.value = defaults.in.value;
                }
                if (!Number.isFinite(kf.handles.out.value)) {
                        kf.handles.out.value = defaults.out.value;
                }
                return kf.handles;
        }

        createHandlePayload(source) {
                return {
                        in: {
                                time: Number.isFinite(source?.in?.time) ? source.in.time : 0,
                                value: Number.isFinite(source?.in?.value) ? source.in.value : 0,
                        },
                        out: {
                                time: Number.isFinite(source?.out?.time) ? source.out.time : 0,
                                value: Number.isFinite(source?.out?.value) ? source.out.value : 0,
                        },
                };
        }

        getDefaultHandleOffsets() {
                const span = rangeSize(this.initialTimeRange) || rangeSize(this.viewTimeRange) || 1;
                const dt = Math.max(span * 0.1, 0.02);
                return {
                        in: { time: -dt, value: 0 },
                        out: { time: dt, value: 0 },
                };
        }

        normalizeHandle(handle, direction) {
                if (!handle) return null;
                const min = Math.max(rangeSize(this.viewTimeRange) * 0.0001, 0.0001);
                if (!Number.isFinite(handle.time) || Math.abs(handle.time) < min) {
                        handle.time = direction * min;
                }
                if (direction === -1 && handle.time > 0) {
                        handle.time = -handle.time;
                }
                if (direction === 1 && handle.time < 0) {
                        handle.time = -handle.time;
                }
                if (!Number.isFinite(handle.value)) {
                        handle.value = 0;
                }
                return handle;
        }

        applyHandleMode(kf) {
                const mode = this.getHandleModeForType(kf.type);
                if (!mode) {
                        return;
                }
                this.ensureHandles(kf);
                this.enforceHandleModeForKeyframe(kf);
        }

        resetHandlesForType(kf) {
                const defaults = this.getDefaultHandleOffsets();
                kf.handles = {
                        in: { ...defaults.in },
                        out: { ...defaults.out },
                };
                this.applyHandleMode(kf);
        }

        enforceHandleModeForKeyframe(kf, sourceKey = null) {
                const mode = this.getHandleModeForType(kf.type);
                if (!mode) return;
                const handles = this.ensureHandles(kf);
                if (!Number.isFinite(handles.in.time) && !Number.isFinite(handles.out.time)) {
                        const defaults = this.getDefaultHandleOffsets();
                        handles.in.time = defaults.in.time;
                        handles.out.time = defaults.out.time;
                }
                this.normalizeHandle(handles.in, -1);
                this.normalizeHandle(handles.out, 1);

                if (mode === HANDLE_MODES.FLAT) {
                        handles.in.value = 0;
                        handles.out.value = 0;
                        return;
                }

                if (mode === HANDLE_MODES.CONTINUOUS) {
                        if (sourceKey === "in") {
                                handles.out.time = -handles.in.time;
                                handles.out.value = -handles.in.value;
                                this.normalizeHandle(handles.out, 1);
                        } else if (sourceKey === "out") {
                                handles.in.time = -handles.out.time;
                                handles.in.value = -handles.out.value;
                                this.normalizeHandle(handles.in, -1);
                        } else {
                                handles.out.time = Math.abs(handles.out.time) > 0 ? handles.out.time : -handles.in.time;
                                handles.out.value = -handles.in.value;
                                this.normalizeHandle(handles.out, 1);
                                handles.in.time = -handles.out.time;
                                handles.in.value = -handles.out.value;
                                this.normalizeHandle(handles.in, -1);
                        }
                }
        }

        getNeighborKeyframes(target) {
                const ordered = this.getOrderedKeyframes();
                const index = ordered.findIndex((item) => item.id === target.id);
                return {
                        prev: index > 0 ? ordered[index - 1] : null,
                        next: index >= 0 && index < ordered.length - 1 ? ordered[index + 1] : null,
                };
        }

        initializeHandlesFromNeighbors(kf) {
                const { prev, next } = this.getNeighborKeyframes(kf);
                const defaults = this.getDefaultHandleOffsets();
                if (next) {
                        const span = Math.max(next.time - kf.time, EPSILON);
                        const { m0 } = computeMonotoneSlopes(kf, next);
                        const dt = span * 0.333;
                        kf.handles.out = { time: dt, value: m0 * dt };
                } else {
                        kf.handles.out = { ...defaults.out };
                }

                if (prev) {
                        const span = Math.max(kf.time - prev.time, EPSILON);
                        const { m1 } = computeMonotoneSlopes(prev, kf);
                        const dt = -span * 0.333;
                        kf.handles.in = { time: dt, value: m1 * Math.abs(dt) };
                } else {
                        kf.handles.in = { ...defaults.in };
                }
        }

        symmetrizeHandlesFromExisting(kf) {
                const defaults = this.getDefaultHandleOffsets();
                const currentOut = kf.handles?.out;
                const currentIn = kf.handles?.in;
                const lengths = [];
                if (currentOut && Math.abs(currentOut.time) > EPSILON) lengths.push(Math.abs(currentOut.time));
                if (currentIn && Math.abs(currentIn.time) > EPSILON) lengths.push(Math.abs(currentIn.time));
                const baseLength = lengths.length
                    ? lengths.reduce((a, b) => a + b, 0) / lengths.length
                    : Math.abs(defaults.out.time);

                const slopes = [];
                if (currentOut && Math.abs(currentOut.time) > EPSILON) {
                        slopes.push(currentOut.value / currentOut.time);
                }
                if (currentIn && Math.abs(currentIn.time) > EPSILON) {
                        slopes.push(currentIn.value / currentIn.time);
                }
                if (!slopes.length) {
                        const neighborSlope = this.deriveSlopeFromNeighbors(kf);
                        slopes.push(neighborSlope);
                }
                const slope = slopes.reduce((a, b) => a + b, 0) / (slopes.length || 1);
                const dt = Math.max(baseLength, Math.abs(defaults.out.time));
                kf.handles.out = { time: dt, value: slope * dt };
                kf.handles.in = { time: -dt, value: -slope * dt };
        }

        deriveSlopeFromNeighbors(kf) {
                const { prev, next } = this.getNeighborKeyframes(kf);
                const slopes = [];
                if (next) slopes.push(computeMonotoneSlopes(kf, next).m0);
                if (prev) slopes.push(computeMonotoneSlopes(prev, kf).m1);
                if (!slopes.length) return 0;
                return slopes.reduce((a, b) => a + b, 0) / slopes.length;
        }

        getHandleAbsolutePosition(kf, handleKey) {
                const handle = kf.handles?.[handleKey];
                if (!handle) return null;
                const time = kf.time + handle.time;
                const value = kf.value + handle.value;
                if (!Number.isFinite(time) || !Number.isFinite(value)) {
                        return null;
                }
                return { time, value };
        }

        getKeyframes() {
                return this.keyframes.map((kf) => ({
                        ...kf,
                        locked: !!kf.locked,
                        handles: {
                                in: { ...(kf.handles?.in || {}) },
                                out: { ...(kf.handles?.out || {}) },
                        },
                }));
        }

        addKeyframe(partial = {}) {
                const keyframe = this.prepareKeyframe(partial);
                this.keyframes.push(keyframe);
                this.setSelection([keyframe.id], keyframe.id, { render: false });
                this.refresh();
                return keyframe;
        }

        /** Removes several keys at once, committing (and notifying Kotlin) a single time. */
        removeKeyframes(ids) {
                const doomed = new Set(ids.filter((id) => !this.keyframes.find((kf) => kf.id === id)?.locked));
                if (!doomed.size) return;
                const remaining = this.keyframes.filter((kf) => !doomed.has(kf.id));
                const lastIndex = Math.max(...this.keyframes.map((kf, i) => (doomed.has(kf.id) ? i : -1)));
                const fallback = this.keyframes.slice(lastIndex + 1).find((kf) => !doomed.has(kf.id)) ?? remaining[remaining.length - 1];
                this.keyframes.splice(0, this.keyframes.length, ...remaining);
                const selection = fallback ? [fallback.id] : [];
                this.setSelection(selection, selection[0] ?? null, { render: false });
                this.refresh();
        }

        removeKeyframe(id) {
                const index = this.keyframes.findIndex((kf) => kf.id === id);
                if (index === -1) return;
                const fallback = this.keyframes[index + 1] ?? this.keyframes[index - 1];
                this.keyframes.splice(index, 1);
                const remainingSelection = this.state.selectedIds.filter((selectedId) => selectedId !== id);
                if (!remainingSelection.length && fallback) {
                        remainingSelection.push(fallback.id);
                }
                this.setSelection(remainingSelection, remainingSelection[remainingSelection.length - 1] ?? null, {
                        render: false,
                });
                this.refresh();
        }

        setKeyframeType(id, type, { silent = false } = {}) {
                if (!this.options.types[type]) return;
                const kf = this.keyframes.find((item) => item.id === id);
                if (!kf) return;
                const prevMode = this.getHandleModeForType(kf.type);
                kf.type = type;
                const nextMode = this.getHandleModeForType(type);
                if (!nextMode) {
                        kf.handles = this.createHandlePayload();
                        if (!silent) this.refresh();
                        return;
                }

                this.ensureHandles(kf);
                if (!prevMode) {
                        this.initializeHandlesFromNeighbors(kf);
                } else if (prevMode === HANDLE_MODES.FLAT && nextMode !== HANDLE_MODES.FLAT) {
                        this.initializeHandlesFromNeighbors(kf);
                } else if (prevMode === HANDLE_MODES.FREE && nextMode === HANDLE_MODES.CONTINUOUS) {
                        this.symmetrizeHandlesFromExisting(kf);
                } else if (prevMode === HANDLE_MODES.CONTINUOUS && nextMode === HANDLE_MODES.FREE) {
                        // preserve existing handles
                }
                this.enforceHandleModeForKeyframe(kf);
                if (!silent) this.refresh();
        }

        setKeyframeTypeBatch(ids, type) {
                const unique = Array.from(new Set(ids));
                unique.forEach((id) => this.setKeyframeType(id, type, { silent: true }));
                this.refresh();
        }

        /**
         * Toggle the locked state of the given keyframe ids.
         * If any of them are unlocked, lock all; otherwise unlock all.
         */
        toggleLock(ids) {
                const targets = ids
                    .map((id) => this.keyframes.find((kf) => kf.id === id))
                    .filter(Boolean);
                if (!targets.length) return;
                const anyUnlocked = targets.some((kf) => !kf.locked);
                targets.forEach((kf) => { kf.locked = anyUnlocked; });
                this.render();
                this.refresh();
        }

        startDrag(event, id) {
                event.preventDefault();
                this._isDragging = true;
                this._didDrag = false;
                this._updateGraphDimming();
                this.hideContextMenu();
                this.hideSnapIndicators();
                this.hideDragLabel();
                const targets = (this.isSelected(id) ? [...this.state.selectedIds] : [id])
                    .map((selectedId) => this.keyframes.find((item) => item.id === selectedId))
                    .filter(Boolean)
                    .filter((kf) => !kf.locked);
                if (!targets.length) {
                        return;
                }
                const pointerOrigin = this.pointerToValues(event);
                const offsets = targets.map((target) => ({
                        id: target.id,
                        time: target.time - pointerOrigin.time,
                        value: target.value - pointerOrigin.value,
                }));
                const pointerId = event.pointerId;
                const movingIds = offsets.map((offset) => offset.id);
                const anchorOffset = offsets.find((item) => item.id === id) ?? offsets[0];
                const anchorId = anchorOffset?.id ?? movingIds[0];
                const move = (ev) => {
                        if (ev.pointerId !== pointerId) return;
                        this._didDrag = true;
                        const point = this.pointerToValues(ev);
                        const planned = offsets.map((offset) => ({
                                id: offset.id,
                                time: point.time + offset.time,
                                value: point.value + offset.value,
                        }));
                        const anchorPosition = planned.find((item) => item.id === anchorId) ?? planned[0];
                        let snap = null;
                        if (!ev.altKey && anchorPosition) {
                                snap = this.computeSnapAdjustments(anchorPosition, { anchorId, movingIds });
                        }
                        if (!snap || !snap.timeIndicator) {
                                this.hideTimeSnapIndicator();
                        } else {
                                this.showTimeSnapIndicator(snap.timeIndicator);
                        }
                        if (!snap || !snap.valueIndicator) {
                                this.hideValueSnapIndicator();
                        } else {
                                this.showValueSnapIndicator(snap.valueIndicator);
                        }
                        const finalAnchor = anchorPosition
                            ? {
                                    time: anchorPosition.time + (snap?.timeOffset ?? 0),
                                    value: anchorPosition.value + (snap?.valueOffset ?? 0),
                            }
                            : null;
                        planned.forEach((plan) => {
                                this.updateKeyframe(plan.id, {
                                        time: plan.time + (snap?.timeOffset ?? 0),
                                        value: plan.value + (snap?.valueOffset ?? 0),
                                });
                        });
                        this.refresh({ notify: false });
                        if (finalAnchor) {
                                this.showDragLabel(finalAnchor);
                        } else {
                                this.hideDragLabel();
                        }
                };

                const stop = (ev) => {
                        if (ev.pointerId !== pointerId) return;
                        window.removeEventListener("pointermove", move);
                        window.removeEventListener("pointerup", stop);
                        window.removeEventListener("pointercancel", stop);
                        this._isDragging = false;
                        this._updateGraphDimming();
                        this.hideSnapIndicators();
                        this.hideDragLabel();
                        this.refresh();
                };

                window.addEventListener("pointermove", move);
                window.addEventListener("pointerup", stop);
                window.addEventListener("pointercancel", stop);
        }

        startHandleDrag(event, keyframeId, handleKey) {
                event.preventDefault();
                this._isDragging = true;
                this._updateGraphDimming();
                this.hideContextMenu();
                const kf = this.keyframes.find((item) => item.id === keyframeId);
                if (!kf) return;
                this.ensureHandles(kf);
                const pointerOrigin = this.pointerToValues(event);
                const handleOrigin = this.getHandleAbsolutePosition(kf, handleKey);
                const offset = handleOrigin
                    ? {
                            time: handleOrigin.time - pointerOrigin.time,
                            value: handleOrigin.value - pointerOrigin.value,
                    }
                    : { time: 0, value: 0 };
                const pointerId = event.pointerId;
                const move = (ev) => {
                        if (ev.pointerId !== pointerId) return;
                        const point = this.pointerToValues(ev);
                        this.updateHandleOffset(keyframeId, handleKey, {
                                time: point.time + offset.time,
                                value: point.value + offset.value,
                        });
                        this.render();
                };

                const stop = (ev) => {
                        if (ev.pointerId !== pointerId) return;
                        window.removeEventListener("pointermove", move);
                        window.removeEventListener("pointerup", stop);
                        window.removeEventListener("pointercancel", stop);
                        this._isDragging = false;
                        this._updateGraphDimming();
                        this.refresh();
                };

                window.addEventListener("pointermove", move);
                window.addEventListener("pointerup", stop);
                window.addEventListener("pointercancel", stop);
        }

        computeSnapAdjustments(anchorPosition, { anchorId, movingIds }) {
                if (!anchorPosition) return null;
                const timeSnap = this.findTimeSnap(anchorPosition, { anchorId, movingIds });
                const valueSnap = this.findValueSnap(anchorPosition, { anchorId, movingIds });
                if (!timeSnap && !valueSnap) return null;
                return {
                        timeOffset: timeSnap ? timeSnap.target - anchorPosition.time : 0,
                        valueOffset: valueSnap ? valueSnap.target - anchorPosition.value : 0,
                        timeIndicator: timeSnap?.indicator ?? null,
                        valueIndicator: valueSnap?.indicator ?? null,
                };
        }

        findTimeSnap(anchorPosition, { anchorId, movingIds }) {
                if (!anchorPosition || !Number.isFinite(anchorPosition.time)) {
                        return null;
                }
                const movingSet = new Set(movingIds ?? []);
                const { values: gridLines, step } = this.buildTimeGuideValues();
                const toleranceFloor = rangeSize(this.viewTimeRange) * 0.005;
                const candidates = [];
                const addCandidate = (target, threshold, indicator) => {
                        if (!Number.isFinite(target)) return;
                        const distance = Math.abs(anchorPosition.time - target);
                        if (distance <= threshold) {
                                candidates.push({ target, threshold, distance, indicator });
                        }
                };
                const baseThreshold = Math.max(Math.abs(step) * 0.1, toleranceFloor);
                gridLines.forEach((time) => addCandidate(time, baseThreshold, { type: "grid", time }));
                if (typeof this.playhead === "number" && Number.isFinite(this.playhead)) {
                        addCandidate(this.playhead, baseThreshold, { type: "playhead", time: this.playhead });
                }
                const neighbors = this.findNeighborPair(anchorId, anchorPosition.time, movingSet);
                if (neighbors.prev && neighbors.next) {
                        const span = Math.abs(neighbors.next.time - neighbors.prev.time);
                        if (span > EPSILON) {
                                const target = neighbors.prev.time + span / 2;
                                const threshold = Math.max(span * 0.1, toleranceFloor);
                                addCandidate(target, threshold, {
                                        type: "between",
                                        time: target,
                                        prev: { time: neighbors.prev.time, value: neighbors.prev.value },
                                        next: { time: neighbors.next.time, value: neighbors.next.value },
                                });
                        }
                }
                return this.pickSnapCandidate(candidates);
        }

        findValueSnap(anchorPosition, { movingIds }) {
                if (!anchorPosition || !Number.isFinite(anchorPosition.value)) {
                        return null;
                }
                const movingSet = new Set(movingIds ?? []);
                const { ticks } = this.buildValueGuideValues();
                const toleranceFloor = rangeSize(this.viewValueRange) * 0.005;
                const candidates = [];
                ticks.forEach((tick) => {
                        const threshold = Math.max(Math.abs(tick.spacing) * 0.1, toleranceFloor);
                        const distance = Math.abs(anchorPosition.value - tick.value);
                        if (distance <= threshold) {
                                candidates.push({
                                        target: tick.value,
                                        threshold,
                                        distance,
                                        indicator: { type: "grid", value: tick.value },
                                });
                        }
                });
                const nodes = this.buildValueNodeTargets(movingSet);
                nodes.forEach((node) => {
                        const threshold = Math.max(Math.abs(node.spacing) * 0.1, toleranceFloor);
                        const distance = Math.abs(anchorPosition.value - node.value);
                        if (distance <= threshold) {
                                candidates.push({
                                        target: node.value,
                                        threshold,
                                        distance,
                                        indicator: { type: "node", value: node.value },
                                });
                        }
                });
                return this.pickSnapCandidate(candidates);
        }

        buildTimeGuideValues() {
                const [min, max] = this.viewTimeRange;
                const span = rangeSize(this.viewTimeRange);
                if (span <= EPSILON) {
                        return { values: [], step: span };
                }
                const step = this.computeTimeStep(span);
                const values = [];
                const start = Math.floor(min / step) * step;
                for (let t = start; t <= max + step; t += step) {
                        if (t < min - step) continue;
                        values.push(t);
                }
                return { values, step };
        }

        buildValueGuideValues() {
                const [min, max] = this.viewValueRange;
                const ticks = this.getValueTicks(min, max);
                const map = new Map();
                ticks.forEach((tick) => {
                        const key = tick.value.toFixed(4);
                        if (!map.has(key)) {
                                map.set(key, tick.value);
                        }
                });
                const values = Array.from(map.values()).sort((a, b) => a - b);
                const fallbackSpacing = rangeSize(this.viewValueRange);
                const guides = values.map((value, index) => {
                        const prev = index > 0 ? values[index - 1] : null;
                        const next = index < values.length - 1 ? values[index + 1] : null;
                        let spacing = fallbackSpacing;
                        if (prev !== null) spacing = Math.min(spacing, Math.abs(value - prev));
                        if (next !== null) spacing = Math.min(spacing, Math.abs(next - value));
                        if (!Number.isFinite(spacing) || spacing <= 0) spacing = fallbackSpacing;
                        return { value, spacing };
                });
                return { ticks: guides, fallbackSpacing };
        }

        buildValueNodeTargets(excludeSet) {
                const others = this.keyframes.filter((kf) => !excludeSet.has(kf.id));
                if (!others.length) return [];
                const fallbackSpacing = rangeSize(this.viewValueRange);
                const sorted = others
                    .map((kf) => ({ id: kf.id, value: kf.value }))
                    .sort((a, b) => a.value - b.value);
                return sorted.map((entry, index) => {
                        const prev = index > 0 ? sorted[index - 1] : null;
                        const next = index < sorted.length - 1 ? sorted[index + 1] : null;
                        let spacing = fallbackSpacing;
                        if (prev) spacing = Math.min(spacing, Math.abs(entry.value - prev.value));
                        if (next) spacing = Math.min(spacing, Math.abs(entry.value - next.value));
                        if (!Number.isFinite(spacing) || spacing <= 0) spacing = fallbackSpacing;
                        return { value: entry.value, spacing };
                });
        }

        findNeighborPair(anchorId, candidateTime, excludeSet = new Set()) {
                const ordered = this.getOrderedKeyframes().filter((kf) => kf.id !== anchorId && !excludeSet.has(kf.id));
                let prev = null;
                let next = null;
                ordered.forEach((node) => {
                        if (node.time < candidateTime && (!prev || node.time > prev.time)) {
                                prev = node;
                        }
                        if (node.time > candidateTime && (!next || node.time < next.time)) {
                                next = node;
                        }
                });
                return { prev, next };
        }

        pickSnapCandidate(candidates) {
                if (!candidates.length) return null;
                let best = null;
                candidates.forEach((candidate) => {
                        const threshold = Math.max(candidate.threshold, EPSILON);
                        const score = candidate.distance / threshold;
                        if (!best || score < best.score || (Math.abs(score - best.score) < 1e-6 && candidate.distance < best.distance)) {
                                best = { ...candidate, score };
                        }
                });
                if (!best) return null;
                return {
                        target: best.target,
                        indicator: best.indicator,
                };
        }

        showTimeSnapIndicator(indicator) {
                if (!this.snapIndicatorTime || !indicator) return;
                const x = this.timeToX(indicator.time);
                let y1 = this.options.padding;
                let y2 = this.options.padding + this.innerHeight;
                if (indicator.prev && indicator.next) {
                        y1 = this.valueToY(indicator.prev.value);
                        y2 = this.valueToY(indicator.next.value);
                        if (y1 > y2) {
                                [y1, y2] = [y2, y1];
                        }
                }
                this.snapIndicatorTime.setAttribute("x1", x);
                this.snapIndicatorTime.setAttribute("x2", x);
                this.snapIndicatorTime.setAttribute("y1", y1);
                this.snapIndicatorTime.setAttribute("y2", y2);
                this.snapIndicatorTime.setAttribute("visibility", "visible");
        }

        showValueSnapIndicator(indicator) {
                if (!this.snapIndicatorValue || !indicator) return;
                const y = this.valueToY(indicator.value);
                const x1 = this.options.padding;
                const x2 = this.options.padding + this.innerWidth;
                this.snapIndicatorValue.setAttribute("x1", x1);
                this.snapIndicatorValue.setAttribute("x2", x2);
                this.snapIndicatorValue.setAttribute("y1", y);
                this.snapIndicatorValue.setAttribute("y2", y);
                this.snapIndicatorValue.setAttribute("visibility", "visible");
        }

        hideTimeSnapIndicator() {
                if (!this.snapIndicatorTime) return;
                this.snapIndicatorTime.setAttribute("visibility", "hidden");
        }

        hideValueSnapIndicator() {
                if (!this.snapIndicatorValue) return;
                this.snapIndicatorValue.setAttribute("visibility", "hidden");
        }

        hideSnapIndicators() {
                this.hideTimeSnapIndicator();
                this.hideValueSnapIndicator();
        }

        updateHandleOffset(keyframeId, handleKey, absolutePoint) {
                const kf = this.keyframes.find((item) => item.id === keyframeId);
                if (!kf) return;
                const handles = this.ensureHandles(kf);
                const direction = handleKey === "in" ? -1 : 1;
                const offset = this.normalizeHandleOffset(
                    {
                            time: absolutePoint.time - kf.time,
                            value: absolutePoint.value - kf.value,
                    },
                    direction
                );
                const mode = this.getHandleModeForType(kf.type);
                if (mode === HANDLE_MODES.FLAT) {
                        offset.value = 0;
                }
                handles[handleKey] = offset;
                this.extendTimeDomainToInclude(kf.time + offset.time);
                this.extendValueDomainToInclude(kf.value + offset.value);
                this.enforceHandleModeForKeyframe(kf, handleKey);
        }

        normalizeHandleOffset(offset, direction) {
                const min = Math.max(rangeSize(this.viewTimeRange) * 0.0001, 0.0001);
                let time = Number.isFinite(offset.time) ? offset.time : direction * min;
                if (direction === -1 && time > 0) {
                        time = -time;
                }
                if (direction === 1 && time < 0) {
                        time = -time;
                }
                if (Math.abs(time) < min) {
                        time = direction * min;
                }
                let value = Number.isFinite(offset.value) ? offset.value : 0;
                return { time, value };
        }

        evaluateValueAt(time) {
                if (!this.cachedSamples) {
                        this.cachedSamples = this.sampleCurve({ stepsPerSegment: 96 });
                }
                const samples = this.cachedSamples;
                if (!samples.length) return 0;
                if (time <= samples[0].time) return samples[0].value;
                const last = samples[samples.length - 1];
                if (time >= last.time) return last.value;
                let low = 0;
                let high = samples.length - 1;
                while (low <= high) {
                        const mid = Math.floor((low + high) / 2);
                        const sample = samples[mid];
                        if (Math.abs(sample.time - time) < EPSILON) {
                                return sample.value;
                        }
                        if (sample.time < time) {
                                low = mid + 1;
                        } else {
                                high = mid - 1;
                        }
                }
                const left = Math.max(0, high);
                const right = Math.min(samples.length - 1, low);
                const segment = samples[right].time - samples[left].time || 1;
                const t = (time - samples[left].time) / segment;
                return samples[left].value + (samples[right].value - samples[left].value) * t;
        }

        sampleCurve({ stepsPerSegment = 48 } = {}) {
                const ordered = this.getOrderedKeyframes();
                if (!ordered.length) {
                        this.currentBreakSegments = [];
                        return [];
                }
                const samples = [{ time: ordered[0].time, value: ordered[0].value }];
                const breaks = [];
                for (let i = 1; i < ordered.length; i += 1) {
                        const prev = ordered[i - 1];
                        const next = ordered[i];
                        this.ensureHandles(prev);
                        this.ensureHandles(next);
                        if (prev.type === "hold") {
                                const span = Math.max(next.time - prev.time, EPSILON);
                                for (let step = 1; step <= stepsPerSegment; step += 1) {
                                        const t = step / stepsPerSegment;
                                        const time = prev.time + span * t;
                                        const value = prev.value;
                                        samples.push({ time, value });
                                }
                                const lastSample = samples[samples.length - 1];
                                const jumpPoint = { time: next.time, value: next.value };
                                breaks.push([lastSample, jumpPoint]);
                                samples.push(jumpPoint);
                                continue;
                        }
                        const span = Math.max(next.time - prev.time, EPSILON);
                        if (this.options.types[prev.type]?.custom) {
                                // A type defined in Kotlin (interpolant(...)): the box sends its shape,
                                // sampled, for the keys either side. While those keys are being dragged
                                // there is no matching shape yet, and the segment is drawn straight.
                                const shape = this.customSegmentFor(prev, next);
                                if (shape) {
                                        shape.forEach(([time, value]) => {
                                                if (time > samples[samples.length - 1].time) samples.push({ time, value });
                                        });
                                } else {
                                        samples.push({ time: next.time, value: next.value });
                                }
                                continue;
                        }
                        if (prev.type === "linear") {
                                for (let step = 1; step <= stepsPerSegment; step += 1) {
                                        const u = step / stepsPerSegment;
                                        const time = prev.time + span * u;
                                        const value = prev.value + (next.value - prev.value) * u;
                                        samples.push({ time, value });
                                }
                        } else {
                                const controls = buildRationalControls(prev, next);
                                for (let step = 1; step <= stepsPerSegment; step += 1) {
                                        const u = step / stepsPerSegment;
                                        const point = rationalBezierPoint(controls, u);
                                        const time = point.x;
                                        const value = point.y;
                                        if (time <= samples[samples.length - 1].time) {
                                                continue;
                                        }
                                        samples.push({ time, value });
                                }
                        }
                }
                this.currentBreakSegments = breaks;
                return samples;
        }

        startPan(event) {
                event.preventDefault();
                this.hideContextMenu();
                const pointerId = event.pointerId;
                const startX = event.clientX;
                const startY = event.clientY;
                const initialTimeRange = [...this.viewTimeRange];
                const initialValueRange = [...this.viewValueRange];
                const timeSpan = rangeSize(this.viewTimeRange);
                const valueSpan = rangeSize(this.viewValueRange);
                const width = Math.max(this.innerWidth, 1);
                const height = Math.max(this.innerHeight, 1);
                if (!timeSpan && !valueSpan) return;

                const move = (ev) => {
                        if (ev.pointerId !== pointerId) return;
                        let timeRange = initialTimeRange;
                        let valueRange = initialValueRange;
                        if (timeSpan) {
                                const deltaX = ev.clientX - startX;
                                const timeShift = -(deltaX / width) * timeSpan;
                                timeRange = [initialTimeRange[0] + timeShift, initialTimeRange[1] + timeShift];
                                this.setViewTimeRange(timeRange, { render: false });
                        }
                        if (valueSpan) {
                                const deltaY = ev.clientY - startY;
                                const valueShift = (deltaY / height) * valueSpan;
                                valueRange = [initialValueRange[0] + valueShift, initialValueRange[1] + valueShift];
                                this.setViewValueRange(valueRange, { render: false });
                        }
                        this.render();
                };

                const stop = (ev) => {
                        if (ev.pointerId !== pointerId) return;
                        window.removeEventListener("pointermove", move);
                        window.removeEventListener("pointerup", stop);
                        window.removeEventListener("pointercancel", stop);
                };

                window.addEventListener("pointermove", move);
                window.addEventListener("pointerup", stop);
                window.addEventListener("pointercancel", stop);
        }

        /**
         * Start a marquee (rubber-band) selection on the hit region.
         * Default: x-range only (full-height band).
         * Alt held: 2D box selection (x + y range).
         * Shift: additive — toggles exclusively-already-selected sets.
         */
        startMarquee(event) {
                event.preventDefault();
                const pointerId = event.pointerId;
                const rect = this.svg.getBoundingClientRect();
                const pad = this.options.padding;
                const startX = clamp(event.clientX - rect.left, pad, pad + this.innerWidth);
                const startY = clamp(event.clientY - rect.top, pad, pad + this.innerHeight);
                const THRESHOLD = 4; // px before we commit to a marquee vs a click
                let didMarquee = false;
                const isShift = event.shiftKey;
                const prevSelection = isShift ? [...this.state.selectedIds] : [];

                const move = (ev) => {
                        if (ev.pointerId !== pointerId) return;
                        const curX = clamp(ev.clientX - rect.left, pad, pad + this.innerWidth);
                        const curY = clamp(ev.clientY - rect.top, pad, pad + this.innerHeight);
                        const dx = curX - startX;
                        const dy = curY - startY;
                        if (!didMarquee && Math.hypot(dx, dy) < THRESHOLD) return;
                        didMarquee = true;
                        const useY = ev.altKey;

                        // Draw marquee rect
                        const mx = Math.min(startX, curX);
                        const mw = Math.abs(dx);
                        const my = useY ? Math.min(startY, curY) : pad;
                        const mh = useY ? Math.abs(dy) : this.innerHeight;
                        this.marqueeRect.setAttribute("x", mx);
                        this.marqueeRect.setAttribute("y", my);
                        this.marqueeRect.setAttribute("width", mw);
                        this.marqueeRect.setAttribute("height", mh);
                        this.marqueeRect.setAttribute("visibility", "visible");

                        // Convert px bounds to time/value space
                        const tMin = this._xToTime(Math.min(startX, curX));
                        const tMax = this._xToTime(Math.max(startX, curX));
                        const vMin = useY ? this._yToValue(Math.max(startY, curY)) : -Infinity;
                        const vMax = useY ? this._yToValue(Math.min(startY, curY)) : Infinity;

                        // Find keyframes inside the marquee
                        const marqueeIds = this.keyframes
                            .filter((kf) => kf.time >= tMin && kf.time <= tMax && kf.value >= vMin && kf.value <= vMax)
                            .map((kf) => kf.id);

                        if (isShift) {
                                // Shift: additive toggle
                                const allAlreadySelected = marqueeIds.length > 0 && marqueeIds.every((mid) => prevSelection.includes(mid));
                                if (allAlreadySelected) {
                                        // Remove the marquee set from previous selection
                                        const filtered = prevSelection.filter((id) => !marqueeIds.includes(id));
                                        this.setSelection(filtered);
                                } else {
                                        // Add marquee set to previous selection
                                        const merged = Array.from(new Set([...prevSelection, ...marqueeIds]));
                                        this.setSelection(merged);
                                }
                        } else {
                                this.setSelection(marqueeIds);
                        }
                };

                const stop = (ev) => {
                        if (ev.pointerId !== pointerId) return;
                        window.removeEventListener("pointermove", move);
                        window.removeEventListener("pointerup", stop);
                        window.removeEventListener("pointercancel", stop);
                        this.marqueeRect.setAttribute("visibility", "hidden");
                        if (!didMarquee) {
                                // It was just a click on empty space
                                if (!isShift) {
                                        this.clearSelection();
                                }
                        }
                };

                window.addEventListener("pointermove", move);
                window.addEventListener("pointerup", stop);
                window.addEventListener("pointercancel", stop);
        }

        setPlayhead(time) {
                if (typeof time === "number" && Number.isFinite(time)) {
                        this.playhead = time;
                } else {
                        this.playhead = null;
                }
                this.renderPlayhead();
        }

        getPlayhead() {
                return this.playhead;
        }

        /** Reset view: fit the keys, or the initial view when there are none. */
        resetView() {
                if (!this.fitToKeyframes()) {
                        this.setViewTimeRange([...this.initialTimeRange], { render: false });
                        this.setViewValueRange([...this.initialValueRange], { render: false });
                }
                this.render();
        }

        /**
         * Frames every key, with a little room: time always includes 0 and rounds out to whole
         * units, value always includes 0 and 1. Returns false when there is nothing to fit.
         */
        fitToKeyframes() {
                const times = this.keyframes.map((kf) => kf.time).filter(Number.isFinite);
                // The curve, not just its keys: an elastic or a spring goes well past them.
                const values = [...this.keyframes.map((kf) => kf.value), ...this.sampleCurve({ stepsPerSegment: 24 }).map((s) => s.value)]
                    .filter(Number.isFinite);
                if (!times.length) return false;
                const pad = (min, max, ratio) => {
                        if (Math.abs(max - min) < 1e-6) {
                                const delta = Math.max(Math.abs(min) * 0.1, 1);
                                return [min - delta, max + delta];
                        }
                        const span = max - min;
                        return [min - span * ratio, max + span * ratio];
                };
                let tMin = Math.min(0, ...times);
                let tMax = Math.max(...times);
                if (tMax - tMin < 1) tMax = tMin + 1;
                const [t0, t1] = pad(tMin, tMax, 0.08);
                if (Math.ceil(t1) - Math.floor(t0) > 0) this.setViewTimeRange([Math.floor(t0), Math.ceil(t1)], { render: false });
                const [v0, v1] = pad(Math.min(0, ...values), Math.max(1, ...values), 0.08);
                if (v1 - v0 > 0) this.setViewValueRange([v0, v1], { render: false });
                return true;
        }

        setViewTimeRange(range, { render = true } = {}) {
                const [min, max] = range;
                if (!Number.isFinite(min) || !Number.isFinite(max)) {
                        return;
                }
                if (max - min <= 0) {
                        return;
                }
                this.viewTimeRange = [min, max];
                this.extendTimeDomainToView();
                if (render) {
                        this.render();
                }
        }

        setViewValueRange(range, { render = true } = {}) {
                const [min, max] = range;
                if (!Number.isFinite(min) || !Number.isFinite(max)) {
                        return;
                }
                if (max - min <= 0) {
                        return;
                }
                this.viewValueRange = [min, max];
                this.extendValueDomainToView();
                if (render) {
                        this.render();
                }
        }

        extendTimeDomainToView() {
                const [viewMin, viewMax] = this.viewTimeRange;
                let [domainMin, domainMax] = this.domainTimeRange;
                if (viewMin < domainMin) {
                        domainMin = viewMin;
                }
                if (viewMax > domainMax) {
                        domainMax = viewMax;
                }
                this.domainTimeRange = [domainMin, domainMax];
        }

        extendTimeDomainToInclude(time) {
                if (!Number.isFinite(time)) return;
                let [domainMin, domainMax] = this.domainTimeRange;
                let changed = false;
                if (time < domainMin) {
                        domainMin = time;
                        changed = true;
                }
                if (time > domainMax) {
                        domainMax = time;
                        changed = true;
                }
                if (changed) {
                        this.domainTimeRange = [domainMin, domainMax];
                }
        }

        extendValueDomainToView() {
                const [viewMin, viewMax] = this.viewValueRange;
                let [domainMin, domainMax] = this.domainValueRange;
                if (viewMin < domainMin) {
                        domainMin = viewMin;
                }
                if (viewMax > domainMax) {
                        domainMax = viewMax;
                }
                this.domainValueRange = [domainMin, domainMax];
        }

        extendValueDomainToInclude(value) {
                if (!Number.isFinite(value)) return;
                let [domainMin, domainMax] = this.domainValueRange;
                let changed = false;
                if (value < domainMin) {
                        domainMin = value;
                        changed = true;
                }
                if (value > domainMax) {
                        domainMax = value;
                        changed = true;
                }
                if (changed) {
                        this.domainValueRange = [domainMin, domainMax];
                }
        }

        updateKeyframe(id, partial) {
                const kf = this.keyframes.find((item) => item.id === id);
                if (!kf) return;
                if (typeof partial.time === "number") {
                        this.extendTimeDomainToInclude(partial.time);
                        const [timeMin, timeMax] = this.domainTimeRange;
                        kf.time = clamp(partial.time, timeMin, timeMax);
                }
                if (typeof partial.value === "number") {
                        this.extendValueDomainToInclude(partial.value);
                        const [valueMin, valueMax] = this.domainValueRange;
                        kf.value = clamp(partial.value, valueMin, valueMax);
                }
        }

        pointerToValues(event) {
                const rect = this.svg.getBoundingClientRect();
                const x = clamp(event.clientX - rect.left - this.options.padding, 0, this.innerWidth);
                const y = clamp(event.clientY - rect.top - this.options.padding, 0, this.innerHeight);
                const [timeMin, timeMax] = this.viewTimeRange;
                const [valueMin, valueMax] = this.viewValueRange;
                const time = timeMin + (x / this.innerWidth) * rangeSize(this.viewTimeRange);
                const value = valueMax - (y / this.innerHeight) * rangeSize(this.viewValueRange);
                return { time, value };
        }

        formatAxisValue(value, span) {
                if (!Number.isFinite(value)) return "--";
                const safeSpan = Math.max(span, EPSILON);
                let decimals = 2;
                if (safeSpan <= 0.2) {
                        decimals = 4;
                } else if (safeSpan <= 0.5) {
                        decimals = 3;
                } else if (safeSpan <= 1) {
                        decimals = 3;
                } else if (safeSpan <= 10) {
                        decimals = 2;
                } else if (safeSpan <= 100) {
                        decimals = 1;
                } else {
                        decimals = 0;
                }
                return value.toFixed(decimals);
        }

        formatTimeDisplay(value) {
                return `${this.formatAxisValue(value, rangeSize(this.viewTimeRange))}s`;
        }

        formatValueDisplay(value) {
                return this.formatAxisValue(value, rangeSize(this.viewValueRange));
        }

        showDragLabel(position) {
                if (!this.dragLabelGroup || !position) return;
                const text = `${this.formatTimeDisplay(position.time)} • ${this.formatValueDisplay(position.value)}`;
                this.dragLabelGroup.setAttribute("visibility", "visible");
                this.dragLabelText.textContent = text;
                const padding = 6;
                this.dragLabelText.setAttribute("x", padding);
                this.dragLabelText.setAttribute("y", padding);
                const bbox = this.dragLabelText.getBBox();
                const width = bbox.width + padding * 2;
                const height = bbox.height + padding * 2;
                this.dragLabelRect.setAttribute("width", width);
                this.dragLabelRect.setAttribute("height", height);
                const anchorX = this.timeToX(position.time);
                const anchorY = this.valueToY(position.value);
                const graphLeft = this.options.padding;
                const graphRight = graphLeft + this.innerWidth;
                const graphTop = this.options.padding;
                const graphBottom = graphTop + this.innerHeight;
                let x = anchorX + 10;
                let y = anchorY - height - 10;
                if (x + width > graphRight - 4) {
                        x = anchorX - width - 10;
                }
                if (x < graphLeft + 4) {
                        x = graphLeft + 4;
                }
                if (x + width > graphRight - 4) {
                        x = graphRight - width - 4;
                }
                if (y < graphTop + 4) {
                        y = anchorY + 10;
                }
                if (y + height > graphBottom - 4) {
                        y = graphBottom - height - 4;
                }
                if (y < graphTop + 4) {
                        y = graphTop + 4;
                }
                this.dragLabelGroup.setAttribute("transform", `translate(${x.toFixed(2)}, ${y.toFixed(2)})`);
        }

        hideDragLabel() {
                if (!this.dragLabelGroup) return;
                this.dragLabelGroup.setAttribute("visibility", "hidden");
        }

        rangeMid(range = this.viewTimeRange) {
                const [min, max] = range;
                return min + (max - min) / 2;
        }

        projectPoint(kf) {
                return {
                        x: this.timeToX(kf.time),
                        y: this.valueToY(kf.value),
                };
        }

        timeToX(time) {
                const [min, max] = this.viewTimeRange;
                const ratio = (time - min) / rangeSize(this.viewTimeRange);
                return this.options.padding + ratio * this.innerWidth;
        }

        valueToY(value) {
                const [min, max] = this.viewValueRange;
                const ratio = (value - min) / rangeSize(this.viewValueRange);
                return this.options.padding + this.innerHeight - ratio * this.innerHeight;
        }

        /** Inverse of timeToX — convert an SVG x-coordinate back to time. */
        _xToTime(x) {
                const ratio = (x - this.options.padding) / this.innerWidth;
                const [min] = this.viewTimeRange;
                return min + ratio * rangeSize(this.viewTimeRange);
        }

        /** Inverse of valueToY — convert an SVG y-coordinate back to value. */
        _yToValue(y) {
                const ratio = (this.options.padding + this.innerHeight - y) / this.innerHeight;
                const [min] = this.viewValueRange;
                return min + ratio * rangeSize(this.viewValueRange);
        }

        getGraphContext() {
                return {
                        width: this.options.width,
                        height: this.options.height,
                        innerWidth: this.innerWidth,
                        innerHeight: this.innerHeight,
                        padding: this.options.padding,
                        timeRange: [...this.viewTimeRange],
                        viewTimeRange: [...this.viewTimeRange],
                        domainTimeRange: [...this.domainTimeRange],
                        valueRange: [...this.viewValueRange],
                        viewValueRange: [...this.viewValueRange],
                        domainValueRange: [...this.domainValueRange],
                        layer: this.graphOverlay,
                        svg: this.svg,
                        project: (kf) => this.projectPoint(kf),
                        timeToX: (time) => this.timeToX(time),
                        valueToY: (value) => this.valueToY(value),
                        createElement: createSVGElement,
                        sampleCurve: (opts) => this.sampleCurve(opts),
                        evaluateValueAt: (time) => this.evaluateValueAt(time),
                        setPlayhead: (time) => this.setPlayhead(time),
                        getPlayhead: () => this.getPlayhead(),
                };
        }

        toJSON() {
                const ordered = this.getOrderedKeyframes();
                return ordered.map((kf) => ({
                        time: kf.time,
                        value: kf.value,
                        type: kf.type,
                        locked: kf.locked || undefined,
                        handles: kf.handles ? {
                                in: {
                                        time: kf.handles.in?.time ?? 0,
                                        value: kf.handles.in?.value ?? 0,
                                },
                                out: {
                                        time: kf.handles.out?.time ?? 0,
                                        value: kf.handles.out?.value ?? 0,
                                },
                        } : null,
                }));
        }
}

export const BUILTIN_KEYFRAME_TYPES = DEFAULT_TYPES;
