import afield.stdlib.Functions
import afield.stdlib.FnGradientMode

// Field's Fn is a closure over Double, so it cannot be differentiated by overloading its
// arithmetic. But every Fn the stdlib builds carries an inspectable graph, and
// autodiff_fn.kt re-interprets THAT in AD arithmetic. Before this existed, every gradient
// in Field was a central difference.
with(Functions) {
    val target = cos(2) * sin(1.5, amplitude = 0.8, offset = 0.2)
    val seed = cos(1.9) * sin(1.3, amplitude = 1.0)

    // Identical call, identical optimiser, identical step size. Only the gradient differs.
    val fd = fitGradient(target, seed, iterations = 400, lr = 0.05)
    val exact = fitGradient(target, seed, iterations = 400, lr = 0.05, gradient = FnGradientMode.EXACT)

    // The exact path never trusts itself: it re-checks its own interpreter against
    // Fn.eval on the loss grid before switching over, and refuses (or falls back under
    // AUTO) rather than returning a confident wrong gradient. Ask it why, for a seed
    // built from a piecewise oscillator it cannot differentiate:
    val refusal = exactFitRefusal(target, saw(1.0, amplitude = 0.8), Functions.Domain.DEFAULT)

    plot(target, channel = "1 target", title = "target   cos(2) * sin(1.5)")
    plot(exact.fitted, channel = "2 fitted", title = "fitted, exact reverse-mode gradients")
    showSignals(
        "finite differences" to fd.losses,
        "exact reverse mode" to exact.losses,
        channel = "3 loss",
        title = "RMSE per Adam step - same optimiser, different gradient",
        xTitle = "step",
        yTitle = "RMSE"
    )

    "finite differences: RMSE " + fd.initialError + " -> " + fd.finalError +
    " in " + fd.evaluations + " loss evaluations\n" +
    "exact reverse mode:  RMSE " + exact.initialError + " -> " + exact.finalError +
    " in " + exact.evaluations + " loss evaluations (usedExactGradient=" + exact.usedExactGradient + ")\n" +
    "a saw-based seed is refused, with a reason: " + refusal
}
