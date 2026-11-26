import afield.graphics.scene.reading.*

// A reading diagram whose words and marks carry what they stand for. Picking hands it back:
// properties on items, annotations on character ranges (innermost first).
val graph = readingDiagram {
    val top = point("top", 0, .16, pinned = true)
    val bottom = point("bottom", 0, -.12, pinned = true)
    val sum = text("sum", "1 + 3 + 5 + 7 = 4 × 4", top)
    sum.all.annotate("the whole identity")
    for (odd in ["1", "3", "5", "7"]) sum.word(odd).annotate("the odd number " + odd)
    sum.span("4 × 4").annotate("the square it makes")
    val gloss = text("gloss", "odd numbers wrap a square", bottom)
    gloss.word("odd").annotate("each term is 2i + 1")
    gloss.word("square").annotate("n × n")
    val turn = arc("turn", sum.word("7").below.offset(y = -.03f), gloss.word("square").above.offset(y = .02f), rise = -.06f) {
        width = .0015f
        arrow = .012f
    }
    endpointDots(turn)
    dot("bead", turn.at(.5f)) { radius = .006f }
}
graph.path("turn").properties["says"] = "the last gnomon completes the square"
graph.dot("bead").properties["says"] = "halfway"
prop<ReadingDiagramPlayer>(sumPlayer) =
    ReadingDiagramPlayer.prepare(graph, Typefaces.named("Serif", 100), "field-slug".file, diagramScore(graph, 20.0) {})
sumPlayer.gravity = 0f
sumPlayer.wind = 0f
println("scene ready: " + graph.texts.size + " runs, " + graph.paths.size + " paths, " + graph.dots.size + " dots")
