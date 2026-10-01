@file:afield.CompletionCategory("Function")

package afield.stdlib

import java.util.concurrent.ConcurrentHashMap

/**
 * Formulas for the ops whose shape is chosen by **data on the node** rather than by the op name:
 * `ease` and `tween` (an [Functions.Easing] in `strings["easing"]`) and `pulseTrain` (a
 * [Functions.PulseEdge], a [Functions.PulseProfile], `booleans["bipolar"]` and `longs["seed"]`).
 *
 * Each is a transcription of the closure in `Functions.kt` — `shapeAlpha`, `bounceOut`, `edgeRamp`,
 * `pulseProfileValue`, `pulseShape` and `pulseTrain`'s own body — operation for operation and in
 * Kotlin's association order, which the operator overloads below reproduce: `c3 * t * t * t` here is
 * `((c3·t)·t)·t` there. `FnShapedFormulaAgreementTest` holds every variant bit-identical to `Fn.eval`.
 * Formulas are built once per variant and cached.
 */
internal object FnShapedFormulas {

    private const val TAU = Math.PI * 2.0

    /** The ops this file declares, lower-cased. */
    val ops: Set<String> = setOf("ease", "tween", "pulsetrain")

    private val cache = ConcurrentHashMap<String, FnExpr>()

    /** The formula for [node], or null when it is not one of [ops] or names a variant this file does not know. */
    fun of(node: FnGraphNode): FnExpr? {
        val op = node.op.trim().lowercase()
        if (op !in ops) return null
        val key = "$op|${node.strings.toSortedMap()}|${node.booleans.toSortedMap()}|${node.longs.toSortedMap()}"
        cache[key]?.let { return it }
        val built = when (op) {
            "ease" -> easing(node.strings["easing"])?.let { e ->
                val source = child("source")
                shape(if (node.booleans["clampInput"] != false) FnExpr.Clamp01(source) else source, e)
            }
            "tween" -> easing(node.strings["easing"])?.let { e ->
                val t = shape(FnExpr.Clamp01(FnExpr.Alpha), e)
                child("from") + (child("to") - child("from")) * t
            }
            "pulsetrain" -> {
                val edge = node.strings["edge"]?.let { n -> Functions.PulseEdge.entries.firstOrNull { it.name == n } }
                val profile = node.strings["profile"]?.let { n -> Functions.PulseProfile.entries.firstOrNull { it.name == n } }
                if (edge == null || profile == null) null
                else pulseTrain(edge, profile, node.booleans["bipolar"] == true, node.longs["seed"] ?: 0L)
            }
            else -> null
        } ?: return null
        cache[key] = built
        return built
    }

    private fun easing(name: String?): Functions.Easing? =
        name?.let { n -> Functions.Easing.entries.firstOrNull { it.name == n } }

    // -- a little algebra over FnExpr, so the transcriptions read like the closures -------------

    private fun child(role: String): FnExpr = FnExpr.Child(role)
    private fun k(v: Double): FnExpr = FnExpr.Lit(v)
    private operator fun FnExpr.plus(o: FnExpr): FnExpr = FnExpr.Add(this, o)
    private operator fun FnExpr.plus(o: Double): FnExpr = FnExpr.Add(this, k(o))
    private operator fun Double.plus(o: FnExpr): FnExpr = FnExpr.Add(k(this), o)
    private operator fun FnExpr.minus(o: FnExpr): FnExpr = FnExpr.Sub(this, o)
    private operator fun FnExpr.minus(o: Double): FnExpr = FnExpr.Sub(this, k(o))
    private operator fun Double.minus(o: FnExpr): FnExpr = FnExpr.Sub(k(this), o)
    private operator fun FnExpr.times(o: FnExpr): FnExpr = FnExpr.Mul(this, o)
    private operator fun FnExpr.times(o: Double): FnExpr = FnExpr.Mul(this, k(o))
    private operator fun Double.times(o: FnExpr): FnExpr = FnExpr.Mul(k(this), o)
    private operator fun FnExpr.div(o: FnExpr): FnExpr = FnExpr.Div(this, o)
    private operator fun FnExpr.div(o: Double): FnExpr = FnExpr.Div(this, k(o))
    private operator fun FnExpr.unaryMinus(): FnExpr = FnExpr.Neg(this)
    private fun FnExpr.pow(p: Double): FnExpr = FnExpr.Pow(this, k(p))
    private fun Double.pow(e: FnExpr): FnExpr = FnExpr.Pow(k(this), e)
    private fun lt(a: FnExpr, b: Double, then: FnExpr, otherwise: FnExpr): FnExpr = FnExpr.IfLt(a, k(b), then, otherwise)
    private fun eq(a: FnExpr, b: Double, then: FnExpr, otherwise: FnExpr): FnExpr = FnExpr.IfEq(a, k(b), then, otherwise)
    private fun sin(a: FnExpr): FnExpr = FnExpr.Sin(a)
    private fun cos(a: FnExpr): FnExpr = FnExpr.Cos(a)
    private fun sqrt(a: FnExpr): FnExpr = FnExpr.Sqrt(a)
    private fun exp(a: FnExpr): FnExpr = FnExpr.Exp(a)

