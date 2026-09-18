// Type a λ-term, get its normal form. `LambdaTerms.normalise` is the whole pipeline in one call:
// parse the text, compile it to an interaction net, reduce the net until nothing fires, read a term
// back out. Nothing here needs a GPU, a network or any setup.

val run = LambdaTerms.normalise("(\\m n f x. m f (n f x)) (\\f x. f x) (\\f x. f (f x))")
println("add one two  =  ${run.printed}")
println("${run.steps} reactions, ${run.fans} fans compiled, ${run.remainingNodes} agents left standing")
println("oracle agrees: " + LambdaTerms.alphaEquivalent(run.result, LambdaTerms.referenceNormalOrder(run.term)))

// That last line is the only reason to trust the one above it. `referenceNormalOrder` is an ordinary
// capture-avoiding substitution evaluator with no nets and no sharing anywhere in it — it exists in
// order to disagree with the net, and the readback is compared to it with `alphaEquivalent`, not with
// `==`, because a readback invents its own binder names.

println()
println("term                                       normal form                   steps  fans  agents")
listOf(
    "\\x. x",
    "(\\x y z. x z (y z)) (\\x y. x) (\\x y. x)",
    "(\\n f x. f (n f x)) (\\f x. f (f x))",
    "(\\m n f. m (n f)) (\\f x. f (f x)) (\\f x. f (f (f x)))",
    "(\\f x. f (f (f x))) (\\f x. f (f x))",
    "(\\n f x. n (\\g h. h (g f)) (\\u. x) (\\u. u)) (\\f x. f (f (f x)))",
    "(\\x. x x) (\\y. ((\\x. x) (\\x. x)) y)",
    "(\\x y. x) (\\x. x) ((\\m n f. m (n f)) (\\f x. f (f x)) (\\f x. f (f (f x))))"
).forEach { source ->
    val each = LambdaTerms.normalise(source)
    val ok = LambdaTerms.alphaEquivalent(each.result, LambdaTerms.referenceNormalOrder(each.term))
    println(
        source.take(40).padEnd(42) + each.printed.take(28).padEnd(30) +
            "%5d %5d %7d".format(each.steps, each.fans, each.remainingNodes) + (if (ok) "" else "   ORACLE DISAGREES")
    )
}

// Read the `agents` column against the `normal form` column on the exponentiation row — `three two`,
// which is 2^3. The net is left standing at 10 agents; the term it reads back as needs 11 nodes (one
// ROOT, two LAM, eight APP). The net is SMALLER than its own answer, because it stayed shared: the
// unfolding happened in the readback, not in the reduction. A fan in a normal form is not a node of
// the term, it is a bracket on the path through it, which is what `Lambda.FAN_LABEL` is for.
//
// Do not read that as a general claim. `mul two three` finishes at 10 agents against a 9-node term
// and `mul three three` comes out even. Which way it goes depends on the term.

println()
println("what the compiler builds, for `\\f. \\y. f (f y)`:")
val compiled = LambdaTerms.compile(LambdaTerms.parse("\\f. \\y. f (f y)"))
println("  " + compiled.net.nodes.groupingBy { it.type.name }.eachCount().toSortedMap())
println("  ${compiled.fans} fan(s) for the variable used twice, ${compiled.erasers} eraser(s) for unused binders")

// Try it: put a term of your own in the list. `\x. x x` applied to itself diverges, and the net will
// too — `normalise` takes a `maxSteps` and `run.finished` is false when it ran out, which is the only
// honest answer to a term that has no normal form.
