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
  // border-radius: 16px;
  padding: 1rem;
  width: fit-content;
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

.kf-menu {
  position: absolute;
  min-width: 200px;
  background: #0a0d13;
  border: 1px solid rgba(255, 255, 255, 0.08);
  // border-radius: 12px;
  box-shadow: 0 20px 45px rgba(0, 0, 0, 0.45);
  padding: 0.5rem;
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
  z-index: 20;
}

.kf-menu.is-hidden {
  display: none;
}

.kf-menu-group {
  display: flex;
  flex-direction: column;
  gap: 0.3rem;
}

.kf-menu-divider {
  height: 1px;
  background: rgba(255, 255, 255, 0.08);
  margin: 0.25rem 0;
}

.kf-menu-label {
  font-size: 0.75rem;
  text-transform: uppercase;
  letter-spacing: 0.12em;
  color: var(--kf-muted);
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
  // border-radius: 10px;
  padding: 0.35rem 0.5rem;
  background: transparent;
  color: inherit;
  cursor: pointer;
}

.kf-menu-item:hover {
  border-color: rgba(255, 255, 255, 0.18);
  background: rgba(255, 255, 255, 0.04);
}

.kf-menu-item.is-active {
  border-color: var(--kf-accent);
  color: var(--kf-accent);
}

