# Field Document: solidsl_inverse

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. 2 - Inverse design - let a gradient find the numbers

<!-- field-box id="inverse" index="0" -->

```edn [field-box id="inverse" role="properties"]
{"id" "inverse" "index" 0 "name" "2 - Inverse design - let a gradient find the numbers" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 - Inverse design - let a gradient find the numbers" "_tombstone" false "frame" #afield/rect [700.0 60.0 600.0 136.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "inverse_code_.kt" "lines" 71 "characters" 3258 "sha256" "fd63c39182f8ca197c132f176fb446cfb30a19bb4d7f5db6164a1c43005ddd9e"}]}
```

```kotlin [field-property box="inverse" name="code" path="inverse_code_.kt"]
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
```

## 2. 1 - A solid, and its exact gradient

<!-- field-box id="gradient" index="1" -->

```edn [field-box id="gradient" role="properties"]
{"id" "gradient" "index" 1 "name" "1 - A solid, and its exact gradient" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 - A solid, and its exact gradient" "_tombstone" false "frame" #afield/rect [60.0 60.0 534.2 136.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "gradient_code_.kt" "lines" 72 "characters" 3607 "sha256" "b816cb0bcf6874371cafb56b95fc8611f21353ade1bbb4592dfbba561287abcc"}]}
```

```kotlin [field-property box="gradient" name="code" path="gradient_code_.kt"]
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
```

## 3. 3 - What refuses, and the one that does not

<!-- field-box id="refusals" index="2" -->

```edn [field-box id="refusals" role="properties"]
{"id" "refusals" "index" 2 "name" "3 - What refuses, and the one that does not" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 - What refuses, and the one that does not" "_tombstone" false "frame" #afield/rect [60.0 226.0 600.0 136.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "refusals_code_.kt" "lines" 60 "characters" 3490 "sha256" "e86c851feb04e0542f0d6656e44d9934d114a395b5acd5b0873482e0b6aec8ae"}]}
```

```kotlin [field-property box="refusals" name="code" path="refusals_code_.kt"]
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
```

