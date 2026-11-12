import afield.graphics.scene.reading.*
import afield.graphics.text.*

// Verbatim excerpts from the source passage; only the line breaks are authored here.
val face = Typefaces.named("Noto Serif", 100, strict = true).withWeight(300.0)
val graph = readingDiagram {
    val near = point("near", -.04, -.02, .22)
    val far = point("far", .12, .08, -.28)
    link(near, far)
    val front = text("front", "How hard it is for a butterfly\nflitting around in the air\nto pronounce itself…", near) {
        font = face
        scale = .56f
        orientation.rotateY(-.12f)
        blurCapacity = .9f
    }
    val back = text("back", "until it alights\non a flower—", far) {
        font = face
        italic = true
        scale = .4f
        // The second end reads from outside the back. Corner matching keeps the cage untwisted.
        orientation.rotateY(-.12f + kotlin.math.PI.toFloat())
        blurCapacity = .9f
    }
    textFrustum("holder", front, back, standoff = .025f) {
        width = .0007f
        color = vec(1f, 1f, 1f, .15f)
        frontFrame.overstrokeFraction = .08f
        frontFrame.overstrokeDistance = .01f
        backFrame.overstrokeFraction = .08f
        backFrame.overstrokeDistance = .01f
        frost { enabled = true; blurPixels = 8f; brightness = 1.1f }
    }
}
val score = diagramScore(graph, 34.0) {
    val holder = graph.textFrustum("holder")
    fadeIn(holder, at = 0.0, seconds = 5.0)
    blurIn(holder.front, at = 0.0, seconds = 8.0, radius = .65f)
    blurIn(holder.back, at = 2.0, seconds = 8.0, radius = .65f)
    // Ordinary text cues change depth and taper, while the frame fit and rail endpoints stay exact.
    move(holder.back, vec(0f, 0f, 0f), vec(.04f, .03f, -.14f), at = 10.0, seconds = 8.0)
    resize(holder.back, 1f, .72f, at = 10.0, seconds = 8.0)
    overstroke(holder.frontFrame, .08f, .18f, at = 10.0, seconds = 8.0)
    blurOut(holder, at = 24.0, seconds = 8.0, radius = .65f)
    fadeOut(holder, at = 28.0, seconds = 5.0)
}

val progress = progressReporter("Prepare 13 · A holder with two faces", showDelayMillis = 0)
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
prop<ReadingExample>(exampleThirteen) = ReadingExample("13 · A holder with two faces", "Walk around to read the outward-facing back; its scored movement and scale open and taper the device.", player)
