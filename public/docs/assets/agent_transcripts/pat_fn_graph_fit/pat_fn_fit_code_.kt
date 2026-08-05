import afield.stdlib.Functions
import afield.stdlib.fitGradient
import afield.stdlib.graphNode

with(Functions) {
    val target = patFn("g:(0.9,0.18) g:(0.1,0.18)", cps = 1.0, range = 0.9)
    // Right structure, wrong numbers — wrong heights and wrong widths.
    val seed = patFn("g:(0.7,0.25) g:(0.3,0.12)", cps = 1.0, range = 0.9)

    val run = fitGradient(target, seed, domain = 0.0..2.0, iterations = 200, lr = 0.02, samples = 96)

    // A pat Fn runs to +infinity, so the plot is told which window to sweep.
    plot(target, 0.0..2.0, channel = "1 target", title = "target")
    plot(run.fitted, 0.0..2.0, channel = "2 fitted", title = "fitted")

    val report = """
    seed    ${seed.graphNode().strings["source"]}
    fitted  ${run.fitted.graphNode().strings["source"]}
    RMSE    ${"%.4f".format(run.initialError)} -> ${"%.4f".format(run.finalError)}
    """.trimIndent()
    println(report)
    display("<pre>" + report + "</pre>")

    run   // Field renders the FnFitRun: RMSE before -> after + a loss sparkline
}