    /** `x >= e`, as Kotlin evaluates it: false when either side is NaN. */
    private fun geq(x: FnExpr, e: FnExpr, then: FnExpr, otherwise: FnExpr): FnExpr =
        FnExpr.IfLt(x, e, otherwise, FnExpr.IfEq(x, x, FnExpr.IfEq(e, e, then, otherwise), otherwise))

    // -- easings: Functions.shapeAlpha ----------------------------------------------------------

    private fun powIn(t: FnExpr, p: Double) = t.pow(p)
    private fun powOut(t: FnExpr, p: Double) = 1.0 - (1.0 - t).pow(p)
    private fun powInOut(t: FnExpr, p: Double) = lt(t, 0.5, 0.5 * (2.0 * t).pow(p), 1.0 - 0.5 * (2.0 - 2.0 * t).pow(p))

    private fun bounceOut(t: FnExpr): FnExpr {
        val n = 7.5625
        val d = 2.75
        fun piece(c: Double, add: Double): FnExpr { val u = t - c; return n * u * u + add }
        return lt(t, 1.0 / d, n * t * t,
            lt(t, 2.0 / d, piece(1.5 / d, 0.75),
                lt(t, 2.5 / d, piece(2.25 / d, 0.9375), piece(2.625 / d, 0.984375))))
    }

