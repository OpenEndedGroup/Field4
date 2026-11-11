import afield.graphics.scene.reading.*
import afield.graphics.text.*

// Verbatim excerpts from the source passage; only the line breaks are authored here.
val face = Typefaces.named("Noto Serif", 100, strict = true).withWeight(300.0)
val graph = readingDiagram {
    val words = text("words", "Each toe\ncould smell the earth\nit touched.", point("p", 0, 0)) {
        font = face
        italic = true
        scale = .78f
        orientation.rotateY(.13f)
        blurCapacity = .8f
    }
    textFrame("holder", words, standoff = .024f, mode = DiagramTextFrameMode.LINE_UNION,
        overstrokeFraction = .12f, overstrokeDistance = .015f,
    ) {
        width = .0007f
        color = vec(1f, 1f, 1f, .18f)
    }.frost.apply { enabled = true; blurPixels = 8f }
}
val score = diagramScore(graph, 28.0) {
    val holder = graph.textFrame("holder")
    fadeIn(graph.text("words"), at = 0.0, seconds = 4.0)
    blurIn(graph.text("words"), at = 0.0, seconds = 6.0, radius = .6f)
    fadeIn(holder, at = 1.0, seconds = 3.0)
    // Eight extensions belong to the overall bounds; no rectlet seam is prolonged.
    overstroke(holder, .12f, .25f, at = 7.0, seconds = 8.0)
    move(graph.text("words"), vec(0f, 0f, 0f), vec(.045f, .025f, .035f), at = 8.0, seconds = 8.0)
    blurOut(graph.text("words"), at = 19.0, seconds = 7.0, radius = .6f)
    fadeOut(graph.text("words"), at = 23.0, seconds = 4.0)
    fadeOut(holder, at = 22.0, seconds = 5.0)
}

val progress = progressReporter("Prepare 11 · Union with corner extensions", showDelayMillis = 0)
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
prop<ReadingExample>(exampleEleven) = ReadingExample("11 · Union with corner extensions", "Eight outer extensions surround a frosted rectlet union; the union and sliding boundary remain unchanged.", player)
