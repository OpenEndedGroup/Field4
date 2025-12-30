// Slider support for Field4
// Sends slider value updates back to the server

window.sendSliderUpdate = function(sliderId, value) {
    if (typeof afield !== 'undefined' && typeof afield.send === 'function') {
        afield.send('slider-update', {
            sliderId: sliderId,
            value: value
        });
    } else {
        console.warn('afield.send not available yet for slider updates');
    }
};

// Handle slider input events
window.handleSliderInput = function(sliderElement) {
    const sliderId = sliderElement.id;
    const value = parseFloat(sliderElement.value);
    const decimalPlaces = parseInt(sliderElement.dataset.decimalPlaces || '0');
    
    // Update the value display
    const valueDisplay = document.getElementById(sliderId + '-value');
    if (valueDisplay) {
        valueDisplay.textContent = value.toFixed(decimalPlaces);
    }
    
    // Send update to server
    window.sendSliderUpdate(sliderId, value);
};

// Vec2 control support
window.handleVec2Input = function(sliderElement) {
    const controlId = sliderElement.dataset.controlId;
    const axis = sliderElement.dataset.axis;
    const value = parseFloat(sliderElement.value);
    const decimalPlaces = parseInt(sliderElement.dataset.decimalPlaces || '0');
    
    // Update the value display for this axis
    const valueDisplay = document.getElementById(controlId + '-' + axis + '-value');
    if (valueDisplay) {
        valueDisplay.textContent = value.toFixed(decimalPlaces);
    }
    
    // Get both x and y values
    const xSlider = document.getElementById(controlId + '-x');
    const ySlider = document.getElementById(controlId + '-y');
    
    if (xSlider && ySlider && typeof afield !== 'undefined' && typeof afield.send === 'function') {
        afield.send('vec2-update', {
            controlId: controlId,
            x: parseFloat(xSlider.value),
            y: parseFloat(ySlider.value)
        });
    }
};

// Checkbox control support
window.handleCheckboxInput = function(checkboxElement) {
    const controlId = checkboxElement.id;
    const value = checkboxElement.checked;
    
    if (typeof afield !== 'undefined' && typeof afield.send === 'function') {
        afield.send('checkbox-update', {
            controlId: controlId,
            value: value
        });
    }
};

// String input control support
window.handleStringInput = function(inputElement) {
    const controlId = inputElement.id;
    const value = inputElement.value;
    
    if (typeof afield !== 'undefined' && typeof afield.send === 'function') {
        afield.send('string-update', {
            controlId: controlId,
            value: value
        });
    }
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
    
    if (typeof afield !== 'undefined' && typeof afield.send === 'function') {
        afield.send('color-update', {
            controlId: controlId,
            r: r,
            g: g,
            b: b,
            a: a
        });
    }
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
        
        if (typeof afield !== 'undefined' && typeof afield.send === 'function') {
            afield.send('color-update', {
                controlId: controlId,
                r: r,
                g: g,
                b: b,
                a: alpha
            });
        }
    }
};