    private fun shape(t: FnExpr, easing: Functions.Easing): FnExpr {
        val c1 = 1.70158
        val c2 = c1 * 1.525
        val c3 = c1 + 1.0
        val c4 = TAU / 3.0
        val c5 = TAU / 4.5
        return when (easing) {
            Functions.Easing.LINEAR -> t
            Functions.Easing.QUAD_IN -> powIn(t, 2.0)
            Functions.Easing.QUAD_OUT -> powOut(t, 2.0)
            Functions.Easing.QUAD_IN_OUT -> powInOut(t, 2.0)
            Functions.Easing.CUBIC_IN -> powIn(t, 3.0)
            Functions.Easing.CUBIC_OUT -> powOut(t, 3.0)
            Functions.Easing.CUBIC_IN_OUT -> powInOut(t, 3.0)
            Functions.Easing.QUART_IN -> powIn(t, 4.0)
            Functions.Easing.QUART_OUT -> powOut(t, 4.0)
            Functions.Easing.QUART_IN_OUT -> powInOut(t, 4.0)
            Functions.Easing.QUINT_IN -> powIn(t, 5.0)
            Functions.Easing.QUINT_OUT -> powOut(t, 5.0)
            Functions.Easing.QUINT_IN_OUT -> powInOut(t, 5.0)
            Functions.Easing.SINE_IN -> 1.0 - cos(t * Math.PI / 2.0)
            Functions.Easing.SINE_OUT -> sin(t * Math.PI / 2.0)
            Functions.Easing.SINE_IN_OUT -> -(cos(Math.PI * t) - 1.0) * 0.5
            Functions.Easing.EXPO_IN -> eq(t, 0.0, k(0.0), 2.0.pow(10.0 * t - 10.0))
            Functions.Easing.EXPO_OUT -> eq(t, 1.0, k(1.0), 1.0 - 2.0.pow(-10.0 * t))
            Functions.Easing.EXPO_IN_OUT -> eq(t, 0.0, k(0.0), eq(t, 1.0, k(1.0),
                lt(t, 0.5, 2.0.pow(20.0 * t - 10.0) / 2.0, (2.0 - 2.0.pow(-20.0 * t + 10.0)) / 2.0)))
            Functions.Easing.CIRC_IN -> 1.0 - sqrt(1.0 - t * t)
            Functions.Easing.CIRC_OUT -> sqrt(1.0 - (t - 1.0) * (t - 1.0))
            Functions.Easing.CIRC_IN_OUT -> lt(t, 0.5,
                (1.0 - sqrt(1.0 - (2.0 * t) * (2.0 * t))) / 2.0,
                (sqrt(1.0 - (-2.0 * t + 2.0) * (-2.0 * t + 2.0)) + 1.0) / 2.0)
            Functions.Easing.BACK_IN -> c3 * t * t * t - c1 * t * t
            Functions.Easing.BACK_OUT -> 1.0 + c3 * (t - 1.0).pow(3.0) + c1 * (t - 1.0).pow(2.0)
            Functions.Easing.BACK_IN_OUT -> lt(t, 0.5,
                ((2.0 * t) * (2.0 * t) * ((c2 + 1.0) * 2.0 * t - c2)) / 2.0,
                ((2.0 * t - 2.0) * (2.0 * t - 2.0) * ((c2 + 1.0) * (t * 2.0 - 2.0) + c2) + 2.0) / 2.0)
            Functions.Easing.ELASTIC_IN -> eq(t, 0.0, k(0.0), eq(t, 1.0, k(1.0),
                -2.0.pow(10.0 * t - 10.0) * sin((t * 10.0 - 10.75) * c4)))
            Functions.Easing.ELASTIC_OUT -> eq(t, 0.0, k(0.0), eq(t, 1.0, k(1.0),
                2.0.pow(-10.0 * t) * sin((t * 10.0 - 0.75) * c4) + 1.0))
            Functions.Easing.ELASTIC_IN_OUT -> eq(t, 0.0, k(0.0), eq(t, 1.0, k(1.0), lt(t, 0.5,
                -(2.0.pow(20.0 * t - 10.0) * sin((20.0 * t - 11.125) * c5)) / 2.0,
                (2.0.pow(-20.0 * t + 10.0) * sin((20.0 * t - 11.125) * c5)) / 2.0 + 1.0)))
            Functions.Easing.BOUNCE_IN -> 1.0 - bounceOut(1.0 - t)
            Functions.Easing.BOUNCE_OUT -> bounceOut(t)
            Functions.Easing.BOUNCE_IN_OUT -> lt(t, 0.5,
                (1.0 - bounceOut(1.0 - 2.0 * t)) / 2.0,
                (1.0 + bounceOut(2.0 * t - 1.0)) / 2.0)
            Functions.Easing.SMOOTHSTEP -> t * t * (3.0 - 2.0 * t)
            Functions.Easing.SMOOTHERSTEP -> t * t * t * (t * (t * 6.0 - 15.0) + 10.0)
        }
    }

    // -- pulseTrain: Functions.edgeRamp / pulseProfileValue / pulseShape ------------------------

    private fun edgeRamp(t: FnExpr, edge: Functions.PulseEdge, curve: FnExpr): FnExpr {
        val u = FnExpr.Clamp01(t)
        return when (edge) {
            Functions.PulseEdge.HARD -> geq(u, k(1.0), k(1.0), k(0.0))
            Functions.PulseEdge.LINEAR -> u
            Functions.PulseEdge.SMOOTH -> u * u * (3.0 - 2.0 * u)
            Functions.PulseEdge.SINE -> 0.5 - 0.5 * cos(Math.PI * u)
            Functions.PulseEdge.EXP -> {
                val top = exp(curve * u) - 1.0
                val bot = exp(curve) - 1.0
                FnExpr.IfZero(curve, u, FnExpr.IfZero(bot, u, top / bot))
            }
        }
    }

