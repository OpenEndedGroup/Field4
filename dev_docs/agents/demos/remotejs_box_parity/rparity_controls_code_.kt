// The same output channel and controls a Kotlin box draws, calling back into the head.
const state = {freq: 3.0, square: false}

function redraw() {
  display("main", `<b>${state.square ? "square" : "sine"}</b> at ${state.freq.toFixed(1)} Hz`)
}

slider("frequency", v => { state.freq = v; redraw() }, {min: 1, max: 10, step: 0.1, value: 3.0})
checkbox("square wave", v => { state.square = v; redraw() })
redraw()
