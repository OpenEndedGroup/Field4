# Field Document: autodiff_worked_example

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 5 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. three descents, one valley

<!-- field-box id="autodiff_optimizers" index="0" -->

### Narrative

From `code`

Rosenbrock's curved valley is the classic separator: a first-order method zig-zags
across it, a curvature model plus a line search walks along it.

```edn [field-box id="autodiff_optimizers" role="properties"]
{"id" "autodiff_optimizers" "index" 0 "name" "three descents, one valley" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "three descents, one valley" "_tombstone" false "frame" #afield/rect [60.0 1800.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "autodiff_optimizers_code_.kt" "lines" 32 "characters" 1493 "sha256" "b490c94b7f8276c5a163f3afde9c473a9a60d4320906f14087c80ee61f7df154"}]}
```

```kotlin [field-property box="autodiff_optimizers" name="code" path="autodiff_optimizers_code_.kt"]
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
```

## 2. exact gradients through the Fn graph

<!-- field-box id="autodiff_fn_fit" index="1" -->

```edn [field-box id="autodiff_fn_fit" role="properties"]
{"id" "autodiff_fn_fit" "index" 1 "name" "exact gradients through the Fn graph" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "exact gradients through the Fn graph" "_tombstone" false "frame" #afield/rect [60.0 3600.0 350.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "autodiff_fn_fit_code_.kt" "lines" 38 "characters" 2011 "sha256" "a2ba7189c56c18a5f40b3aa4cc3abac7fc61606ed3e96a09a6b3fbe446a4ca81"}]}
```

```kotlin [field-property box="autodiff_fn_fit" name="code" path="autodiff_fn_fit_code_.kt"]
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
```

## 3. a differentiable circle packing

<!-- field-box id="autodiff_pack" index="2" -->

### Narrative

From `code`

Overlap is a hinge: relu(r1 + r2 - distance)^2, zero when the discs are disjoint and
growing quadratically once they interpenetrate. Walls are the same shape. Every term is
differentiable, so the packing is a descent rather than a relaxation loop of nudges.

```edn [field-box id="autodiff_pack" role="properties"]
{"id" "autodiff_pack" "index" 2 "name" "a differentiable circle packing" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "a differentiable circle packing" "_tombstone" false "frame" #afield/rect [1400.0 1800.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "autodiff_pack_code_.kt" "lines" 60 "characters" 2473 "sha256" "e00e0046cdb4926e8f132998b700f0602ec9cffc644e81c580709dc4d9e795b5"}]}
```

```kotlin [field-property box="autodiff_pack" name="code" path="autodiff_pack_code_.kt"]
// Overlap is a hinge: relu(r1 + r2 - distance)^2, zero when the discs are disjoint and
// growing quadratically once they interpenetrate. Walls are the same shape. Every term is
// differentiable, so the packing is a descent rather than a relaxation loop of nudges.
val radii = doubleArrayOf(38.0, 32.0, 30.0, 26.0, 24.0, 22.0, 20.0, 18.0, 16.0, 14.0, 12.0, 10.0)
val boxWidth = 340.0
val boxHeight = 250.0
val packing = packCircles(radii, boxWidth, boxHeight, rngSeed = 5L)

// run.initialX is the seeded scatter the descent started from, so "before" is real.
val before = radii.indices.map {
    PackedCircle(vec(packing.run.initialX[2 * it], packing.run.initialX[2 * it + 1]), radii[it])
}

fun frame() = fline {
    moveTo(0.0, 0.0); lineTo(boxWidth, 0.0); lineTo(boxWidth, boxHeight); lineTo(0.0, boxHeight)
    close()
    color = vec(0.36, 0.44, 0.56, 0.7)
    thickness = 1.2
}
fun rings(circles: List<PackedCircle>, r: Double, g: Double, b: Double) = circles.map {
    it.toFLine(52).apply {
        color = vec(r, g, b, 0.95)
        thickness = 1.8
    }
}

fun worstOverlapOf(circles: List<PackedCircle>): Double {
    var worst = 0.0
    for (i in circles.indices) for (j in i + 1 until circles.size) {
        val d = kotlin.math.hypot(
            (circles[i].centre.x - circles[j].centre.x).toDouble(),
            (circles[i].centre.y - circles[j].centre.y).toDouble()
        )
        worst = kotlin.math.max(worst, circles[i].radius + circles[j].radius - d)
    }
    return worst
}

val window = Rect(-20.0, -20.0, boxWidth + 40.0, boxHeight + 40.0)
showPlaneGrid(
    "before\nseeded scatter, worst overlap " + worstOverlapOf(before) + " units" to
        (rings(before, 0.52, 0.58, 0.72) + frame()),
    "after\n" + packing.run.iterations + " L-BFGS iterations, worst overlap " + packing.worstOverlap + " units" to
        (rings(packing.circles, 0.42, 0.86, 0.62) + frame()),
    channel = "1 packing",
    columns = 2,
    cellWidth = 380,
    window = window
)
showSignals(
    "overlap + wall + pull loss" to packing.run.losses,
    channel = "2 loss",
    title = "packing loss per L-BFGS iteration",
    xTitle = "iteration"
)

"worst pairwise overlap " + worstOverlapOf(before) + " -> " + packing.worstOverlap + " units\n" +
"worst escape beyond the box wall: " + packing.worstEscape + " units\n" +
"loss " + packing.run.initialValue + " -> " + packing.run.value +
" in " + packing.run.iterations + " iterations (" + packing.run.reason + ")"
```

