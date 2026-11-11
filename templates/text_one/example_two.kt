import afield.graphics.scene.reading.*
import afield.graphics.text.*
import afield.graphics.text.sentences.*
import kotlin.math.*

// 2 · Complete canopy. Preserve the parser's geometry, while the visible words come from the passage.
val parsed = ReadingPassage.sentence(2)
check(parsed.problems().isEmpty()) { parsed.problems().joinToString() }
val face = Typefaces.named("Noto Serif Light", 100, strict = true)
val layout =
    SentenceDiagram()
        .use(parsed)
        .apply {
            fontSize = 32.0
            wordFont = face.awtFont.deriveFont(32f)
            heights = Heights.Flat()
            layerGap = 55.0
            wordGap = 50.0
            tagFontSize = 13.0
            labelFontSize = 11.0
        }
        .diagram()
// Include the lower arcs and their labels when fitting the whole diagram into reading space.
val bounds = layout.bounds
val cx = bounds.centre.x
val cy = bounds.centre.y
val metres = min(2.15 / bounds.width, 1.7 / bounds.height)
fun world(p: P, z: Double = 0.0) = vec(((p.x - cx) * metres).toFloat(), (-(p.y - cy) * metres).toFloat(), z.toFloat())
fun direction(p: P) = vec((p.x * metres).toFloat(), (-p.y * metres).toFloat(), 0f)
val graph = readingDiagram {
    val nodes =
        layout.nodes.associate { n ->
            val p = world(n.at, .018 * sin(n.id * 1.71) + n.depth * .009)
            n.id to point("node-${n.id}", p.x, p.y, p.z, pinned = n.id == parsed.constituency.id)
        }
    val degrees = mutableMapOf<Int, Int>()
    for (edge in layout.treeEdges) {
        // Physics has four reciprocal slots; the visible topology always includes every branch.
        if (degrees.getOrDefault(edge.parent, 0) < 4 && degrees.getOrDefault(edge.child, 0) < 4) {
            link(nodes.getValue(edge.parent), nodes.getValue(edge.child), .65f)
            degrees[edge.parent] = degrees.getOrDefault(edge.parent, 0) + 1
            degrees[edge.child] = degrees.getOrDefault(edge.child, 0) + 1
        }
        val a = nodes.getValue(edge.parent).anchor
        val b = nodes.getValue(edge.child).anchor
        val c1 = direction(edge.curve.c1 - edge.curve.a)
        val c2 = direction(edge.curve.c2 - edge.curve.b)
        val branch = path(
            "branch-${edge.parent}-${edge.child}",
            shape = {
                val from = position(a)
                val to = position(b)
                FLine().moveTo(from).cubicTo(from + c1, to + c2, to)
            },
        ) {
            width = .0013f
            color = vec(1f, 1f, 1f, .7f)
        }
        endpointDots(branch)
    }
    for (n in layout.nodes) {
        val anchor = nodes.getValue(n.id).anchor
        dot("joint-${n.id}", anchor) {
            radius = if (n.leaf) .0025f else .004f
            shape = DiagramPointShape.RING
        }

    }
    val particles = mutableMapOf<Int, DiagramParticle>()
    val words =
        layout.words.associate { w ->
            val p = world(P(w.centre, w.baseline), .025 * sin(w.token * 1.3))
            val node = point("word-point-${w.token}", p.x, p.y, p.z)
            particles[w.token] = node
            w.token to text("word-${w.token}", w.word, node) { scale = (32.0 * metres / .09).toFloat() }
        }
    val wordLayouts = layout.words.associateBy { it.token }
    // A font-metric baseline anchor follows its word; the offset preserves the original layout.
    fun wordAnchor(token: Int, at: P): DiagramAnchor {
        val w = wordLayouts.getValue(token)
        return words
            .getValue(token)
            .all
            .baseline
            .offset(x = (at.x - w.centre) * metres, y = -(at.y - w.baseline) * metres)
    }
    for (edge in layout.wordEdges) {
        if (degrees.getOrDefault(edge.node, 0) < 4) link(nodes.getValue(edge.node), particles.getValue(edge.token), .6f)
        val a = nodes.getValue(edge.node).anchor
        val b = wordAnchor(edge.token, edge.curve.b)
        val c1 = direction(edge.curve.c1 - edge.curve.a)
        val c2 = direction(edge.curve.c2 - edge.curve.b)
        val stem = path(
            "stem-${edge.token}",
            shape = {
                val from = position(a)
                val to = position(b)
                FLine().moveTo(from).cubicTo(from + c1, to + c2, to)
            },
        ) {
            width = .0010f
            color = vec(1f, 1f, 1f, .6f)
        }
        endpointDots(stem)
    }
    for (w in layout.words) if (w.underlined)
        underline("mark-${w.token}", words.getValue(w.token).all, gap = .010f) {
            color = vec(1f, 1f, 1f, .75f)
            width = .0012f
        }
    for ((i, dep) in layout.arcs.withIndex()) {
        if (dep.edge.isRoot) continue
        val a = wordAnchor(dep.edge.gov, dep.curve.a)
        val b = wordAnchor(dep.edge.dep, dep.curve.b)
        val c1 = direction(dep.curve.c1 - dep.curve.a)
        val c2 = direction(dep.curve.c2 - dep.curve.b)
        val arc = path(
            "dependency-$i",
            shape = {
                val from = position(a)
                val to = position(b)
                FLine().moveTo(from).cubicTo(from + c1, to + c2, to)
            },
        ) {
            width = .0012f
            color = vec(1f, 1f, 1f, .45f)
        }
        endpointDots(arc)
    }
}
// Let the words lead: structural strokes and markers are more translucent.
for (path in graph.paths) path.ink = path.ink.copy(color = Vector4f(path.ink.color).apply { w *= .65f })
for (dot in graph.dots) dot.color.w *= .65f
val progress = progressReporter("Prepare 2 / 4: complete canopy", showDelayMillis = 0)
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
    "The complete constituent tree and word stems, with collapsed dependencies beneath the passage’s words. Curves follow their moving anchors.",
    player,
)
