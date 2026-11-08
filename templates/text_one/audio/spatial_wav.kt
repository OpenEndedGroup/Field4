import afield.audio.synth.*

// Launch Reading space first, with the Quest connected and headset audio enabled.
// A mono resonant percussion WAV already shipped with Field: loop it as a fixed world emitter.
val sound = SpatialWavPlayer(
    "spatial-demo.wav".file,
    renderer.openXRHmdTarget!!.asHeadPoseSource(),
)
// Explicit output selection. If WiVRn is absent this fails rather than playing on the desktop.
try {
    sound.openWiVRn()
} catch (failure: Throwable) {
    sound.close()
    throw failure
}
prop<SpatialWavPlayer>(spatialWordSound) = sound
sound.place(-.6, 1.5, -1.5) // OpenXR stage/local coordinates: X right, Y up, -Z forward, metres
sound.gain = .2f
sound.looping = true
// Code API: spatialWordSound.play(), .pause(), .seek(seconds), .restart(), .place(x,y,z).
var x = -.6
var y = 1.5
var z = -1.5
controlGroup("head-tracked WAV") {
    button("Play WAV") { down -> if (down) sound.play() }
    button("Pause WAV") { down -> if (down) sound.pause() }
    button("Restart WAV") { down -> if (down) sound.restart() }
    button("Place at head") { down ->
        if (down) {
            val p = Vec3()
            if (renderer.openXRHmdTarget!!.headPose(p, Quat())) {
                x = p.x.toDouble(); y = p.y.toDouble(); z = p.z.toDouble()
                sound.place(x,y,z)
            }
        }
    }
    slider("Source X (m)", initialValue = -.6, min = -3.0, max = 3.0) { x = it; sound.place(x,y,z) }
    slider("Source Y (m)", initialValue = 1.5, min = 0.0, max = 3.0) { y = it; sound.place(x,y,z) }
    slider("Source Z (m)", initialValue = -1.5, min = -4.0, max = 2.0) { z = it; sound.place(x,y,z) }
    slider("Gain", initialValue = .2, min = 0.0, max = 1.0) { sound.gain = it.toFloat() }
    checkbox("Loop WAV", initialValue = true) { sound.looping = it }
}
var reported = 0L
loop {
    val now = System.nanoTime()
    if (now - reported > 500_000_000L) {
        reported = now
        display("audio status", "<div style='padding:12px;width:430px'>${if(sound.playing) "Playing" else "Paused"} · ${"%.2f".format(sound.positionSeconds)} / ${"%.2f".format(sound.durationSeconds)} s<br>Output: ${sound.outputDevice} · ${sound.sampleRate} Hz<br>Source: ($x, $y, $z) m · ${sound.renderedBlocks} binaural blocks</div>")
    }
    frame()
}.end { sound.close() }
