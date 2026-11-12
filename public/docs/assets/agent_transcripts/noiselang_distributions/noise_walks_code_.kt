import afield.math.distribution.*

// One graph: heading drifts by an independent normal each step; x and y accumulate.
val steps = 40
var heading: Dist = Dist.dirac(0)
var x: Dist = Dist.dirac(0)
var y: Dist = Dist.dirac(0)
val xs = ArrayList<Dist>()
val ys = ArrayList<Dist>()
for (k in 0 until steps) {
    heading = heading + normal(0, 0.25)
    x = x + heading.cos() * 4
    y = y + heading.sin() * 4
    xs += x
    ys += y
}
val reach = (x * x + y * y).sqrt()

// 120 joint draws of every node: each sample is one whole path.
val walks = 120
val drawn = jointSamples(*(xs + ys).toTypedArray(), n = walks, seed = 7)
val paths =
    (0 until walks).map { w ->
        fline {
            moveTo(200, 150)
            for (k in 0 until steps) lineTo(200 + drawn[k][w], 150 - drawn[steps + k][w])
            color = vec(0.0, 0.67, 1.0, 0.35)
        }
    }
lines(paths, channel = "walks", w = 400.0, h = 300.0)

println("E(reach) = ${E(reach, n = 50_000)}   (a straight line would be ${steps * 4})")
__m("reach").distribution(reach, batch = 20_000, title = "distance from the start after $steps steps")
