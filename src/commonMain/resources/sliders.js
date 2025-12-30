// Slider support for Field4
// Sends slider value updates back to the server

let afieldModulePromise = null;

function getAfield() {
    if (typeof afield !== 'undefined' && typeof afield.send === 'function') {
        return Promise.resolve(afield);
    }

    if (!afieldModulePromise) {
        afieldModulePromise = import("./afield.mjs").catch((err) => {
            console.warn('Failed to load afield.mjs for slider updates', err);
            afieldModulePromise = null;
            throw err;
        });
    }

    return afieldModulePromise;
}

function sendViaAfield(type, payload) {
    getAfield()
        .then((afieldModule) => {
            if (afieldModule && typeof afieldModule.send === 'function') {
                afieldModule.send(type, payload);
            } else {
                console.warn('afield.send not available yet for slider updates');
            }
        })
        .catch((err) => {
            console.warn(`Unable to send ${type}`, err);
        });
}

window.sendSliderUpdate = function(sliderId, value) {
    sendViaAfield('slider-update', {
        sliderId: sliderId,
        value: value
    });
};

// Small helpers for the new vector UI
const clamp = (v, min, max) => Math.min(Math.max(v, min), max);
const snap = (v, step, min) => {
    if (!step || step <= 0) return v;
    const offset = isNaN(min) ? 0 : min;
    return Math.round((v - offset) / step) * step + offset;
};
const fmt = (v, decimals) => {
    const d = Number.isInteger(decimals) ? decimals : 0;
    return isFinite(v) ? Number(v).toFixed(d) : '—';
};

// Slider (1D) ---------------------------------------------------------------
const sliderState = { active: null };

function sliderValueFromPointer(container, event) {
    const svg = container.querySelector('.field-slider-visual');
    if (!svg) return null;

    const rect = svg.getBoundingClientRect();
    const viewWidth = parseFloat(container.dataset.viewWidth || '320');
    const padding = parseFloat(container.dataset.padding || '14');
    const min = parseFloat(container.dataset.min);
    const max = parseFloat(container.dataset.max);

    const viewX = ((event.clientX - rect.left) / rect.width) * viewWidth;
    const t = clamp((viewX - padding) / (viewWidth - padding * 2), 0, 1);
    return min + t * (max - min);
}

function updateSlider(container, value, { send = false } = {}) {
    const min = parseFloat(container.dataset.min);
    const max = parseFloat(container.dataset.max);
    const step = parseFloat(container.dataset.step || '0');
    const decimals = parseInt(container.dataset.decimalPlaces || '0', 10);
    const sliderId = container.dataset.sliderId;
    const viewWidth = parseFloat(container.dataset.viewWidth || '320');
    const padding = parseFloat(container.dataset.padding || '14');

    let v = clamp(value, min, max);
    v = snap(v, step, min);
    container.dataset.value = v;

    const trackLen = Math.max(viewWidth - padding * 2, 1);
    const normalized = max === min ? 0 : (v - min) / (max - min);
    const cx = padding + trackLen * normalized;

    const svg = container.querySelector('.field-slider-visual');
    if (svg) {
        const thumb = svg.querySelector('.slider-thumb');
        const thumbDot = svg.querySelector('.slider-thumb-dot');
        const fill = svg.querySelector('.slider-fill');
        if (thumb) thumb.setAttribute('cx', cx);
        if (thumbDot) thumbDot.setAttribute('cx', cx);
        if (fill) fill.setAttribute('x2', cx);
    }

    const valueDisplay = document.getElementById(`${sliderId}-value`);
    if (valueDisplay) {
        valueDisplay.textContent = fmt(v, decimals);
    }

    if (send && sliderId) {
        window.sendSliderUpdate(sliderId, v);
    }
}

function startSliderDrag(container, event) {
    event.preventDefault();
    if (event.target?.setPointerCapture && event.pointerId !== undefined) {
        try { event.target.setPointerCapture(event.pointerId); } catch (_) {}
    }
    const v = sliderValueFromPointer(container, event);
    if (v !== null) {
        updateSlider(container, v, { send: true });
    }
    sliderState.active = container;
    window.addEventListener('pointermove', dragSliderMove);
    window.addEventListener('pointerup', endSliderDrag);
}

