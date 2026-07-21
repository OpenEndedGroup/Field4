import afield.stdlib.Functions
import afield.stdlib.fitBest

with(Functions) {
    val target = cos(2) * sin(1.5, amplitude = 0.8, offset = 0.2)
    val seed   = cos(3.4) * sin(1.0, amplitude = 2.0)

    // fitBest = jittered restarts; escapes the frequency local-minimum a single
    // fitGradient would stall in. domain defaults to 0..1; no Functions.Domain(...) needed.
    val run = fitBest(target, seed, restarts = 16)

    plot(target,     channel = "1 target", title = "target  cos·sin")
    plot(run.fitted, channel = "2 fitted", title = "gradient-fitted (best of 16 restarts)")

    run   // Field renders the FnFitRun: RMSE before -> after + a loss sparkline
}
