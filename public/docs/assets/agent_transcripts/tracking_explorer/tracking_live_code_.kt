import afield.geometry.topology.morsesmale.*
import afield.geometry.topology.tracking.*

// Launch this one (Alt-R). Six hills circle; every frame's maxima are matched to the last frame's, so
// each hill keeps its track and its colour for as long as it stays a separate peak.
val hills = (0 until 6).map { i -> Triple(0.4 + 0.25 * i, 30.0 + 9.0 * i, 1.0 - 0.1 * i) }
fun field(t: Double) = Img.generate(200, 150) { x, y ->
    hills.withIndex().sumOf { (i, h) ->
        val (speed, radius, amplitude) = h
        val cx = 100 + radius * cos(speed * t + i); val cy = 75 + radius * 0.7 * sin(speed * t * 1.3 + i)
        amplitude * exp(-((x - cx).pow(2) + (y - cy).pow(2)) / 120.0)
    }.toFloat()
}
val tracker = FeatureTracker(MatchingWeights(birthDistance = 10.0, maxDistance = 20.0))

loop {
    val f = field(frameNumber / 40.0)
    val ms = MorseSmale.compute(f, persistence = 0.05)
    tracker.add(frameNumber.toDouble(), ms.features(minPersistence = 0.05, types = setOf(CriticalType.MAXIMUM)))
    // the last 120 frames of each living track
    val trails = tracker.alive.map { t ->
        FLine().also { line -> t.samples.takeLast(120).forEachIndexed { k, (_, s) -> if (k == 0) line.moveTo(s.x, s.y) else line.lineTo(s.x, s.y) }
            line.properties["color"] = FeatureTracker.colourOf(t.id); line.properties["thickness"] = 2.0 }
    }
    showImgGrid("frame $frameNumber: ${tracker.alive.size} peaks, ${tracker.tracks.size} tracks so far" to
        f.normalized().colormap("viridis").drawFLines(trails + tracker.aliveFLine(9.0), scale = 3.0), columns = 1, cellSide = 600)
}
