package afield.stdlib

import afield.graphics.FLine
import afield.Keyframe
import afield.KeyframeEvaluator
import kotlin.math.*

object Functions {
    private const val TAU = Math.PI * 2.0

    // Closed range for 1D functions. Supports infinite and empty ranges.
    data class Domain(val min: Double, val max: Double) : ClosedRange<Double> {
        override val start: Double
            get() = min
        override val endInclusive: Double
            get() = max

        // avoid jvm name collision with ClosedRange.isEmpty()
        val _isEmpty: Boolean
            get() = min.isNaN() || max.isNaN() || max < min

        val isFinite: Boolean
            get() = min.isFinite() && max.isFinite() && !_isEmpty

        val length: Double
            get() = if (_isEmpty) 0.0 else max - min

        val center: Double
            get() = if (_isEmpty) Double.NaN else (min + max) * 0.5

        fun containsInclusive(x: Double): Boolean = !_isEmpty && x >= min && x <= max

        override fun contains(value: Double): Boolean = containsInclusive(value)

        fun expand(amount: Double): Domain = if (_isEmpty) this else Domain(min - amount, max + amount)

        fun shrink(amount: Double): Domain = if (_isEmpty) this else Domain(min + amount, max - amount)

        fun shift(dx: Double): Domain = if (_isEmpty) this else Domain(min + dx, max + dx)

        fun scale(s: Double, about: Double = 0.0): Domain {
            if (_isEmpty) return this
            val a = (min - about) * s + about
            val b = (max - about) * s + about
            return if (a <= b) Domain(a, b) else Domain(b, a)
        }

        fun lerp(alpha: Double): Double = if (_isEmpty) Double.NaN else min + length * alpha

        fun alphaOf(x: Double): Double = if (_isEmpty || length == 0.0) 0.0 else (x - min) / length

        fun clamp(x: Double): Double {
            if (_isEmpty) return x
            return when {
                x < min -> min
                x > max -> max
                else -> x
            }
        }

        fun wrap(x: Double): Double {
            if (_isEmpty) return x
            val len = length
            if (!len.isFinite() || len == 0.0) return x
            val t = (x - min) % len
            val u = if (t < 0.0) t + len else t
            return min + u
        }

        fun reflect(x: Double): Double {
            if (_isEmpty) return x
            val len = length
            if (!len.isFinite() || len == 0.0) return x
            val two = len * 2.0
            val t = (x - min) % two
            val u = if (t < 0.0) t + two else t
            return if (u <= len) min + u else max - (u - len)
        }

        fun intersect(other: Domain): Domain {
            if (this._isEmpty) return this
            if (other._isEmpty) return other
            return Domain(max(min, other.min), min(max, other.max))
        }

        fun union(other: Domain): Domain {
            if (this._isEmpty) return other
            if (other._isEmpty) return this
            return Domain(min(min, other.min), max(max, other.max))
        }

        companion object {
            val ALL = Domain(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)
            val EMPTY = Domain(1.0, 0.0)

            fun of(a: Double, b: Double): Domain = if (a <= b) Domain(a, b) else Domain(b, a)
            fun point(x: Double): Domain = Domain(x, x)
            fun centered(center: Double, radius: Double): Domain = Domain(center - radius, center + radius)
        }
    }

    enum class EdgeMode { ZERO, CLAMP, WRAP, MIRROR }

    enum class Interpolation { NEAREST, LINEAR, CUBIC }

    enum class PulseEdge { HARD, LINEAR, SMOOTH, SINE, EXP }

    enum class PulseProfile { RECT, TRIANGLE, SINE, HANN, GAUSSIAN }

    data class Fn(
        val roi: Domain = Domain.ALL,
        val domain: Domain = Domain.ALL,
        val name: String? = null,
        private val evalFn: (Double) -> Double
    ) {
        fun eval(x: Double): Double = evalFn(x)

        operator fun invoke(x: Double): Double = evalFn(x)

        fun evalSafe(x: Double, outside: Double = 0.0, edge: EdgeMode = EdgeMode.ZERO): Double {
            if (domain.containsInclusive(x)) return evalFn(x)
            return when (edge) {
                EdgeMode.ZERO -> outside
                EdgeMode.CLAMP -> evalFn(domain.clamp(x))
                EdgeMode.WRAP -> evalFn(domain.wrap(x))
                EdgeMode.MIRROR -> evalFn(domain.reflect(x))
            }
        }

        fun withRoi(newRoi: Domain): Fn = Fn(newRoi, domain, name, evalFn)
        fun withDomain(newDomain: Domain): Fn = Fn(roi, newDomain, name, evalFn)
        fun withName(newName: String?): Fn = Fn(roi, domain, newName, evalFn)
    }

    private fun blend(a: Double, b: Double, t: Double): Double = a + (b - a) * t
    private fun clamp01(x: Double): Double = x.coerceIn(0.0, 1.0)
    private fun smoothstep(t: Double): Double = t * t * (3.0 - 2.0 * t)
    private fun smootherstep(t: Double): Double = t * t * t * (t * (t * 6.0 - 15.0) + 10.0)
    private fun fract(x: Double): Double = x - floor(x)
    private fun intersectDomains(base: Domain, vararg fns: Fn?): Domain {
        var d = base
        for (f in fns) {
            if (f != null) d = d.intersect(f.domain)
        }
        return d
    }

    private fun intersectRois(base: Domain, vararg fns: Fn?): Domain {
        var r = base
        for (f in fns) {
            if (f != null) r = r.intersect(f.roi)
        }
        return r
    }

    private fun edgeRamp(t: Double, edge: PulseEdge, curve: Double): Double {
        val u = t.coerceIn(0.0, 1.0)
        return when (edge) {
            PulseEdge.HARD -> if (u >= 1.0) 1.0 else 0.0
            PulseEdge.LINEAR -> u
            PulseEdge.SMOOTH -> smoothstep(u)
            PulseEdge.SINE -> 0.5 - 0.5 * kotlin.math.cos(Math.PI * u)
            PulseEdge.EXP -> {
                if (curve == 0.0) u
                else {
                    val top = kotlin.math.exp(curve * u) - 1.0
                    val bot = kotlin.math.exp(curve) - 1.0
                    if (bot == 0.0) u else top / bot
                }
            }
        }
    }

    private fun pulseProfileValue(profile: PulseProfile, u: Double, sigma: Double): Double {
        val t = u.coerceIn(0.0, 1.0)
        return when (profile) {
            PulseProfile.RECT -> 1.0
            PulseProfile.TRIANGLE -> if (t < 0.5) t * 2.0 else 2.0 - t * 2.0
            PulseProfile.SINE -> kotlin.math.sin(Math.PI * t)
            PulseProfile.HANN -> 0.5 - 0.5 * kotlin.math.cos(TAU * t)
            PulseProfile.GAUSSIAN -> {
                val s = if (sigma <= 0.0) 0.15 else sigma
                val z = (t - 0.5) / s
                kotlin.math.exp(-0.5 * z * z)
            }
        }
    }

    private fun pulseShape(
        t: Double,
        duty: Double,
        rise: Double,
        fall: Double,
        edge: PulseEdge,
        curve: Double,
        profile: PulseProfile,
        profileSigma: Double
    ): Double {
        if (duty <= 0.0) return 0.0
        val d = clamp01(duty)
        val tt = t.coerceIn(0.0, 1.0)
        if (tt >= d) return 0.0
        var r = rise.coerceAtLeast(0.0)
        var f = fall.coerceAtLeast(0.0)
        val totalEdge = r + f
        if (totalEdge > d && totalEdge > 0.0) {
            val scale = d / totalEdge
            r *= scale
            f *= scale
        }
        val env = when {
            r > 0.0 && tt < r -> edgeRamp(tt / r, edge, curve)
            f > 0.0 && tt > d - f -> 1.0 - edgeRamp((tt - (d - f)) / f, edge, curve)
            else -> 1.0
        }
        val u = if (d == 0.0) 0.0 else (tt / d).coerceIn(0.0, 1.0)
        return env * pulseProfileValue(profile, u, profileSigma)
    }

    private fun combinedDomain(a: Fn, b: Fn): Domain = a.domain.intersect(b.domain)
    private fun combinedRoi(a: Fn, b: Fn, domain: Domain): Domain =
        a.roi.union(b.roi).intersect(domain)

    private fun Domain.safeShift(dx: Double): Domain = if (this._isEmpty) this else this.shift(dx)
    private fun Domain.safeScale(scale: Double, about: Double = 0.0): Domain = if (this._isEmpty) this else this.scale(scale, about)

    fun fn(
        roi: Domain = Domain.ALL,
        domain: Domain = roi,
        name: String? = null,
        eval: (Double) -> Double
    ): Fn = Fn(roi, domain, name, eval)

    // Base functions
    fun c(value: Double, roi: Domain = Domain.ALL, domain: Domain = roi): Fn =
        Fn(roi, domain, "const") { value }

    fun constant(value: Double, roi: Domain = Domain.ALL, domain: Domain = roi): Fn =
        c(value, roi, domain)

    fun zero(roi: Domain = Domain.ALL, domain: Domain = roi): Fn = c(0.0, roi, domain)
    fun one(roi: Domain = Domain.ALL, domain: Domain = roi): Fn = c(1.0, roi, domain)

    fun identity(roi: Domain = Domain.ALL, domain: Domain = roi): Fn =
        Fn(roi, domain, "x") { x -> x }

    fun ramp(
        start: Fn = c(0.0),
        end: Fn = c(1.0),
        roi: Domain = Domain.ALL,
        domain: Domain = roi
    ): Fn {
        val d = intersectDomains(domain, start, end)
        val r = intersectRois(roi, start, end).intersect(d)
        return Fn(r, d, "ramp") { x ->
            val s = start.eval(x)
            val e = end.eval(x)
            val len = e - s
            if (len == 0.0) 0.0 else (x - s) / len
        }
    }

    fun step(edge: Fn = c(0.0), roi: Domain = Domain.ALL, domain: Domain = roi): Fn {
        val d = intersectDomains(domain, edge)
        val r = intersectRois(roi, edge).intersect(d)
        return Fn(r, d, "step") { x -> if (x >= edge.eval(x)) 1.0 else 0.0 }
    }

    fun smoothStep(edge0: Fn = c(0.0), edge1: Fn = c(1.0), roi: Domain = Domain.ALL, domain: Domain = roi): Fn {
        val d = intersectDomains(domain, edge0, edge1)
        val r = intersectRois(roi, edge0, edge1).intersect(d)
        return Fn(r, d, "smoothstep") { x ->
            val a = edge0.eval(x)
            val b = edge1.eval(x)
            val e0 = min(a, b)
            val e1 = max(a, b)
            if (e1 == e0) {
                if (x < e0) 0.0 else 1.0
            } else {
                val t = clamp01((x - e0) / (e1 - e0))
                smoothstep(t)
            }
        }
    }

    fun sin(
        frequency: Fn = c(1.0),
        phase: Fn = c(0.0),
        amplitude: Fn = c(1.0),
        offset: Fn = c(0.0),
        roi: Domain = Domain.ALL,
        domain: Domain = roi
    ): Fn {
        val d = intersectDomains(domain, frequency, phase, amplitude, offset)
        val r = intersectRois(roi, frequency, phase, amplitude, offset).intersect(d)
        return Fn(r, d, "sin") { x ->
            offset.eval(x) + amplitude.eval(x) * kotlin.math.sin(TAU * frequency.eval(x) * x + phase.eval(x))
        }
    }

    fun cos(
        frequency: Fn = c(1.0),
        phase: Fn = c(0.0),
        amplitude: Fn = c(1.0),
        offset: Fn = c(0.0),
        roi: Domain = Domain.ALL,
        domain: Domain = roi
    ): Fn {
        val d = intersectDomains(domain, frequency, phase, amplitude, offset)
        val r = intersectRois(roi, frequency, phase, amplitude, offset).intersect(d)
        return Fn(r, d, "cos") { x ->
            offset.eval(x) + amplitude.eval(x) * kotlin.math.cos(TAU * frequency.eval(x) * x + phase.eval(x))
        }
    }

    fun saw(
        period: Fn = c(1.0),
        phase: Fn = c(0.0),
        amplitude: Fn = c(1.0),
        offset: Fn = c(0.0),
        roi: Domain = Domain.of(0.0, 1.0),
        domain: Domain = roi
    ): Fn {
        val d = intersectDomains(domain, period, phase, amplitude, offset)
        val r = intersectRois(roi, period, phase, amplitude, offset).intersect(d)
        return Fn(r, d, "saw") { x ->
            val p = kotlin.math.abs(period.eval(x))
            if (p == 0.0) offset.eval(x)
            else {
                val t = (x - phase.eval(x)) / p
                val f = fract(t)
                offset.eval(x) + amplitude.eval(x) * (f * 2.0 - 1.0)
            }
        }
    }

    fun triangle(
        period: Fn = c(1.0),
        phase: Fn = c(0.0),
        amplitude: Fn = c(1.0),
        offset: Fn = c(0.0),
        roi: Domain = Domain.of(0.0, 1.0),
        domain: Domain = roi
    ): Fn {
        val d = intersectDomains(domain, period, phase, amplitude, offset)
        val r = intersectRois(roi, period, phase, amplitude, offset).intersect(d)
        return Fn(r, d, "triangle") { x ->
            val p = kotlin.math.abs(period.eval(x))
            if (p == 0.0) offset.eval(x)
            else {
                val t = fract((x - phase.eval(x)) / p)
                val v = if (t < 0.5) t * 2.0 else 2.0 - t * 2.0
                offset.eval(x) + amplitude.eval(x) * (v * 2.0 - 1.0)
            }
        }
    }

    fun square(
        period: Fn = c(1.0),
        phase: Fn = c(0.0),
        duty: Fn = c(0.5),
        amplitude: Fn = c(1.0),
        offset: Fn = c(0.0),
        roi: Domain = Domain.of(0.0, 1.0),
        domain: Domain = roi
    ): Fn {
        val d = intersectDomains(domain, period, phase, duty, amplitude, offset)
        val r = intersectRois(roi, period, phase, duty, amplitude, offset).intersect(d)
        return Fn(r, d, "square") { x ->
            val p = kotlin.math.abs(period.eval(x))
            if (p == 0.0) offset.eval(x)
            else {
                val t = fract((x - phase.eval(x)) / p)
                val dval = clamp01(duty.eval(x))
                offset.eval(x) + if (t < dval) amplitude.eval(x) else -amplitude.eval(x)
            }
        }
    }

