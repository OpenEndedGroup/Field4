import afield.solidsl.*

// ---- the shape, written once, as a value ------------------------------------------
// A ball blended onto a rod. Change a number and everything below follows, because a
// SolidExpr is a value rather than a closure — which is the whole of afield.solidsl.
val part = smoothUnion(sphere(0.45), cylinder(1.90, 0.16).at(0.0, 0.0, 0.0), k = 0.18)
// -------------------------------------------------------------------------------------

// Every Double in the tree that a parameter could be bound to, at its structural address.
// There are no names inside a SolidExpr — it is an anonymous tree of data classes — so the
// only stable way to point at the rod's radius is to say where it is.
println("---- what is bindable ----")
part.numericSlots().forEach { println("  $it") }
println()
println("`segments` is absent, and that is a refusal rather than an oversight: it is an")
println("integer facet count that toScalarField() ignores completely, so there is no")
println("derivative to take. Try sphere(0.45, segments = 64).numericSlots().")
println()

// Promote three of them into named parameters. `barRadius` drives BOTH radii, which is how
// a symmetry constraint is written: "the rod is a cylinder, not a frustum", in the
// parameterisation rather than in a comment.
val template = part.parameterize {
    param("sphereRadius", "SmoothUnion/a/Sphere.radius")
    param("barHeight", "SmoothUnion/b/Translate/Cylinder.height")
    param("barRadius") { it.node == "Cylinder" && it.field.startsWith("radius") }
}.orThrow()

println("---- the template ----")
print(template.report())
println()

// The gradient at a point — position AND parameters, from one reverse-mode tape.
val g = template.gradientAt(0.9, 0.0, 0.0, template.seed)
println("---- the gradient at (0.9, 0, 0) ----")
println("  $g")
println("  the position gradient is the surface normal, unnormalised")
println("  the parameter gradient says how the distance HERE moves when each number moves")
println()

// And the second opinion, computed on the spot rather than remembered. `usableComponents`
// is the oracle declining to have an opinion where a central difference disagrees with
// itself between h and h/2 — at a sphere's own centre, on a polygon's outline, within h of
// any kink. A census that did not count those would pass while checking nothing.
println("---- analytic vs central differences, right now ----")
val probes = listOf(
    Triple(0.9, 0.0, 0.0),
    Triple(0.0, 0.0, 1.2),
    Triple(0.31, -0.44, 0.62),
    Triple(0.0, 0.0, 0.0),      // the ball's own centre
)
probes.forEach { (x, y, z) ->
    val c = template.checkGradient(x, y, z)
    println("  dParam (%5.2f, %5.2f, %5.2f)  %s".format(x, y, z, c))
}
println()

// The same four points, for the POSITION gradient — and the last one is the interesting
// one. At the ball's own centre sqrt(x2+y2+z2) has an infinite derivative, so the analytic
// answer is not a number and the check reports 0/3 usable rather than pretending. The
// PARAMETER gradient at that same point is exact: d/d(radius) = -1 everywhere, including
// there. That asymmetry is why fitting works at all on shapes full of radial primitives.
probes.forEach { (x, y, z) ->
    val c = template.checkPositionGradient(x, y, z)
    println("  dPos   (%5.2f, %5.2f, %5.2f)  %s".format(x, y, z, c))
}
println()

// The third notes function in this package, beside fieldNotes() and allRefusals(). Every
// place the derivative is a choice, and what was chosen.
println("---- where the derivative is a choice ----")
part.gradientNotes().forEach { println("  - $it") }
