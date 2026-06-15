
Tone = window.Tone

// we create this once the audio has started
var osc = null

// we have to start the audio inside an event handler
// otherwise the browser thinks we're an ad, trying
// to make noise without provocation
mouseDown.startAudio = () => {
    // if we haven't make an oscilator
    if (!osc)
    {
        Tone.start().then( () => {
            print(" started ")

            // a new oscilator
            osc = new Tone.Oscillator().toDestination()
            osc.start()
        })
    }
}

mouseMove.wiggle = (event) => {
    // if we've made an oscilator
    if (osc)
    {
        //event.ray.origin.y is in meters off the ground
        osc.frequency.value = event.ray.origin.y*800+40
    }
}