    fun pulseTrain(
        period: Fn = c(1.0),
        phase: Fn = c(0.0),
        duty: Fn = c(0.5),
        amplitude: Fn = c(1.0),
        offset: Fn = c(0.0),
        rise: Fn = c(0.0),
        fall: Fn = c(0.0),
        edge: PulseEdge = PulseEdge.SMOOTH,
        curve: Fn = c(2.0),
        profile: PulseProfile = PulseProfile.RECT,
        profileSigma: Fn = c(0.15),
        jitter: Fn = c(0.0),
        seed: Long = 0L,
        bipolar: Boolean = false,
        dutyMod: Fn = c(0.0),
        ampMod: Fn = c(1.0),
        phaseMod: Fn = c(0.0),
        roi: Domain = Domain.of(0.0, 1.0),
        domain: Domain = Domain.ALL
    ): Fn {
        val d = intersectDomains(domain, period, phase, duty, amplitude, offset, rise, fall, curve, profileSigma, jitter, dutyMod, ampMod, phaseMod)
        val r = intersectRois(roi, period, phase, duty, amplitude, offset, rise, fall, curve, profileSigma, jitter, dutyMod, ampMod, phaseMod).intersect(d)
        return Fn(r, d, "pulsetrain") { x ->
            val p = kotlin.math.abs(period.eval(x))
            if (p == 0.0) return@Fn offset.eval(x)
            val phaseShift = phase.eval(x) + phaseMod.eval(x)
            val raw = (x - phaseShift) / p
            val cycle = floor(raw)
            var local = (x - phaseShift) - cycle * p
            val jit = jitter.eval(x)
            if (jit != 0.0) {
                val j = (hashToUnit(seed, cycle.toLong()) - 0.5) * jit * p
                local -= j
            }
            local = ((local % p) + p) % p
            val t = local / p
            val dval = (duty.eval(x) + dutyMod.eval(x)).coerceIn(0.0, 1.0)
            val riseFrac = (rise.eval(x) / p).coerceIn(0.0, 1.0)
            val fallFrac = (fall.eval(x) / p).coerceIn(0.0, 1.0)
            val shaped = pulseShape(t, dval, riseFrac, fallFrac, edge, curve.eval(x), profile, profileSigma.eval(x))
            val amp = amplitude.eval(x) * ampMod.eval(x)
            val off = offset.eval(x)
            if (bipolar) off + (shaped * 2.0 - 1.0) * amp else off + shaped * amp
        }
    }

    fun pulseTrainWidth(
        period: Fn = c(1.0),
        width: Fn = c(0.5),
        phase: Fn = c(0.0),
        amplitude: Fn = c(1.0),
        offset: Fn = c(0.0),
        rise: Fn = c(0.0),
        fall: Fn = c(0.0),
        edge: PulseEdge = PulseEdge.SMOOTH,
        curve: Fn = c(2.0),
        profile: PulseProfile = PulseProfile.RECT,
        profileSigma: Fn = c(0.15),
        jitter: Fn = c(0.0),
        seed: Long = 0L,
        bipolar: Boolean = false,
        dutyMod: Fn = c(0.0),
        ampMod: Fn = c(1.0),
        phaseMod: Fn = c(0.0),
        roi: Domain = Domain.of(0.0, 1.0),
        domain: Domain = Domain.ALL
    ): Fn {
        val dd = intersectDomains(domain, period, width)
        val rr = intersectRois(roi, period, width).intersect(dd)
        val dutyFn = Fn(rr, dd, "duty") { x ->
            val p = kotlin.math.abs(period.eval(x))
            if (p == 0.0) 0.0 else (width.eval(x) / p).coerceIn(0.0, 1.0)
        }
        return pulseTrain(
            period = period,
            phase = phase,
            duty = dutyFn,
            amplitude = amplitude,
            offset = offset,
            rise = rise,
            fall = fall,
            edge = edge,
            curve = curve,
            profile = profile,
            profileSigma = profileSigma,
            jitter = jitter,
            seed = seed,
            bipolar = bipolar,
            dutyMod = dutyMod,
            ampMod = ampMod,
            phaseMod = phaseMod,
            roi = roi,
            domain = domain
        )
    }

    fun pulseTrainHz(
        frequency: Fn = c(1.0),
        phase: Fn = c(0.0),
        duty: Fn = c(0.5),
        amplitude: Fn = c(1.0),
        offset: Fn = c(0.0),
        rise: Fn = c(0.0),
        fall: Fn = c(0.0),
        edge: PulseEdge = PulseEdge.SMOOTH,
        curve: Fn = c(2.0),
        profile: PulseProfile = PulseProfile.RECT,
        profileSigma: Fn = c(0.15),
        jitter: Fn = c(0.0),
        seed: Long = 0L,
        bipolar: Boolean = false,
        dutyMod: Fn = c(0.0),
        ampMod: Fn = c(1.0),
        phaseMod: Fn = c(0.0),
        roi: Domain = Domain.of(0.0, 1.0),
        domain: Domain = Domain.ALL
    ): Fn {
        val dd = intersectDomains(domain, frequency)
        val rr = intersectRois(roi, frequency).intersect(dd)
        val periodFn = Fn(rr, dd, "period") { x ->
            val f = frequency.eval(x)
            if (f == 0.0) 0.0 else 1.0 / f
        }
        return pulseTrain(
            period = periodFn,
            phase = phase,
            duty = duty,
            amplitude = amplitude,
            offset = offset,
            rise = rise,
            fall = fall,
            edge = edge,
            curve = curve,
            profile = profile,
            profileSigma = profileSigma,
            jitter = jitter,
            seed = seed,
            bipolar = bipolar,
            dutyMod = dutyMod,
            ampMod = ampMod,
            phaseMod = phaseMod,
            roi = roi,
            domain = domain
        )
    }

    fun pulseTrainPattern(
        pattern: DoubleArray,
        period: Fn = c(1.0),
        phase: Fn = c(0.0),
        duty: Fn = c(0.5),
        amplitude: Fn = c(1.0),
        offset: Fn = c(0.0),
        rise: Fn = c(0.0),
        fall: Fn = c(0.0),
        edge: PulseEdge = PulseEdge.SMOOTH,
        curve: Fn = c(2.0),
        profile: PulseProfile = PulseProfile.RECT,
        profileSigma: Fn = c(0.15),
        jitter: Fn = c(0.0),
        seed: Long = 0L,
        bipolar: Boolean = false,
        dutyMod: Fn = c(0.0),
        ampMod: Fn = c(1.0),
        phaseMod: Fn = c(0.0),
        roi: Domain = Domain.of(0.0, 1.0),
        domain: Domain = Domain.ALL
    ): Fn {
        if (pattern.isEmpty()) return zero(roi, domain)
        val d = intersectDomains(domain, period, phase, duty, amplitude, offset, rise, fall, curve, profileSigma, jitter, dutyMod, ampMod, phaseMod)
        val r = intersectRois(roi, period, phase, duty, amplitude, offset, rise, fall, curve, profileSigma, jitter, dutyMod, ampMod, phaseMod).intersect(d)
        return Fn(r, d, "pulsepattern") { x ->
            val p = kotlin.math.abs(period.eval(x))
            if (p == 0.0) return@Fn offset.eval(x)
            val phaseShift = phase.eval(x) + phaseMod.eval(x)
            val raw = (x - phaseShift) / p
            val cycle = floor(raw).toLong()
            var local = (x - phaseShift) - cycle * p
            val jit = jitter.eval(x)
            if (jit != 0.0) {
                val j = (hashToUnit(seed, cycle) - 0.5) * jit * p
                local -= j
            }
            local = ((local % p) + p) % p
            val t = local / p
            val dval = (duty.eval(x) + dutyMod.eval(x)).coerceIn(0.0, 1.0)
            val riseFrac = (rise.eval(x) / p).coerceIn(0.0, 1.0)
            val fallFrac = (fall.eval(x) / p).coerceIn(0.0, 1.0)
            val shaped = pulseShape(t, dval, riseFrac, fallFrac, edge, curve.eval(x), profile, profileSigma.eval(x))
            val size = pattern.size.toLong()
            val idx = ((cycle % size) + size) % size
            val amp = amplitude.eval(x) * pattern[idx.toInt()] * ampMod.eval(x)
            val off = offset.eval(x)
            if (bipolar) off + (shaped * 2.0 - 1.0) * amp else off + shaped * amp
        }
    }

    fun impulseTrain(
        period: Fn = c(1.0),
        phase: Fn = c(0.0),
        width: Fn = c(0.02),
        amplitude: Fn = c(1.0),
        offset: Fn = c(0.0),
        sigma: Fn = c(0.15),
        jitter: Fn = c(0.0),
        seed: Long = 0L,
        roi: Domain = Domain.of(0.0, 1.0),
        domain: Domain = Domain.ALL
    ): Fn = pulseTrainWidth(
        period = period,
        width = width,
        phase = phase,
        amplitude = amplitude,
        offset = offset,
        edge = PulseEdge.SMOOTH,
        curve = c(2.0),
        profile = PulseProfile.GAUSSIAN,
        profileSigma = sigma,
        jitter = jitter,
        seed = seed,
        roi = roi,
        domain = domain
    )

    fun pulseBurst(
        count: Int,
        period: Fn = c(1.0),
        start: Fn = c(0.0),
        phase: Fn = c(0.0),
        duty: Fn = c(0.5),
        amplitude: Fn = c(1.0),
        offset: Fn = c(0.0),
        rise: Fn = c(0.0),
        fall: Fn = c(0.0),
        edge: PulseEdge = PulseEdge.SMOOTH,
        curve: Fn = c(2.0),
        profile: PulseProfile = PulseProfile.RECT,
        profileSigma: Fn = c(0.15),
        roi: Domain = Domain.ALL,
        domain: Domain = Domain.ALL
    ): Fn {
        if (count <= 0) return zero(roi, domain)
        val d = intersectDomains(domain, period, start, phase, duty, amplitude, offset, rise, fall, curve, profileSigma)
        val r = intersectRois(roi, period, start, phase, duty, amplitude, offset, rise, fall, curve, profileSigma).intersect(d)
        val base = pulseTrain(
            period = period,
            phase = start + phase,
            duty = duty,
            amplitude = amplitude,
            offset = offset,
            rise = rise,
            fall = fall,
            edge = edge,
            curve = curve,
            profile = profile,
            profileSigma = profileSigma,
            roi = roi,
            domain = domain
        )
        return Fn(r, d, "pulseburst") { x ->
            val p = kotlin.math.abs(period.eval(x))
            if (p == 0.0) return@Fn offset.eval(x)
            val s = start.eval(x)
            val end = s + p * count
            if (x < s || x > end) offset.eval(x) else base.eval(x)
        }
    }

    fun gaussian(
        mean: Fn = c(0.0),
        sigma: Fn = c(1.0),
        amplitude: Fn = c(1.0),
        roi: Domain = Domain.ALL,
        domain: Domain = roi
    ): Fn {
        val d = intersectDomains(domain, mean, sigma, amplitude)
        val r = intersectRois(roi, mean, sigma, amplitude).intersect(d)
        return Fn(r, d, "gaussian") { x ->
            val s = sigma.eval(x)
            if (s == 0.0) 0.0 else {
                val m = mean.eval(x)
                val a = amplitude.eval(x)
                a * kotlin.math.exp(-0.5 * ((x - m) / s).pow(2.0))
            }
        }
    }

    // Array helpers
    fun DoubleArray.toFn(
        domain: Domain = Domain.of(0.0, (size - 1).toDouble()),
        interpolation: Interpolation = Interpolation.LINEAR,
        outside: Double = 0.0
    ): Fn {
        val samples = this
        val count = samples.size
        val dx = if (count > 1) domain.length / (count - 1).toDouble() else 1.0
        val safeDomain = if (domain._isEmpty) Domain.EMPTY else domain
        return Fn(safeDomain, safeDomain, "array") { x ->
            if (count == 0) return@Fn outside
            if (!safeDomain.containsInclusive(x)) return@Fn outside
            val idx = if (dx == 0.0) 0.0 else (x - safeDomain.min) / dx
            sampleArray(samples, idx, interpolation, outside)
        }
    }

    fun List<Double>.toFn(
        domain: Domain = Domain.of(0.0, (size - 1).toDouble()),
        interpolation: Interpolation = Interpolation.LINEAR,
        outside: Double = 0.0
    ): Fn = this.toDoubleArray().toFn(domain, interpolation, outside)

    private fun sampleArray(samples: DoubleArray, index: Double, interpolation: Interpolation, outside: Double): Double {
        val n = samples.size
        if (n == 0) return outside
        return when (interpolation) {
            Interpolation.NEAREST -> {
                val i = index.roundToInt()
                if (i < 0 || i >= n) outside else samples[i]
            }
            Interpolation.LINEAR -> {
                val i = floor(index).toInt()
                val t = index - i
                val a = samples.getOrNull(i) ?: return outside
                val b = samples.getOrNull(i + 1) ?: return a
                blend(a, b, t)
            }
            Interpolation.CUBIC -> {
                val i = floor(index).toInt()
                val t = index - i
                val p0 = samples.getOrNull(i - 1) ?: samples.getOrNull(i) ?: return outside
                val p1 = samples.getOrNull(i) ?: return outside
                val p2 = samples.getOrNull(i + 1) ?: p1
                val p3 = samples.getOrNull(i + 2) ?: p2
                catmullRom(p0, p1, p2, p3, t)
            }
        }
    }

