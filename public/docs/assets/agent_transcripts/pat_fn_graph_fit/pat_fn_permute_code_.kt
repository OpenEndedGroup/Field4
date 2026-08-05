import afield.stdlib.Functions
import afield.stdlib.fitGradient
import afield.stdlib.graphNode

with(Functions) {
    val target = patFn("g:(0.9,0.18) g:(0.1,0.18)", cps = 1.0, range = 0.9)
    // The same two heights, swapped. Getting to the target needs 0.9 to move from the
    // second atom to the first: a permutation, not a direction.
    val mirrored = patFn("g:(0.2,0.18) g:(0.8,0.18)", cps = 1.0, range = 0.9)

    val run = fitGradient(target, mirrored, domain = 0.0..2.0, iterations = 200, lr = 0.02, samples = 96)

    plot(target, 0.0..2.0, channel = "1 target", title = "target")
    plot(run.fitted, 0.0..2.0, channel = "2 stalled", title = "stalled fit")

    val report = """
    mirrored seed  ${mirrored.graphNode().strings["source"]}
    ended at       ${run.fitted.graphNode().strings["source"]}
    RMSE           ${"%.4f".format(run.initialError)} -> ${"%.4f".format(run.finalError)}

    It descends, but not to the answer: it escapes into wide kernels, where a
    normalised blend flattens toward the mean of its atom values. Structure search
    (evolve) is the tool for re-assigning values between atoms.
    """.trimIndent()
    println(report)
    display("<pre>" + report + "</pre>")
}
