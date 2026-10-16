// A closed curve with three dials: how far it wobbles, where the wobble starts, and how much
// it is squashed sideways. One setting of the dials is the target; pretend we cannot see it.
fun curve(p: Map<String, Double>) =
    (0..300).map { i ->
        val t = 2 * PI * i / 300
        val r = 1 + p["wobble"]!! * cos(5 * t + p["phase"]!!)
        (p["squash"]!! * r * cos(t)) to (r * sin(t))
    }
val target = curve(mapOf("wobble" to 0.32, "phase" to 1.1, "squash" to 0.8))
fun misfit(p: Map<String, Double>) =
    curve(p).zip(target).sumOf { (a, b) ->
        (a.first - b.first).pow(2) + (a.second - b.second).pow(2)
    } / target.size

// Each step fits a Gaussian process to every try so far and evaluates where it expects
// the biggest improvement. Thirty tries, minimising.
val opt = GpOptimizer("wobble" to 0.0..0.6, "phase" to 0.0..2 * PI, "squash" to 0.4..1.4, minimize = true)
repeat(30) { opt.step { misfit(it) } }

fun draw(pts: List<Pair<Double, Double>>, dx: Double, colour: Vec4, width: Double) =
    FLine().also { f ->
        f.breakNext()
        pts.forEach { (x, y) -> f.lineTo(dx + 100 * x, -100 * y) }
        f.color = colour
        f.thickness = width
    }
val grey = vec(0.6, 0.6, 0.6, 1.0)
lines(
    [draw(target, 0.0, grey, 6.0)] +
        opt.history.take(5).map { draw(curve(it.parameters), 0.0, vec(0.2, 0.4, 0.9, 0.35), 1.0) } +
        draw(target, 320.0, grey, 6.0) +
        draw(curve(opt.best!!.parameters), 320.0, vec(0.9, 0.2, 0.2, 1.0), 2.0),
    w = 640.0,
    h = 300.0,
    fitToSize = true,
)
showChart(
    "best misfit so far" to opt.bestSoFar.map { log10(it) },
    channel = "convergence",
    title = "log10 misfit after each try",
    xLabel = "try",
    yLabel = "log10 misfit",
)

println(
    "best after 30 tries: misfit %.2e at %s"
        .format(
            opt.best!!.value,
            opt.best!!.parameters.mapValues { "%.3f".format(it.value) },
        ),
)
println("first five tries (blue, left): " + opt.history.take(5).joinToString { "%.3f".format(it.value) })