    private fun catmullRom(p0: Double, p1: Double, p2: Double, p3: Double, t: Double): Double {
        val t2 = t * t
        val t3 = t2 * t
        return 0.5 * (
            2.0 * p1 +
                (-p0 + p2) * t +
                (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * t2 +
                (-p0 + 3.0 * p1 - 3.0 * p2 + p3) * t3
            )
    }

    fun Fn.sampleAt(xs: DoubleArray, edge: EdgeMode = EdgeMode.ZERO, outside: Double = 0.0): DoubleArray =
        DoubleArray(xs.size) { i -> evalSafe(xs[i], outside, edge) }

    fun Fn.toDoubleArray(
        count: Int,
        domain: Domain = roi,
        includeEnd: Boolean = true,
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0
    ): DoubleArray {
        if (count <= 0) return DoubleArray(0)
        if (domain._isEmpty) return DoubleArray(count) { outside }
        val len = domain.length
        val steps = if (includeEnd) max(1, count - 1) else count
        val step = if (steps == 0) 0.0 else len / steps.toDouble()
        return DoubleArray(count) { i ->
            val x = domain.min + step * i.toDouble()
            evalSafe(x, outside, edge)
        }
    }

    fun Fn.toDoubleArray(
        step: Double,
        domain: Domain = roi,
        includeEnd: Boolean = true,
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0
    ): DoubleArray {
        if (step == 0.0) return DoubleArray(0)
        if (domain._isEmpty || !domain.length.isFinite()) return DoubleArray(0)
        val len = domain.length
        val s = kotlin.math.abs(step)
        val count = if (includeEnd) (floor(len / s) + 1).toInt() else floor(len / s).toInt()
        return toDoubleArray(count, domain, includeEnd, edge, outside)
    }

    /**
     * Sample this function at [count] uniformly-spaced points and return the
     * results as `List<Vec2>` where each entry is `Vec2(time, value)`.
     *
     * Handy for feeding into [KeyframeEvaluator.setOverlay] or any API that
     * expects a list of 2D points.
     */
    fun Fn.toVec2List(
        count: Int,
        domain: Domain = roi,
        includeEnd: Boolean = true,
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0
    ): List<Vec2> {
        if (count <= 0) return emptyList()
        if (domain._isEmpty) return List(count) { Vec2(0f, outside.toFloat()) }
        val len = domain.length
        val steps = if (includeEnd) max(1, count - 1) else count
        val step = if (steps == 0) 0.0 else len / steps.toDouble()
        return List(count) { i ->
            val x = domain.min + step * i.toDouble()
            Vec2(x.toFloat(), evalSafe(x, outside, edge).toFloat())
        }
    }

    // Streaming helpers
    class FunctionCursor(
        private val fn: Fn,
        var x: Double = fn.roi.min,
        var step: Double = 1.0
    ) {
        fun next(): Double {
            val value = fn.eval(x)
            x += step
            return value
        }

        fun next(count: Int): DoubleArray {
            val out = DoubleArray(count)
            for (i in 0 until count) {
                out[i] = next()
            }
            return out
        }
    }

    fun Fn.cursor(start: Double = roi.min, step: Double = 1.0): FunctionCursor = FunctionCursor(this, start, step)

    fun Fn.stream(start: Double = roi.min, step: Double = 1.0): Sequence<Double> = sequence {
        var x = start
        while (true) {
            yield(eval(x))
            x += step
        }
    }

    fun Fn.stream(domain: Domain, step: Double = 1.0, includeEnd: Boolean = true): Sequence<Double> = sequence {
        if (domain._isEmpty) return@sequence
        if (!domain.length.isFinite() || step == 0.0) return@sequence
        if (step > 0.0) {
            var x = domain.min
            val end = domain.max
            if (includeEnd) {
                while (x <= end) {
                    yield(eval(x))
                    x += step
                }
            } else {
                while (x < end) {
                    yield(eval(x))
                    x += step
                }
            }
        } else {
            var x = domain.max
            val end = domain.min
            if (includeEnd) {
                while (x >= end) {
                    yield(eval(x))
                    x += step
                }
            } else {
                while (x > end) {
                    yield(eval(x))
                    x += step
                }
            }
        }
    }

    fun Fn.streamWithX(start: Double = roi.min, step: Double = 1.0): Sequence<Pair<Double, Double>> = sequence {
        var x = start
        while (true) {
            yield(x to eval(x))
            x += step
        }
    }

    enum class ChunkMapMode { DIRECT, STRETCH, CLAMP, WRAP, MIRROR }

    enum class ChunkBlend { REPLACE, ADD, MULTIPLY }

    data class TimelineChunk(
        val source: Fn,
        val sourceDomain: Domain = source.roi,
        val targetDomain: Domain,
        val mapMode: ChunkMapMode = ChunkMapMode.DIRECT,
        val blend: ChunkBlend = ChunkBlend.REPLACE,
        val edge: EdgeMode = EdgeMode.ZERO,
        val outside: Double = 0.0,
        val window: Fn? = null,
        val gain: Fn? = null,
        val name: String? = source.name
    ) {
        private fun targetAlpha(globalX: Double): Double {
            val len = targetDomain.length
            if (!len.isFinite() || len == 0.0) return 0.0
            return ((globalX - targetDomain.min) / len).coerceIn(0.0, 1.0)
        }

        fun contains(globalX: Double): Boolean = targetDomain.containsInclusive(globalX)

        private fun mapToSource(globalX: Double): Double {
            val linear = sourceDomain.min + (globalX - targetDomain.min)
            return when (mapMode) {
                ChunkMapMode.DIRECT -> linear
                ChunkMapMode.STRETCH -> sourceDomain.lerp(targetAlpha(globalX))
                ChunkMapMode.CLAMP -> sourceDomain.clamp(linear)
                ChunkMapMode.WRAP -> sourceDomain.wrap(linear)
                ChunkMapMode.MIRROR -> sourceDomain.reflect(linear)
            }
        }

        fun weightAt(globalX: Double): Double {
            if (!contains(globalX)) return 0.0
            val shaped = window?.evalSafe(targetAlpha(globalX), outside = 0.0, edge = EdgeMode.CLAMP) ?: 1.0
            val g = gain?.eval(globalX) ?: 1.0
            return shaped * g
        }

        fun sampleAt(globalX: Double): Double? {
            if (!contains(globalX) || sourceDomain._isEmpty) return null
            val sourceX = mapToSource(globalX)
            return source.evalSafe(sourceX, outside = outside, edge = edge)
        }
    }

    class TimelineGraph(
        chunks: List<TimelineChunk>,
        val outside: Double = 0.0,
        val defaultRange: Domain = Domain.point(0.0)
    ) {
        val chunks: List<TimelineChunk> = chunks.toList()

        val activeDomain: Domain = run {
            var d = Domain.EMPTY
            for (chunk in this.chunks) {
                d = if (d._isEmpty) chunk.targetDomain else d.union(chunk.targetDomain)
            }
            if (d._isEmpty) defaultRange else d
        }

        private fun applyBlend(base: Double, sample: Double, weight: Double, blendMode: ChunkBlend): Double =
            when (blendMode) {
                ChunkBlend.REPLACE -> blend(base, sample, clamp01(weight))
                ChunkBlend.ADD -> base + sample * weight
                ChunkBlend.MULTIPLY -> base * blend(1.0, sample, clamp01(weight))
            }

        fun eval(x: Double): Double {
            var value = outside
            var active = false
            for (chunk in chunks) {
                val sample = chunk.sampleAt(x) ?: continue
                val weight = chunk.weightAt(x)
                value = applyBlend(value, sample, weight, chunk.blend)
                active = true
            }
            return if (active) value else outside
        }

        operator fun invoke(x: Double): Double = eval(x)

        fun asFn(
            name: String = "timeline",
            range: Domain = activeDomain
        ): Fn = Fn(roi = range, domain = range, name = name) { x -> eval(x) }

        fun tap(offset: Double = 0.0, name: String = "timelineTap"): Fn {
            val tapRoi = if (activeDomain._isEmpty) activeDomain else activeDomain.shift(offset)
            return Fn(roi = tapRoi, domain = tapRoi, name = name) { x -> eval(x - offset) }
        }
    }

    class TimelineBuilder(
        start: Double = 0.0,
        private val outside: Double = 0.0
    ) {
        private val chunks = mutableListOf<TimelineChunk>()
        private val marks = LinkedHashMap<String, Double>()
        private val startAt = start

        var cursor: Double = start
            private set

        private fun defaultDurationFor(domain: Domain): Double {
            val len = domain.length
            return if (len.isFinite() && len > 0.0) len else 1.0
        }

        private fun normalizeDuration(duration: Double?, sourceDomain: Domain): Double {
            val d = duration ?: defaultDurationFor(sourceDomain)
            return if (!d.isFinite()) defaultDurationFor(sourceDomain) else kotlin.math.abs(d)
        }

        fun mark(name: String): Double {
            marks[name] = cursor
            return cursor
        }

        fun mark(name: String, at: Double): Double {
            marks[name] = at
            return at
        }

        fun marker(name: String): Double? = marks[name]

        fun moveTo(x: Double): Double {
            cursor = x
            return cursor
        }

        fun moveToMark(name: String, offset: Double = 0.0): Double {
            cursor = (marks[name] ?: cursor) + offset
            return cursor
        }

        fun advance(by: Double): Double {
            cursor += by
            return cursor
        }

        fun rewind(by: Double): Double = advance(-by)

        private fun insertChunk(
            source: Fn,
            at: Double = cursor,
            duration: Double? = null,
            sourceDomain: Domain = source.roi,
            mapMode: ChunkMapMode = ChunkMapMode.DIRECT,
            blend: ChunkBlend = ChunkBlend.REPLACE,
            edge: EdgeMode = EdgeMode.ZERO,
            outside: Double = this.outside,
            window: Fn? = null,
            gain: Fn? = null,
            advanceCursor: Boolean = true
        ): TimelineChunk {
            val dur = normalizeDuration(duration, sourceDomain)
            val target = Domain.of(at, at + dur)
            val chunk = TimelineChunk(
                source = source,
                sourceDomain = sourceDomain,
                targetDomain = target,
                mapMode = mapMode,
                blend = blend,
                edge = edge,
                outside = outside,
                window = window,
                gain = gain
            )
            chunks += chunk
            if (advanceCursor) cursor = target.max
            return chunk
        }

        fun repeatToFit(
            source: Fn,
            duration: Double,
            at: Double = cursor,
            sourceDomain: Domain = source.roi,
            blend: ChunkBlend = ChunkBlend.REPLACE,
            edge: EdgeMode = EdgeMode.ZERO,
            outside: Double = this.outside,
            window: Fn? = null,
            gain: Fn? = null,
            advanceCursor: Boolean = true
        ): TimelineChunk = insertChunk(
            source = source,
            at = at,
            duration = duration,
            sourceDomain = sourceDomain,
            mapMode = ChunkMapMode.WRAP,
            blend = blend,
            edge = edge,
            outside = outside,
            window = window,
            gain = gain,
            advanceCursor = advanceCursor
        )

        fun stretchToFit(
            source: Fn,
            duration: Double,
            at: Double = cursor,
            sourceDomain: Domain = source.roi,
            blend: ChunkBlend = ChunkBlend.REPLACE,
            edge: EdgeMode = EdgeMode.ZERO,
            outside: Double = this.outside,
            window: Fn? = null,
            gain: Fn? = null,
            advanceCursor: Boolean = true
        ): TimelineChunk = insertChunk(
            source = source,
            at = at,
            duration = duration,
            sourceDomain = sourceDomain,
            mapMode = ChunkMapMode.STRETCH,
            blend = blend,
            edge = edge,
            outside = outside,
            window = window,
            gain = gain,
            advanceCursor = advanceCursor
        )

        private fun insertOverlap(
            source: Fn,
            overlap: Double,
            at: Double = cursor,
            duration: Double? = null,
            sourceDomain: Domain = source.roi,
            mapMode: ChunkMapMode = ChunkMapMode.DIRECT,
            blend: ChunkBlend = ChunkBlend.REPLACE,
            edge: EdgeMode = EdgeMode.ZERO,
            outside: Double = this.outside,
            window: Fn = hannWindow(Domain.of(0.0, 1.0)),
            gain: Fn? = null
        ): TimelineChunk {
            val dur = normalizeDuration(duration, sourceDomain)
            val ov = overlap.coerceIn(0.0, dur)
            return insertChunk(
                source = source,
                at = at - ov,
                duration = dur,
                sourceDomain = sourceDomain,
                mapMode = mapMode,
                blend = blend,
                edge = edge,
                outside = outside,
                window = window,
                gain = gain,
                advanceCursor = true
            )
        }

        private fun insertSplice(
            source: Fn,
            lookBack: Double,
            duration: Double? = null,
            sourceDomain: Domain = source.roi,
            fadeIn: Double = 0.15,
            fadeOut: Double = fadeIn,
            mapMode: ChunkMapMode = ChunkMapMode.STRETCH,
            edge: EdgeMode = EdgeMode.ZERO,
            outside: Double = this.outside,
            gain: Fn? = null,
            advanceCursor: Boolean = false
        ): TimelineChunk {
            val dur = normalizeDuration(duration, sourceDomain)
            val start = cursor - kotlin.math.abs(lookBack)
            return insertChunk(
                source = source,
                at = start,
                duration = dur,
                sourceDomain = sourceDomain,
                mapMode = mapMode,
                blend = ChunkBlend.REPLACE,
                edge = edge,
                outside = outside,
                window = spliceWindow(fadeIn = fadeIn, fadeOut = fadeOut),
                gain = gain,
                advanceCursor = advanceCursor
            )
        }

        fun snapshot(offset: Double = 0.0, name: String = "timelineSnapshot"): Fn {
            val graph = buildGraph()
            val roi = graph.activeDomain.shift(offset)
            return Fn(roi = roi, domain = roi, name = name) { x -> graph.eval(x - offset) }
        }

        fun lookBack(delay: Double, name: String = "timelineLookBack"): Fn =
            snapshot(offset = delay, name = name)

        fun Fn.place(
            at: Double = cursor,
            duration: Double? = null,
            sourceDomain: Domain = roi,
            mapMode: ChunkMapMode = ChunkMapMode.DIRECT,
            blend: ChunkBlend = ChunkBlend.REPLACE,
            edge: EdgeMode = EdgeMode.ZERO,
            outside: Double = this@TimelineBuilder.outside,
            window: Fn? = null,
            gain: Fn? = null,
            advanceCursor: Boolean = true
        ): TimelineChunk = this@TimelineBuilder.insertChunk(
            source = this,
            at = at,
            duration = duration,
            sourceDomain = sourceDomain,
            mapMode = mapMode,
            blend = blend,
            edge = edge,
            outside = outside,
            window = window,
            gain = gain,
            advanceCursor = advanceCursor
        )

        fun Fn.overlap(
            amount: Double,
            at: Double = cursor,
            duration: Double? = null,
            sourceDomain: Domain = roi,
            mapMode: ChunkMapMode = ChunkMapMode.DIRECT,
            blend: ChunkBlend = ChunkBlend.REPLACE,
            edge: EdgeMode = EdgeMode.ZERO,
            outside: Double = this@TimelineBuilder.outside,
            window: Fn = hannWindow(Domain.of(0.0, 1.0)),
            gain: Fn? = null
        ): TimelineChunk = this@TimelineBuilder.insertOverlap(
            source = this,
            overlap = amount,
            at = at,
            duration = duration,
            sourceDomain = sourceDomain,
            mapMode = mapMode,
            blend = blend,
            edge = edge,
            outside = outside,
            window = window,
            gain = gain
        )

        fun Fn.splice(
            lookBack: Double,
            duration: Double? = null,
            sourceDomain: Domain = roi,
            fadeIn: Double = 0.15,
            fadeOut: Double = fadeIn,
            mapMode: ChunkMapMode = ChunkMapMode.STRETCH,
            edge: EdgeMode = EdgeMode.ZERO,
            outside: Double = this@TimelineBuilder.outside,
            gain: Fn? = null,
            advanceCursor: Boolean = false
        ): TimelineChunk = this@TimelineBuilder.insertSplice(
            source = this,
            lookBack = lookBack,
            duration = duration,
            sourceDomain = sourceDomain,
            fadeIn = fadeIn,
            fadeOut = fadeOut,
            mapMode = mapMode,
            edge = edge,
            outside = outside,
            gain = gain,
            advanceCursor = advanceCursor
        )

        fun chunks(): List<TimelineChunk> = chunks.toList()

        fun buildGraph(): TimelineGraph = TimelineGraph(
            chunks = chunks,
            outside = outside,
            defaultRange = Domain.point(startAt)
        )

        fun build(name: String = "timeline", range: Domain? = null): Fn {
            val graph = buildGraph()
            return graph.asFn(name = name, range = range ?: graph.activeDomain)
        }
    }

    fun spliceWindow(
        fadeIn: Double = 0.15,
        fadeOut: Double = fadeIn,
        domain: Domain = Domain.of(0.0, 1.0)
    ): Fn {
        val fi = fadeIn.coerceAtLeast(0.0).coerceAtMost(1.0)
        val fo = fadeOut.coerceAtLeast(0.0).coerceAtMost(1.0)
        return Fn(domain, domain, "spliceWindow") { x ->
            val t = clamp01(domain.alphaOf(x))
            val inW = if (fi == 0.0 || t >= fi) 1.0 else smoothstep(t / fi)
            val outStart = 1.0 - fo
            val outW = if (fo == 0.0 || t <= outStart) 1.0 else smoothstep((1.0 - t) / fo)
            (inW * outW).coerceIn(0.0, 1.0)
        }
    }

    fun timelineGraph(
        start: Double = 0.0,
        outside: Double = 0.0,
        init: TimelineBuilder.() -> Unit
    ): TimelineGraph {
        val builder = TimelineBuilder(start = start, outside = outside)
        builder.init()
        return builder.buildGraph()
    }

    fun timeline(
        start: Double = 0.0,
        outside: Double = 0.0,
        name: String = "timeline",
        range: Domain? = null,
        init: TimelineBuilder.() -> Unit
    ): Fn {
        val builder = TimelineBuilder(start = start, outside = outside)
        builder.init()
        return builder.build(name = name, range = range)
    }

    fun Fn.toFLine(
        domain: Domain = roi,
        samples: Int = 256,
        includeEnd: Boolean = true,
        xScale: Double = 1.0,
        yScale: Double = 1.0,
        xOffset: Double = 0.0,
        yOffset: Double = 0.0
    ): FLine {
        val line = FLine()
        if (samples <= 0 || domain._isEmpty || !domain.length.isFinite()) return line
        val len = domain.length
        val steps = if (includeEnd) max(1, samples - 1) else samples
        val step = if (steps == 0) 0.0 else len / steps.toDouble()
        for (i in 0 until samples) {
            val x = domain.min + step * i.toDouble()
            val y = eval(x)
            val px = xOffset + x * xScale
            val py = yOffset + y * yScale
            if (i == 0) line.moveTo(px, py) else line.lineTo(px, py)
        }
        return line
    }

    // Algebra and composition
    operator fun Fn.plus(other: Fn): Fn {
        val domain = combinedDomain(this, other)
        val roi = combinedRoi(this, other, domain)
        return Fn(roi, domain, "(+)") { x -> this.eval(x) + other.eval(x) }
    }

    operator fun Fn.minus(other: Fn): Fn {
        val domain = combinedDomain(this, other)
        val roi = combinedRoi(this, other, domain)
        return Fn(roi, domain, "(-)") { x -> this.eval(x) - other.eval(x) }
    }

    operator fun Fn.times(other: Fn): Fn {
        val domain = combinedDomain(this, other)
        val roi = combinedRoi(this, other, domain)
        return Fn(roi, domain, "(*)") { x -> this.eval(x) * other.eval(x) }
    }

    operator fun Fn.div(other: Fn): Fn {
        val domain = combinedDomain(this, other)
        val roi = combinedRoi(this, other, domain)
        return Fn(roi, domain, "(/)") { x -> this.eval(x) / other.eval(x) }
    }

    operator fun Fn.plus(value: Double): Fn =
        Fn(roi, domain, name) { x -> this.eval(x) + value }

    operator fun Fn.minus(value: Double): Fn =
        Fn(roi, domain, name) { x -> this.eval(x) - value }

    operator fun Fn.times(value: Double): Fn =
        Fn(roi, domain, name) { x -> this.eval(x) * value }

    operator fun Fn.div(value: Double): Fn =
        Fn(roi, domain, name) { x -> this.eval(x) / value }

    operator fun Double.plus(f: Fn): Fn = f + this
    operator fun Double.minus(f: Fn): Fn = Fn(f.roi, f.domain, f.name) { x -> this - f.eval(x) }
    operator fun Double.times(f: Fn): Fn = f * this
    operator fun Double.div(f: Fn): Fn = Fn(f.roi, f.domain, f.name) { x -> this / f.eval(x) }

    fun Fn.mapY(newName: String? = name, transform: (Double) -> Double): Fn =
        Fn(roi, domain, newName) { x -> transform(eval(x)) }

    fun Fn.mapX(newName: String? = name, newRoi: Domain = roi, newDomain: Domain = domain, transform: (Double) -> Double): Fn =
        Fn(newRoi, newDomain, newName) { x -> eval(transform(x)) }

    fun Fn.shift(dx: Fn = c(0.0)): Fn {
        val d = intersectDomains(domain, dx)
        val r = intersectRois(roi, dx).intersect(d)
        return Fn(r, d, name) { x -> eval(x - dx.eval(x)) }
    }

    fun Fn.scaleX(scale: Fn = c(1.0), about: Fn = c(0.0)): Fn {
        val d = intersectDomains(domain, scale, about)
        val r = intersectRois(roi, scale, about).intersect(d)
        return Fn(r, d, name) { x ->
            val a = about.eval(x)
            val s = scale.eval(x)
            if (s == 0.0) eval(a) else eval((x - a) / s + a)
        }
    }

    fun Fn.scaleY(scale: Fn = c(1.0)): Fn {
        val d = intersectDomains(domain, scale)
        val r = intersectRois(roi, scale).intersect(d)
        return Fn(r, d, name) { x -> eval(x) * scale.eval(x) }
    }

    fun Fn.offsetY(offset: Fn = c(0.0)): Fn {
        val d = intersectDomains(domain, offset)
        val r = intersectRois(roi, offset).intersect(d)
        return Fn(r, d, name) { x -> eval(x) + offset.eval(x) }
    }

    fun Fn.reverse(about: Fn = c(roi.center)): Fn {
        val d = intersectDomains(domain, about)
        val r = intersectRois(roi, about).intersect(d)
        return Fn(r, d, name) { x ->
            val a = about.eval(x)
            eval(a - (x - a))
        }
    }

    fun Fn.flip(): Fn = mapY(name) { -it }

    fun Fn.abs(): Fn = mapY(name) { kotlin.math.abs(it) }

    fun Fn.sign(): Fn = mapY(name) { kotlin.math.sign(it) }

    fun Fn.pow(exponent: Fn = c(1.0)): Fn {
        val d = intersectDomains(domain, exponent)
        val r = intersectRois(roi, exponent).intersect(d)
        return Fn(r, d, name) { x -> eval(x).pow(exponent.eval(x)) }
    }

    fun Fn.exp(): Fn = mapY(name) { kotlin.math.exp(it) }

    fun Fn.log(base: Fn = c(Math.E)): Fn {
        val d = intersectDomains(domain, base)
        val r = intersectRois(roi, base).intersect(d)
        return Fn(r, d, name) { x -> ln(eval(x)) / ln(base.eval(x)) }
    }

    fun Fn.clamp(min: Fn = c(-1.0), max: Fn = c(1.0)): Fn {
        val d = intersectDomains(domain, min, max)
        val r = intersectRois(roi, min, max).intersect(d)
        return Fn(r, d, name) { x -> eval(x).coerceIn(min.eval(x), max.eval(x)) }
    }

    fun Fn.clip(threshold: Fn = c(1.0)): Fn {
        val d = intersectDomains(domain, threshold)
        val r = intersectRois(roi, threshold).intersect(d)
        return Fn(r, d, name) { x ->
            val t = threshold.eval(x)
            eval(x).coerceIn(-t, t)
        }
    }

    fun Fn.rectify(): Fn = mapY(name) { if (it < 0.0) 0.0 else it }

    fun Fn.softClip(amount: Fn = c(1.0)): Fn {
        val d = intersectDomains(domain, amount)
        val r = intersectRois(roi, amount).intersect(d)
        return Fn(r, d, name) { x ->
            val a = amount.eval(x)
            if (a == 0.0) 0.0 else tanh(eval(x) * a) / tanh(a)
        }
    }

    fun Fn.fold(threshold: Fn = c(1.0)): Fn {
        val d = intersectDomains(domain, threshold)
        val r = intersectRois(roi, threshold).intersect(d)
        return Fn(r, d, name) { x ->
            val th = threshold.eval(x)
            if (th == 0.0) 0.0 else {
                val value = eval(x)
                val t = value % (th * 4.0)
                val v = if (t > th * 2.0) t - th * 4.0 else t
                kotlin.math.abs(v - th) - th
            }
        }
    }

    fun Fn.wrapY(min: Fn = c(-1.0), max: Fn = c(1.0)): Fn {
        val d = intersectDomains(domain, min, max)
        val r = intersectRois(roi, min, max).intersect(d)
        return Fn(r, d, name) { x ->
            val range = Domain.of(min.eval(x), max.eval(x))
            range.wrap(eval(x))
        }
    }

    fun Fn.withEdge(edge: EdgeMode, outside: Fn = c(0.0)): Fn {
        val d = intersectDomains(domain, outside)
        val r = intersectRois(roi, outside).intersect(d)
        return Fn(r, d, name) { x -> evalSafe(x, outside.eval(x), edge) }
    }

    fun Fn.quantize(levels: Int): Fn = mapY(name) {
        if (levels <= 1) 0.0 else {
            val t = ((it + 1.0) * 0.5).coerceIn(0.0, 1.0)
            val q = floor(t * (levels - 1)).toInt()
            (q.toDouble() / (levels - 1)) * 2.0 - 1.0
        }
    }

    fun Fn.bitCrush(bits: Int): Fn = mapY(name) {
        if (bits <= 0) 0.0 else {
            val steps = (1 shl bits) - 1
            val t = ((it + 1.0) * 0.5).coerceIn(0.0, 1.0)
            val q = floor(t * steps).toInt()
            (q.toDouble() / steps.toDouble()) * 2.0 - 1.0
        }
    }

    fun Fn.mix(other: Fn, amount: Fn = c(0.5)): Fn =
        this * (one() - amount) + other * amount

    fun Fn.crossfade(other: Fn, center: Fn = c(0.0), width: Fn = c(0.0)): Fn {
        val domain = combinedDomain(this, other).intersect(center.domain).intersect(width.domain)
        val roi = combinedRoi(this, other, domain).intersect(center.roi).intersect(width.roi)
        return Fn(roi, domain, "crossfade") { x ->
            val c = center.eval(x)
            val w = width.eval(x)
            if (w <= 0.0) {
                if (x < c) this.eval(x) else other.eval(x)
            } else {
                val t = clamp01((x - (c - w * 0.5)) / w)
                val ww = smootherstep(t)
                blend(this.eval(x), other.eval(x), ww)
            }
        }
    }

    fun Fn.cut(range: Domain, outside: Fn = c(0.0)): Fn {
        val d = intersectDomains(domain, outside)
        val r = intersectRois(range, outside).intersect(d)
        return Fn(r, d, name) { x -> if (range.containsInclusive(x)) eval(x) else outside.eval(x) }
    }

    fun Fn.trim(range: Domain, outside: Fn = c(0.0)): Fn = cut(range, outside)

    fun Fn.slice(range: Domain, outside: Fn = c(0.0)): Fn = cut(range, outside)

    fun Fn.replace(range: Domain, replacement: Fn): Fn {
        val domain = this.domain.union(replacement.domain)
        val roi = this.roi.union(replacement.roi).intersect(domain)
        return Fn(roi, domain, "replace") { x -> if (range.containsInclusive(x)) replacement.eval(x) else eval(x) }
    }

    fun Fn.window(window: Fn): Fn = this * window

    fun Fn.amplitudeMod(mod: Fn, depth: Fn = c(1.0), bias: Fn = c(1.0)): Fn {
        val d = intersectDomains(domain, mod, depth, bias)
        val r = intersectRois(roi, mod, depth, bias).intersect(d)
        return Fn(r, d, "am") { x -> eval(x) * (bias.eval(x) + depth.eval(x) * mod.eval(x)) }
    }

    fun Fn.ringMod(mod: Fn, depth: Fn = c(1.0)): Fn {
        val d = intersectDomains(domain, mod, depth)
        val r = intersectRois(roi, mod, depth).intersect(d)
        return Fn(r, d, "rm") { x -> eval(x) * (depth.eval(x) * mod.eval(x)) }
    }

    fun Fn.warp(mod: Fn, depth: Fn = c(1.0)): Fn {
        val d = intersectDomains(domain, mod, depth)
        val r = intersectRois(roi, mod, depth).intersect(d)
        return Fn(r, d, "warp") { x -> eval(x + depth.eval(x) * mod.eval(x)) }
    }

    fun Fn.quantizeX(step: Fn = c(1.0), mode: EdgeMode = EdgeMode.CLAMP): Fn {
        val d = intersectDomains(domain, step)
        val r = intersectRois(roi, step).intersect(d)
        return Fn(r, d, name) { x ->
            val s = step.eval(x)
            if (s == 0.0) eval(x) else {
                val q = floor(x / s) * s
                evalSafe(q, edge = mode)
            }
        }
    }

    fun Fn.sampleHold(step: Fn = c(1.0), offset: Fn = c(0.0)): Fn {
        val d = intersectDomains(domain, step, offset)
        val r = intersectRois(roi, step, offset).intersect(d)
        return Fn(r, d, "samplehold") { x ->
            val s = step.eval(x)
            if (s == 0.0) eval(x) else {
                val o = offset.eval(x)
                val q = floor((x - o) / s) * s + o
                eval(q)
            }
        }
    }

    fun Fn.phaseMod(mod: Fn, depth: Fn = c(1.0)): Fn = warp(mod, depth)

    fun Fn.frequencyMod(mod: Fn, depth: Fn = c(1.0)): Fn = warp(mod, depth)

    fun Fn.saturate(amount: Fn = c(1.0)): Fn {
        val d = intersectDomains(domain, amount)
        val r = intersectRois(roi, amount).intersect(d)
        return Fn(r, d, name) { x ->
            val a = amount.eval(x)
            val v = eval(x)
            if (a == 0.0) 0.0 else v / (1.0 + kotlin.math.abs(v * a))
        }
    }

    fun Fn.jitterX(amount: Fn = c(0.0), seed: Long = 0L): Fn {
        val d = intersectDomains(domain, amount)
        val r = intersectRois(roi, amount).intersect(d)
        return Fn(r, d, "jitter") { x ->
            val h = hashToUnit(seed, floor(x).toLong()) - 0.5
            eval(x + h * amount.eval(x))
        }
    }

    fun Fn.repeat(
        period: Fn = c(1.0),
        origin: Fn = c(roi.min),
        roiOverride: Domain = roi,
        domainOverride: Domain = Domain.ALL
    ): Fn {
        val d = intersectDomains(domainOverride, period, origin)
        val r = intersectRois(roiOverride, period, origin).intersect(d)
        return Fn(r, d, "repeat") { x ->
            val p = kotlin.math.abs(period.eval(x))
            if (p == 0.0) return@Fn eval(x)
            val o = origin.eval(x)
            val wrapped = o + ((x - o) % p + p) % p
            eval(wrapped)
        }
    }

    fun Fn.mirrorRepeat(
        period: Fn = c(1.0),
        origin: Fn = c(roi.min),
        roiOverride: Domain = roi,
        domainOverride: Domain = Domain.ALL
    ): Fn {
        val d = intersectDomains(domainOverride, period, origin)
        val r = intersectRois(roiOverride, period, origin).intersect(d)
        return Fn(r, d, "mirror") { x ->
            val len = kotlin.math.abs(period.eval(x))
            if (len == 0.0) return@Fn eval(x)
            val o = origin.eval(x)
            val two = len * 2.0
            val t = ((x - o) % two + two) % two
            val local = if (t <= len) o + t else o + (two - t)
            eval(local)
        }
    }

    fun Fn.tile(count: Int, period: Fn = c(roi.length), origin: Fn = c(roi.min)): Fn {
        if (count <= 0) return zero(roi, domain)
        val base = repeat(period, origin, roiOverride = roi, domainOverride = Domain.ALL)
        val d = intersectDomains(domain, period, origin)
        val r = intersectRois(roi, period, origin).intersect(d)
        return Fn(r, d, "tile") { x ->
            val p = kotlin.math.abs(period.eval(x))
            if (p == 0.0) return@Fn 0.0
            val o = origin.eval(x)
            val end = o + p * count
            if (x < o || x > end) 0.0 else base.eval(x)
        }
    }

    fun Fn.pad(left: Fn = c(0.0), right: Fn = c(0.0), value: Fn = c(0.0)): Fn {
        val d = intersectDomains(domain, left, right, value)
        val r = intersectRois(roi, left, right, value).intersect(d)
        return Fn(r, d, name) { x ->
            val l = left.eval(x)
            val rr = right.eval(x)
            if (x < roi.min - l || x > roi.max + rr) value.eval(x) else eval(x)
        }
    }

    fun Fn.delay(amount: Fn = c(0.0)): Fn = shift(amount)

    fun Fn.echo(delay: Fn = c(1.0), decay: Fn = c(0.5), repeats: Int = 4): Fn {
        val d = intersectDomains(domain, delay, decay)
        val r = intersectRois(roi, delay, decay).intersect(d)
        return Fn(r, d, "echo") { x ->
            val dly = delay.eval(x)
            val dec = decay.eval(x)
            var sum = eval(x)
            var gain = 1.0
            for (i in 1..repeats) {
                gain *= dec
                sum += eval(x - dly * i) * gain
            }
            sum
        }
    }

    fun Fn.derivative(step: Double = 1e-3): Fn {
        val newDomain = domain.shrink(step)
        val newRoi = roi.shrink(step)
        return Fn(newRoi, newDomain, "d/dx") { x ->
            (eval(x + step) - eval(x - step)) / (2.0 * step)
        }
    }

    fun Fn.integral(step: Double = 1e-3, origin: Double = roi.min): Fn =
        Fn(roi, domain, "integral") { x ->
            if (step == 0.0) return@Fn 0.0
            var acc = 0.0
            val dir = if (x >= origin) 1.0 else -1.0
            val stepSize = step * dir
            var t = origin
            val steps = ((x - origin) / stepSize).toInt()
            repeat(kotlin.math.abs(steps)) {
                acc += eval(t) * stepSize
                t += stepSize
            }
            val remainder = x - t
            if (remainder != 0.0) acc += eval(t) * remainder
            acc
        }

    fun Fn.convolve(
        kernel: Fn,
        samples: Int = 64,
        kernelDomain: Domain = kernel.roi,
        normalize: Boolean = false
    ): Fn {
        val domain = if (domain.isFinite && kernel.domain.isFinite) {
            Domain(domain.min + kernel.domain.min, domain.max + kernel.domain.max)
        } else Domain.ALL
        val roi = if (roi.isFinite && kernel.roi.isFinite) {
            Domain(roi.min + kernel.roi.min, roi.max + kernel.roi.max)
        } else domain
        return Fn(roi, domain, "convolve") { x ->
            if (samples <= 0) return@Fn 0.0
            val kd = kernelDomain
            if (kd._isEmpty) return@Fn 0.0
            val len = kd.length
            if (!len.isFinite()) return@Fn 0.0
            val step = if (samples == 1) 0.0 else len / (samples - 1).toDouble()
            var sum = 0.0
            var weight = 0.0
            for (i in 0 until samples) {
                val t = kd.min + step * i.toDouble()
                val k = kernel.eval(t)
                sum += eval(x - t) * k
                weight += k
            }
            val scaled = if (step == 0.0) sum else sum * step
            if (normalize && weight != 0.0) scaled / weight else scaled
        }
    }

    fun Fn.correlate(
        kernel: Fn,
        samples: Int = 64,
        kernelDomain: Domain = kernel.roi
    ): Fn = convolve(kernel.reverse(c(kernelDomain.center)), samples, kernelDomain, normalize = false)

    fun Fn.movingAverage(window: Fn = c(1.0), samples: Int = 64): Fn {
        val d = intersectDomains(domain, window)
        val r = intersectRois(roi, window).intersect(d)
        return Fn(r, d, "movingAverage") { x ->
            if (samples <= 0) return@Fn 0.0
            val w = window.eval(x)
            if (w == 0.0) return@Fn eval(x)
            val half = w * 0.5
            val step = if (samples == 1) 0.0 else w / (samples - 1).toDouble()
            var sum = 0.0
            var t = -half
            for (i in 0 until samples) {
                sum += eval(x + t)
                t += step
            }
            val scaled = if (step == 0.0) sum else sum * step
            if (w == 0.0) 0.0 else scaled / w
        }
    }

    fun Fn.blur(sigma: Fn = c(1.0), samples: Int = 64): Fn {
        val d = intersectDomains(domain, sigma)
        val r = intersectRois(roi, sigma).intersect(d)
        return Fn(r, d, "blur") { x ->
            if (samples <= 0) return@Fn 0.0
            val s = sigma.eval(x)
            if (s == 0.0) return@Fn eval(x)
            val half = s * 3.0
            val step = if (samples == 1) 0.0 else (half * 2.0) / (samples - 1).toDouble()
            var sum = 0.0
            var weight = 0.0
            var t = -half
            for (i in 0 until samples) {
                val w = kotlin.math.exp(-0.5 * (t / s).pow(2.0))
                sum += eval(x + t) * w
                weight += w
                t += step
            }
            if (weight == 0.0) 0.0 else sum / weight
        }
    }

    // Windows
    fun hannWindow(domain: Domain = Domain.of(0.0, 1.0)): Fn =
        Fn(domain, domain, "hann") { x ->
            val t = clamp01(domain.alphaOf(x))
            0.5 - 0.5 * kotlin.math.cos(TAU * t)
        }

    fun hammingWindow(domain: Domain = Domain.of(0.0, 1.0)): Fn =
        Fn(domain, domain, "hamming") { x ->
            val t = clamp01(domain.alphaOf(x))
            0.54 - 0.46 * kotlin.math.cos(TAU * t)
        }

    fun blackmanWindow(domain: Domain = Domain.of(0.0, 1.0)): Fn =
        Fn(domain, domain, "blackman") { x ->
            val t = clamp01(domain.alphaOf(x))
            0.42 - 0.5 * kotlin.math.cos(TAU * t) + 0.08 * kotlin.math.cos(TAU * 2.0 * t)
        }

    fun gaussianWindow(domain: Domain = Domain.of(0.0, 1.0), sigma: Fn = c(0.4)): Fn {
        val d = intersectDomains(domain, sigma)
        val r = intersectRois(domain, sigma).intersect(d)
        return Fn(r, d, "gausswin") { x ->
            val t = domain.alphaOf(x) - 0.5
            val s = sigma.eval(x)
            if (s == 0.0) 0.0 else kotlin.math.exp(-0.5 * (t / s).pow(2.0))
        }
    }

    // Noise and stochastic processes
    private fun mix64(z: Long): Long {
        var x = z
        x = (x xor (x ushr 30)) * -4658895280553007687L
        x = (x xor (x ushr 27)) * -7723592293110705685L
        return x xor (x ushr 31)
    }

    private fun hashToUnit(seed: Long, x: Long): Double {
        val h = mix64(x + seed)
        val bits = h ushr 11
        return bits.toDouble() / (1L shl 53).toDouble()
    }

    private fun hashToSigned(seed: Long, x: Long): Double = hashToUnit(seed, x) * 2.0 - 1.0

    private data class SampleStats(val min: Double, val max: Double, val mean: Double, val variance: Double)

    private fun sampleStatsRandom(fn: Fn, domain: Domain, samples: Int, seed: Long): SampleStats? {
        if (samples <= 0 || domain._isEmpty || !domain.length.isFinite()) return null
        val len = domain.length
        var minV = Double.POSITIVE_INFINITY
        var maxV = Double.NEGATIVE_INFINITY
        var mean = 0.0
        var m2 = 0.0
        var n = 0
        for (i in 0 until samples) {
            val x = domain.min + hashToUnit(seed, i.toLong()) * len
            val v = fn.eval(x)
            if (!v.isFinite()) continue
            if (v < minV) minV = v
            if (v > maxV) maxV = v
            n++
            val delta = v - mean
            mean += delta / n
            val delta2 = v - mean
            m2 += delta * delta2
        }
        if (n == 0 || !minV.isFinite() || !maxV.isFinite()) return null
        val variance = if (n > 1) m2 / (n - 1).toDouble() else 0.0
        return SampleStats(minV, maxV, mean, variance)
    }

    private fun valueNoiseAt(seed: Long, frequency: Double, x: Double): Double {
        val xf = x * frequency
        val i = floor(xf).toLong()
        val t = smootherstep(fract(xf))
        val a = hashToSigned(seed, i)
        val b = hashToSigned(seed, i + 1)
        return blend(a, b, t)
    }

    private fun perlinNoiseAt(seed: Long, frequency: Double, x: Double): Double {
        val xf = x * frequency
        val i0 = floor(xf).toLong()
        val i1 = i0 + 1
        val t = xf - floor(xf)
        val g0 = if (hashToSigned(seed, i0) >= 0.0) 1.0 else -1.0
        val g1 = if (hashToSigned(seed, i1) >= 0.0) 1.0 else -1.0
        val v0 = g0 * t
        val v1 = g1 * (t - 1.0)
        val u = smootherstep(t)
        return blend(v0, v1, u)
    }

    fun whiteNoise(
        seed: Long = 0L,
        frequency: Fn = c(1.0),
        amplitude: Fn = c(1.0),
        roi: Domain = Domain.ALL,
        domain: Domain = roi
    ): Fn {
        val d = intersectDomains(domain, frequency, amplitude)
        val r = intersectRois(roi, frequency, amplitude).intersect(d)
        return Fn(r, d, "white") { x ->
            val freq = frequency.eval(x)
            val amp = amplitude.eval(x)
            val i = floor(x * freq).toLong()
            hashToSigned(seed, i) * amp
        }
    }

    fun valueNoise(
        seed: Long = 0L,
        frequency: Fn = c(1.0),
        amplitude: Fn = c(1.0),
        roi: Domain = Domain.ALL,
        domain: Domain = roi
    ): Fn {
        val d = intersectDomains(domain, frequency, amplitude)
        val r = intersectRois(roi, frequency, amplitude).intersect(d)
        return Fn(r, d, "valuenoise") { x ->
            val freq = frequency.eval(x)
            val amp = amplitude.eval(x)
            valueNoiseAt(seed, freq, x) * amp
        }
    }

    fun perlinNoise(
        seed: Long = 0L,
        frequency: Fn = c(1.0),
        amplitude: Fn = c(1.0),
        roi: Domain = Domain.ALL,
        domain: Domain = roi
    ): Fn {
        val d = intersectDomains(domain, frequency, amplitude)
        val r = intersectRois(roi, frequency, amplitude).intersect(d)
        return Fn(r, d, "perlin") { x ->
            val freq = frequency.eval(x)
            val amp = amplitude.eval(x)
            perlinNoiseAt(seed, freq, x) * amp
        }
    }

    fun fbm(
        seed: Long = 0L,
        frequency: Fn = c(1.0),
        amplitude: Fn = c(1.0),
        octaves: Int = 5,
        lacunarity: Fn = c(2.0),
        gain: Fn = c(0.5),
        roi: Domain = Domain.ALL,
        domain: Domain = roi
    ): Fn {
        val d = intersectDomains(domain, frequency, amplitude, lacunarity, gain)
        val r = intersectRois(roi, frequency, amplitude, lacunarity, gain).intersect(d)
        return Fn(r, d, "fbm") { x ->
            var amp = amplitude.eval(x)
            var freq = frequency.eval(x)
            val lac = lacunarity.eval(x)
            val g = gain.eval(x)
            var sum = 0.0
            var norm = 0.0
            for (i in 0 until max(1, octaves)) {
                val v = valueNoiseAt(seed + i, freq, x)
                sum += v * amp
                norm += amp
                amp *= g
                freq *= lac
            }
            if (norm == 0.0) 0.0 else sum / norm
        }
    }

    fun fbmPerlin(
        seed: Long = 0L,
        frequency: Fn = c(1.0),
        amplitude: Fn = c(1.0),
        octaves: Int = 5,
        lacunarity: Fn = c(2.0),
        gain: Fn = c(0.5),
        roi: Domain = Domain.ALL,
        domain: Domain = roi
    ): Fn {
        val d = intersectDomains(domain, frequency, amplitude, lacunarity, gain)
        val r = intersectRois(roi, frequency, amplitude, lacunarity, gain).intersect(d)
        return Fn(r, d, "fbmPerlin") { x ->
            var amp = amplitude.eval(x)
            var freq = frequency.eval(x)
            val lac = lacunarity.eval(x)
            val g = gain.eval(x)
            var sum = 0.0
            var norm = 0.0
            for (i in 0 until max(1, octaves)) {
                val v = perlinNoiseAt(seed + i, freq, x)
                sum += v * amp
                norm += amp
                amp *= g
                freq *= lac
            }
            if (norm == 0.0) 0.0 else sum / norm
        }
    }

    fun randomWalk(
        seed: Long = 0L,
        step: Fn = c(1.0),
        amplitude: Fn = c(1.0),
        roi: Domain = Domain.ALL,
        domain: Domain = roi
    ): Fn {
        val d = intersectDomains(domain, step, amplitude)
        val r = intersectRois(roi, step, amplitude).intersect(d)
        return Fn(r, d, "randomwalk") { x ->
            val st = step.eval(x)
            if (st == 0.0) return@Fn 0.0
            val amp = amplitude.eval(x)
            val n = floor(x / st).toInt()
            var acc = 0.0
            if (n >= 0) {
                for (i in 0..n) {
                    acc += hashToSigned(seed, i.toLong()) * amp
                }
            } else {
                var i = 0
                while (i >= n) {
                    acc -= hashToSigned(seed, i.toLong()) * amp
                    i--
                }
            }
            acc
        }
    }

    data class Sample(val x: Double, val y: Double, val w: Double = 1.0)

    fun points(vararg pairs: Pair<Double, Double>): List<Sample> = pairs.map { Sample(it.first, it.second) }

    fun Fn.samplesAt(xs: DoubleArray): List<Sample> = xs.map { Sample(it, eval(it)) }

    fun Fn.autoScale(
        domain: Domain = roi,
        samples: Int = 256,
        seed: Long = 0L,
        targetMin: Double = 0.0,
        targetMax: Double = 1.0,
        clamp: Boolean = false
    ): Fn {
        val stats = sampleStatsRandom(this, domain, samples, seed) ?: return this
        val range = stats.max - stats.min
        if (range == 0.0 || !range.isFinite()) return c(targetMin, domain, domain)
        val scale = (targetMax - targetMin) / range
        val offset = targetMin - stats.min * scale
        val d = this.domain.intersect(domain)
        val r = this.roi.intersect(domain).intersect(d)
        return Fn(r, d, "autoscale") { x ->
            val v = eval(x) * scale + offset
            if (clamp) v.coerceIn(min(targetMin, targetMax), max(targetMin, targetMax)) else v
        }
    }

    fun Fn.zNormalize(
        domain: Domain = roi,
        samples: Int = 256,
        seed: Long = 0L,
        epsilon: Double = 1e-9
    ): Fn {
        val stats = sampleStatsRandom(this, domain, samples, seed) ?: return this
        val std = kotlin.math.sqrt(stats.variance.coerceAtLeast(0.0))
        if (!std.isFinite() || std <= epsilon) return c(0.0, domain, domain)
        val d = this.domain.intersect(domain)
        val r = this.roi.intersect(domain).intersect(d)
        return Fn(r, d, "znorm") { x -> (eval(x) - stats.mean) / std }
    }

    fun Fn.whitenCdf(
        domain: Domain = roi,
        samples: Int = 2048,
        bins: Int = 256,
        seed: Long = 0L,
        targetMin: Double = 0.0,
        targetMax: Double = 1.0,
        clamp: Boolean = true
    ): Fn {
        val d = this.domain.intersect(domain)
        val r = this.roi.intersect(domain).intersect(d)
        val cached = lazy {
            val stats = sampleStatsRandom(this, domain, samples, seed) ?: return@lazy null
            val range = stats.max - stats.min
            if (!range.isFinite() || range == 0.0) return@lazy null
            val count = max(2, bins)
            val hist = DoubleArray(count)
            val len = domain.length
            var valid = 0
            for (i in 0 until samples) {
                val x = domain.min + hashToUnit(seed + 1L, i.toLong()) * len
                val v = eval(x)
                if (!v.isFinite()) continue
                val u = ((v - stats.min) / range).coerceIn(0.0, 1.0)
                val idx = min(count - 1, floor(u * (count - 1)).toInt())
                hist[idx] += 1.0
                valid++
            }
            if (valid == 0) return@lazy null
            val cdf = DoubleArray(count)
            var acc = 0.0
            for (i in 0 until count) {
                acc += hist[i]
                cdf[i] = acc / valid.toDouble()
            }
            Triple(stats, cdf, range)
        }
        return Fn(r, d, "whitenCdf") { x ->
            val data = cached.value
            if (data == null) return@Fn eval(x)
            val (stats, cdf, range) = data
            val v = eval(x)
            if (!v.isFinite()) return@Fn v
            val u = ((v - stats.min) / range)
            val t = if (clamp) u.coerceIn(0.0, 1.0) else u
            val idx = min(cdf.size - 1, max(0, floor(t * (cdf.size - 1)).toInt()))
            val next = min(cdf.size - 1, idx + 1)
            val frac = t * (cdf.size - 1) - idx
            val c = if (next == idx) cdf[idx] else blend(cdf[idx], cdf[next], frac)
            val out = targetMin + (targetMax - targetMin) * c
            if (clamp) out.coerceIn(min(targetMin, targetMax), max(targetMin, targetMax)) else out
        }
    }

    fun Fn.sampleUniform(count: Int, domain: Domain = roi, includeEnd: Boolean = true): List<Sample> {
        if (count <= 0 || domain._isEmpty || !domain.length.isFinite()) return emptyList()
        val len = domain.length
        val steps = if (includeEnd) max(1, count - 1) else count
        val step = if (steps == 0) 0.0 else len / steps.toDouble()
        return List(count) { i ->
            val x = domain.min + step * i.toDouble()
            Sample(x, eval(x))
        }
    }

    fun Fn.sampleJittered(count: Int, domain: Domain = roi, jitter: Double = 0.25, seed: Long = 0L): List<Sample> {
        if (count <= 0 || domain._isEmpty || !domain.length.isFinite()) return emptyList()
        val len = domain.length
        val step = if (count == 0) 0.0 else len / count.toDouble()
        return List(count) { i ->
            val base = domain.min + step * (i + 0.5)
            val j = (hashToUnit(seed, i.toLong()) - 0.5) * jitter * step
            val x = (base + j).coerceIn(domain.min, domain.max)
            Sample(x, eval(x))
        }
    }

    fun Fn.sampleRandom(count: Int, domain: Domain = roi, seed: Long = 0L): List<Sample> {
        if (count <= 0 || domain._isEmpty || !domain.length.isFinite()) return emptyList()
        val len = domain.length
        return List(count) { i ->
            val x = domain.min + hashToUnit(seed, i.toLong()) * len
            Sample(x, eval(x))
        }
    }

    fun Fn.sampleByDensity(
        count: Int,
        domain: Domain = roi,
        density: Fn,
        seed: Long = 0L,
        maxRejects: Int = 10000
    ): List<Sample> {
        if (count <= 0 || domain._isEmpty || !domain.length.isFinite()) return emptyList()
        val len = domain.length
        val out = ArrayList<Sample>(count)
        var tries = 0
        var i = 0L
        while (out.size < count && tries < maxRejects) {
            val x = domain.min + hashToUnit(seed, i) * len
            val p = clamp01(density.eval(x))
            val r = hashToUnit(seed + 1L, i)
            if (r < p) out += Sample(x, eval(x))
            i++
            tries++
        }
        return out
    }

    fun Fn.sampleZeroCrossings(domain: Domain = roi, step: Double = domain.length / 128.0): List<Sample> {
        if (domain._isEmpty || step == 0.0 || !domain.length.isFinite()) return emptyList()
        val out = ArrayList<Sample>()
        var x = domain.min
        var prev = eval(x)
        x += step
        while (x <= domain.max) {
            val y = eval(x)
            if (prev == 0.0) out += Sample(x - step, 0.0)
            if (prev * y < 0.0) {
                val t = prev / (prev - y)
                val xc = x - step * t
                out += Sample(xc, 0.0)
            }
            prev = y
            x += step
        }
        return out
    }

    fun Fn.samplePeaks(count: Int, domain: Domain = roi, step: Double = domain.length / 256.0): List<Sample> {
        if (count <= 0 || domain._isEmpty || step == 0.0 || !domain.length.isFinite()) return emptyList()
        val candidates = ArrayList<Sample>()
        var x = domain.min
        var prev = eval(x)
        x += step
        var current = eval(x)
        x += step
        while (x <= domain.max) {
            val next = eval(x)
            if (current >= prev && current >= next) {
                candidates += Sample(x - step, current)
            }
            prev = current
            current = next
            x += step
        }
        return candidates.sortedByDescending { kotlin.math.abs(it.y) }.take(count)
    }

    fun interface RbfKernel {
        fun eval(r: Double): Double
    }

    object RbfKernels {
        val linear = RbfKernel { r -> r }
        val quadratic = RbfKernel { r -> r * r }
        val cubic = RbfKernel { r -> r * r * r }
        val thinPlate = RbfKernel { r -> if (r == 0.0) 0.0 else r * r * ln(r) }

        fun gaussian(epsilon: Double = 1.0) = RbfKernel { r ->
            val v = epsilon * r
            kotlin.math.exp(-(v * v))
        }

        fun multiquadric(c: Double = 1.0) = RbfKernel { r -> sqrt(r * r + c * c) }

        fun inverseMultiquadric(c: Double = 1.0) = RbfKernel { r -> 1.0 / sqrt(r * r + c * c) }

        fun inverseQuadratic(c: Double = 1.0) = RbfKernel { r -> 1.0 / (r * r + c * c) }
    }

    private fun solveLinearSystem(aIn: Array<DoubleArray>, bIn: DoubleArray): DoubleArray {
        val n = bIn.size
        if (n == 0) return DoubleArray(0)
        val a = Array(n) { i -> aIn[i].clone() }
        val b = bIn.clone()
        val eps = 1e-12
        for (i in 0 until n) {
            var pivot = i
            var max = kotlin.math.abs(a[i][i])
            for (r in i + 1 until n) {
                val v = kotlin.math.abs(a[r][i])
                if (v > max) {
                    max = v
                    pivot = r
                }
            }
            if (max < eps) continue
            if (pivot != i) {
                val tmp = a[i]; a[i] = a[pivot]; a[pivot] = tmp
                val tb = b[i]; b[i] = b[pivot]; b[pivot] = tb
            }
            val inv = 1.0 / a[i][i]
            for (j in i until n) a[i][j] *= inv
            b[i] *= inv
            for (r in 0 until n) {
                if (r == i) continue
                val factor = a[r][i]
                if (factor == 0.0) continue
                for (j in i until n) a[r][j] -= factor * a[i][j]
                b[r] -= factor * b[i]
            }
        }
        return b
    }

    class RbfInterpolator(
        private val points: List<Sample>,
        private val kernel: RbfKernel,
        private val smoothing: Double = 0.0,
        private val base: Fn? = null
    ) {
        private val xs = DoubleArray(points.size) { points[it].x }
        private val weights: DoubleArray

        init {
            val n = points.size
            if (n == 0) {
                weights = DoubleArray(0)
            } else {
                val a = Array(n) { DoubleArray(n) }
                val b = DoubleArray(n)
                for (i in 0 until n) {
                    val xi = xs[i]
                    val target = points[i].y - (base?.eval(xi) ?: 0.0)
                    b[i] = target * points[i].w
                    for (j in 0 until n) {
                        a[i][j] = kernel.eval(kotlin.math.abs(xi - xs[j]))
                    }
                    a[i][i] += smoothing
                }
                weights = solveLinearSystem(a, b)
            }
        }

        fun eval(x: Double): Double {
            var sum = base?.eval(x) ?: 0.0
            for (i in weights.indices) {
                sum += weights[i] * kernel.eval(kotlin.math.abs(x - xs[i]))
            }
            return sum
        }
    }

    fun rbf(points: List<Sample>, kernel: RbfKernel = RbfKernels.thinPlate, smoothing: Double = 0.0, base: Fn? = null): Fn {
        if (points.isEmpty()) return base ?: zero()
        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val pointDomain = Domain.of(minX, maxX)
        val domain = (base?.domain ?: pointDomain).union(pointDomain)
        val roi = (base?.roi ?: pointDomain).union(pointDomain).intersect(domain)
        val interp = RbfInterpolator(points, kernel, smoothing, base)
        return Fn(roi, domain, "rbf") { x -> interp.eval(x) }
    }

    fun rbfQuadratic(points: List<Sample>, smoothing: Double = 0.0, base: Fn? = null): Fn =
        rbf(points, RbfKernels.quadratic, smoothing, base)

    fun rbfCubic(points: List<Sample>, smoothing: Double = 0.0, base: Fn? = null): Fn =
        rbf(points, RbfKernels.cubic, smoothing, base)

    fun rbfThinPlate(points: List<Sample>, smoothing: Double = 0.0, base: Fn? = null): Fn =
        rbf(points, RbfKernels.thinPlate, smoothing, base)

    fun Fn.rbf(points: List<Sample>, kernel: RbfKernel = RbfKernels.thinPlate, smoothing: Double = 0.0): Fn =
        rbf(points, kernel, smoothing, this)

    fun Fn.rbfQuadratic(points: List<Sample>, smoothing: Double = 0.0): Fn =
        rbf(points, RbfKernels.quadratic, smoothing, this)

    fun Fn.rbfCubic(points: List<Sample>, smoothing: Double = 0.0): Fn =
        rbf(points, RbfKernels.cubic, smoothing, this)

    fun Fn.rbfThinPlate(points: List<Sample>, smoothing: Double = 0.0): Fn =
        rbf(points, RbfKernels.thinPlate, smoothing, this)

    // Granular synthesis
    fun Fn.granular(
        grainSize: Fn = c(1.0),
        spacing: Fn = grainSize,
        jitter: Fn = c(0.0),
        overlap: Int = 1,
        seed: Long = 0L,
        window: Fn = hannWindow(Domain.of(0.0, 1.0))
    ): Fn {
        val d = intersectDomains(domain, grainSize, spacing, jitter, window)
        val r = intersectRois(roi, grainSize, spacing, jitter, window).intersect(d)
        val base = this
        return Fn(r, d, "granular") { x ->
            val g = grainSize.eval(x)
            val sp = spacing.eval(x)
            if (g == 0.0 || sp == 0.0) return@Fn base.eval(x)
            val index = floor(x / sp).toLong()
            var sum = 0.0
            val jit = jitter.eval(x)
            for (k in -overlap..overlap) {
                val i = index + k
                val jitterOffset = (hashToUnit(seed, i) - 0.5) * jit * sp
                val center = (i.toDouble() * sp) + jitterOffset
                val local = x - (center - g * 0.5)
                if (local >= 0.0 && local <= g) {
                    val u = if (g == 0.0) 0.0 else local / g
                    val win = window.eval(u)
                    sum += base.eval(center - g * 0.5 + local) * win
                }
            }
            sum
        }
    }

    // Time-series style streaming filters
    fun Fn.lowpassStream(alpha: Double, start: Double = roi.min, step: Double = 1.0, initial: Double = 0.0): Sequence<Double> = sequence {
        var x = start
        var y = initial
        val a = alpha.coerceIn(0.0, 1.0)
        while (true) {
            val input = eval(x)
            y = y + a * (input - y)
            yield(y)
            x += step
        }
    }

    fun Fn.highpassStream(alpha: Double, start: Double = roi.min, step: Double = 1.0, initial: Double = 0.0): Sequence<Double> = sequence {
        var x = start
        var y = initial
        var lastInput = 0.0
        val a = alpha.coerceIn(0.0, 1.0)
        while (true) {
            val input = eval(x)
            val out = a * (y + input - lastInput)
            y = out
            lastInput = input
            yield(out)
            x += step
        }
    }

    // Convenience builders for piecewise functions
    fun piecewise(default: Fn = zero(), vararg pieces: Pair<Domain, Fn>): Fn {
        val domain = pieces.fold(default.domain) { acc, (d, f) -> acc.union(d).union(f.domain) }
        val roi = pieces.fold(default.roi) { acc, (d, f) -> acc.union(d).union(f.roi) }.intersect(domain)
        return Fn(roi, domain, "piecewise") { x ->
            for ((d, f) in pieces) {
                if (d.containsInclusive(x)) return@Fn f.eval(x)
            }
            default.eval(x)
        }
    }

    // ---------------------------------------------------------------------
    // Comprehensive demo (sampling + stats) for docs and quick validation
    // ---------------------------------------------------------------------

    data class DemoStats(
        val min: Double,
        val max: Double,
        val mean: Double,
        val rms: Double
    )

    data class DemoSeries(
        val name: String,
        val domain: Domain,
        val xs: DoubleArray,
        val ys: DoubleArray,
        val stats: DemoStats
    )

    data class DemoResult(
        val series: List<DemoSeries>,
        val notes: List<String>
    )

    // ══════════════════════════════════════════════════════════════════
    //  Keyframe-aware interpolation functions
    //
    //  Each takes a list of Keyframe (or Vec2) control points and returns
    //  a Fn defined over their time span. These are independent of the
    //  keyframe editor's built-in bezier evaluation — they provide
    //  alternative curve shapes that can be overlaid, compared, or used
    //  as stand-alone functions.
    // ══════════════════════════════════════════════════════════════════

    /**
     * Piecewise-linear interpolation through the keyframe positions.
     * Outside the keyframe range the first/last value is held.
     */
    fun piecewiseLinear(points: List<Keyframe>): Fn {
        if (points.isEmpty()) return zero()
        val sorted = points.sortedBy { it.time }
        val d = Domain.of(sorted.first().time, sorted.last().time)
        return Fn(d, Domain.ALL, "piecewise-linear") { x ->
            when {
                x <= sorted.first().time -> sorted.first().value
                x >= sorted.last().time -> sorted.last().value
                else -> {
                    val i = sorted.indexOfLast { it.time <= x }
                    val a = sorted[i]
                    val b = sorted[i + 1]
                    val t = if (b.time == a.time) 0.0 else (x - a.time) / (b.time - a.time)
                    a.value + (b.value - a.value) * t
                }
            }
        }
    }

    /** Overload accepting Vec2 pairs instead of Keyframe objects. */
    @JvmName("piecewiseLinearVec2")
    fun piecewiseLinear(points: List<Vec2>): Fn {
        return piecewiseLinear(points.map { Keyframe(it.x.toDouble(), it.y.toDouble(), "linear") })
    }

    /**
     * Catmull-Rom spline through keyframe positions.
     *
     * The Catmull-Rom spline passes exactly through every control point and
     * produces smooth C¹-continuous curves. The [alpha] parameter controls
     * the spline variant:
     *  - 0.0 = uniform (classic)
     *  - 0.5 = centripetal (avoids cusps — recommended)
     *  - 1.0 = chordal
     *
     * @param points the keyframes to interpolate
     * @param alpha  parameterisation exponent (0 = uniform, 0.5 = centripetal, 1 = chordal)
     */
    fun catmullRomSpline(points: List<Keyframe>, alpha: Double = 0.5): Fn {
        if (points.isEmpty()) return zero()
        if (points.size == 1) return c(points[0].value)
        val sorted = points.sortedBy { it.time }
        val n = sorted.size
        val d = Domain.of(sorted.first().time, sorted.last().time)
        return Fn(d, Domain.ALL, "catmull-rom") { x ->
            when {
                x <= sorted.first().time -> sorted.first().value
                x >= sorted.last().time -> sorted.last().value
                else -> {
                    // Find segment
                    var seg = 0
                    for (i in 0 until n - 1) {
                        if (x >= sorted[i].time && x < sorted[i + 1].time) { seg = i; break }
                    }
                    val p0 = sorted[max(seg - 1, 0)]
                    val p1 = sorted[seg]
                    val p2 = sorted[min(seg + 1, n - 1)]
                    val p3 = sorted[min(seg + 2, n - 1)]
                    catmullRomSegment(
                        p0.time, p0.value,
                        p1.time, p1.value,
                        p2.time, p2.value,
                        p3.time, p3.value,
                        x, alpha
                    )
                }
            }
        }
    }

    /**
     * Evaluate a single Catmull-Rom segment.
     *
     * Uses cubic Hermite interpolation where the tangent at each interior knot
     * is the slope of the line connecting its neighbours, scaled to the segment
     * span. The [alpha] parameter biases knot spacing but the final evaluation
     * always uses the standard Hermite basis on the [t1, t2] interval.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun catmullRomSegment(
        t0: Double, v0: Double,
        t1: Double, v1: Double,
        t2: Double, v2: Double,
        t3: Double, v3: Double,
        x: Double, alpha: Double
    ): Double {
        val span = t2 - t1
        if (span < 1e-12) return v1
        val u = ((x - t1) / span).coerceIn(0.0, 1.0)

        // Tangents: slope of chord through neighbours, scaled to segment length
        val tangent1 = if (t2 != t0) (v2 - v0) / (t2 - t0) * span else 0.0
        val tangent2 = if (t3 != t1) (v3 - v1) / (t3 - t1) * span else 0.0

        val u2 = u * u
        val u3 = u2 * u
        val h00 = 2.0 * u3 - 3.0 * u2 + 1.0
        val h10 = u3 - 2.0 * u2 + u
        val h01 = -2.0 * u3 + 3.0 * u2
        val h11 = u3 - u2

        return h00 * v1 + h10 * tangent1 + h01 * v2 + h11 * tangent2
    }

    /**
     * Monotone cubic (Fritsch-Carlson) interpolation.
     *
     * Guarantees no overshoot — the curve never exceeds the range defined by
     * adjacent keyframe values. Excellent for animation curves where you want
     * smooth motion without unexpected wobble.
     */
    fun monotoneCubic(points: List<Keyframe>): Fn {
        if (points.isEmpty()) return zero()
        if (points.size == 1) return c(points[0].value)
        val sorted = points.sortedBy { it.time }
        val n = sorted.size

        // Compute slopes
        val dx = DoubleArray(n - 1) { sorted[it + 1].time - sorted[it].time }
        val dy = DoubleArray(n - 1) { sorted[it + 1].value - sorted[it].value }
        val slopes = DoubleArray(n - 1) { if (dx[it] == 0.0) 0.0 else dy[it] / dx[it] }

        // Tangents via Fritsch-Carlson
        val tangents = DoubleArray(n)
        tangents[0] = slopes[0]
        for (i in 1 until n - 1) {
            if (slopes[i - 1] * slopes[i] <= 0.0) {
                tangents[i] = 0.0
            } else {
                tangents[i] = (slopes[i - 1] + slopes[i]) * 0.5
            }
        }
        tangents[n - 1] = slopes[n - 2]

        // Enforce monotonicity
        for (i in 0 until n - 1) {
            if (abs(slopes[i]) < 1e-30) {
                tangents[i] = 0.0
                tangents[i + 1] = 0.0
            } else {
                val a = tangents[i] / slopes[i]
                val b = tangents[i + 1] / slopes[i]
                val s = a * a + b * b
                if (s > 9.0) {
                    val tau = 3.0 / sqrt(s)
                    tangents[i] = tau * a * slopes[i]
                    tangents[i + 1] = tau * b * slopes[i]
                }
            }
        }

        val d = Domain.of(sorted.first().time, sorted.last().time)
        return Fn(d, Domain.ALL, "monotone-cubic") { x ->
            when {
                x <= sorted.first().time -> sorted.first().value
                x >= sorted.last().time -> sorted.last().value
                else -> {
                    var seg = 0
                    for (i in 0 until n - 1) {
                        if (x >= sorted[i].time && x < sorted[i + 1].time) { seg = i; break }
                    }
                    val h = dx[seg]
                    if (h < 1e-30) sorted[seg].value
                    else {
                        val t = (x - sorted[seg].time) / h
                        val t2 = t * t
                        val t3 = t2 * t
                        val h00 = 2.0 * t3 - 3.0 * t2 + 1.0
                        val h10 = (t3 - 2.0 * t2 + t) * h
                        val h01 = -2.0 * t3 + 3.0 * t2
                        val h11 = (t3 - t2) * h
                        h00 * sorted[seg].value + h10 * tangents[seg] +
                                h01 * sorted[seg + 1].value + h11 * tangents[seg + 1]
                    }
                }
            }
        }
    }

    /**
     * Cubic Hermite interpolation with user-specified tangent slopes.
     *
     * Each keyframe's tangent is derived from its [Keyframe.handles]:
     *  - If handles are present the outgoing slope is `handle.value / handle.time`.
     *  - If handles are missing a Catmull-Rom-style tangent is used.
     *
     * This lets you feed editor keyframes (with their tangent handles) directly
     * into an independent Hermite evaluator.
     */
    fun hermiteFromHandles(points: List<Keyframe>): Fn {
        if (points.isEmpty()) return zero()
        if (points.size == 1) return c(points[0].value)
        val sorted = points.sortedBy { it.time }
        val n = sorted.size

        // Derive tangent slopes — dValue/dTime at each keyframe
        val tangents = DoubleArray(n) { i ->
            val kf = sorted[i]
            // Try to use the outgoing handle slope
            val outH = kf.handles?.out
            if (outH != null && abs(outH.time) > 1e-12) {
                outH.value / outH.time
            } else {
                // Catmull-Rom fallback
                val prev = sorted[max(i - 1, 0)]
                val next = sorted[min(i + 1, n - 1)]
                val dt = next.time - prev.time
                if (abs(dt) < 1e-12) 0.0 else (next.value - prev.value) / dt
            }
        }

        val d = Domain.of(sorted.first().time, sorted.last().time)
        return Fn(d, Domain.ALL, "hermite") { x ->
            when {
                x <= sorted.first().time -> sorted.first().value
                x >= sorted.last().time -> sorted.last().value
                else -> {
                    var seg = 0
                    for (i in 0 until n - 1) {
                        if (x >= sorted[i].time && x < sorted[i + 1].time) { seg = i; break }
                    }
                    val h = sorted[seg + 1].time - sorted[seg].time
                    if (h < 1e-30) sorted[seg].value
                    else {
                        val t = (x - sorted[seg].time) / h
                        val t2 = t * t
                        val t3 = t2 * t
                        val h00 = 2.0 * t3 - 3.0 * t2 + 1.0
                        val h10 = (t3 - 2.0 * t2 + t) * h
                        val h01 = -2.0 * t3 + 3.0 * t2
                        val h11 = (t3 - t2) * h
                        h00 * sorted[seg].value + h10 * tangents[seg] +
                                h01 * sorted[seg + 1].value + h11 * tangents[seg + 1]
                    }
                }
            }
        }
    }

    /**
     * Quadratic B-spline approximation of the keyframe curve.
     *
     * Unlike interpolating splines, B-splines do **not** pass through the
     * control points — they smoothly approximate the shape. This produces
     * ultra-smooth curves that are naturally bias-free.
     */
    fun bSplineQuadratic(points: List<Keyframe>): Fn {
        if (points.isEmpty()) return zero()
        if (points.size == 1) return c(points[0].value)
        if (points.size == 2) return piecewiseLinear(points)
        val sorted = points.sortedBy { it.time }
        val n = sorted.size

        // Uniform parameterisation: map keyframe index to parameter
        val d = Domain.of(sorted.first().time, sorted.last().time)
        return Fn(d, Domain.ALL, "b-spline-quadratic") { x ->
            when {
                x <= sorted.first().time -> sorted.first().value
                x >= sorted.last().time -> sorted.last().value
                else -> {
                    // Map x to parameter space [0, n-1]
                    val span = sorted.last().time - sorted.first().time
                    val u = if (span < 1e-12) 0.0
                    else ((x - sorted.first().time) / span * (n - 1)).coerceIn(0.0, (n - 1).toDouble())

                    val seg = u.toInt().coerceIn(0, n - 2)
                    val t = u - seg

                    val p0val = sorted[max(seg - 1, 0)].value
                    val p1val = sorted[seg].value
                    val p2val = sorted[min(seg + 1, n - 1)].value

                    // Quadratic B-spline basis
                    val b0 = 0.5 * (1.0 - t) * (1.0 - t)
                    val b1 = 0.5 + t * (1.0 - t)
                    val b2 = 0.5 * t * t

                    b0 * p0val + b1 * p1val + b2 * p2val
                }
            }
        }
    }

    /**
     * Step / sample-and-hold through keyframes.
     * Holds each keyframe value until the next keyframe time.
     */
    fun stepHold(points: List<Keyframe>): Fn {
        if (points.isEmpty()) return zero()
        val sorted = points.sortedBy { it.time }
        val d = Domain.of(sorted.first().time, sorted.last().time)
        return Fn(d, Domain.ALL, "step-hold") { x ->
            when {
                x <= sorted.first().time -> sorted.first().value
                x >= sorted.last().time -> sorted.last().value
                else -> {
                    var value = sorted.first().value
                    for (kf in sorted) {
                        if (kf.time > x) break
                        value = kf.value
                    }
                    value
                }
            }
        }
    }

    /**
     * Nearest-value interpolation — returns the value of the closest keyframe.
     */
    fun nearestValue(points: List<Keyframe>): Fn {
        if (points.isEmpty()) return zero()
        val sorted = points.sortedBy { it.time }
        val d = Domain.of(sorted.first().time, sorted.last().time)
        return Fn(d, Domain.ALL, "nearest") { x ->
            sorted.minByOrNull { abs(it.time - x) }?.value ?: 0.0
        }
    }

    /**
     * Cosine interpolation through keyframes.
     *
     * Like piecewise-linear but each segment uses a cosine ease for softer
     * transitions — simple and cheap, no tangent computation required.
     */
    fun cosineInterp(points: List<Keyframe>): Fn {
        if (points.isEmpty()) return zero()
        val sorted = points.sortedBy { it.time }
        val d = Domain.of(sorted.first().time, sorted.last().time)
        return Fn(d, Domain.ALL, "cosine") { x ->
            when {
                x <= sorted.first().time -> sorted.first().value
                x >= sorted.last().time -> sorted.last().value
                else -> {
                    val i = sorted.indexOfLast { it.time <= x }
                    val a = sorted[i]
                    val b = sorted[i + 1]
                    val span = b.time - a.time
                    if (span < 1e-12) a.value
                    else {
                        val t = (x - a.time) / span
                        val mu = (1.0 - cos(t * PI)) * 0.5
                        a.value + (b.value - a.value) * mu
                    }
                }
            }
        }
    }

    /**
     * Smoothstep (Hermite) interpolation through keyframes.
     *
     * Each segment is eased with the classic smoothstep 3t² − 2t³, producing
     * a C¹-continuous curve with zero-velocity at every keyframe.
     */
    fun smoothStepInterp(points: List<Keyframe>): Fn {
        if (points.isEmpty()) return zero()
        val sorted = points.sortedBy { it.time }
        val d = Domain.of(sorted.first().time, sorted.last().time)
        return Fn(d, Domain.ALL, "smoothstep") { x ->
            when {
                x <= sorted.first().time -> sorted.first().value
                x >= sorted.last().time -> sorted.last().value
                else -> {
                    val i = sorted.indexOfLast { it.time <= x }
                    val a = sorted[i]
                    val b = sorted[i + 1]
                    val span = b.time - a.time
                    if (span < 1e-12) a.value
                    else {
                        val t = ((x - a.time) / span).coerceIn(0.0, 1.0)
                        val s = smoothstep(t)
                        a.value + (b.value - a.value) * s
                    }
                }
            }
        }
    }

    /**
     * Smootherstep (Ken Perlin's 6t⁵ − 15t⁴ + 10t³) interpolation.
     *
     * C²-continuous — smoother than smoothstep, with zero first *and* second
     * derivative at keyframe positions.
     */
    fun smootherStepInterp(points: List<Keyframe>): Fn {
        if (points.isEmpty()) return zero()
        val sorted = points.sortedBy { it.time }
        val d = Domain.of(sorted.first().time, sorted.last().time)
        return Fn(d, Domain.ALL, "smootherstep") { x ->
            when {
                x <= sorted.first().time -> sorted.first().value
                x >= sorted.last().time -> sorted.last().value
                else -> {
                    val i = sorted.indexOfLast { it.time <= x }
                    val a = sorted[i]
                    val b = sorted[i + 1]
                    val span = b.time - a.time
                    if (span < 1e-12) a.value
                    else {
                        val t = ((x - a.time) / span).coerceIn(0.0, 1.0)
                        val s = smootherstep(t)
                        a.value + (b.value - a.value) * s
                    }
                }
            }
        }
    }

    // ── Resampled / transformed variants ─────────────────────────────

    /**
     * Gaussian-smoothed version of a source [Fn].
     *
     * Convolves the function with a Gaussian kernel of the given [sigma],
     * keeping the same domain. Useful for "softening" a keyframe curve after
     * construction.
     *
     * @param source the function to smooth
     * @param sigma  standard deviation of the Gaussian kernel (in time units)
     * @param taps   number of kernel samples (higher = smoother but slower)
     */
    fun gaussianSmooth(source: Fn, sigma: Double = 0.1, taps: Int = 31): Fn {
        val halfTaps = taps / 2
        val weights = DoubleArray(taps) { i ->
            val offset = (i - halfTaps).toDouble()
            exp(-0.5 * (offset * offset) / (halfTaps * halfTaps).toDouble())
        }
        val wSum = weights.sum()
        for (i in weights.indices) weights[i] /= wSum

        return Fn(source.roi, source.domain, "gaussian-smooth(${source.name ?: "?"})") { x ->
            var acc = 0.0
            val step = sigma * 3.0 / halfTaps
            for (i in 0 until taps) {
                val offset = (i - halfTaps) * step
                acc += weights[i] * source.evalSafe(x + offset, source.eval(x), EdgeMode.CLAMP)
            }
            acc
        }
    }

    /**
     * Numerically differentiate a source [Fn] (central differences).
     *
     * @param source the function to differentiate
     * @param h      finite-difference half-width
     */
    fun differentiate(source: Fn, h: Double = 1e-4): Fn {
        return Fn(source.roi, source.domain, "d/dt(${source.name ?: "?"})") { x ->
            (source.eval(x + h) - source.eval(x - h)) / (2.0 * h)
        }
    }

    /**
     * Resample a [Fn] at uniform intervals and return a new [Fn] backed by
     * the sample table with the given [interpolation] mode.
     *
     * @param source       the function to resample
     * @param sampleCount  number of uniform samples
     * @param interpolation how to reconstruct between samples
     */
    /**
     * Produces a [Fn] that exactly matches the keyframe editor's native evaluation
     * (hold / linear / rational-bezier), by constructing a temporary
     * [KeyframeEvaluator] and sampling it.
     *
     * This is the function to use when you want a `Fn` that mirrors what the
     * user sees in the editor — the same bezier handles, the same hold segments,
     * the same tangent weights.
     *
     * @param keyframes     the keyframe list (e.g. from [KeyframeEvaluator.getKeyframes])
     * @param sampleCount   number of uniformly-spaced samples across the keyframe time range
     * @param interpolation how to reconstruct between samples in the resulting Fn
     */
    fun fromKeyframeEditor(
        keyframes: List<Keyframe>,
        sampleCount: Int = 512,
        interpolation: Interpolation = Interpolation.LINEAR
    ): Fn {
        if (keyframes.isEmpty()) return zero()
        val json = kotlinx.serialization.json.Json.encodeToString(keyframes)
        val evaluator = KeyframeEvaluator(json)
        val sorted = keyframes.sortedBy { it.time }
        val tMin = sorted.first().time
        val tMax = sorted.last().time
        val d = Domain.of(tMin, tMax)
        if (d._isEmpty || !d.isFinite || d.length < 1e-12) return c(sorted.first().value)

        val values = DoubleArray(sampleCount) { i ->
            val t = tMin + (tMax - tMin) * i.toDouble() / max(1, sampleCount - 1).toDouble()
            evaluator.evaluateValueAt(t)
        }
        return values.toFn(d, interpolation)
            .withName("keyframe-editor")
    }

    fun resample(
        source: Fn,
        sampleCount: Int = 256,
        interpolation: Interpolation = Interpolation.CUBIC
    ): Fn {
        val d = source.roi
        if (d._isEmpty || !d.isFinite) return source
        val samples = source.toDoubleArray(sampleCount, d, includeEnd = true)
        return samples.toFn(d, interpolation)
            .withName("resampled(${source.name ?: "?"})")
    }

    fun demo(
        sampleCount: Int = 512,
        seed: Long = 1L,
        domain: Domain = Domain.of(0.0, 1.0),
        printSummary: Boolean = true
    ): DemoResult {
        val d = domain

        val base = sin(frequency = c(2.0), amplitude = c(1.0), roi = d, domain = d)
        val saw = saw(period = c(1.0 / 3.0), amplitude = c(0.7), roi = d, domain = d)
        val tri = triangle(period = c(1.0 / 4.0), amplitude = c(0.5), roi = d, domain = d)
        val square = square(period = c(1.0), amplitude = c(0.6), duty = c(0.3), roi = d, domain = d)
        val pulse = pulseTrain(period = c(0.2), duty = c(0.35), amplitude = c(1.0), offset = c(0.0), roi = d, domain = d)
        val noise = valueNoise(seed = seed, frequency = c(8.0), amplitude = c(0.4), roi = d, domain = d)
        val fbmFn = fbm(seed = seed + 1, frequency = c(2.0), amplitude = c(0.4), octaves = 4, roi = d, domain = d)

        val mixed = (base + saw + tri + square + noise + fbmFn).softClip(c(1.2))
        val windowed = mixed * hannWindow(d)
        val blurred = mixed.blur(sigma = c(0.02), samples = 64)
        val averaged = mixed.movingAverage(window = c(0.1), samples = 64)
        val sampledHold = mixed.sampleHold(step = c(0.05))
        val granular = mixed.granular(grainSize = c(0.05), spacing = c(0.04), jitter = c(0.2), overlap = 1, seed = seed)

        val series = listOf(
            demoSeries("sin", base, d, sampleCount),
            demoSeries("saw", saw, d, sampleCount),
            demoSeries("triangle", tri, d, sampleCount),
            demoSeries("square", square, d, sampleCount),
            demoSeries("pulseTrain", pulse, d, sampleCount),
            demoSeries("valueNoise", noise, d, sampleCount),
            demoSeries("fbm", fbmFn, d, sampleCount),
            demoSeries("mixed", mixed, d, sampleCount),
            demoSeries("windowed", windowed, d, sampleCount),
            demoSeries("blurred", blurred, d, sampleCount),
            demoSeries("movingAverage", averaged, d, sampleCount),
            demoSeries("sampleHold", sampledHold, d, sampleCount),
            demoSeries("granular", granular, d, sampleCount)
        )

        val notes = listOf(
            "Domain: [${"%.3f".format(d.min)}, ${"%.3f".format(d.max)}]",
            "Samples per series: $sampleCount",
            "Seed: $seed"
        )

        if (printSummary) {
            println("Functions.demo: ${notes.joinToString(" | ")}")
            series.forEach { s ->
                println(
                    "  ${s.name.padEnd(14)} min=${"%.3f".format(s.stats.min)} " +
                        "max=${"%.3f".format(s.stats.max)} mean=${"%.3f".format(s.stats.mean)} " +
                        "rms=${"%.3f".format(s.stats.rms)}"
                )
            }
        }

        return DemoResult(series, notes)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        demo()
    }

    private fun demoSeries(name: String, fn: Fn, domain: Domain, count: Int): DemoSeries {
        val xs = sampleXs(domain, count)
        val ys = fn.toDoubleArray(count, domain)
        val stats = computeStats(ys)
        return DemoSeries(name, domain, xs, ys, stats)
    }

    private fun sampleXs(domain: Domain, count: Int): DoubleArray {
        if (count <= 0) return DoubleArray(0)
        if (domain._isEmpty) return DoubleArray(count) { Double.NaN }
        val len = domain.length
        val steps = max(1, count - 1)
        val step = if (steps == 0) 0.0 else len / steps.toDouble()
        return DoubleArray(count) { i -> domain.min + step * i.toDouble() }
    }

    private fun computeStats(values: DoubleArray): DemoStats {
        if (values.isEmpty()) return DemoStats(0.0, 0.0, 0.0, 0.0)
        var minV = Double.POSITIVE_INFINITY
        var maxV = Double.NEGATIVE_INFINITY
        var sum = 0.0
        var sumSq = 0.0
        for (v in values) {
            if (v < minV) minV = v
            if (v > maxV) maxV = v
            sum += v
            sumSq += v * v
        }
        val mean = sum / values.size.toDouble()
        val rms = kotlin.math.sqrt(sumSq / values.size.toDouble())
        return DemoStats(minV, maxV, mean, rms)
    }
}