    private fun profileValue(profile: Functions.PulseProfile, u: FnExpr, sigma: FnExpr): FnExpr {
        val t = FnExpr.Clamp01(u)
        return when (profile) {
            Functions.PulseProfile.RECT -> k(1.0)
            Functions.PulseProfile.TRIANGLE -> lt(t, 0.5, t * 2.0, 2.0 - t * 2.0)
            Functions.PulseProfile.SINE -> sin(Math.PI * t)
            Functions.PulseProfile.HANN -> 0.5 - 0.5 * cos(TAU * t)
            Functions.PulseProfile.GAUSSIAN -> {
                // `if (sigma <= 0.0) 0.15 else sigma`, which keeps a NaN sigma.
                val s = FnExpr.IfLt(k(0.0), sigma, sigma, FnExpr.IfEq(sigma, sigma, k(0.15), sigma))
                val z = (t - 0.5) / s
                exp(-0.5 * z * z)
            }
        }
    }

    private fun pulseShape(
        t: FnExpr, duty: FnExpr, rise: FnExpr, fall: FnExpr,
        edge: Functions.PulseEdge, curve: FnExpr, profile: Functions.PulseProfile, sigma: FnExpr,
    ): FnExpr {
        val d = FnExpr.Clamp01(duty)
        val tt = FnExpr.Clamp01(t)
        // `coerceAtLeast(0.0)` is `if (this < 0.0) 0.0 else this`.
        val r0 = FnExpr.IfLt(rise, k(0.0), k(0.0), rise)
        val f0 = FnExpr.IfLt(fall, k(0.0), k(0.0), fall)
        val total = r0 + f0
        fun scaled(v: FnExpr) = FnExpr.IfLt(d, total, FnExpr.IfLt(k(0.0), total, v * (d / total), v), v)
        val r = scaled(r0)
        val f = scaled(f0)
        val fallPart = FnExpr.IfLt(k(0.0), f,
            FnExpr.IfLt(d - f, tt, 1.0 - edgeRamp((tt - (d - f)) / f, edge, curve), k(1.0)), k(1.0))
        val env = FnExpr.IfLt(k(0.0), r, FnExpr.IfLt(tt, r, edgeRamp(tt / r, edge, curve), fallPart), fallPart)
        val u = eq(d, 0.0, k(0.0), FnExpr.Clamp01(tt / d))
        val body = env * profileValue(profile, u, sigma)
        // `if (duty <= 0.0) return 0.0`, then `if (tt >= d) return 0.0` — both false for NaN.
        val afterDuty = geq(tt, d, k(0.0), body)
        return FnExpr.IfLt(k(0.0), duty, afterDuty, FnExpr.IfEq(duty, duty, k(0.0), afterDuty))
    }

    private fun pulseTrain(edge: Functions.PulseEdge, profile: Functions.PulseProfile, bipolar: Boolean, seed: Long): FnExpr {
        val p = FnExpr.Abs(child("period"))
        val phaseShift = child("phase") + child("phaseMod")
        val raw = (FnExpr.X - phaseShift) / p
        val cycle = FnExpr.Floor(raw)
        val local0 = (FnExpr.X - phaseShift) - cycle * p
        val jit = child("jitter")
        // The closure skips this when `jit == 0.0`; subtracting `(h - 0.5)·0·p`, a signed zero, gives
        // the same bits, and keeping it unconditional is what gives jitter a gradient at zero, where
        // the function is continuous in it. An IfZero here differentiates "as taken" and reports 0.
        val local1 = local0 - (FnExpr.Hash(seed, cycle) - 0.5) * jit * p
        val local = FnExpr.Rem(FnExpr.Rem(local1, p) + p, p)
        val t = local / p
        val dval = FnExpr.Clamp01(child("duty") + child("dutyMod"))
        val riseFrac = FnExpr.Clamp01(child("rise") / p)
        val fallFrac = FnExpr.Clamp01(child("fall") / p)
        val shaped = pulseShape(t, dval, riseFrac, fallFrac, edge, child("curve"), profile, child("profileSigma"))
        val amp = child("amplitude") * child("ampMod")
        val off = child("offset")
        val value = if (bipolar) off + (shaped * 2.0 - 1.0) * amp else off + shaped * amp
        return FnExpr.IfZero(p, off, value)
    }
}
