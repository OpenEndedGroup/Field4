import afield.solidsl.*

val body = cuboid(1.0, 1.0, 0.4) - cylinder(1.2, 0.3).at(0.3, 0.0, 0.0)

// The differentiable reading is TOTAL on the algebra — every node with a field form has a
// differentiable form, because AD is closed over everything Field3 uses. So the refusals
// are not in the lowering. They are in the *parameterisation*, and there are seven.
println("---- seven ways a binding can refuse, each with a reason ----")
listOf<Pair<String, SolidLowering<*>>>(
    "a path that does not exist" to body.parameterize { param("r", "Difference/0/Cuboid.w") },
    "a predicate that matches nothing" to body.parameterize { param("r") { it.node == "Torus" } },
    "one name declared twice" to body.parameterize {
        param("r", "Difference/0/Cuboid.x"); param("r", "Difference/0/Cuboid.y")
    },
    "two names for one number" to body.parameterize {
        param("a", "Difference/0/Cuboid.x"); param("b", "Difference/0/Cuboid.x")
    },
    "tied slots with different seeds" to body.parameterize { param("size") { it.node == "Cuboid" } },
    "a Scale factor at zero" to sphere(0.5).sized(1.0, 1.0, 1.0)
        .parameterize { param("s", "Scale.x", seed = 1e-9) },
    "a blend radius at zero" to smoothUnion(sphere(0.4), sphere(0.4).at(0.5, 0.0, 0.0), 0.2)
        .parameterize { param("k", "SmoothUnion.k", seed = 0.0) },
).forEach { (what, l) ->
    println("  $what:")
    println("    ${l.refusalOrNull()}")
}
println()
println("A SolidLowering.Refused rather than a thrown exception, for SolidExact.kt's own")
println("reason: a null invites a caller to substitute something plausible, and an exception")
println("loses the reason at the first runCatching. A mis-bound parameter is exactly the")
println("failure that presents as 'the optimiser ran and nothing happened'.")
println()

// ---- and now the failure mode that is NOT a refusal -----------------------------------
// A parameter can be perfectly legal and still have a gradient of exactly zero, which
// presents as a CONVERGED optimisation. This is the thing to know about inverse design.
val outer = sphere(1.0)
val inner = sphere(0.25)                 // entirely swallowed by `outer`
val samples = outer.sampleField(count = 800, seed = 3)

fun deadUnder(expr: SolidExpr, path: String): SolidFit {
    val t = expr.parameterize { param("inner", path) }.orThrow()
    return t.fitParameters(samples, doubleArrayOf(0.25), SolidFitOptions(iterations = 1))
}

println("---- a swallowed child, under two kinds of union ----")
val hard = deadUnder(unionOf(listOf(outer, inner)), "Union/1/Sphere.radius")
val soft = deadUnder(smoothUnion(outer, inner, 0.9), "SmoothUnion/b/Sphere.radius")
println("  hard Union      dead: ${hard.deadParameters}")
println("  smoothUnion     dead: ${soft.deadParameters}")
println()
println("A pointwise min gives the WHOLE derivative to whichever child is nearest. A child")
println("that is nowhere nearest receives exactly 0.0 from every sample, and a descent on")
println("it reports convergence immediately, because the gradient really is zero.")
println()
println("A smooth blend has no kink at all — it is C1 everywhere — so every child within k")
println("of the seam contributes. That is the inversion this reading turns on: SmoothUnion")
println("and SmoothDifference are the two nodes the EXACT lowering refuses outright, and")
println("they are the two this one is best at. If a shape is going to be fitted, those are")
println("the operators to write it with.")
