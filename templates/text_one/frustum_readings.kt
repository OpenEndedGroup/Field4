import afield.graphics.scene.reading.*
import afield.graphics.text.*

// Verbatim excerpts from the source passage; only the line breaks are authored here.
val face = Typefaces.named("Noto Serif", 100, strict = true).withWeight(300.0)
val graph = readingDiagram {
    val near = point("near", -.04, -.02, .18)
    val far = point("far", .22, .15, -.25)
    link(near, far)
    val front = text("front", "Marble.\nGoing back to antiquity.", near) {
        font = face
        scale = .72f
        blurCapacity = .8f
    }
    val back = text("back", "Faintly hinted,\nthe heady scent of a rose.", far) {
        font = face
        scale = .44f
        blurCapacity = .8f
    }
    textFrustum("holder", front, back, standoff = .025f) {
        width = .0007f
        color = vec(1f, 1f, 1f, .17f)
        aspectRatio = 3.2f
        frost { enabled = true; blurPixels = 7f; brightness = 1.1f }
    }
}
val score = diagramScore(graph, 30.0) {
    val holder = graph.textFrustum("holder")
    fadeIn(holder, at = 0.0, seconds = 4.0)
    blurIn(holder, at = 0.0, seconds = 7.0, radius = .6f)
    // Move the end texts once; both fitted frames, frosting and rails follow those poses.
    move(holder, vec(0f, 0f, 0f), vec(.06f, .03f, .02f), at = 8.0, seconds = 8.0)
    blurOut(holder, at = 21.0, seconds = 7.0, radius = .6f)
    fadeOut(holder, at = 24.0, seconds = 5.0)
}

val progress = progressReporter("Prepare 12 · A transparent text frustum", showDelayMillis = 0)
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
prop<ReadingExample>(exampleTwelve) = ReadingExample("12 · A transparent text frustum", "Two frosted, text-fitted rectangular ends share an aspect and four rails; a whole-holder cue moves both ends.", player)
