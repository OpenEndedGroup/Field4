// exp(t·u) summed as a power series, once with Field's Quat and once in the bicomplex numbers.
// Both are four numbers (real, i, j, k) with i² = j² = -1; they differ in what ij and ji are.
fun quatExp(u: Quat, t: Double): Quat {
    var sum = Quat(0f, 0f, 0f, 0f)
    var term = Quat() // the identity, 1
    for (n in 1..40) {
        sum = sum + term
        term = term * u * (t / n)
    }
    return sum
}

// Bicomplex: ij = ji = k, so k² = +1. Stored as (real, i, j, k).
fun biTimes(p: Vec4, q: Vec4) = vec(
    p.x * q.x - p.y * q.y - p.z * q.z + p.w * q.w,
    p.x * q.y + p.y * q.x - p.z * q.w - p.w * q.z,
    p.x * q.z + p.z * q.x - p.y * q.w - p.w * q.y,
    p.x * q.w + p.w * q.x + p.y * q.z + p.z * q.y,
)

fun biExp(u: Vec4, t: Double): Vec4 {
    var sum = vec(0, 0, 0, 0)
    var term = vec(1, 0, 0, 0)
    for (n in 1..40) {
        sum = sum + term
        term = biTimes(term, u * (t / n).toFloat())
    }
    return sum
}

// Draw (real, i, j) in an oblique projection and colour by k: blue for k < 0, red for k > 0.
val s = 55.0
fun path(points: List<Vec4>, ox: Double) =
    points
        .zipWithNext()
        .filter { (a, _) -> a.length() < 3.5 }
        .map { (a, b) ->
            fun at(p: Vec4) = vec(ox + s * (p.y + 0.45 * p.x), -s * (p.z + 0.3 * p.x))
            val c = (a.w * 0.8).coerceIn(-1.0, 1.0)
            fline {
                moveTo(at(a))
                lineTo(at(b))
                color = vec(0.6 + 0.4 * c, 0.65 - 0.25 * kotlin.math.abs(c), 0.6 - 0.4 * c, 0.95)
                thickness = 1.8
            }
        }

val ts = (0..240).map { it * 2 * Math.PI / 240 }
val angles = (0..6).map { it * Math.PI / 6 } // u = cos θ·i + sin θ·j, a unit "imaginary"

val quaternion = angles.flatMap { th ->
    val u = Quat(kotlin.math.cos(th).toFloat(), kotlin.math.sin(th).toFloat(), 0f, 0f)
    path(ts.map { quatExp(u, it).let { q -> vec(q.w, q.x, q.y, q.z) } }, 0.0)
}
val bicomplex = angles.flatMap { th ->
    val u = vec(0.0, kotlin.math.cos(th), kotlin.math.sin(th), 0.0)
    path(ts.map { biExp(u, it) }, 230.0)
}

lines(*(quaternion + bicomplex).toTypedArray(), w = 760.0, h = 340.0, fitToSize = true)