## 4. inverse kinematics on an FLine chain

<!-- field-box id="autodiff_ik" index="3" -->

### Narrative

From `code`

Forward kinematics is a cumulative sum of joint angles and a running position, which is
perfectly differentiable, so the inverse problem is just L-BFGS on |tip - target|^2.

```edn [field-box id="autodiff_ik" role="properties"]
{"id" "autodiff_ik" "index" 3 "name" "inverse kinematics on an FLine chain" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "inverse kinematics on an FLine chain" "_tombstone" false "frame" #afield/rect [1400.0 60.0 350.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "autodiff_ik_code_.kt" "lines" 62 "characters" 2826 "sha256" "5a6943c5f014f39745e4692cf8b897f1a763d60d5cf76f6ebf986df0bbc0b3ba"}]}
```

```kotlin [field-property box="autodiff_ik" name="code" path="autodiff_ik_code_.kt"]
// Forward kinematics is a cumulative sum of joint angles and a running position, which is
// perfectly differentiable, so the inverse problem is just L-BFGS on |tip - target|^2.
val chain = IkChain(vec(120.0, 300.0), doubleArrayOf(90.0, 80.0, 70.0, 55.0))
val target = vec(330.0, 150.0)
val restAngles = DoubleArray(chain.linkCount)

val solved = solveIk(chain, target)
val smoothed = solveIk(chain, target, options = IkOptions(smoothness = 2.0))

// A ring *and* a cross, both wider than the chain's own joint dots, so "the tip landed on
// the target" is visible rather than hidden under the end effector.
fun marker(p: Vec2, r: Double) = fline {
    moveTo(p.x - r * 1.7, p.y); lineTo(p.x - r * 0.5, p.y)
    moveTo(p.x + r * 0.5, p.y); lineTo(p.x + r * 1.7, p.y)
    moveTo(p.x, p.y - r * 1.7); lineTo(p.x, p.y - r * 0.5)
    moveTo(p.x, p.y + r * 0.5); lineTo(p.x, p.y + r * 1.7)
    for (i in 0..40) {
        val a = i / 40.0 * Math.PI * 2.0
        val px = p.x + r * kotlin.math.cos(a)
        val py = p.y + r * kotlin.math.sin(a)
        if (i == 0) moveTo(px, py) else lineTo(px, py)
    }
    color = vec(1.0, 0.42, 0.3, 1.0)
    thickness = 2.0
}
fun pose(line: FLine, r: Double, g: Double, b: Double) = line.apply {
    color = vec(r, g, b, 1.0)
    thickness = 3.5
    pointed = true
    pointSize = 3.0
}

// One shared window, so the three panels are actually comparable.
val window = Rect(40.0, 60.0, 400.0, 320.0)
// 26 units across is wider than the chain's own joint dots at this window scale, so the
// tip lands visibly *inside* the reticle rather than swallowing it.
val cross = marker(target, 26.0)

showPlaneGrid(
    "start pose\nall four joint angles zero - the arm points along +x" to
        listOf(pose(chain.toFLine(restAngles), 0.45, 0.55, 0.72), cross),
    "solved\nreach error " + solved.reachError + " units, " + solved.run.iterations + " L-BFGS iterations" to
        listOf(pose(chain.toFLine(solved.angles), 0.42, 0.86, 0.58), cross),
    "solved, smoothness = 2.0\nstraighter, and it costs " + smoothed.reachError + " units of reach" to
        listOf(pose(chain.toFLine(smoothed.angles), 0.98, 0.86, 0.32), cross),
    channel = "1 poses",
    columns = 3,
    cellWidth = 260,
    window = window
)
showSignals(
    "reach loss" to solved.run.losses,
    channel = "2 loss",
    title = "|tip - target|^2 per L-BFGS iteration",
    xTitle = "iteration"
)

"solved joint angles (radians, relative link to link): " + solved.angles.toList() + "\n" +
"tip landed at (" + solved.endEffector.x + ", " + solved.endEffector.y + "), target (" +
target.x + ", " + target.y + "), error " + solved.reachError + "\n" +
"with smoothness = 2.0 the pose straightens and the reach error rises to " + smoothed.reachError +
" - the two terms genuinely compete, and reachError always tells the truth"
```

## 5. reverse mode, forward mode, and the oracle

<!-- field-box id="autodiff_basics" index="4" -->

### Narrative

From `code`

AD is not a new numeric tower you have to learn — it is ordinary Kotlin arithmetic on a
scalar that happens to remember how it was made. `grad { }` returns a *function*.

```edn [field-box id="autodiff_basics" role="properties"]
{"id" "autodiff_basics" "index" 4 "name" "reverse mode, forward mode, and the oracle" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "reverse mode, forward mode, and the oracle" "_tombstone" false "frame" #afield/rect [60.0 60.0 390.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "autodiff_basics_code_.kt" "lines" 37 "characters" 1919 "sha256" "a99d56d9b22d88ab15c3be29e340402a579e35b093e97af1df85b38b9fe278e0"}]}
```

```kotlin [field-property box="autodiff_basics" name="code" path="autodiff_basics_code_.kt"]
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
```