function dragSliderMove(event) {
    if (!sliderState.active) return;
    const v = sliderValueFromPointer(sliderState.active, event);
    if (v !== null) {
        updateSlider(sliderState.active, v, { send: true });
    }
}

function endSliderDrag() {
    sliderState.active = null;
    window.removeEventListener('pointermove', dragSliderMove);
    window.removeEventListener('pointerup', endSliderDrag);
}

// Vec2 (2D) ---------------------------------------------------------------
const vec2State = { active: null };

function vec2ValueFromPointer(container, event) {
    const svg = container.querySelector('.vec2-visual');
    if (!svg) return null;
    const rect = svg.getBoundingClientRect();
    const viewSize = parseFloat(container.dataset.viewSize || '240');
    const padding = parseFloat(container.dataset.padding || '10');
    const area = Math.max(viewSize - padding * 2, 1);

    const minX = parseFloat(container.dataset.minX);
    const maxX = parseFloat(container.dataset.maxX);
    const minY = parseFloat(container.dataset.minY);
    const maxY = parseFloat(container.dataset.maxY);

    const viewX = ((event.clientX - rect.left) / rect.width) * viewSize;
    const viewY = ((event.clientY - rect.top) / rect.height) * viewSize;

    const tx = clamp((viewX - padding) / area, 0, 1);
    const ty = clamp((viewY - padding) / area, 0, 1);

    const x = minX + tx * (maxX - minX);
    const y = maxY - ty * (maxY - minY); // invert so +Y is up
    return { x, y };
}

