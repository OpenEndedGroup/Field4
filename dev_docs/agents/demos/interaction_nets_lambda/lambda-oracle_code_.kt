// The whole corpus against the oracle, and then against four schedulers. This is the box that says
// whether any of the rest is true: `readback(reduce(compile(t)))` against an ordinary
// capture-avoiding normal-order evaluator, for every named term, printed rather than summarised.

val corpus = LambdaTerms.corpus()
val orders = listOf(
    ActivationOrder.FIRST_FOUND,
    ActivationOrder.LEFTMOST_BY_ID,
    ActivationOrder.RIGHTMOST_BY_ID,
    ActivationOrder.HIGHEST_PAIR_ENERGY
)
println("${corpus.size} named closed terms, ${orders.size} schedulers")
println()

println("                        steps  fans  agents  normal form")
var netTotal = 0
val disagreed = mutableListOf<String>()
val notConfluent = mutableListOf<String>()

corpus.forEach { (name, source) ->
    val term = LambdaTerms.parse(source)
    val oracleKey = LambdaTerms.deBruijnKey(LambdaTerms.referenceNormalOrder(term))
    val keys = orders.associateWith { LambdaTerms.deBruijnKey(LambdaTerms.normalise(term, order = it).result) }
    val run = LambdaTerms.normalise(term)
    netTotal += run.steps
    if (keys.getValue(ActivationOrder.FIRST_FOUND) != oracleKey) disagreed += name
    if (keys.values.toSet().size != 1) notConfluent += name
    println(
        name.padEnd(22) + "%6d %5d %7d".format(run.steps, run.fans, run.remainingNodes) +
            "  " + run.printed.take(46)
    )
}

println()
println(
    "agreed with the oracle : ${corpus.size - disagreed.size} of ${corpus.size}" +
        if (disagreed.isEmpty()) "" else "   DISAGREED: $disagreed"
)
println(
    "confluent across ${orders.size} schedulers: ${corpus.size - notConfluent.size} of ${corpus.size}" +
        if (notConfluent.isEmpty()) "" else "   NOT CONFLUENT: $notConfluent"
)
println("net reactions          : $netTotal over ${corpus.size} terms at FIRST_FOUND")

// Two things that number is not. It is not comparable to a β-step count — a net reaction is any rule
// firing, β or duplication or erasure or a fan meeting, so the two are different units. And it is not
// the word "optimal": there are no brackets and no croissants here, so the sharing is correct but
// nothing bounds how many times a fan may be copied.

println()
println("and the pieces on their own:")
println("  parse -> format round-trips exactly: " +
    corpus.values.all { LambdaTerms.parse(LambdaTerms.format(LambdaTerms.parse(it))) == LambdaTerms.parse(it) })
println("  every corpus term is closed: " +
    corpus.values.all { LambdaTerms.freeVariablesOf(LambdaTerms.parse(it)).isEmpty() })
println("  de Bruijn key of Church two: " + LambdaTerms.deBruijnKey(LambdaTerms.parse("\\f x. f (f x)")))
println("  substitution avoids capture: " +
    LambdaTerms.format(LambdaTerms.substitute(LambdaTerms.parse("\\y. x y"), "x", LambdaTerms.parse("y"))))

// Try it: add a term to `LambdaTerms.corpus()` in `src/jvmMain/kotlin/afield/stdlib/lambdaterms.kt`.
// `stdlib.lambda-terms.smoke` asserts the corpus by the SET OF ITS NAMES, so a new entry fails that
// test until you name it there too — which is the point. A corpus that can shrink is not a corpus.
