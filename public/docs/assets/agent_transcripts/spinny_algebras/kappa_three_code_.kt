// Keep i² = -1 and ij = -ji = k, but let j² = κ be any number. κ = -1 is the quaternions.
// Elements are (real, i, j, k); with these rules k² = κ as well.
fun times(p: Vec4, q: Vec4, kappa: Double): Vec4 {
    val K = kappa.toFloat()
    return vec(
        p.x * q.x - p.y * q.y + K * (p.z * q.z + p.w * q.w),
        p.x * q.y + p.y * q.x + K * (p.w * q.z - p.z * q.w),
        p.x * q.z + p.z * q.x - p.y * q.w + p.w * q.y,
        p.x * q.w + p.w * q.x + p.y * q.z - p.z * q.y,
    )
}

fun exp(u: Vec4, t: Double, kappa: Double): Vec4 {
    var sum = vec(0, 0, 0, 0)
    var term = vec(1, 0, 0, 0)
    for (n in 1..40) {
        sum = sum + term
        term = times(term, u * (t / n).toFloat(), kappa)
    }
    return sum
}

val s = 45.0
fun panel(kappa: Double, ox: Double): List<FLine> {
    val ts = (0..240).map { it * 2 * Math.PI / 240 }
    return (0..6).flatMap { step ->
        val th = step * Math.PI / 6
        val u = vec(0.0, kotlin.math.cos(th), kotlin.math.sin(th), 0.0)
        ts.map { exp(u, it, kappa) }
            .zipWithNext()
            .filter { (a, _) -> a.length() < 3.5 }
            .map { (a, b) ->
                fun at(p: Vec4) = vec(ox + s * (p.y + 0.45 * p.x), -s * (p.z + 0.3 * p.x))
                val c = (a.w * 0.8).coerceIn(-1.0, 1.0)
                fline {
                    moveTo(at(a))
                    lineTo(at(b))
                    color = vec(0.6 + 0.4 * c, 0.65 - 0.25 * kotlin.math.abs(c), 0.6 - 0.4 * c, 0.95)
                    thickness = 1.6
                }
            }
    }
}

lines(
    *(panel(-1.0, 0.0) + panel(0.0, 260.0) + panel(1.0, 520.0)).toTypedArray(),
    w = 760.0,
    h = 280.0,
    fitToSize = true,
)
