import afield.graphics.scene.reading.*
import afield.graphics.text.sentences.*

// 3 · Undertow. Nested dependency arcs, with independently scored labels and beads.
val parsed = Sentences.example(3)
check(parsed.problems().isEmpty()) { parsed.problems().joinToString() }
val face = Typefaces.named("Noto Serif Light", 100, strict = true)
val layout =
    SentenceDiagram()
        .use(parsed)
        .apply {
            fontSize = 32.0
            wordFont = face.awtFont.deriveFont(32f)
            wordGap = 42.0
            heights = Heights.Wandering(seed = 17, amplitude = 20.0)
            arcLabels = false
            arcSpanRatio = .43
        }
        .diagram()
val cx = (layout.words.first().left + layout.words.last().right) * .5
val top = layout.words.minOf { it.baseline } - 38.0
val bottom = max(layout.arcs.maxOf { it.curve.lowestPoint().y }, layout.words.maxOf { it.baseline }) + 30.0
val cy = (top + bottom) * .5
val metres = min(2.05 / (layout.words.last().right - layout.words.first().left), 1.05 / (bottom - top))
fun world(p: P, z: Float = 0f) = vec(((p.x - cx) * metres).toFloat(), (-(p.y - cy) * metres).toFloat(), z)
val graph = readingDiagram {
    val particles = mutableMapOf<Int, DiagramParticle>()
    val words =
        layout.words.associate { w ->
            val p = world(P(w.centre, w.baseline), (.025 * sin(w.token * 1.5)).toFloat())
            val particle = point("particle-${w.token}", p.x, p.y, p.z, pinned = w.token == 4)
            particles[w.token] = particle
            w.token to text("word-${w.token}", w.word, particle) { scale = (32.0 * metres / .09).toFloat() }
        }
    val physics = words.keys.sorted()
    for ((a, b) in physics.zipWithNext()) link(particles.getValue(a), particles.getValue(b))
    for (w in layout.words) if (w.underlined)
        underline("mark-${w.token}", words.getValue(w.token).all, gap = .012f) { color = vec(.5f, .33f, .14f, 1f) }
    for ((i, dep) in layout.arcs.withIndex()) {
        if (dep.edge.isRoot) continue
        val a = words.getValue(dep.edge.gov).below.offset(y = -.02f)
        val b = words.getValue(dep.edge.dep).below.offset(y = -.02f)
        val originalA = world(dep.curve.a)
        val originalB = world(dep.curve.b)
        val c1 = world(dep.curve.c1) - originalA
        val c2 = world(dep.curve.c2) - originalB
        val path = path(
            "arc-$i",
            shape = {
                val from = position(a)
                val to = position(b)
                FLine()
                    .moveTo(from)
                    .cubicTo(from + c1 + vec(0f, 0f, -dep.lane * .012f), to + c2 + vec(0f, 0f, -dep.lane * .012f), to)
            },
        ) {
            width = .0015f
            arrow = .012f
            if (i % 2 == 0) dashed(.026f)
        }
        dot("bead-$i", path.at(.50f)) {
            radius = .004f
            color = vec(.85f, .48f, .15f, 1f)
        }
        text("label-$i", dep.label, path.at(.52f).offset(y = -.027f)) {
            scale = (8.8 * metres / .09).toFloat()
            color = vec(1f, 1f, 1f, .55f)
        }
    }
}
// Let the words lead: structural strokes and markers are more translucent.
for (path in graph.paths) path.ink = path.ink.copy(color = Vector4f(path.ink.color).apply { w *= .65f })
for (dot in graph.dots) dot.color.w *= .65f
val progress = progressReporter("Prepare 3 / 4: dependency undertow", showDelayMillis = 0)
val player = try {
    ReadingDiagramPlayer.prepare(graph, face, "field-slug".file, diagramScore(graph, 20.0) {}) { n, total, text ->
        progress.update(n.toDouble() / total, "$n / $total: $text")
    }
} finally {
        progress.close()
    }
player.wind = .010f
prop<ReadingExample>(exampleThree) = ReadingExample(
    "3 · Dependency undertow",
    "The real collapsed dependencies form nested undercurrents. Beads and relation labels follow each moving arc's arclength.",
    player,
)
