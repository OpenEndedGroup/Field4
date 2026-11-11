import afield.graphics.scene.reading.*
import afield.graphics.text.*

// Verbatim excerpts from the source passage; only the line breaks are authored here.
val face = Typefaces.named("Noto Serif", 100, strict = true).withWeight(300.0)
val graph = readingDiagram {
    val words = text("words", "And now this sentence,\npulled taut, goes nowhere:", point("p", 0, 0)) {
        font = face
        scale = .7f
        blurCapacity = .8f
    }
    textFrame("holder", words, standoff = .028f,
        overstrokeFraction = .08f, overstrokeDistance = .012f,
    ) {
        width = .0007f
        color = vec(1f, 1f, 1f, .2f)
    }.frost.apply { enabled = true; blurPixels = 7f }
}
val score = diagramScore(graph, 28.0) {
    val holder = graph.textFrame("holder")
    fadeIn(graph.text("words"), at = 0.0, seconds = 4.0)
    blurIn(graph.text("words"), at = 0.0, seconds = 6.0, radius = .6f)
    fadeIn(holder, at = .7, seconds = 4.0)
    // Eight outward corner extensions change length gently; the rectangle itself stays complete.
    overstroke(holder, .08f, .2f, at = 7.0, seconds = 7.0, fromDistance = .012f, toDistance = .025f)
    blurOut(graph.text("words"), at = 19.0, seconds = 7.0, radius = .6f)
    fadeOut(graph.text("words"), at = 23.0, seconds = 4.0)
    fadeOut(holder, at = 22.0, seconds = 5.0)
}

val progress = progressReporter("Prepare 10 · Extended rectangle", showDelayMillis = 0)
val player = try {
    ReadingDiagramPlayer.prepare(graph, face, "field-slug".file, score) { n, total, text ->
        progress.update(n.toDouble() / total, "$n / $total: $text")
    }
} finally {
    progress.close()
}
player.spring = 8f
player.dampingRate = 4f
player.gravity = 0f
player.wind = 0f
prop<ReadingExample>(exampleTen) = ReadingExample("10 · Extended rectangle", "Eight outward corner extensions of a rectangular frame; a seekable score lengthens these mechanical marks.", player)