`;

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

                this.buildDOM();
                this.refresh({ notify: false });
        }

        get innerWidth() {
                return Math.max(this.options.width - this.options.padding * 2, 10);
        }

        get innerHeight() {
                return Math.max(this.options.height - this.options.padding * 2, 10);
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
                };
                this.ensureHandles(prepared);
                this.applyHandleMode(prepared);
                return prepared;
        }

        buildDOM() {
                this.root.innerHTML = "";
                this.wrapper = document.createElement("div");
                this.wrapper.className = "kf-editor";
                this.wrapper.style.width = `${this.options.width}px`;
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
                        if (event.button === 1 || event.button === 2) {
                                this.startPan(event);
                                return;
                        }
                        if (event.button !== 0) return;
                        this.hideContextMenu();
                        this.clearSelection();
                });

                hitRegion.addEventListener("contextmenu", (event) => {
                        event.preventDefault();
                        this.hideContextMenu();
                });

                hitRegion.addEventListener("dblclick", (event) => {
                        event.preventDefault();
                        const point = this.pointerToValues(event);
                        this.addKeyframe(point);
                });
                svg.appendChild(gridGroup);
                svg.appendChild(axisGroup);
                svg.appendChild(bg);
                svg.appendChild(hitRegion);
                svg.appendChild(this.graphPath);
                svg.appendChild(overlayGroup);
                svg.appendChild(keyframeLayer);
                svg.appendChild(this.playheadLine);
                svg.appendChild(this.playheadDot);

                this.svg = svg;
                this.axisGroup = axisGroup;
                this.wrapper.appendChild(svg);

                this.renderGrid();
        }

        buildContextMenu() {
                const menu = document.createElement("div");
                menu.className = "kf-menu is-hidden";
                this.menuTargetId = null;
                this.menuTypeButtons = {};

                const resetButton = document.createElement("button");
                resetButton.type = "button";
                resetButton.className = "kf-menu-item";
                resetButton.textContent = "Reset view";
                resetButton.addEventListener("click", () => {
                        this.resetView();
                        this.hideContextMenu();
                });

                const deleteButton = document.createElement("button");
                deleteButton.type = "button";
                deleteButton.className = "kf-menu-item";
                deleteButton.textContent = "Delete keyframe";
                deleteButton.addEventListener("click", () => {
                        if (this.menuTargetId) {
                                this.removeKeyframe(this.menuTargetId);
                        }
                        this.hideContextMenu();
                });

                const divider = document.createElement("div");
                divider.className = "kf-menu-divider";

                const typeLabel = document.createElement("div");
                typeLabel.className = "kf-menu-label";
                typeLabel.textContent = "Type";

                const typeGroup = document.createElement("div");
                typeGroup.className = "kf-menu-group";

                Object.entries(this.options.types).forEach(([key, meta]) => {
                        const button = document.createElement("button");
                        button.type = "button";
                        button.className = "kf-menu-item";
                        button.textContent = meta.label ?? key;
                        button.addEventListener("click", () => {
                                if (this.menuTargetId) {
                                        const ids =
                                            this.state.selectedIds.includes(this.menuTargetId) && this.state.selectedIds.length > 1
                                                ? [...this.state.selectedIds]
                                                : [this.menuTargetId];
                                        this.setKeyframeTypeBatch(ids, key);
                                }
                                this.hideContextMenu();
                        });
                        this.menuTypeButtons[key] = button;
                        typeGroup.appendChild(button);
                });

                menu.appendChild(resetButton);
                menu.appendChild(deleteButton);
                menu.appendChild(divider);
                menu.appendChild(typeLabel);
                menu.appendChild(typeGroup);

                this.wrapper.appendChild(menu);
                this.contextMenu = menu;

                this.handleGlobalPointerDown = (event) => {
                        if (!this.contextMenu || this.contextMenu.classList.contains("is-hidden")) {
                                return;
                        }
                        if (this.contextMenu.contains(event.target)) {
                                return;
                        }
                        this.hideContextMenu();
                };

                this.handleEscapeKey = (event) => {
                        if (event.key === "Escape") {
                                this.hideContextMenu();
                        }
                };

                window.addEventListener("pointerdown", this.handleGlobalPointerDown);
                window.addEventListener("keydown", this.handleEscapeKey);
        }

        showContextMenu(event, id) {
                if (!this.contextMenu) return;
                const target = this.keyframes.find((kf) => kf.id === id);
                if (!target) return;
                this.menuTargetId = id;
                this.contextMenu.classList.remove("is-hidden");
                this.contextMenu.style.left = "0px";
                this.contextMenu.style.top = "0px";
                const wrapperRect = this.wrapper.getBoundingClientRect();
                const menuRect = this.contextMenu.getBoundingClientRect();
                const localX = event.clientX - wrapperRect.left;
                const localY = event.clientY - wrapperRect.top;
                const wrapperWidth = this.wrapper.clientWidth || this.options.width;
                const wrapperHeight = this.wrapper.clientHeight || this.options.height;
                const maxX = Math.max(wrapperWidth - menuRect.width - 8, 8);
                const maxY = Math.max(wrapperHeight - menuRect.height - 8, 8);
                const clampedX = clamp(localX, 8, maxX);
                const clampedY = clamp(localY, 8, maxY);
                this.contextMenu.style.left = `${clampedX}px`;
                this.contextMenu.style.top = `${clampedY}px`;
                this.updateContextMenuSelection(target.type);
        }

        hideContextMenu() {
                if (!this.contextMenu || this.contextMenu.classList.contains("is-hidden")) {
                        return;
                }
                this.contextMenu.classList.add("is-hidden");
                this.menuTargetId = null;
        }

        updateContextMenuSelection(type) {
                if (!this.menuTypeButtons) return;
                Object.entries(this.menuTypeButtons).forEach(([key, button]) => {
                        if (!button) return;
                        if (key === type) {
                                button.classList.add("is-active");
                        } else {
                                button.classList.remove("is-active");
                        }
                });
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
                const start = Math.floor(min / step) * step;
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
                                y2: this.options.padding + this.innerHeight,
                                stroke: lineStyle.stroke,
                                "stroke-width": lineStyle.width,
                        });
                        this.gridGroup.appendChild(line);
                }
        }

        drawValueGridLines() {
                const [min, max] = this.viewValueRange;
                const ticks = this.getValueTicks(min, max);
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
                                x1: this.options.padding,
                                y1: y,
                                x2: this.options.padding + this.innerWidth,
                                y2: y,
                                stroke,
                                "stroke-width": width,
                        });
                        this.gridGroup.appendChild(line);
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

        refresh({ notify = true } = {}) {
                this.keyframes.sort((a, b) => a.time - b.time);
                this.render();
                if (notify && typeof this.options.onChange === "function") {
                        this.options.onChange(this.getKeyframes());
                }
        }

        render() {
                this.renderGrid();
                this.cachedSamples = this.sampleCurve({ stepsPerSegment: 96 });
                this.renderKeyframes();
                this.renderGraph();
                this.renderPlayhead();
        }

        renderKeyframes() {
                this.keyframeLayer.innerHTML = "";
                const ctx = this.getGraphContext();
                this.keyframes.forEach((kf) => {
                        const { x, y } = ctx.project(kf);
                        const typeClass = this.getNodeStyleClass(kf);
                        const group = createSVGElement("g", {
                                'class': `kf-keyframe ${typeClass}` + (this.isSelected(kf.id) ? " is-selected" : ""),
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
                                this.handleSelectionClick(kf.id, event);
                                this.startDrag(event, kf.id);
                        });

                        group.addEventListener("pointerenter", () => {
                                group.classList.add("is-hovered");
                        });

                        group.addEventListener("pointerleave", () => {
                                group.classList.remove("is-hovered");
                        });

                        group.addEventListener("click", (event) => {
                                event.stopPropagation();
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
                                this.startHandleDrag(event, kf.id, key);
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

        handleSelectionClick(id, event) {
                if (event.shiftKey) {
                        this.selectRange(id);
                } else {
                        this.selectKeyframe(id);
                }
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

        startDrag(event, id) {
                event.preventDefault();
                this.hideContextMenu();
                this.hideSnapIndicators();
                this.hideDragLabel();
                const targets = (this.isSelected(id) ? [...this.state.selectedIds] : [id])
                    .map((selectedId) => this.keyframes.find((item) => item.id === selectedId))
                    .filter(Boolean);
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

        resetView() {
                this.setViewTimeRange([...this.initialTimeRange], { render: false });
                this.setViewValueRange([...this.initialValueRange], { render: false });
                this.render();
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
