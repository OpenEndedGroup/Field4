import afield.audio.modal.*

// The flower drum as a live voice in the audio engine: it strikes itself every 1.5 s.
// Call drum.strike(x, y) from any box or callback to hit it somewhere else.
val modes = polarDrumMesh(0.06) { t -> 1 + 0.3 * cos(5 * t) }.modes(16)
val drum = ModalDrum(modes, fundamental = 110.0, every = 1.5, autoX = 0.35, autoY = 0.1)
drum
