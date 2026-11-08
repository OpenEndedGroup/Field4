import afield.ui.output.ControlValueHandle

// A local path, with ~ expansion; changing this field alone does not reopen the decoder.
// The checkout ships an original stereo calibration movie; an environment override selects yours.
var selectedFile = System.getenv("FIELD_STEREO_VIDEO")
    ?: java.io.File("public/docs/assets/stereo_video/stereo-demo.mkv").absolutePath

var scrubber: ControlValueHandle<Double>? = null
var playingControl: ControlValueHandle<Boolean>? = null
var speedControl: ControlValueHandle<Double>? = null
var persistenceControl: ControlValueHandle<Double>? = null
var mixControl: ControlValueHandle<Double>? = null
var eyeSwapControl: ControlValueHandle<Boolean>? = null

// Rebuild only on file change, when duration changes. Handles keep playback ticks out of
// the document edit log; seeks pause, and an explicit Playing gesture resumes.
fun showTransport() {
    displayClear("transport")
    controlGroup("transport") {
        playingControl = checkbox("Playing", initialValue = movie.playing) { if (it) movie.play() else movie.pause() }
        scrubber = slider(
            "Seek (seconds)",
            min = 0.0,
            max = movie.duration.coerceAtLeast(.001),
            step = .01,
            initialValue = 0.0,
        ) {
                movie.seek(it)
            }
        speedControl = slider("Speed", min = .1, max = 8.0, step = .01, initialValue = movie.speed) {
            movie.speed = it
        }
        button("Material: unlimited") { down -> if (down) movie.materialFramesPerSecond = 0 }
        button("Material: 24 fps") { down -> if (down) movie.materialFramesPerSecond = 24 }
        button("Material: 12 fps") { down -> if (down) movie.materialFramesPerSecond = 12 }
        checkbox("Alternate frames above 4x", initialValue = movie.selectiveDecode) { movie.selectiveDecode = it }
        checkbox("Skip non-reference pictures", initialValue = movie.skipNonReferenceFrames) { movie.skipNonReferenceFrames = it }
        mixControl = slider("Accumulation mix", min = 0.0, max = 1.0, step = .001, initialValue = movie.accumulationMix.toDouble()) {
            movie.accumulationMix = it.toFloat()
        }
        persistenceControl = slider("Persistence half-life (s)", min = 0.0, max = 10.0, step = .01, initialValue = movie.accumulationHalfLife) {
            movie.accumulationHalfLife = it
        }
        slider("Incoming frame weight", min = 0.0, max = 4.0, step = .01, initialValue = movie.accumulationInputWeight) {
            movie.accumulationInputWeight = it
        }
        button("Clear accumulation") { down -> if (down) movie.clearAccumulation() }
        button("1x sharp") { down ->
            if (down) {
                movie.speed = 1.0
                movie.accumulationMix = 0f
                speedControl?.set(1.0)
                mixControl?.set(0.0)
            }
        }
        button("2x sharp") { down ->
            if (down) {
                movie.speed = 2.0
                movie.accumulationMix = 0f
                speedControl?.set(2.0)
                mixControl?.set(0.0)
            }
        }
        button("2x blur") { down ->
            if (down) {
                movie.speed = 2.0
                movie.accumulationMix = 1f
                movie.accumulationHalfLife = 4.0
                speedControl?.set(2.0)
                mixControl?.set(1.0)
                persistenceControl?.set(4.0)
                movie.play()
            }
        }
        button("8x blur") { down ->
            if (down) {
                movie.speed = 8.0
                movie.accumulationMix = 1f
                speedControl?.set(8.0)
                mixControl?.set(1.0)
                movie.play()
            }
        }
        button("Back to start") { down -> if (down) movie.seek(0.0) }
        eyeSwapControl = checkbox("Swap left and right", initialValue = movie.swapEyes) { movie.swapEyes = it }
        button("Swap eyes") { down ->
            if (down) {
                movie.swapEyes = !movie.swapEyes
                eyeSwapControl?.set(movie.swapEyes)
            }
        }
        checkbox("Flip vertically", initialValue = false) { movie.flipVertically = it }
    }
}

fun openMovie() {
    if (movie.load(selectedFile)) {
        aspectControl.set(movie.nativeAspect.toDouble())
        showTransport()
        movie.play()
    }
}

controlGroup("movie file") {
    stringInput("Full SBS file", initialValue = selectedFile) { selectedFile = it }
    button("Load movie") { down -> if (down) openMovie() }
}

// Rerunning the transport refreshes controls without reopening the playing movie.
if (!movie.hasFrame && selectedFile.isNotBlank()) openMovie() else showTransport()

displayOpen("movie file")
displayOpen("transport")
displayOpen("status")

// Render at the runtime cadence; send inexpensive transport/status updates at four Hz.
var lastStatus = 0L

loop {
    renderer.windows.forEach { it.pollEvents() }
    renderer.render()

    val now = System.nanoTime()
    if (now - lastStatus > 250_000_000L) {
        lastStatus = now
        scrubber?.set(movie.timestamp)
        playingControl?.set(movie.playing)

        val label = movie.error ?: movie.path ?: "Choose a full-resolution SBS movie, then Load movie"
        val safe = label.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        display(
            "status",
            "<div style='padding:12px;width:430px'>$safe<br>${movie.width} × ${movie.height} packed · native per-eye aspect ${movie.nativeAspect}<br>${movie.timestamp.f2} / ${movie.duration.f2} seconds · ${if (movie.playing) "playing" else "paused"} · frame ${movie.uploadedFrameIndex}<br>${movie.speed}× · material ${if (movie.materialFramesPerSecond == 0) "uncapped" else "${movie.materialFramesPerSecond} fps"} · stride ${movie.selectedStride} · decode ${movie.decodeMicros / 1000.0} ms · queue drops ${movie.droppedFrames}<br>Material omissions ${movie.omittedMaterialFrames} · accumulated ${movie.accumulationSamples} frames · linear RGBA16F → floating-point eyes. No sound. Playback loops at the end. Scrubbing pauses.</div>",
        )
    }

    frame()
}
