import afield.stdlib.Functions
import afield.stdlib.FnIntervals
import afield.stdlib.StudyEvent
import afield.stdlib.certifiedEquals
import afield.stdlib.certifiedNeverBelow
import afield.stdlib.certifiedNeverExceeds
import afield.stdlib.certifiedRange
import kotlin.math.PI

// The function workbench's worked example (public/docs/assets/function_conversation/final.kt),
// twice. `lambda` is its channel code, verbatim: one fn { } per channel, which the interval walk
// refuses. `formula` is the same function from formula-bearing ops. The only construct that needed
// a lambda was the piecewise event selection; here it is exact 0/1 `step` indicators, with every
// product and sum in the lambda's order, so the two agree bit for bit.
val events = listOf(
    StudyEvent("e1", 185.0, 100.0, 30.0, 100.0), StudyEvent("e2", 785.0, 100.0, 30.0, 100.0),
    StudyEvent("e3", 1425.0, 200.0, 30.0, 100.0), StudyEvent("e5", 2585.0, 100.0, 30.0, 100.0),
    StudyEvent("e6", 3185.0, 100.0, 30.0, 100.0))
val names = listOf("x", "y", "z")

fun smooth01(value: Double): Double { val u = value.coerceIn(0.0, 1.0); return u * u * u * (10.0 + u * (-15.0 + 6.0 * u)) }
fun backgroundOf(seconds: Double, channel: Int): Double {
    val drift = 2.0 * PI / 3.0 * kotlin.math.sin(2.0 * PI * seconds / 100.0)
    val phase = 2.0 * PI * seconds / 40.0 + (channel - 1) * drift
    return 0.45 + 0.20 * kotlin.math.sin(phase) + 0.04 * kotlin.math.sin(2.0 * PI * seconds / 230.0)
}
fun shoulderOf(base: Double, age: Double, span: Double): Double {
    val u = (age / span).coerceIn(0.0, 1.0)
    val cycles = 0.06 * age + 0.5 * (0.80 - 0.06) * age * age / span
    val pulse = 0.5 - 0.5 * kotlin.math.cos(2.0 * PI * cycles)
    val strength = smooth01(u)
    val pulsed = (1.0 - strength) * base + strength * (0.25 + (1.0 - 0.25) * pulse)
    val merge = smooth01((u - (1.0 - 0.20)) / 0.20)
    return (1.0 - merge) * pulsed + merge
}
fun lambda(index: Int, name: String) = with(Functions) {
    fn(0.0..3600.0, name) { seconds ->
        val base = backgroundOf(seconds, index)
        val event = events.firstOrNull { seconds >= it.start && seconds <= it.end }
        when {
            event == null -> base
            seconds < event.plateauStart -> {
                val age = seconds - event.start
                val lag = if (event.id == "e3" && name == "y" && age < 180.0) {
                    val u = age / 180.0; val v = 1.0 - u; 6.0 * 64.0 * u * u * u * v * v * v
                } else 0.0
                shoulderOf(base, age - lag, event.rise)
            }
            seconds <= event.plateauEnd -> 1.0
            else -> shoulderOf(base, event.end - seconds, event.fall)
        }
    }
}

fun formula(index: Int, name: String) = with(Functions) {
    val all = Functions.Domain.ALL
    fun k(v: Double) = c(v, all)
    val t = identity(all)
    val one = k(1.0)
    fun sinOf(p: Functions.Fn) = sin(frequency = k(0.0), phase = p) // sin(0*x + p) is sin(p), exactly
    fun cosOf(p: Functions.Fn) = cos(frequency = k(0.0), phase = p)
    fun smooth(v: Functions.Fn): Functions.Fn { val u = v.clamp(0.0, 1.0); return u * u * u * (k(10.0) + u * (k(-15.0) + k(6.0) * u)) }
    val drift = k(2.0 * PI / 3.0) * sinOf(k(2.0 * PI) * t / k(100.0))
    val base = k(0.45) + k(0.20) * sinOf(k(2.0 * PI) * t / k(40.0) + k((index - 1).toDouble()) * drift) +
        k(0.04) * sinOf(k(2.0 * PI) * t / k(230.0))
    fun shoulder(age: Functions.Fn, span: Double): Functions.Fn {
        val u = (age / k(span)).clamp(0.0, 1.0)
        val cycles = k(0.06) * age + k(0.5 * (0.80 - 0.06)) * age * age / k(span)
        val pulse = k(0.5) - k(0.5) * cosOf(k(2.0 * PI) * cycles)
        val strength = smooth(u)
        val pulsed = (one - strength) * base + strength * (k(0.25) + k(1.0 - 0.25) * pulse)
        val merge = smooth((u - k(1.0 - 0.20)) / k(0.20))
        return (one - merge) * pulsed + merge
    }
    fun from(a: Double) = step(a, all) // exactly [x >= a]; [x <= b] is 1 - from(nextUp(b))
    var sum: Functions.Fn = k(0.0) * t
    var inside: Functions.Fn = k(0.0)
    for (e in events) {
        val age = t - k(e.start)
        val local = if (e.id == "e3" && name == "y") {
            val u = age / k(180.0); val v = one - u
            age - (k(180.0) - age).sign().clamp(0.0, 1.0) * (k(6.0 * 64.0) * u * u * u * v * v * v)
        } else age
        sum = sum + ((from(e.start) - from(e.plateauStart)) * shoulder(local, e.rise) +
            (from(e.plateauStart) - from(Math.nextUp(e.plateauEnd))) * one +
            (from(Math.nextUp(e.plateauEnd)) - from(Math.nextUp(e.end))) * shoulder(k(e.end) - t, e.fall))
        inside = inside + (from(e.start) - from(Math.nextUp(e.end)))
    }
    sum + (one - inside) * base
}

val rows = mutableListOf<List<Any?>>()
for ((i, name) in names.withIndex()) {
    val l = lambda(i, name)
    val f = formula(i, name)
    val grid = (0..72_000).map { it * 0.05 }
    val mismatches = grid.count { l(it).toRawBits() != f(it).toRawBits() }
    val holds = events.count { f.certifiedEquals(1.0, it.plateauStart, it.plateauEnd).proven }
    val above = f.certifiedNeverExceeds(1.0, 0.0, 3600.0)
    val witness = above.witness!!
    rows += listOf(name, "${FnIntervals.unsupportedOps(l)} / ${FnIntervals.unsupportedOps(f)}",
        "$mismatches of ${grid.size}", "$holds of ${events.size} PROVEN",
        f.certifiedNeverBelow(0.0, 0.0, 3600.0).kind.name,
        "${above.kind} at $witness: lambda ${l(witness)}")
}
display("claims", Table(listOf("ch", "refused ops (lambda / formula)", "bit mismatches", "== 1 on plateau", ">= 0", "<= 1"),
    title = "the worked example's claims, on its formula rewrite")
    .rows(rows)
    .role("<= 1", CellRole.WARN))

// How far above 1: certifiedRange's proven bound is the certificate for <= 1 + 1e-9 (x only; ~11 s).
val xMax = formula(0, "x").certifiedRange(0.0, 3600.0, tolerance = 9e-10).max!!
display("max", Table(listOf("what", "value"), title = "channel x, the whole hour")
    .row("max reached", xMax.value.toString()).row("at x", xMax.witness.toString())
    .row("proven bound", xMax.bound.toString())
    .row("enclosures", xMax.enclosures).role("value", CellRole.ACCENT))
