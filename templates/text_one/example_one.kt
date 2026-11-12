import afield.graphics.scene.reading.*
import afield.graphics.text.*

// 1 · Marked breath. Whole runs, two selected words and an arclength bead.
// Verbatim fragments of the supplied passage; font preparation happens before playback.
val graph = readingDiagram {
    val peg = point("peg", -.78, .52, pinned = true)
    dot("peg-marker", peg.anchor) {
        shape = DiagramPointShape.RING
        radius = .009f
        color = vec(.8f, .43f, .13f, 1f)
    }
    val first = point("first", 0, .24, .025)
    val second = point("second", 0, -.08, -.025)
    link(peg, first)
    link(first, second)
    val opening = text("opening", "Then let go,", first)
    val sentence = text("sentence", "hands lifting—feeling around\nin front of me.", second)
    endpointDots(arc("hanging", peg.anchor, opening.above, rise = .09f) { dashed(.025f) })
    val birds = underline("birdsong", sentence.word("hands"), gap = .018f) {
        width = .002f
        color = vec(.8f, .43f, .13f, 1f)
    }
    underline("walls", sentence.word("me"), gap = .018f) { color = vec(.8f, .43f, .13f, 1f) }
    dot("beside-word", opening.word("Then").left.offset(x = -.028f)) {
        shape = DiagramPointShape.RING
        radius = .005f
    }
    dot("on-underline", birds.at(.65f)) { radius = .0045f }
    val relation = arc(
        "reflection",
        sentence.word("hands").below.offset(y = -.055f),
        sentence.word("me").below.offset(y = -.055f),
        rise = -.23f,
    ) {
        width = .0012f
        arrow = .013f
    }
    endpointDots(relation)
    dot("bead", relation.at(.50f)) { radius = .003f }

}
val face = Typefaces.named("Noto Serif Light", 100, strict = true)
// Let the words lead: structural strokes and markers are more translucent.
for (path in graph.paths) path.ink = path.ink.copy(color = Vector4f(path.ink.color).apply { w *= .65f })
for (dot in graph.dots) dot.color.w *= .65f
val progress = progressReporter("Prepare 1 / 4: marked breath", showDelayMillis = 0)
val player = try {
    ReadingDiagramPlayer.prepare(graph, face, "field-slug".file, diagramScore(graph, 20.0) {}) { n, total, text ->
        progress.update(n.toDouble() / total, "$n / $total: $text")
    }
} finally {
        progress.close()
    }
prop<ReadingExample>(exampleOne) = ReadingExample(
    "1 · Marked breath",
    "Whole-run spans, a dot beside a word, and dots on an underline and an arc. Two baselines sit 5 cm apart in depth.",
    player,
)
