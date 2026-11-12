import afield.graphics.scene.reading.*
import afield.graphics.text.sentences.*

// 4 · Sentence furniture. Reed-Kellogg shelves become a shallow spatial mobile, then a reading line.
val parsed = ReadingPassage.sentence(4)
check(parsed.problems().isEmpty()) { parsed.problems().joinToString() }
val face = Typefaces.named("Noto Serif Light", 100, strict = true)
val config = KelloggConfig(
    slantAngle = 30.0,
    nestAngleStep = 10.0,
    maxSlantAngle = 48.0,
    fontSize = 32.0,
    metrics = Java2DMetrics(face.awtFont.deriveFont(32f)),
    modifierSize = 23.0,
    modifierMetrics = Java2DMetrics(face.awtFont.deriveFont(23f)),
)
val layout = Kellogg(config).layout(parsed)
check(layout.problems().isEmpty()) { layout.problems().joinToString() }
val metres = min(2.15 / layout.bounds.width, 1.1 / layout.bounds.height)
val center = layout.bounds.centre
fun world(p: P, z: Float = 0f) = vec(((p.x - center.x) * metres).toFloat(), (-(p.y - center.y) * metres).toFloat(), z)
val graph = readingDiagram {
    group("furniture") {
        val points = ArrayList<Pair<KWord, DiagramParticle>>()
        for ((i, w) in layout.words.withIndex()) {
            val angle = Math.toRadians(w.angle)
            val midpoint = P(w.at.x + cos(angle) * w.width * .5, w.at.y + sin(angle) * w.width * .5)
            val p = world(midpoint, if (w.role == Role.MODIFIER) -.035f else .02f)
            val particle = point("word-point-$i", p.x, p.y, p.z, pinned = w.role == Role.PREDICATE)
            points.add(w to particle)
            // Kellogg can merge detached clause punctuation into a predicate ("could smell .").
            // Keep a verbatim phrase here; the complete reading run retains the original punctuation.
            val quote = w.text.trim().let { label ->
                if (ReadingPassage.text.contains(label)) label
                else label.trimEnd { it.isWhitespace() || it in ".,;:!?…" }
            }
            check(ReadingPassage.text.contains(quote)) { "Layout changed the source phrase: $quote" }
            text("word-$i", quote, particle) {
                scale = (w.size * metres / .09).toFloat()
                rotation = -angle.toFloat()
            }
        }
        for ((a, b) in points.zipWithNext()) link(a.second, b.second, .35f)
        fun attached(p: P): DiagramAnchor {
            val position = world(p)
            val nearest = points.minBy { it.second.rest.distanceSquared(position) }.second
            val offset = position - nearest.rest
            return nearest.anchor.offset(offset.x, offset.y, offset.z)
        }
        for ((i, l) in layout.lines.withIndex()) line("shelf-$i", attached(l.a), attached(l.b)) {
            width = if (l.kind == KLineKind.BASELINE) .002f else .0013f
            color = vec(.3f, .49f, .65f, 1f)
            if (l.kind == KLineKind.RISER) dashed(.022f)
        }
        for ((i, entry) in points.withIndex()) if (i % 2 == 0)
            dot("joint-$i", entry.second.anchor.offset(y = -.015f)) {
                radius = .0035f
                shape = DiagramPointShape.RING
            }
    }
    group("reading") {
        val p = point("reading-point", 0, 0, .07, pinned = true)
        val sentence = text("reading-run", parsed.text, p) { scale = .66f }
        val wind = underline("reading-wind", sentence.word("toe"), gap = .018f) { color = vec(.8f, .42f, .12f, 1f) }
        underline("reading-weather", sentence.word("earth"), gap = .018f) { color = vec(.8f, .42f, .12f, 1f) }
        dot("reading-bead", wind.at(.5f)) { radius = .004f }
    }
}
// Let the words lead: structural strokes and markers are more translucent.
for (path in graph.paths) path.ink = path.ink.copy(color = Vector4f(path.ink.color).apply { w *= .65f })
for (dot in graph.dots) dot.color.w *= .65f
val progress = progressReporter("Prepare 4 / 4: sentence furniture", showDelayMillis = 0)
val player = try {
    ReadingDiagramPlayer.prepare(graph, face, "field-slug".file, diagramScore(graph, 24.0) {}) { n, total, text ->
        progress.update(n.toDouble() / total, "$n / $total: $text")
    }
} finally {
        progress.close()
    }
player.wind = .012f
prop<ReadingExample>(exampleFour) = ReadingExample(
    "4 · Sentence furniture",
    "A Reed-Kellogg structure with gently separated shelves, a softened slant angle, then a scored crossfade into one complete reading run.",
    player,
)
