// Rosenbrock's curved valley is the classic separator: a first-order method zig-zags
// across it, a curvature model plus a line search walks along it.
val rosenbrock: (List<AD>) -> AD = { p -> (1.0 - p[0]).sq() + 100.0 * (p[1] - p[0].sq()).sq() }
val start = doubleArrayOf(-1.2, 1.0)
val budget = 300

val quiet = OptimizeOptions(iterations = budget, showProgress = false)
val sgdRun = minimize(start, quiet.copy(method = OptimizeMethod.SGD, learningRate = 0.0012, momentum = 0.92), rosenbrock)
val adamRun = minimize(start, quiet.copy(method = OptimizeMethod.ADAM, learningRate = 0.05), rosenbrock)
val lbfgsRun = minimize(start, quiet.copy(method = OptimizeMethod.LBFGS), rosenbrock)

// Losses span 30 orders of magnitude by the end, so plot log10 to see anything at all.
fun logLoss(values: List<Double>) = values.map { kotlin.math.log10(kotlin.math.max(it, 1e-32)) }

showSignals(
    "SGD + momentum" to logLoss(sgdRun.losses),
    "Adam" to logLoss(adamRun.losses),
    "L-BFGS" to logLoss(lbfgsRun.losses),
    channel = "1 trajectories",
    title = "log10 Rosenbrock loss per iteration, from (-1.2, 1.0)",
    xTitle = "iteration",
    yTitle = "log10 loss"
)

fun report(label: String, run: OptimizeRun) =
    label + ": value " + run.value + " at " + run.x.toList() +
    " after " + run.iterations + " iterations / " + run.evaluations +
    " evaluations (" + run.reason + ")"

report("SGD+momentum", sgdRun) + "\n" +
report("Adam", adamRun) + "\n" +
report("L-BFGS", lbfgsRun)
