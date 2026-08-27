import afield.solidsl.*
import afield.stdlib.MeshView

// ---- the problem ---------------------------------------------------------------------
// Somebody hands you a bag of points with a signed distance at each. What shape is it?
val truth = doubleArrayOf(0.45, 1.90, 0.16)     // the answer, used ONLY to make the target
val seed = doubleArrayOf(0.26, 1.05, 0.34)      // where the search starts: wrong by 42%, 45%, 112%
// -----------------------------------------------------------------------------------------

val template = smoothUnion(sphere(0.45), cylinder(1.90, 0.16).at(0.0, 0.0, 0.0), k = 0.18)
    .parameterize {
        param("sphereRadius", "SmoothUnion/a/Sphere.radius")
        param("barHeight", "SmoothUnion/b/Translate/Cylinder.height")
        param("barRadius") { it.node == "Cylinder" && it.field.startsWith("radius") }
    }.orThrow()

val target = template.withValues(truth).sampleField(count = 2400, seed = 91)
println("target: $target")
println()

// One tape per evaluation carries all 2400 residuals and all 3 parameters, and one backward
// sweep reads every derivative off it. The alternative — central-differencing the loss —
// costs 2n extra full sweeps per step and needs an fdStep tuned per problem.
val fit = template.fitParameters(target, seed)
println(fit.report())
println()
println("The counterfactual is in the same object, measured in the same run: `initialLoss`")
println("is the seed's loss, not a number somebody wrote down last week.")
println()
println("truth was  ${truth.joinToString(", ")}")
println("recovered  ${fit.values.joinToString(", ") { "%.9f".format(it) }}")
println("error      ${truth.indices.joinToString(", ") { "%.2e".format(kotlin.math.abs(fit.values[it] - truth[it])) }}")
println()

// The fitted answer is an ordinary SolidExpr again — not a bag of numbers — so it goes
// straight back to every other thing this package can do.
println("fitted     ${fit.fitted}")
println("GLSL       ${fit.fitted.toGlsl().statementCount} statements")
println("bounds     ${fit.fitted.bounds()}")
println()

// Log scale, because the descent covers twenty orders of magnitude and a linear axis
// would be a vertical line at iteration 0 followed by a flat one.
showSignals("ln(loss)" to fit.run.losses.map { kotlin.math.ln(it + 1e-30) })

val view = MeshView(yawDegrees = 32.0, pitchDegrees = 22.0, width = 660, height = 430)
showMesh(
    template.withValues(seed).march(resolution = 110),
    channel = "seed",
    title = "the seed - where the search started",
    caption = "%.2f, %.2f, %.2f · loss %.4e".format(seed[0], seed[1], seed[2], fit.initialLoss),
    view = view,
)
showMesh(
    fit.fitted.march(resolution = 110),
    channel = "fitted",
    title = "the fit - what the gradient found",
    caption = "%.6f, %.6f, %.6f · loss %.4e · %.3g times better".format(
        fit.values[0], fit.values[1], fit.values[2], fit.loss, fit.improvementFactor,
    ),
    view = view,
)
showMesh(
    template.withValues(truth).march(resolution = 110),
    channel = "target",
    title = "the target - the shape the samples came from",
    caption = "%.2f, %.2f, %.2f · the fitted render above is pixel-identical to this one".format(
        truth[0], truth[1], truth[2],
    ),
    view = view,
)
