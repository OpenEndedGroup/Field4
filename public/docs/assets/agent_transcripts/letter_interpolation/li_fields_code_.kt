import afield.stdlib.FlowCoupling
import afield.stdlib.FlowMatchOptions
import afield.stdlib.contourComponents
import afield.stdlib.flowMatch

prop<afield.stdlib.FlowMatchExport>(fieldA)
prop<afield.stdlib.FlowMatchExport>(fieldB)

val LETTERS = "C" to "S"
val N = 512

// Both fields must start from the *same* cloud. That is what gives a particle one start
// point and two destinations, and it is also what makes the two normalisation frames
// agree -- `blendWith` refuses to mix two coordinate systems, so this is load-bearing
// rather than tidy.
val source = (0 until N).map {
    val a = 2 * Math.PI * it / N
    vec(Math.cos(a), Math.sin(a), 0.0)
}

// A letter as N points in arc-length order, centred and scaled to unit RMS radius so
// that both letters live in the same frame as the circle.
fun glyph(ch: String): List<Vec3> {
    val parts = contourComponents(layoutText(ch, typeface("Helvetica", 300.0)).toFLines(), total = N, seed = 3)
    if (parts.size != 1) error("'$ch' has ${parts.size} closed contours; this scene pairs one circle with one contour, so pick a letter like C, S, E, F, L, T, V or Z")
    val p = parts[0]
    val cx = p.map { it.x.toDouble() }.average()
    val cy = p.map { it.y.toDouble() }.average()
    val rms = Math.sqrt(p.sumOf { (it.x - cx) * (it.x - cx) + (it.y - cy) * (it.y - cy) } / p.size)
    return p.map { vec((it.x - cx) / rms, (it.y - cy) / rms, 0.0) }
}

// Rotate and if necessary reverse the contour so index i starts near circle index i.
// Without this each letter carries an arbitrary rotation relative to the circle, and --
// worse for this scene -- a *different* one, so the two fields would disagree about
// which part of the circle becomes which part of the glyph and the interpolation would
// be comparing nothing.
fun align(contour: List<Vec3>): List<Vec3> {
    var best = contour
    var bestCost = Double.MAX_VALUE
    for (reversed in listOf(false, true)) {
        val base = if (reversed) contour.reversed() else contour
        for (shift in 0 until N) {
            var cost = 0.0
            for (i in 0 until N) {
                val q = base[(i + shift) % N]; val s = source[i]
                cost += (q.x - s.x) * (q.x - s.x) + (q.y - s.y) * (q.y - s.y)
                if (cost > bestCost) break
            }
            if (cost < bestCost) { bestCost = cost; best = List(N) { base[(it + shift) % N] } }
        }
    }
    return best
}

// The same seed in both calls. Smile's Xavier initialisation is drawn from a generator
// that `flowMatch` reseeds, so this really is the same starting point twice.
fun train(target: List<Vec3>) = flowMatch(
    target, source,
    FlowMatchOptions(steps = 9000, coupling = FlowCoupling.PAIRED, seed = 7, hidden = intArrayOf(64, 64)),
).export()

fieldA = train(align(glyph(LETTERS.first)))
fieldB = train(align(glyph(LETTERS.second)))
println("${LETTERS.first} and ${LETTERS.second}: ${fieldA.network.parameterCount} parameters each, one source of ${fieldA.sourceCount} points")