function updateVec2(container, x, y, { send = false } = {}) {
    const minX = parseFloat(container.dataset.minX);
    const maxX = parseFloat(container.dataset.maxX);
    const minY = parseFloat(container.dataset.minY);
    const maxY = parseFloat(container.dataset.maxY);
    const step = parseFloat(container.dataset.step || '0');
    const decimals = parseInt(container.dataset.decimalPlaces || '0', 10);
    const viewSize = parseFloat(container.dataset.viewSize || '240');
    const padding = parseFloat(container.dataset.padding || '14');
    const controlId = container.dataset.controlId;
    const area = Math.max(viewSize - padding * 2, 1);

    let nx = clamp(x, minX, maxX);
    let ny = clamp(y, minY, maxY);
    nx = snap(nx, step, minX);
    ny = snap(ny, step, minY);

    container.dataset.x = nx;
    container.dataset.y = ny;

    const cx = padding + area * ((nx - minX) / (maxX - minX || 1));
    const cy = padding + area * (1 - (ny - minY) / (maxY - minY || 1));
    const center = viewSize / 2;

    const svg = container.querySelector('.vec2-visual');
    if (svg) {
        const handle = svg.querySelector('.vec2-handle');
        const line = svg.querySelector('.vec2-line');
        const arc = svg.querySelector('.vec2-arc');
        const angleLabelSvg = svg.querySelector('.vec2-angle-label');
        const magLabelSvg = svg.querySelector('.vec2-mag-label');
        if (handle) {
            handle.setAttribute('cx', cx);
            handle.setAttribute('cy', cy);
        }
        if (line) {
            line.setAttribute('x1', center);
            line.setAttribute('y1', center);
            line.setAttribute('x2', cx);
            line.setAttribute('y2', cy);
        }
        if (arc) {
            const dx = cx - center;
            const dy = cy - center;
            const angle = Math.atan2(dy, dx);
            const mag = Math.sqrt(dx * dx + dy * dy);
            const arcR = Math.min(Math.max(10, mag * 0.35), 28);
            const startX = center + arcR;
            const startY = center;
            const endX = center + arcR * Math.cos(angle);
            const endY = center + arcR * Math.sin(angle);
            const largeArc = Math.abs(angle) > Math.PI ? 1 : 0;
            const sweep = angle >= 0 ? 1 : 0;
            const d = `M ${startX} ${startY} A ${arcR} ${arcR} 0 ${largeArc} ${sweep} ${endX} ${endY}`;
            arc.setAttribute('d', d);
        }
        if (angleLabelSvg) {
            const dx = cx - center;
            const dy = cy - center;
            const angle = Math.atan2(dy, dx);
            const arcR = Math.min(Math.max(10, Math.sqrt(dx * dx + dy * dy) * 0.35), 28);
            const midA = angle / 2;
            const lx = center + (arcR + 6) * Math.cos(midA);
            const ly = center + (arcR + 6) * Math.sin(midA);
            angleLabelSvg.setAttribute('x', lx);
            angleLabelSvg.setAttribute('y', ly);
            angleLabelSvg.textContent = `${Math.atan2(dy, dx) * 180 / Math.PI >= 0 ? '' : ''}${(Math.atan2(dy, dx) * 180 / Math.PI).toFixed(0)}°`;
            angleLabelSvg.setAttribute('text-anchor', 'middle');
        }
        if (magLabelSvg) {
            const mx = (center + cx) / 2;
            const my = (center + cy) / 2;
            const dx = cx - center;
            const dy = cy - center;
            const angleDeg = Math.atan2(dy, dx) * 180 / Math.PI;
            const perpGap = 10; // push label slightly off the line
            const norm = Math.sqrt(dx * dx + dy * dy) || 1;
            const offsetX = (-dy / norm) * perpGap;
            const offsetY = (dx / norm) * perpGap;
            const lx = mx + offsetX;
            const ly = my + offsetY;
            magLabelSvg.setAttribute('x', lx);
            magLabelSvg.setAttribute('y', ly);
            magLabelSvg.setAttribute('text-anchor', 'middle');
            magLabelSvg.setAttribute('transform', `rotate(${angleDeg} ${lx} ${ly})`);
            magLabelSvg.textContent = `|v| ${Math.sqrt(nx * nx + ny * ny).toFixed(2)}`;
        }
    }

        const xSpan = document.getElementById(`${controlId}-x-value`);
        const ySpan = document.getElementById(`${controlId}-y-value`);
        const composite = document.getElementById(`${controlId}-value`);
        const angleSpan = document.getElementById(`${controlId}-angle`);
        const magSpan = document.getElementById(`${controlId}-mag`);
        const fx = fmt(nx, decimals);
        const fy = fmt(ny, decimals);
        const angle = Math.atan2(ny, nx) * 180 / Math.PI;
        const mag = Math.sqrt(nx * nx + ny * ny);
    if (xSpan) xSpan.textContent = fx;
    if (ySpan) ySpan.textContent = fy;
    if (composite) composite.textContent = `(${fx}, ${fy})`;
    if (angleSpan) angleSpan.textContent = `θ ${angle.toFixed(0)}°`;
    if (magSpan) magSpan.textContent = `|v| ${mag.toFixed(2)}`;

    if (send && controlId) {
        sendViaAfield('vec2-update', {
            controlId: controlId,
            x: nx,
            y: ny
        });
    }
}

function startVec2Drag(container, event) {
    event.preventDefault();
    if (event.target?.setPointerCapture && event.pointerId !== undefined) {
        try { event.target.setPointerCapture(event.pointerId); } catch (_) {}
    }
    const next = vec2ValueFromPointer(container, event);
    if (next) {
        updateVec2(container, next.x, next.y, { send: true });
    }
    vec2State.active = container;
    window.addEventListener('pointermove', dragVec2Move);
    window.addEventListener('pointerup', endVec2Drag);
}

function dragVec2Move(event) {
    if (!vec2State.active) return;
    const next = vec2ValueFromPointer(vec2State.active, event);
    if (next) {
        updateVec2(vec2State.active, next.x, next.y, { send: true });
    }
}

function endVec2Drag() {
    vec2State.active = null;
    window.removeEventListener('pointermove', dragVec2Move);
    window.removeEventListener('pointerup', endVec2Drag);
}

