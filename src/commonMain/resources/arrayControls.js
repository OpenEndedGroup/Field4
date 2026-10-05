// Multislider, matrix, range slider and menu controls (afield/visualizers.kt).
// Each container carries its whole value in data-values; a change sends it all as `array-update`.
// On the canvas it is loaded after sliders.js, whose sendViaAfield it uses. The phone page
// (controllers.html) loads it without sliders.js and marks its controls data-send="false": each
// throttled change then arrives as a `field-array-input` event for the page to send its own way.

(function () {
    const SEND_INTERVAL_MS = 30;

    function valuesOf(container) {
        return String(container.dataset.values || '')
            .split(',')
            .filter((s) => s.length)
            .map(Number);
    }

    function num(container, key, fallback) {
        const v = parseFloat(container.dataset[key]);
        return Number.isFinite(v) ? v : fallback;
    }

    function quantize(container, value) {
        const min = num(container, 'min', 0);
        const max = num(container, 'max', 1);
        const step = num(container, 'step', 0);
        let v = step > 0 ? min + Math.round((value - min) / step) * step : value;
        return Math.min(Math.max(v, Math.min(min, max)), Math.max(min, max));
    }

    function format(v) {
        return Math.abs(v) >= 100 ? v.toFixed(0) : v.toFixed(2);
    }

    const escapeId = (id) => (typeof cssEscape === 'function' ? cssEscape(id) : CSS.escape(id));

    // Sending: at most one message per SEND_INTERVAL_MS during a gesture, and always the last one.
    const pending = new Map();
    function send(container, values) {
        container.dataset.values = values.join(',');
        const id = container.dataset.controlId;
        const entry = pending.get(id);
        if (entry) { entry.values = values; return; }
        const flush = () => {
            const e = pending.get(id);
            pending.delete(id);
            if (!e) return;
            if (container.dataset.send === 'false') {
                container.dispatchEvent(new CustomEvent('field-array-input', {
                    bubbles: true, detail: { controlId: id, values: e.values },
                }));
            } else {
                sendViaAfield('array-update', { controlId: id, values: e.values });
            }
        };
        pending.set(id, { values });
        setTimeout(flush, SEND_INTERVAL_MS);
    }

    // Polar geometry. A polar control draws in a 200x200 viewBox centred on (100, 100); a turn
    // fraction `a` runs clockwise from twelve o'clock.

    const SVG_NS = 'http://www.w3.org/2000/svg';
    const C = 100;

    function isPolar(container) {
        return container.dataset.polar === 'true';
    }

    function polarPoint(r, a) {
        const t = a * 2 * Math.PI;
        return [C + r * Math.sin(t), C - r * Math.cos(t)];
    }

    // An annular sector between radii r0 < r1 and turn fractions a0 < a1.
    function sectorPath(r0, r1, a0, a1) {
        const large = a1 - a0 > 0.5 ? 1 : 0;
        const [x0, y0] = polarPoint(r1, a0);
        const [x1, y1] = polarPoint(r1, a1);
        const [x2, y2] = polarPoint(r0, a1);
        const [x3, y3] = polarPoint(r0, a0);
        return `M${x0} ${y0}A${r1} ${r1} 0 ${large} 1 ${x1} ${y1}L${x2} ${y2}` +
            (r0 > 0 ? `A${r0} ${r0} 0 ${large} 0 ${x3} ${y3}Z` : 'Z');
    }

    function arcPath(r, a0, a1) {
        const [x0, y0] = polarPoint(r, a0);
        const [x1, y1] = polarPoint(r, a1);
        return `M${x0} ${y0}A${r} ${r} 0 ${a1 - a0 > 0.5 ? 1 : 0} 1 ${x1} ${y1}`;
    }

    // The pointer in viewBox units: radius from the centre and turn fraction in [0, 1).
    function polarPointer(svg, event) {
        const rect = svg.getBoundingClientRect();
        const x = ((event.clientX - rect.left) / Math.max(rect.width, 1)) * 200 - C;
        const y = ((event.clientY - rect.top) / Math.max(rect.height, 1)) * 200 - C;
        let a = Math.atan2(x, -y) / (2 * Math.PI);
        if (a < 0) a += 1;
        return { r: Math.hypot(x, y), a };
    }

    function svgChild(svg, tag, cls) {
        // Every class must match: `cls` may name several, as the two thumbs do.
        let el = svg.querySelector(cls.split(/\s+/).map((c) => `.${c}`).join(''));
        if (!el) {
            el = document.createElementNS(SVG_NS, tag);
            el.setAttribute('class', cls);
            svg.appendChild(el);
        }
        return el;
    }

    // Polar multislider: wedges between these radii.
    const MS_R0 = 22;
    const MS_R1 = 96;

    // Polar matrix: rings between these radii, row 0 outermost.
    const MX_R0 = 26;
    const MX_R1 = 96;

    // Polar range: a whole circle. min and max meet at twelve o'clock, and the band runs clockwise
    // from its first value to its second, so it may wrap through the top.
    const RG_R = 78;

    function turn(t) {
        return ((t % 1) + 1) % 1;
    }

    // Clockwise distance from turn a to turn b.
    function clockwise(a, b) {
        return turn(b - a);
    }

    // Multislider ----------------------------------------------------------

    function renderMultislider(container, values, focus = -1) {
        const svg = container.querySelector('.multislider-visual');
        if (!svg) return;
        const min = num(container, 'min', 0);
        const max = num(container, 'max', 1);
        const n = values.length;
        if (svg.querySelectorAll('.multislider-bar').length !== n) {
            svg.querySelectorAll('.multislider-bar').forEach((r) => r.remove());
            for (let i = 0; i < n; i++) {
                const r = document.createElementNS(SVG_NS, isPolar(container) ? 'path' : 'rect');
                r.setAttribute('class', 'multislider-bar');
                svg.appendChild(r);
            }
        }
        const span = max - min || 1;
        if (isPolar(container)) {
            const guide = svgChild(svg, 'circle', 'polar-guide');
            guide.setAttribute('cx', C); guide.setAttribute('cy', C); guide.setAttribute('r', MS_R1);
            svg.insertBefore(guide, svg.firstChild);
            const gap = Math.min(0.06 / n, 0.004);
            svg.querySelectorAll('.multislider-bar').forEach((bar, i) => {
                const t = Math.min(Math.max((values[i] - min) / span, 0), 1);
                const r1 = MS_R0 + Math.max(t, 0.01) * (MS_R1 - MS_R0);
                bar.setAttribute('d', sectorPath(MS_R0, r1, i / n + gap, (i + 1) / n - gap));
            });
        } else svg.querySelectorAll('.multislider-bar').forEach((r, i) => {
            const t = Math.min(Math.max((values[i] - min) / span, 0), 1);
            r.setAttribute('x', String(i + 0.06));
            r.setAttribute('width', '0.88');
            r.setAttribute('y', String(1 - t));
            r.setAttribute('height', String(Math.max(t, 0.004)));
        });
        const readout = container.querySelector('.array-readout');
        if (readout) readout.textContent = focus >= 0 ? `${focus}: ${format(values[focus])}` : '';
    }

    function multisliderPoint(container, event) {
        const svg = container.querySelector('.multislider-visual');
        const rect = svg.getBoundingClientRect();
        const n = num(container, 'count', 1);
        const min = num(container, 'min', 0);
        const max = num(container, 'max', 1);
        if (isPolar(container)) {
            const { r, a } = polarPointer(svg, event);
            const t = Math.min(Math.max((r - MS_R0) / (MS_R1 - MS_R0), 0), 1);
            return { index: Math.min(Math.floor(a * n), n - 1), value: quantize(container, min + t * (max - min)) };
        }
        const fx = (event.clientX - rect.left) / Math.max(rect.width, 1);
        const fy = (event.clientY - rect.top) / Math.max(rect.height, 1);
        return {
            index: Math.min(Math.max(Math.floor(fx * n), 0), n - 1),
            value: quantize(container, min + (1 - Math.min(Math.max(fy, 0), 1)) * (max - min)),
        };
    }

    // Matrix ---------------------------------------------------------------

    function renderMatrix(container, values) {
        const grid = container.querySelector('.matrix-visual');
        if (!grid) return;
        if (grid.querySelectorAll('.matrix-cell').length !== values.length) {
            grid.innerHTML = '';
            const polar = isPolar(container);
            const rows = num(container, 'rows', 1);
            const columns = num(container, 'columns', 1);
            const ring = (MX_R1 - MX_R0) / rows;
            values.forEach((_, i) => {
                let cell;
                if (polar) {
                    const row = Math.floor(i / columns);
                    const col = i % columns;
                    const r1 = MX_R1 - row * ring;
                    const r0 = r1 - ring + 1.5;
                    // A gap of the same width at every radius: an angle that shrinks outward.
                    const pad = 0.9 / (2 * Math.PI * ((r0 + r1) / 2));
                    cell = document.createElementNS(SVG_NS, 'path');
                    cell.setAttribute('d', sectorPath(r0, r1, col / columns + pad, (col + 1) / columns - pad));
                    cell.setAttribute('class', 'matrix-cell');
                } else {
                    cell = document.createElement('div');
                    cell.className = 'matrix-cell';
                }
                cell.dataset.index = String(i);
                grid.appendChild(cell);
            });
        }
        const states = num(container, 'states', 2);
        grid.querySelectorAll('.matrix-cell').forEach((cell, i) => {
            const state = Math.round(values[i] || 0);
            cell.classList.toggle('on', state > 0);
            cell.style.setProperty('--cell-color', state > 0 && states > 2 ? matrixColor(state, states) : '');
            cell.dataset.state = String(state);
        });
        const readout = container.querySelector('.array-readout');
        if (readout) readout.textContent = `${values.filter((v) => v >= 0.5).length} on`;
    }

    // State 0 is black; states 1..n-1 are equally spaced hues starting at Field's red.
    function matrixColor(state, states) {
        const hue = (360 * (state - 1)) / (states - 1);
        return `hsl(${hue}, 72%, 58%)`;
    }

    function matrixCellAt(container, event) {
        return matrixCellAtPoint(container, event.clientX, event.clientY);
    }

    function matrixCellAtPoint(container, x, y) {
        const el = document.elementFromPoint(x, y);
        const cell = el?.closest?.('.matrix-cell');
        return cell && container.contains(cell) ? Number(cell.dataset.index) : -1;
    }

    // Range slider ---------------------------------------------------------

    function renderRange(container, values) {
        const min = num(container, 'min', 0);
        const max = num(container, 'max', 1);
        const span = max - min || 1;
        if (isPolar(container)) {
            const svg = container.querySelector('.range-polar');
            if (!svg) return;
            const t0 = turn((values[0] - min) / span);
            const t1 = turn((values[1] - min) / span);
            const sweep = clockwise(t0, t1);
            const track = svgChild(svg, 'circle', 'range-arc-track');
            track.setAttribute('cx', C); track.setAttribute('cy', C); track.setAttribute('r', RG_R);
            const fill = svgChild(svg, 'path', 'range-arc-fill');
            fill.setAttribute('d', sweep > 0 ? arcPath(RG_R, t0, t0 + sweep) : '');
            // The seam, where max turns back into min.
            const [sx0, sy0] = polarPoint(RG_R - 10, 0);
            const [sx1, sy1] = polarPoint(RG_R + 10, 0);
            const seam = svgChild(svg, 'line', 'range-arc-seam');
            seam.setAttribute('x1', sx0); seam.setAttribute('y1', sy0);
            seam.setAttribute('x2', sx1); seam.setAttribute('y2', sy1);
            const seamLabel = svgChild(svg, 'text', 'range-arc-end');
            seamLabel.setAttribute('x', C); seamLabel.setAttribute('y', C - RG_R - 13);
            seamLabel.textContent = `${format(max)} | ${format(min)}`;
            svg.querySelectorAll('.range-arc-stop').forEach((el) => el.remove());
            [t0, t1].forEach((t, k) => {
                const [x, y] = polarPoint(RG_R, t);
                const thumb = svgChild(svg, 'circle', `range-arc-thumb range-arc-thumb-${k}`);
                thumb.setAttribute('cx', x); thumb.setAttribute('cy', y); thumb.setAttribute('r', 7);
            });
            const label = svgChild(svg, 'text', 'range-arc-label');
            label.setAttribute('x', C); label.setAttribute('y', C + 5);
            label.textContent = `${format(values[0])} → ${format(values[1])}`;
            const readout = container.querySelector('.array-readout');
            if (readout) readout.textContent = values[0] > values[1] ? 'wraps' : '';
            return;
        }
        const a = ((values[0] - min) / span) * 100;
        const b = ((values[1] - min) / span) * 100;
        const fill = container.querySelector('.range-fill');
        const thumbs = container.querySelectorAll('.range-thumb');
        if (fill) { fill.style.left = `${a}%`; fill.style.width = `${b - a}%`; }
        if (thumbs[0]) thumbs[0].style.left = `${a}%`;
        if (thumbs[1]) thumbs[1].style.left = `${b}%`;
        const readout = container.querySelector('.array-readout');
        if (readout) readout.textContent = `${format(values[0])} – ${format(values[1])}`;
    }

    // Where along the track the pointer is, 0 to 1. On the polar arc the bottom gap belongs to
    // whichever end is nearer.
    function rangeT(container, event) {
        const visual = container.querySelector('.range-visual');
        if (isPolar(container)) return polarPointer(visual, event).a;
        const rect = visual.getBoundingClientRect();
        return Math.min(Math.max((event.clientX - rect.left) / Math.max(rect.width, 1), 0), 1);
    }

    function rangeValueAt(container, event) {
        const min = num(container, 'min', 0);
        const max = num(container, 'max', 1);
        return min + rangeT(container, event) * (max - min);
    }

    // A turn fraction as a value on a polar range; a full turn is back at min, never past max.
    function polarRangeValue(container, t) {
        const min = num(container, 'min', 0);
        const max = num(container, 'max', 1);
        const v = quantize(container, min + turn(t) * (max - min));
        return v >= max ? min : v;
    }

    // How close, in value units, a press must be to a thumb to take it: 8 px on the line, 0.04
    // of the arc on the dial.
    function rangeGrab(container) {
        const span = num(container, 'max', 1) - num(container, 'min', 0);
        if (isPolar(container)) return Math.abs(span) * 0.04;
        const rect = container.querySelector('.range-visual').getBoundingClientRect();
        return (Math.abs(span) * 8) / Math.max(rect.width, 1);
    }

    // Playhead: written by the box (ArrayControlHandle.playhead), never by the pointer ----------

    function renderPlayhead(container) {
        const p = parseFloat(container.dataset.playhead);
        const kind = container.dataset.kind;
        if (kind === 'multislider') {
            const svg = container.querySelector('.multislider-visual');
            if (!svg) return;
            let mark = svg.querySelector('.multislider-playhead');
            const n = num(container, 'count', 1);
            const i = Math.floor(p);
            if (!Number.isFinite(p) || i < 0 || i >= n) { mark?.remove(); return; }
            if (isPolar(container)) {
                if (!mark) {
                    mark = document.createElementNS(SVG_NS, 'path');
                    mark.setAttribute('class', 'multislider-playhead');
                }
                const guide = svg.querySelector('.polar-guide');
                svg.insertBefore(mark, guide ? guide.nextSibling : svg.firstChild); // behind the bars
                mark.setAttribute('d', sectorPath(MS_R0, MS_R1, i / n, (i + 1) / n));
                return;
            }
            if (!mark) {
                mark = document.createElementNS('http://www.w3.org/2000/svg', 'rect');
                mark.setAttribute('class', 'multislider-playhead');
                mark.setAttribute('y', '0');
                mark.setAttribute('height', '1');
                mark.setAttribute('width', '1');
            }
            svg.insertBefore(mark, svg.firstChild); // behind the bars
            mark.setAttribute('x', String(i));
        } else if (kind === 'matrix') {
            const columns = num(container, 'columns', 1);
            const col = Number.isFinite(p) ? Math.floor(p) : -1;
            container.querySelectorAll('.matrix-cell').forEach((cell, idx) =>
                cell.classList.toggle('playhead', idx % columns === col));
        } else if (kind === 'range') {
            const min = num(container, 'min', 0);
            const max = num(container, 'max', 1);
            const t = (p - min) / ((max - min) || 1);
            const visible = Number.isFinite(p) && t >= 0 && t <= 1;
            if (isPolar(container)) {
                const svg = container.querySelector('.range-polar');
                if (!svg) return;
                const tick = svgChild(svg, 'line', 'range-arc-playhead');
                tick.style.display = visible ? '' : 'none';
                if (visible) {
                    const [x0, y0] = polarPoint(RG_R - 14, t);
                    const [x1, y1] = polarPoint(RG_R + 14, t);
                    tick.setAttribute('x1', x0); tick.setAttribute('y1', y0);
                    tick.setAttribute('x2', x1); tick.setAttribute('y2', y1);
                }
                return;
            }
            const mark = container.querySelector('.range-playhead');
            if (!mark) return;
            mark.style.display = visible ? 'block' : 'none';
            if (visible) mark.style.left = `${t * 100}%`;
        }
    }

    // Dispatch -------------------------------------------------------------

    function render(container, values = valuesOf(container)) {
        const kind = container.dataset.kind;
        if (kind === 'multislider') renderMultislider(container, values);
        else if (kind === 'matrix') renderMatrix(container, values);
        else if (kind === 'range') renderRange(container, values);
        if (kind !== 'menu') renderPlayhead(container);
        if (kind === 'menu') {
            const select = container.querySelector('select');
            if (select && values.length && document.activeElement !== select) select.value = String(values[0]);
        }
    }

    let active = null;

    function begin(container, event) {
        const kind = container.dataset.kind;
        const values = valuesOf(container);
        if (kind === 'multislider') {
            const p = multisliderPoint(container, event);
            values[p.index] = p.value;
            active = { container, kind, values, last: p };
        } else if (kind === 'matrix') {
            const i = matrixCellAt(container, event);
            if (i < 0) return false;
            const states = num(container, 'states', 2);
            const paint = (Math.round(values[i] || 0) + 1) % states;
            values[i] = paint;
            active = { container, kind, values, paint, x: event.clientX, y: event.clientY };
        } else if (kind === 'range' && isPolar(container)) {
            const min = num(container, 'min', 0);
            const span = (num(container, 'max', 1) - min) || 1;
            const t = rangeT(container, event);
            const t0 = turn((values[0] - min) / span);
            const t1 = turn((values[1] - min) / span);
            const d0 = Math.min(clockwise(t, t0), clockwise(t0, t));
            const d1 = Math.min(clockwise(t, t1), clockwise(t1, t));
            let mode;
            if (Math.min(d0, d1) <= 0.04) mode = d0 <= d1 ? 0 : 1;
            else if (clockwise(t0, t) < clockwise(t0, t1)) mode = 'both';
            else mode = d0 <= d1 ? 0 : 1;
            active = { container, kind, values, mode, start: t, origin: [t0, t1] };
            if (mode !== 'both') values[mode] = polarRangeValue(container, t);
        } else if (kind === 'range') {
            const v = rangeValueAt(container, event);
            const grab = rangeGrab(container);
            let mode;
            if (Math.abs(v - values[0]) <= grab && Math.abs(v - values[0]) <= Math.abs(v - values[1])) mode = 0;
            else if (Math.abs(v - values[1]) <= grab) mode = 1;
            else if (v > values[0] && v < values[1]) mode = 'both';
            else mode = v < values[0] ? 0 : 1;
            active = { container, kind, values, mode, start: v, origin: values.slice() };
            if (mode !== 'both') values[mode] = quantize(container, v);
        } else {
            return false;
        }
        update(active.values);
        return true;
    }

    function update(values) {
        render(active.container, values);
        if (active.kind === 'multislider') renderMultislider(active.container, values, active.last.index);
        send(active.container, values.slice());
    }

    function move(event) {
        if (!active) return;
        event.preventDefault();
        const { container, kind, values } = active;
        if (kind === 'multislider') {
            // Fill every bar between the last point and this one, so a fast sweep leaves no gaps.
            const p = multisliderPoint(container, event);
            const a = active.last;
            const n = values.length;
            let d = p.index - a.index;
            // On the dial the last bar sits next to the first: cross the top the short way.
            if (isPolar(container) && Math.abs(d) > n / 2) d -= Math.sign(d) * n;
            const steps = Math.abs(d);
            for (let k = 0; k <= steps; k++) {
                const t = steps === 0 ? 1 : k / steps;
                const i = (((a.index + Math.round(d * t)) % n) + n) % n;
                values[i] = quantize(container, a.value + (p.value - a.value) * t);
            }
            active.last = p;
        } else if (kind === 'matrix') {
            // Paint every cell the pointer crossed since the last event, every 3 px along its path,
            // so a fast stroke leaves no gaps.
            const dx = event.clientX - active.x;
            const dy = event.clientY - active.y;
            const n = Math.max(1, Math.ceil(Math.hypot(dx, dy) / 3));
            let changed = false;
            for (let k = 1; k <= n; k++) {
                const i = matrixCellAtPoint(container, active.x + (dx * k) / n, active.y + (dy * k) / n);
                if (i >= 0 && values[i] !== active.paint) { values[i] = active.paint; changed = true; }
            }
            active.x = event.clientX;
            active.y = event.clientY;
            if (!changed) return;
        } else if (kind === 'range' && isPolar(container)) {
            // Nothing clamps and nothing swaps: on a circle every position is reachable from either end.
            const t = rangeT(container, event);
            if (active.mode === 'both') {
                const d = t - active.start;
                values[0] = polarRangeValue(container, active.origin[0] + d);
                values[1] = polarRangeValue(container, active.origin[1] + d);
            } else {
                values[active.mode] = polarRangeValue(container, t);
            }
        } else if (kind === 'range') {
            const v = rangeValueAt(container, event);
            if (active.mode === 'both') {
                const min = num(container, 'min', 0);
                const max = num(container, 'max', 1);
                const width = active.origin[1] - active.origin[0];
                let lo = active.origin[0] + (v - active.start);
                lo = Math.min(Math.max(lo, Math.min(min, max)), Math.max(min, max) - width);
                values[0] = quantize(container, lo);
                values[1] = quantize(container, lo + width);
            } else {
                values[active.mode] = quantize(container, v);
                if (values[0] > values[1]) {
                    // Crossing over swaps which thumb is held, as rslider does.
                    values.reverse();
                    active.mode = 1 - active.mode;
                }
            }
        }
        update(values);
    }

    function end() {
        if (!active) return;
        const { container, kind } = active;
        if (kind === 'multislider') renderMultislider(container, active.values);
        active = null;
        window.removeEventListener('pointermove', move);
    }

    document.addEventListener('pointerdown', (event) => {
        const visual = event.target.closest?.('.field-array .array-visual');
        if (!visual || event.button > 0) return;
        const container = visual.closest('.field-array');
        if (!container || !begin(container, event)) return;
        event.preventDefault();
        event.stopPropagation();
        window.addEventListener('pointermove', move);
    }, true);
    window.addEventListener('pointerup', end);
    window.addEventListener('pointercancel', end);
    window.addEventListener('blur', end);

    document.addEventListener('change', (event) => {
        const select = event.target.closest?.('.field-menu-select');
        if (!select) return;
        const container = select.closest('.field-array');
        send(container, [Number(select.value)]);
    }, true);

    function refresh(root) {
        if (root.matches?.('.field-array')) render(root);
        root.querySelectorAll?.('.field-array').forEach((c) => render(c));
    }

    // Changes made on another surface, or by a ControlValueHandle.
    const kinds = new Set(['multislider', 'matrix', 'range', 'menu']);
    const previousApply = window.applyFieldControlUpdate;
    window.applyFieldControlUpdate = function (control) {
        if (control && kinds.has(String(control.type))) {
            const id = String(control.id);
            const container = document.querySelector(`.field-array[data-control-id="${escapeId(id)}"]`);
            if (!container) return;
            if (control.value != null) {
                container.dataset.playhead = String(control.value);
                renderPlayhead(container);
            }
            // A value from elsewhere must not fight the gesture this browser is making.
            if (active?.container === container) return;
            if (control.text != null && control.text !== container.dataset.values) {
                container.dataset.values = String(control.text);
                render(container);
            }
            return;
        }
        if (typeof previousApply === 'function') previousApply(control);
    };

    // For a page that builds these controls itself (the phone): redraw one, or move its playhead,
    // without going through applyFieldControlUpdate.
    window.FieldArrayControls = {
        render,
        setPlayhead(container, position) {
            container.dataset.playhead = String(position);
            renderPlayhead(container);
        },
        isActive: (container) => active?.container === container,
    };

    function start() {
        refresh(document);
        if (window.MutationObserver) {
            new MutationObserver((mutations) => mutations.forEach((m) =>
                m.addedNodes.forEach((node) => { if (node.nodeType === 1) refresh(node); })
            )).observe(document.body, { childList: true, subtree: true });
        }
    }
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start);
    else start();
})();
