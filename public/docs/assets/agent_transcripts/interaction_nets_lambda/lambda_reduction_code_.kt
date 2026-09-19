// One reduction, one panel per stage. `(\x. x x) (\y. y)` is the smallest term that uses the whole
// vocabulary, and the four reactions it takes are the two that did not exist before 2026-09-18:
// `dup-lam` on an abstraction whose binder is wired to its own body, and `fan-fan` annihilating the
// two halves of that duplication.

import afield.Rect
import afield.graphics.FLine

val source = "(\\x. x x) (\\y. y)"
val compiled = LambdaTerms.compile(LambdaTerms.parse(source))
// relaxIterations = 8 rather than the headless default of 0: this one IS the picture.
val reducer = Reducer(Lambda.rules(), relaxIterations = 8)

compiled.net.relax(60)
val stages = mutableListOf<Triple<String, List<FLine>, String>>()
stages += Triple("compiled", compiled.net.toFLines(), compiled.net.summaryHtml("compiled", maxNodes = 3))

while (stages.size < 8) {
    val detail = reducer.stepDetailed(compiled.net) ?: break
    compiled.net.relax(24)
    stages += Triple(
        "${stages.size}. ${detail.ruleName}",
        compiled.net.toFLines(),
        compiled.net.summaryHtml("${stages.size}. ${detail.ruleName}", maxNodes = 3)
    )
}

val readback = LambdaTerms.readback(compiled)
val oracle = LambdaTerms.referenceNormalOrder(LambdaTerms.parse(source))
println("$source  ->  ${LambdaTerms.format(readback)}")
println("oracle: ${LambdaTerms.format(oracle)}   agree: ${LambdaTerms.alphaEquivalent(readback, oracle)}")
println("stages: " + stages.joinToString(" | ") { it.first })

// Each panel is fitted to its own frame, so the scales are NOT comparable across panels. The agent
// counts on the cards are what say how big each net was.
val panelWidth = 196.0
val gutter = 10.0
val panels = stages.mapIndexed { index, stage ->
    val frame = Rect(14.0 + index * (panelWidth + gutter), 18.0, panelWidth, 250.0)
    listOf(frame.toFLine(filled = true, color = vec(0.07, 0.11, 0.16, 0.5))) +
        stage.second.fitInto(frame, padding = 22.0)
}.flatten()

showInteractionOverview(
    graphicLines = panels,
    cards = stages.map { it.third },
    channel = "reduction",
    graphicTitle = "$source, one panel per stage",
    graphicWidth = 14.0 + stages.size * (panelWidth + gutter),
    graphicHeight = 286.0
)

// Read the cards left to right and the agent count goes 6, 4, 6, 4, 2. It goes UP at `dup-lam`,
// which is the point: duplication is work, and it is work the net does explicitly rather than by
// copying a subtree behind your back.
//
// Try it: change `source` to `(\f. \y. f (f y)) ((\x. x) (\x. x))` and run again — a redex shared
// under a binder, reduced once for both of its uses.