function refreshControls(root = document) {
    root.querySelectorAll('.field-slider').forEach((container) => {
        const initial = parseFloat(container.dataset.value);
        if (!Number.isNaN(initial)) {
            updateSlider(container, initial, { send: false });
        }
    });
    root.querySelectorAll('.field-vec2').forEach((container) => {
        const x = parseFloat(container.dataset.x || '0');
        const y = parseFloat(container.dataset.y || '0');
        updateVec2(container, x, y, { send: false });
    });
    root.querySelectorAll('.field-color').forEach((container) => {
        const alphaSlider = container.querySelector('.alpha-slider');
        if (alphaSlider) {
            const alpha = parseFloat(alphaSlider.value);
            updateAlphaVisual(alphaSlider, alpha);
        }
    });
}

function addDelegatedListeners() {
    const pointerHandler = (evt) => {
        const sliderVis = evt.target.closest?.('.field-slider-visual');
        if (sliderVis) {
            const container = sliderVis.closest('.field-slider');
            if (container) startSliderDrag(container, evt);
            return;
        }
        const alphaVis = evt.target.closest?.('.alpha-visual');
        if (alphaVis) {
            startAlphaDrag(alphaVis, evt);
            return;
        }
        const vec2Vis = evt.target.closest?.('.vec2-visual');
        if (vec2Vis) {
            const container = vec2Vis.closest('.field-vec2');
            if (container) startVec2Drag(container, evt);
        }
    };
    document.addEventListener('pointerdown', pointerHandler, true);
    document.addEventListener('mousedown', pointerHandler, true);
    document.addEventListener('touchstart', (evt) => pointerHandler(evt), { passive: false, capture: true });
}

document.addEventListener('DOMContentLoaded', () => {
    refreshControls(document);
    addDelegatedListeners();
    if (window.MutationObserver) {
        const observer = new MutationObserver((mutations) => {
            mutations.forEach((mutation) => {
                mutation.addedNodes.forEach((node) => {
                    if (node.nodeType === 1) {
                        refreshControls(node);
                    }
                });
            });
        });
        observer.observe(document.body, { childList: true, subtree: true });
    }
});

// Checkbox control support
window.handleCheckboxInput = function(checkboxElement) {
    const controlId = checkboxElement.id;
    const value = checkboxElement.checked;
    
    sendViaAfield('checkbox-update', {
        controlId: controlId,
        value: value
    });
};

// String input control support
window.handleStringInput = function(inputElement) {
    const controlId = inputElement.id;
    const value = inputElement.value;
    
    sendViaAfield('string-update', {
        controlId: controlId,
        value: value
    });
};

// Color picker control support
window.handleColorInput = function(colorElement) {
    const controlId = colorElement.id;
    const hexColor = colorElement.value;
    
    // Parse hex color to RGB (normalized 0-1)
    const r = parseInt(hexColor.slice(1, 3), 16) / 255.0;
    const g = parseInt(hexColor.slice(3, 5), 16) / 255.0;
    const b = parseInt(hexColor.slice(5, 7), 16) / 255.0;
    
    // Get alpha value from slider
    const alphaSlider = document.getElementById(controlId + '-alpha');
    const a = alphaSlider ? parseFloat(alphaSlider.value) : 1.0;
    
    // Update the value display
    const valueDisplay = document.getElementById(controlId + '-value');
    if (valueDisplay) {
        valueDisplay.textContent = `rgba(${r.toFixed(2)}, ${g.toFixed(2)}, ${b.toFixed(2)}, ${a.toFixed(2)})`;
    }
    sendViaAfield('color-update', {
        controlId: controlId,
        r: r,
        g: g,
        b: b,
        a: a
    });
};

