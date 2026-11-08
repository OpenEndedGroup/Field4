import afield.graphics.text.sentences.*

// "It is altogether fitting and proper that we should do this", without its full stop.
val tree = SentenceTexts.load("gettysburg").sentences[4].tree()!!
val rng = java.util.Random(5)
val rows =
    listOf("as spoken" to tree.observed) +
        (1..3).map { "shuffled" to Linearization.freeRandom(tree, rng) } +
        listOf("shortest" to Linearization.optimal(tree))

val caption = font("Serif", 30.0)
var y = 0.0
val figure = rows.flatMap { (name, order) ->
    val d = SentenceDiagram().use(tree.sentence(order))
    d.scheme = Scheme.UD
    d.wordFont = font("Serif", 30.0)
    d.wordGap = 22.0
    d.heights = Heights.Flat(y)
    d.arcLabels = false
    d.x0 = 250.0
    val f = d.flines()
    // Keep the words and the arcs; the constituency tree is a stand-in for a reordered sentence.
    val arcs =
        f.copy(treeEdges = emptyList(), wordEdges = emptyList(), texts = f.texts.filter { it.kind == TextKind.WORD })
    val label = outlinedText("$name  ${tree.length(order)}", P(0.0, y), caption)
    y = d.diagram().bounds.y1 + 60.0
    arcs.everything() + label
}
display("figure", figureSvg(figure))
