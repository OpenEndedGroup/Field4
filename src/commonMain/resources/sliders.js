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

function initSliders(root = document) {
    root.querySelectorAll('.field-slider').forEach((container) => {
        if (container.dataset.wired === 'true') return;
        container.dataset.wired = 'true';
        const svg = container.querySelector('.field-slider-visual');
        if (!svg) return;
        svg.addEventListener('pointerdown', (evt) => startSliderDrag(container, evt));
        // Ensure visuals match initial value
        const initial = parseFloat(container.dataset.value);
        if (!Number.isNaN(initial)) {
            updateSlider(container, initial, { send: false });
        }
    });
}

// Vec2 (2D) ---------------------------------------------------------------
const vec2State = { active: null };

function vec2ValueFromPointer(container, event) {
    const svg = container.querySelector('.vec2-visual');
    if (!svg) return null;
    const rect = svg.getBoundingClientRect();
    const viewSize = parseFloat(container.dataset.viewSize || '240');
    const padding = parseFloat(container.dataset.padding || '14');
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

    const svg = container.querySelector('.vec2-visual');
    if (svg) {
        const handle = svg.querySelector('.vec2-handle');
        if (handle) {
            handle.setAttribute('cx', cx);
            handle.setAttribute('cy', cy);
        }
    }

    const xSpan = document.getElementById(`${controlId}-x-value`);
    const ySpan = document.getElementById(`${controlId}-y-value`);
    const composite = document.getElementById(`${controlId}-value`);
    const fx = fmt(nx, decimals);
    const fy = fmt(ny, decimals);
    if (xSpan) xSpan.textContent = fx;
    if (ySpan) ySpan.textContent = fy;
    if (composite) composite.textContent = `(${fx}, ${fy})`;

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

function initVec2(root = document) {
    root.querySelectorAll('.field-vec2').forEach((container) => {
        if (container.dataset.wired === 'true') return;
        container.dataset.wired = 'true';
        const svg = container.querySelector('.vec2-visual');
        if (!svg) return;
        svg.addEventListener('pointerdown', (evt) => startVec2Drag(container, evt));
        const x = parseFloat(container.dataset.x || '0');
        const y = parseFloat(container.dataset.y || '0');
        updateVec2(container, x, y, { send: false });
    });
}

// Auto-wire any controls that appear on the page
function wireControls(root = document) {
    initSliders(root);
    initVec2(root);
}

document.addEventListener('DOMContentLoaded', () => {
    wireControls(document);
    if (window.MutationObserver) {
        const observer = new MutationObserver((mutations) => {
            mutations.forEach((mutation) => {
                mutation.addedNodes.forEach((node) => {
                    if (node.nodeType === 1) {
                        wireControls(node);
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
        sendViaAfield('color-update', {
            controlId: controlId,
            r: r,
            g: g,
            b: b,
            a: alpha
        });
    }
};
