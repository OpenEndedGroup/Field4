import afield.ui.output.ControlValueHandle

displayClearAll()

var selectedFile = System.getenv("FIELD_STEREO_VIDEO")
    ?: java.io.File("public/docs/assets/stereo_video/stereo-demo.mkv").absolutePath
var scrubber: ControlValueHandle<Double>? = null
var playingControl: ControlValueHandle<Boolean>? = null
var speedControl: ControlValueHandle<Double>? = null
val source = carousel.source

// Transport is shared. Style buttons broadcast; individual screen boxes remain independently editable.
fun setAllStyles(speed: Double, mix: Float, halfLife: Double? = null) {
    source.speed = speed
    speedControl?.set(speed)
    carousel.surfaces.forEach { view ->
        view.accumulationMix = mix
        if (halfLife != null) view.accumulationHalfLife = halfLife
    }
}
fun showTransport() {
    controlGroup("shared transport") {
        playingControl = checkbox("Playing", initialValue = source.playing,
            hoverText = "Pause or resume the one shared decoder and clock. Every screen retains its own delayed picture and exposure.") { if (it) source.play() else source.pause() }
        scrubber = slider("Seek (seconds)", min = 0.0, max = source.duration.coerceAtLeast(.001), step = .01, initialValue = source.timestamp,
            hoverText = "Seek the shared source. Scrubbing pauses and clears the delay/exposure histories so old and new timelines cannot mix. Delayed screens warm up from the oldest available picture.") { source.seek(it) }
        speedControl = slider("Speed", min = .1, max = 8.0, step = .01, initialValue = source.speed,
            hoverText = "One shared media-clock speed. Changes anchor to the current displayed source image, avoiding fast-playback clock debt when returning to 1x.") { source.speed = it }
        button("Back to start") { down -> if (down) source.seek(0.0) }
        button("Material: unlimited") { down -> if (down) source.materialFramesPerSecond = 0 }
        button("Material: 24 fps") { down -> if (down) source.materialFramesPerSecond = 24 }
        button("Material: 12 fps") { down -> if (down) source.materialFramesPerSecond = 12 }
        checkbox("Alternate frames above 4x", initialValue = source.selectiveDecode,
            hoverText = "At more than 4x, publish alternate source pictures before conversion/upload. Delays count published pictures, so this selection affects their source-time spacing.") { source.selectiveDecode = it }
        checkbox("Skip non-reference pictures", initialValue = source.skipNonReferenceFrames,
            hoverText = "Allow the shared FFmpeg decoder to skip disposable pictures during fast playback. Required reference pictures remain decoded; exact seeks ignore skipping.") { source.skipNonReferenceFrames = it }
        checkbox("Swap left and right", initialValue = movie.swapEyes) { swap -> carousel.surfaces.forEach { it.swapEyes = swap } }
        checkbox("Flip vertically", initialValue = movie.flipVertically) { flip -> carousel.surfaces.forEach { it.flipVertically = flip } }
    }
    controlGroup("all screens") {
        button("1x sharp · all") { down -> if (down) setAllStyles(1.0, 0f) }
        button("2x sharp · all") { down -> if (down) setAllStyles(2.0, 0f) }
        button("2x blur · all") { down -> if (down) { setAllStyles(2.0, 1f, 4.0); source.play() } }
        button("8x blur · all") { down -> if (down) { setAllStyles(8.0, 1f); source.play() } }
        button("Clear all exposures") { down -> if (down) carousel.surfaces.forEach { it.clearAccumulation() } }
        button("Copy screen 1 look to all", hoverText = "Copy screen 1's accumulation and paired-exposure settings to screens 2–8. Their delayed input pictures and independent histories remain separate.") { down ->
            if (down) carousel.surfaces.drop(1).forEach { view ->
                view.accumulationMix = movie.accumulationMix
                view.accumulationHalfLife = movie.accumulationHalfLife
                view.accumulationInputWeight = movie.accumulationInputWeight
                view.exposureCadenceEnabled = movie.exposureCadenceEnabled
                view.exposurePairsPerSecond = movie.exposurePairsPerSecond
                view.exposureBlurSigma = movie.exposureBlurSigma
                view.exposureBlurMix = movie.exposureBlurMix
                view.exposureDarkening = movie.exposureDarkening
            }
        }
    }
    displayOpen("shared transport")
    displayOpen("all screens")
}
fun openMovie() {
    if (carousel.load(selectedFile)) showTransport()
}
controlGroup("movie file") {
    stringInput("Full SBS file", initialValue = selectedFile) { selectedFile = it }
    button("Load movie") { down -> if (down) openMovie() }
}
if (!source.hasFrame && selectedFile.isNotBlank()) openMovie() else showTransport()
displayOpen("movie file")
displayOpen("time carousel")

var lastStatus = 0L
loop {
    renderer.windows.forEach { it.pollEvents() }
    renderer.render()
    val now = System.nanoTime()
    if (now - lastStatus > 250_000_000L) {
        lastStatus = now
        scrubber?.set(source.timestamp)
        playingControl?.set(source.playing)
        speedControl?.set(source.speed)
        val rows = carousel.surfaces.mapIndexed { i, view ->
            "| ${i + 1} | ${view.delayFrames} | ${view.timestamp.f2} | ${(source.timestamp - view.timestamp).coerceAtLeast(0.0).f2} | ${view.accumulationSamples} |"
        }.joinToString("\n")
        markdown("time carousel", """
### One movie · ${carousel.surfaces.size} screens

**${source.timestamp.f2} / ${source.duration.f2}s** · ${source.speed.f2}× · ${if (source.playing) "playing" else "paused"}

GPU history **${source.retainedFrames}/${source.historyFrames}** pictures · ${(source.historyBytes / 1048576.0).f2} MiB · ${source.materialUpdates} shared uploads.

| Screen | Frame delay | Video seconds | Source age (s) | Exposure samples |
| --- | --- | --- | --- | --- |
$rows

${source.error ?: "One active decode stream; each newly published picture is copied once to the ring. Warm-up holds the oldest available image. Headset rendering uses current poses."}
        """, open = false)
    }
    frame()
}
