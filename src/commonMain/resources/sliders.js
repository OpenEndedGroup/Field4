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
