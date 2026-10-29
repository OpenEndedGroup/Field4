/*
One reduction, one panel per stage. `(\x. x x) (\y. y)` is the smallest term that uses the whole
vocabulary: beta reduction, duplication and the delimiter reactions that carry sharing through
the graph. Each local reaction gets its own snapshot, including the bookkeeping steps.
*/

val source = "(\\x. x x) (\\y. y)"
val compiled = LambdaTerms.compile(LambdaTerms.parse(source))
// relaxIterations = 8 rather than the headless default of 0: this one IS the picture.
val reducer = Reducer(Lambda.rules(), relaxIterations = 8)

compiled.net.relax(60)
val stages = mutableListOf<InteractionStage>()
stages += stage("compiled", compiled.net.toFLines(), compiled.net.summaryHtml("compiled", maxNodes = 3))

while (stages.size < 32) {
    val detail = reducer.stepDetailed(compiled.net) ?: break
    compiled.net.relax(24)
    stages +=
        stage(
            "${stages.size}. ${detail.ruleName}",
            compiled.net.toFLines(),
            compiled.net.summaryHtml("${stages.size}. ${detail.ruleName}", maxNodes = 3),
        )
}

check(compiled.net.isNormal(Lambda.rules())) {
    "Reached the 32-stage limit; try a terminating term or raise the limit."
}
val readback = LambdaTerms.readback(compiled)
val oracle = LambdaTerms.referenceNormalOrder(LambdaTerms.parse(source))
println("$source  ->  ${LambdaTerms.format(readback)}")
println("oracle: ${LambdaTerms.format(oracle)}   agree: ${LambdaTerms.alphaEquivalent(readback, oracle)}")
println("stages: " + stages.joinToString(" | ") { it.title })

// Each panel is fitted to its own frame, so the scales are NOT comparable across panels. The agent
// counts on the cards are what say how big each net was.
showInteractionStages(
    *stages.toTypedArray(),
    channel = "reduction",
    graphicTitle = "$source, one panel per stage",
    stageWidth = 196.0,
    stageHeight = 250.0,
    gap = 10.0,
    padding = 22.0,
)

/*
 Read the cards left to right and the agent count goes 6, 4, 6, 4, 2. It goes UP at `dup-lam`,
 which is the point: duplication is work, and it is work the net does explicitly rather than by
 copying a subtree behind your back.

 Try it: change `source` to `(\f. \y. f (f y)) ((\x. x) (\x. x))` and run again — a redex shared
 under a binder, reduced once for both of its uses.
 */
