// Slider support for Field4
// Sends slider value updates back to the server

window.sendSliderUpdate = function(sliderId, value) {
    if (typeof afield !== 'undefined' && afield.send) {
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
