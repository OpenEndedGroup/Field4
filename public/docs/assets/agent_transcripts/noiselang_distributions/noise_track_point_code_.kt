import afield.math.distribution.*

// A point wanders, moving about 3 units a step, and is seen only through noise of about 12 units.
val steps = 60
val moves = normal(0, 3).samples(2 * steps, seed = 8)
val noise = normal(0, 12).samples(2 * steps, seed = 9)
val truth = ArrayList<Pair<Double, Double>>()
var tx = 200.0
var ty = 150.0
for (k in 0 until steps) {
    tx += moves[2 * k] + 1.5 // a slow drift to the right
    ty += moves[2 * k + 1]
    truth += tx to ty
}
val seen = truth.mapIndexed { k, (a, b) -> (a + noise[2 * k]) to (b + noise[2 * k + 1]) }

// Where is it? Two unknowns, a position. Between sightings it moves; each sighting is it, ±12.
val x = normal(200, 20)
val y = normal(150, 20)
val post = Posterior(x, y, particles = 2000)
val path = ArrayList<Pair<Double, Double>>()
for ((sx, sy) in seen) {
    post.evolve(x to x + 1.5 + normal(0, 3), y to y + normal(0, 3))
    post.observe(normal(x, 12), sx)
    post.observe(normal(y, 12), sy)
    path += post.E(x).value to post.E(y).value
}

// The truth, what was seen, the posterior's path, and 300 draws of where it is now
val drawn = post.samples(x, y, n = 300)
val pictures = [
    fline {
        moveTo(truth[0].first, truth[0].second)
        for ((a, b) in truth) lineTo(a, b)
        color = vec(1.0, 1.0, 1.0, 0.6)
    },
    fline {
        moveTo(path[0].first, path[0].second)
        for ((a, b) in path) lineTo(a, b)
        color = vec(0.0, 0.67, 1.0, 1.0)
    },
] +
        seen.map { (a, b) ->
            fline { circle(a, b, 0.8) }
                .apply {
                    filled = true
                    color = vec(1.0, 0.67, 0.0, 0.8)
                }
        } +
        (0 until 300).map { i ->
            fline { circle(drawn[0][i], drawn[1][i], 0.45) }
                .apply {
                    filled = true
                    color = vec(0.0, 0.67, 1.0, 0.35)
                }
        }
lines(pictures, channel = "track", w = 480.0, h = 300.0)

fun rms(ps: List<Pair<Double, Double>>) = kotlin.math.sqrt(
    ps.indices.sumOf { k ->
        val dx = ps[k].first - truth[k].first
        val dy = ps[k].second - truth[k].second
        dx * dx + dy * dy
    } / ps.size,
)
println("distance from the truth: sightings ${rms(seen).f1}, posterior ${rms(path).f1}")
