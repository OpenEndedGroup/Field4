// Multislider, matrix, range slider and menu controls (afield/visualizers.kt).
// Each container carries its whole value in data-values; a change sends it all as `array-update`.
// Loaded after sliders.js, whose sendViaAfield and cssEscape it uses.

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

    // Sending: at most one message per SEND_INTERVAL_MS during a gesture, and always the last one.
    const pending = new Map();
    function send(container, values) {
        container.dataset.values = values.join(',');
        if (container.dataset.send === 'false') return;
        const id = container.dataset.controlId;
        const entry = pending.get(id);
        if (entry) { entry.values = values; return; }
        const flush = () => {
            const e = pending.get(id);
            pending.delete(id);
            if (e) sendViaAfield('array-update', { controlId: id, values: e.values });
        };
        pending.set(id, { values });
        setTimeout(flush, SEND_INTERVAL_MS);
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
                const r = document.createElementNS('http://www.w3.org/2000/svg', 'rect');
                r.setAttribute('class', 'multislider-bar');
                svg.appendChild(r);
            }
        }
        const span = max - min || 1;
        svg.querySelectorAll('.multislider-bar').forEach((r, i) => {
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
        if (grid.childElementCount !== values.length) {
            grid.innerHTML = '';
            values.forEach((_, i) => {
                const cell = document.createElement('div');
                cell.className = 'matrix-cell';
                cell.dataset.index = String(i);
                grid.appendChild(cell);
            });
        }
        Array.from(grid.children).forEach((cell, i) => cell.classList.toggle('on', values[i] >= 0.5));
        const readout = container.querySelector('.array-readout');
        if (readout) readout.textContent = `${values.filter((v) => v >= 0.5).length} on`;
    }

    function matrixCellAt(container, event) {
        const el = document.elementFromPoint(event.clientX, event.clientY);
        const cell = el?.closest?.('.matrix-cell');
        return cell && container.contains(cell) ? Number(cell.dataset.index) : -1;
    }

    // Range slider ---------------------------------------------------------

    function renderRange(container, values) {
        const min = num(container, 'min', 0);
        const max = num(container, 'max', 1);
        const span = max - min || 1;
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

    function rangeValueAt(container, event) {
        const rect = container.querySelector('.range-visual').getBoundingClientRect();
        const min = num(container, 'min', 0);
        const max = num(container, 'max', 1);
        const t = Math.min(Math.max((event.clientX - rect.left) / Math.max(rect.width, 1), 0), 1);
        return min + t * (max - min);
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
            const mark = container.querySelector('.range-playhead');
            if (!mark) return;
            const min = num(container, 'min', 0);
            const max = num(container, 'max', 1);
            const t = (p - min) / ((max - min) || 1);
            const visible = Number.isFinite(p) && t >= 0 && t <= 1;
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
            const paint = values[i] >= 0.5 ? 0 : 1;
            values[i] = paint;
            active = { container, kind, values, paint };
        } else if (kind === 'range') {
            const v = rangeValueAt(container, event);
            const min = num(container, 'min', 0);
            const max = num(container, 'max', 1);
            const rect = container.querySelector('.range-visual').getBoundingClientRect();
            const grab = ((max - min) * 8) / Math.max(rect.width, 1); // 8 px either side of a thumb
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
            const steps = Math.abs(p.index - a.index);
            for (let k = 0; k <= steps; k++) {
                const t = steps === 0 ? 1 : k / steps;
                const i = Math.round(a.index + (p.index - a.index) * t);
                values[i] = quantize(container, a.value + (p.value - a.value) * t);
            }
            active.last = p;
        } else if (kind === 'matrix') {
            const i = matrixCellAt(container, event);
            if (i < 0 || values[i] === active.paint) return;
            values[i] = active.paint;
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
            const container = document.querySelector(`.field-array[data-control-id="${cssEscape(id)}"]`);
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
