import afield.graphics.scene.reading.*
import afield.graphics.text.*

// Two verbatim excerpts, with authored line breaks. These are independent physical runs,
// not a single billboard: the near surround can frost part of the later, more distant run.
val face = Typefaces.named("Noto Serif", 100, strict = true).withWeight(300.0)
val graph = readingDiagram {
    val near = text("near-reading",
        "My words found a few\nlast handholds of their own\nonce my body collapsed.",
        point("near-point", -.14, -.04, .28),
    ) {
        font = face
        scale = .58f
        orientation.rotateY(-.10f)
        blurCapacity = 1.5f // both entrance and exit tracks reserve their Gaussian range before Drawing
    }
    val far = text("far-reading",
        "A whole school of ideas\nswam before me,\nbut I could hook only one:\ndon’t believe what you see.",
        point("far-point", .14, .11, -.24),
    ) {
        font = face
        scale = .56f
        orientation.rotateY(.12f)
        blurCapacity = 1.5f
    }
    val nearFrame = textFrame("near-frame", near, .025f, DiagramTextFrameMode.LINE_UNION) {
        width = .0007f
        color = vec(1f, 1f, 1f, .10f)
    }
    val farFrame = textFrame("far-frame", far, .025f, DiagramTextFrameMode.LINE_UNION) {
        width = .0007f
        color = vec(1f, 1f, 1f, .10f)
    }
    val thread = connection("reading-thread", nearFrame.attach(), farFrame.attach(), interiorNodes = 8) {
        responseSeconds = 1.6f
        dampingRatio = .9f
        lengthStiffness = 25f
        curvatureStiffness = 18f
        bow.set(0f, -.19f, .10f)
        tension = .15f
        width = .001f
        color = vec(1f, 1f, 1f, .25f)
    }
    endpointDots(thread.path, radius = .002f, opacity = .4f)
}

// This example owns its score. Box 6 deliberately leaves it alone when re-scoring other examples.
// All visible paths fade; none draw themselves in. Dots lead the connecting thread by .7 seconds.
val score = diagramScore(graph, 36.0) {
    val near = graph.text("near-reading")
    val far = graph.text("far-reading")
    fadeIn(near, at = 0.0, seconds = 5.0)
    blurIn(near, at = 0.0, seconds = 8.0, radius = .75f)
    fadeIn(graph.path("near-frame"), at = .7, seconds = 5.0)
    fadeIn(far, at = 5.0, seconds = 5.5)
    blurIn(far, at = 5.0, seconds = 9.0, radius = .75f)
    fadeIn(graph.path("far-frame"), at = 6.0, seconds = 5.0)
    for (dot in graph.dots) fadeIn(dot, at = 6.4, seconds = 1.0)
    fadeIn(graph.path("reading-thread"), at = 7.1, seconds = 2.9)

    // Let the room stay readable, then release the two runs on different clocks.
    blurOut(near, at = 22.0, seconds = 7.0, radius = .75f)
    fadeOut(near, at = 22.0, seconds = 6.0)
    fadeOut(graph.path("near-frame"), at = 24.0, seconds = 5.0)
    blurOut(far, at = 26.0, seconds = 8.0, radius = .75f)
    fadeOut(far, at = 27.0, seconds = 6.0)
    fadeOut(graph.path("far-frame"), at = 28.0, seconds = 6.0)
    fadeOut(graph.path("reading-thread"), at = 25.0, seconds = 6.0)
    for (dot in graph.dots) fadeOut(dot, at = 26.0, seconds = 5.0)
}
val progress = progressReporter("Prepare two readings", showDelayMillis = 0)
val player = try {
    ReadingDiagramPlayer.prepare(graph, face, "field-slug".file, score) { n, total, text ->
        progress.update(n.toDouble() / total, "$n / $total: $text")
    }
} finally {
    progress.close()
}
player.spring = 3f
player.dampingRate = 3f
player.wind = 0f
player.gravity = 0f
prop<ReadingExample>(exampleNine) = ReadingExample(
    "9 · Two readings",
    "Staggered blur/fade entrances, overlapping frosted rectlet surrounds, a dotted elastic thread and body-responsive text. Both runs sharpen by 14 seconds; they dissolve separately from 22 seconds.",
    player,
)

controlGroup("sequence") {
    button("Replay this sequence") { down ->
        if (down) {
            player.restart()
            player.playback.playing = true
        }
    }
    button("Hold both readings") { down ->
        if (down) {
            player.playback.seek(16.0)
            player.playback.playing = false
        }
    }
}
display("timing", "<div style='padding:12px;width:440px'>Near reading: enter 0–8s, dissolve 22–29s.<br>Far reading: enter 5–14s, dissolve 26–34s.<br>Dots lead the thread; the thread fades at 7.1s. Both text anchors are unpinned, so the body can waft them and their surrounds away.</div>")