// Color alpha slider support
window.handleColorAlphaInput = function(alphaSlider) {
    const controlId = alphaSlider.dataset.controlId;
    const alpha = parseFloat(alphaSlider.value);
    
    // Update alpha value display
    const alphaDisplay = document.getElementById(controlId + '-alpha-value');
    if (alphaDisplay) {
        alphaDisplay.textContent = alpha.toFixed(2);
    }
    
    // Get RGB values from color picker
    const colorPicker = document.getElementById(controlId);
    if (colorPicker) {
        const hexColor = colorPicker.value;
        const r = parseInt(hexColor.slice(1, 3), 16) / 255.0;
        const g = parseInt(hexColor.slice(3, 5), 16) / 255.0;
        const b = parseInt(hexColor.slice(5, 7), 16) / 255.0;
        
        // Update the main value display
        const valueDisplay = document.getElementById(controlId + '-value');
        if (valueDisplay) {
            valueDisplay.textContent = `rgba(${r.toFixed(2)}, ${g.toFixed(2)}, ${b.toFixed(2)}, ${alpha.toFixed(2)})`;
        }
        updateAlphaVisual(alphaSlider, alpha);
        sendViaAfield('color-update', {
            controlId: controlId,
            r: r,
            g: g,
            b: b,
            a: alpha
        });
    }
};

function updateAlphaVisual(alphaSlider, alpha) {
    let svg = null;
    if (alphaSlider.previousElementSibling) {
        svg = alphaSlider.previousElementSibling.querySelector?.('.alpha-visual');
    }
    if (!svg) {
        svg = alphaSlider.closest('.field-color')?.querySelector('.alpha-visual');
    }
    if (!svg) return;

    const padding = parseFloat(svg.dataset.padding || '14');
    const viewWidth = svg.viewBox?.baseVal?.width || parseFloat(svg.dataset.viewWidth || '320');
    const viewHeight = svg.viewBox?.baseVal?.height || parseFloat(svg.dataset.viewHeight || '32');
    const centerY = viewHeight / 2;
    const trackLen = Math.max(viewWidth - padding * 2, 1);
    const clamped = clamp(alpha, 0, 1);
    const cx = padding + trackLen * clamped;

    const track = svg.querySelector('.alpha-track');
    const fill = svg.querySelector('.alpha-fill');
    const thumb = svg.querySelector('.alpha-thumb');
    const dot = svg.querySelector('.alpha-thumb-dot');
    if (track) {
        track.setAttribute('x1', padding);
        track.setAttribute('x2', viewWidth - padding);
        track.setAttribute('y1', centerY);
        track.setAttribute('y2', centerY);
    }
    if (fill) {
        fill.setAttribute('x1', padding);
        fill.setAttribute('x2', cx);
        fill.setAttribute('y1', centerY);
        fill.setAttribute('y2', centerY);
    }
    if (thumb) {
        thumb.setAttribute('cx', cx);
        thumb.setAttribute('cy', centerY);
    }
    if (dot) {
        dot.setAttribute('cx', cx);
        dot.setAttribute('cy', centerY);
    }
}

// Alpha slider (SVG) -------------------------------------------------------
function alphaValueFromPointer(svg, event) {
    const rect = svg.getBoundingClientRect();
    const viewWidth = svg.viewBox?.baseVal?.width || parseFloat(svg.dataset.viewWidth || '320');
    const padding = parseFloat(svg.dataset.padding || '14');
    const viewX = ((event.clientX - rect.left) / rect.width) * viewWidth;
    const t = clamp((viewX - padding) / (viewWidth - padding * 2), 0, 1);
    return t;
}

function startAlphaDrag(svg, event) {
    event.preventDefault();
    if (event.target?.setPointerCapture && event.pointerId !== undefined) {
        try { event.target.setPointerCapture(event.pointerId); } catch (_) {}
    }
    const alphaSlider = svg.closest('.field-color')?.querySelector('.alpha-slider');
    if (!alphaSlider) return;
    const value = alphaValueFromPointer(svg, event);
    if (value != null) {
        alphaSlider.value = value;
        updateAlphaVisual(alphaSlider, value);
        window.handleColorAlphaInput(alphaSlider);
    }
    const move = (evt) => {
        const v = alphaValueFromPointer(svg, evt);
        if (v != null) {
            alphaSlider.value = v;
            updateAlphaVisual(alphaSlider, v);
            window.handleColorAlphaInput(alphaSlider);
        }
    };
    const end = () => {
        window.removeEventListener('pointermove', move);
        window.removeEventListener('pointerup', end);
    };
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', end);
}
