// AD is not a new numeric tower you have to learn — it is ordinary Kotlin arithmetic on a
// scalar that happens to remember how it was made. `grad { }` returns a *function*.
val rosenbrock: (List<AD>) -> AD = { p -> (1.0 - p[0]).sq() + 100.0 * (p[1] - p[0].sq()).sq() }
val gradient = grad(rosenbrock)
val at = doubleArrayOf(-1.2, 1.0)

// One expression, differentiated three ways that must agree.
val xs = (0..300).map { it / 300.0 * 2.0 - 1.0 }
val taylor = xs.map { x -> taylor2(x) { u -> exp(-u * u) * sin(6.0 * u) } }
val reverse = xs.map { x -> derivative(x) { u -> exp(-u * u) * sin(6.0 * u) } }
val oracle = xs.map { x ->
    val h = 1e-6
    val plain = { u: Double -> kotlin.math.exp(-u * u) * kotlin.math.sin(6.0 * u) }
    (plain(x + h) - plain(x - h)) / (2.0 * h)
}
val worstReverseVsForward = xs.indices.maxOf { kotlin.math.abs(taylor[it].first - reverse[it]) }
val worstVsOracle = xs.indices.maxOf { kotlin.math.abs(reverse[it] - oracle[it]) }

// f'' comes free with the forward pass, so all three curves are one sweep.
showSignals(
    "f" to taylor.map { it.value },
    "f' (reverse mode)" to reverse,
    "f'' (forward mode)" to taylor.map { it.second },
    x = xs,
    channel = "1 derivatives",
    title = "exp(-x^2)*sin(6x), its slope and its curvature - one sweep, three orders",
    xTitle = "x"
)

// The exact Hessian, via forward-over-nothing: one Dual2 pass gives vT H v, and
// polarisation recovers the off-diagonals. Not a finite difference of a gradient.
val h = hessian(at) { p -> (1.0 - p[0]).sq() + 100.0 * (p[1] - p[0].sq()).sq() }

"grad Rosenbrock at (-1.2, 1.0) = " + gradient(at).toList() + "\n" +
"exact Hessian there = [[" + h[0][0] + ", " + h[0][1] + "], [" + h[1][0] + ", " + h[1][1] + "]]\n" +
"reverse vs forward first derivative, worst of 301 samples: " + worstReverseVsForward + "\n" +
"reverse vs central-difference oracle, worst of 301 samples: " + worstVsOracle
