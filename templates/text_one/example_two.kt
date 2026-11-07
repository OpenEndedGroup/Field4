import afield.graphics.scene.reading.*
import afield.graphics.text.*
import kotlin.math.*
import afield.graphics.text.sentences.*

// 2 · Constituency canopy. Use the real parse/layout, then rebuild its connections in gentle 3D.
val parsed = Sentences.example(2)
check(parsed.problems().isEmpty()) { parsed.problems().joinToString() }
val face = Typefaces.named("Noto Serif", 100, strict = true)
val layout =
    SentenceDiagram()
        .use(parsed)
        .apply {
            fontSize = 32.0
            wordFont = face.awtFont.deriveFont(32f)
            heights = Heights.Flat()
            layerGap = 55.0
            wordGap = 36.0
        }
        .diagram()
val xs = layout.words.flatMap { listOf(it.left, it.right) } + layout.nodes.map { it.at.x }
val ys = layout.words.map { it.baseline } + layout.nodes.map { it.at.y }
val cx = (xs.min() + xs.max()) * .5
val cy = (ys.min() + ys.max()) * .5
val metres = min(2.0 / (xs.max() - xs.min()), 1.15 / (ys.max() - ys.min()))
val tagMetrics = Java2DMetrics(face.awtFont.deriveFont(13f))
fun world(p: P, z: Double = 0.0) = vec(((p.x - cx) * metres).toFloat(), (-(p.y - cy) * metres).toFloat(), z.toFloat())
val graph = readingDiagram {
    val nodes =
        layout.nodes.associate { n ->
            val p = world(n.at, .018 * sin(n.id * 1.71) + n.depth * .009)
            n.id to point("node-${n.id}", p.x, p.y, p.z, pinned = n.id == parsed.constituency.id)
        }
    val degrees = mutableMapOf<Int, Int>()
    // The visible topology is complete; the reciprocal physical stencil is limited to four links.
    for (edge in layout.treeEdges) {
        if (degrees.getOrDefault(edge.parent, 0) < 4 && degrees.getOrDefault(edge.child, 0) < 4) {
            link(nodes.getValue(edge.parent), nodes.getValue(edge.child), .65f)
            degrees[edge.parent] = degrees.getOrDefault(edge.parent, 0) + 1
            degrees[edge.child] = degrees.getOrDefault(edge.child, 0) + 1
        }
        val a = nodes.getValue(edge.parent).anchor
        val b = nodes.getValue(edge.child).anchor
        path(
            "branch-${edge.parent}-${edge.child}",
            shape = {
                val from = position(a)
                val to = position(b)
                val drop = to.y - from.y
                FLine().moveTo(from).cubicTo(from + vec(0f, drop * .5f, .012f), to - vec(0f, drop * .5f, .012f), to)
            },
        ) {
            width = .0013f
            color = vec(.27f, .45f, .6f, 1f)
        }
    }
    for (n in layout.nodes) {
        val anchor = nodes.getValue(n.id).anchor
        dot("joint-${n.id}", anchor) {
            radius = if (n.leaf) .0025f else .004f
            shape = DiagramPointShape.RING
        }
        if (!n.leaf)
            // Text anchors are centred: add half its measured width, then clear the joint ring.
            text("tag-${n.id}", n.label, anchor.offset(x = (.012 + tagMetrics.width(n.label) * metres * .5).toFloat(), y = .018f)) {
                scale = (13.0 * metres / .09).toFloat()
                color = vec(.75f, .47f, .2f, 1f)
            }
    }
    for (w in layout.words) {
        val p = world(P(w.centre, w.baseline), .025 * sin(w.token * 1.3))
        val node = point("word-point-${w.token}", p.x, p.y, p.z)
        val word = text("word-${w.token}", w.word, node) { scale = (32.0 * metres / .09).toFloat() }
        val leaf = layout.nodes.single { it.leaf && it.token == w.token }
        if (degrees.getOrDefault(leaf.id, 0) < 4) link(nodes.getValue(leaf.id), node, .6f)
        line("stem-${w.token}", nodes.getValue(leaf.id).anchor, word.above) { width = .0010f }
        if (w.underlined)
            underline("mark-${w.token}", word.all, gap = .010f) {
                color = vec(.5f, .33f, .13f, 1f)
                width = .0012f
            }
    }
}
val progress = progressReporter("Prepare 2 / 4: constituency canopy", showDelayMillis = 0)
val player = try {
    ReadingDiagramPlayer.prepare(graph, face, "field-slug".file, diagramScore(graph, 20.0) {}) { n, total, text ->
        progress.update(n.toDouble() / total, "$n / $total: $text")
    }
} finally {
        progress.close()
    }
player.wind = .012f
prop<ReadingExample>(exampleTwo) = ReadingExample(
    "2 · Constituency canopy",
    "The parser's actual tree hangs above the words. Branches occupy shallow depth layers; type stays in readable planes.",
    player,
)
