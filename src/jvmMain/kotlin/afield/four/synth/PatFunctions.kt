package afield.four.synth

import afield.Box
import afield.stdlib.Functions
import kotlin.jvm.JvmName
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.reflect.KProperty1

/*
 Bridge from `pat(...)` into scalar functions (`Functions.Fn`).

 There are now two complementary surfaces:

 1. String atoms:

      val fn = pat("g:(0,0.12) tent:(1,0.08,0.8) adsr:(0.6,0.01,0.08,0.4,0.2)")
          .asFn(cps = 2.0, range = 0.9)

 2. Typed atoms with late-bound values:

      val fn = pat("a b c") { atom ->
          when (atom) {
              "a" -> gaussianAtom(sigma = 0.08) { x -> kotlin.math.sin(local(x) * 12.0) }
              "b" -> gaussianAtom(sigma = 0.12) { x -> bipolarRandom() * (1.0 - normalized(x)) }
              else -> adsrAtom(attack = 0.01, decay = 0.08, sustain = 0.4, release = 0.2) { x ->
                  0.5 + 0.5 * noise(x, frequency = 18.0)
              }
          }
      }.asFn(cps = 2.0, maxLookAhead = 0.5)

 The evaluator keeps a rolling cache of decoded atoms and only unrolls the
 source pattern far enough ahead to cover the maximum possible pre-support of
 future atoms. The current string forms are:

 - `g:(value,sigma[,weight])`
 - `tent:(value,radius[,weight])`
 - `box:(value,radius[,weight])`
 - `wendland:(value,radius[,weight])`
 - `spline:(value,radius[,weight])`
 - `env:(value,attack,release[,weight])`
 - `adsr:(value,attack,decay,sustain,release[,weight])`

 3. String atoms with `%binding` placeholders resolved at event-decode time:

      var t = 0.0
      val fn = pat("g:(%x,0.2) tent:(0.3,0.08,0.8) adsr:(%y,0.01,0.08,0.4,0.2)")
          .asFn(cps = 2.0, range = 0.9) { binding ->
              when (binding) {
                  "x" -> Math.random()
                  "y" -> Math.sin(t)
                  else -> 0.0
              }
          }

    Any numeric position in a string atom can hold `%name` instead of a literal.
    The resolver is called at event-decode time (when the evaluator unrolls
    events), so each cycle gets a fresh snapshot of the bound values.

    If no explicit resolver is given, `%name` bindings are resolved from the
    current box's KotlinInterface vars table (`Box.context` → `ki.context.vars`).
    Any `Number`-typed var is coerced to `Double`; non-numeric or missing vars
    resolve to `0.0`.
 */

data class PatFunctionAtom(
    val start: Double,
    val end: Double,
    val valueAt: (x: Double) -> Double,
    val weightAt: (x: Double) -> Double
) {
    fun contains(x: Double): Boolean = x >= start && x <= end

    fun safeWeightAt(x: Double): Double {
        if (!contains(x)) return 0.0
        val w = weightAt(x)
        return if (w.isFinite() && w > 0.0) w else 0.0
    }

    fun safeValueAt(x: Double): Double {
        val v = valueAt(x)
        return if (v.isFinite()) v else 0.0
    }
}

class PatFunctionAtomScope internal constructor(
    val event: PatScheduledEvent<*>,
    val maxLookAhead: Double
) {
    val start: Double
        get() = event.start

    val end: Double
        get() = event.end

    val duration: Double
        get() = event.duration

    val cycleIndex: Long
        get() = event.cycleIndex

    val seed: Long by lazy {
        mix64(
            cycleIndex xor
                start.toRawBits() xor
                end.toRawBits() xor
                event.value.hashCode().toLong()
        )
    }

    fun local(x: Double): Double = x - start

    fun centered(x: Double, center: Double = start): Double = x - center

    fun normalized(x: Double, from: Double = start, to: Double = end): Double {
        val span = (to - from).coerceAtLeast(1e-9)
        return ((x - from) / span).coerceIn(0.0, 1.0)
    }

    fun random01(salt: Int = 0): Double = unitFromHash(seed xor salt.toLong())

    fun bipolarRandom(salt: Int = 0): Double = random01(salt) * 2.0 - 1.0

    fun noise(x: Double, frequency: Double = 1.0, salt: Int = 0): Double {
        val u = local(x) * frequency
        val i0 = floor(u).toLong()
        val t = u - i0.toDouble()
        val a = unitFromHash(seed xor salt.toLong() xor i0)
        val b = unitFromHash(seed xor salt.toLong() xor (i0 + 1L))
        val s = t * t * (3.0 - 2.0 * t)
        return a + (b - a) * s
    }
}

fun interface PatFunctionAtomSpec {
    fun materialize(event: PatScheduledEvent<*>, maxLookAhead: Double): PatFunctionAtom
}

private class PatFunctionField<A>(
    private val pattern: PatPattern<A>,
    private val cps: Double,
    private val outside: Double,
    private val maxLookAhead: Double,
    private val strict: Boolean,
    private val atomDecoder: (PatScheduledEvent<A>, Double) -> PatFunctionAtom?
) {
    private var iterator = pattern.scheduled(cps = cps, clock = null).iterator()
    private val cachedAtoms = ArrayDeque<PatFunctionAtom>()
    private var generatedUntil = Double.NEGATIVE_INFINITY
    private var lastEvalX = Double.NEGATIVE_INFINITY

    fun eval(x: Double): Double {
        if (!x.isFinite() || x < 0.0) return outside
        if (x + 1e-9 < lastEvalX) reset()
        ensureUnrolled(x + maxLookAhead)
        pruneBefore(x)

        var numerator = 0.0
        var denominator = 0.0
        for (atom in cachedAtoms) {
            if (!atom.contains(x)) continue
            val weight = atom.safeWeightAt(x)
            if (weight == 0.0) continue
            numerator += atom.safeValueAt(x) * weight
            denominator += weight
        }
        lastEvalX = x
        return if (abs(denominator) <= 1e-12) outside else numerator / denominator
    }

    private fun reset() {
        iterator = pattern.scheduled(cps = cps, clock = null).iterator()
        cachedAtoms.clear()
        generatedUntil = Double.NEGATIVE_INFINITY
        lastEvalX = Double.NEGATIVE_INFINITY
    }

    private fun ensureUnrolled(target: Double) {
        if (target <= generatedUntil) return
        while (iterator.hasNext() && generatedUntil < target) {
            val event = iterator.next()
            generatedUntil = max(generatedUntil, event.end)
            val atom = atomDecoder(event, maxLookAhead)
            if (atom != null) {
                cachedAtoms.addLast(atom)
                generatedUntil = max(generatedUntil, atom.end)
            } else if (strict) {
                throw IllegalArgumentException("Unsupported pat function atom `${event.value}` at ${event.start}")
            }
        }
    }

    private fun pruneBefore(threshold: Double) {
        while (cachedAtoms.isNotEmpty() && cachedAtoms.first().end < threshold) {
            cachedAtoms.removeFirst()
        }
    }
}

fun defaultBindingResolver(name: String): Double {
    val box = Box.context.get() ?: return 0.0
    return resolveBindingFromBox(box, name)
}

private fun resolveBindingFromBox(box: Box, name: String): Double {
    val varInfo = box.ki.context.vars[name] ?: return 0.0
    val live = try {
        @Suppress("UNCHECKED_CAST")
        (varInfo.descriptor as? KProperty1<Any, *>)?.get(varInfo.line)
    } catch (_: Exception) {
        varInfo.value
    }
    return (live as? Number)?.toDouble() ?: 0.0
}

private fun defaultStringAtomDecoder(): (PatScheduledEvent<String>, Double) -> PatFunctionAtom? {
    val box = Box.context.get()
    return if (box != null) {
        { event, maxLookAhead ->
            decodePatFunctionAtomCore(event, maxLookAhead) { name -> resolveBindingFromBox(box, name) }
        }
    } else {
        ::decodePatFunctionAtom
    }
}

fun patFn(
    source: String,
    cps: Number = 1.0,
    range: Number = 1.0,
    outside: Number = 0.0,
    strict: Boolean = true,
    atomDecoder: (PatScheduledEvent<String>, Double) -> PatFunctionAtom? = defaultStringAtomDecoder()
): Functions.Fn = pat(source).asFn(
    cps = cps,
    range = range,
    outside = outside,
    strict = strict,
    atomDecoder = atomDecoder
)

fun patFn(
    source: String,
    cps: Number = 1.0,
    range: Number = 1.0,
    outside: Number = 0.0,
    strict: Boolean = true,
    bindingResolver: (String) -> Double
): Functions.Fn = pat(source).asFn(
    cps = cps,
    range = range,
    outside = outside,
    strict = strict,
    bindingResolver = bindingResolver
)

fun PatPattern<String>.asFn(
    cps: Number = 1.0,
    range: Number = 1.0,
    outside: Number = 0.0,
    strict: Boolean = true,
    atomDecoder: (PatScheduledEvent<String>, Double) -> PatFunctionAtom? = defaultStringAtomDecoder()
): Functions.Fn = buildPatFunction(
    pattern = this,
    cps = cps,
    maxLookAhead = range,
    outside = outside,
    strict = strict,
    atomDecoder = atomDecoder
)

fun PatPattern<String>.asFn(
    cps: Number = 1.0,
    range: Number = 1.0,
    outside: Number = 0.0,
    strict: Boolean = true,
    bindingResolver: (String) -> Double
): Functions.Fn = buildPatFunction(
    pattern = this,
    cps = cps,
    maxLookAhead = range,
    outside = outside,
    strict = strict,
    atomDecoder = { event, maxLookAhead ->
        decodePatFunctionAtomWithBindings(event, maxLookAhead, bindingResolver)
    }
)

fun PatPattern<PatFunctionAtomSpec>.asFn(
    cps: Number = 1.0,
    maxLookAhead: Number = 1.0,
    outside: Number = 0.0,
    strict: Boolean = true
): Functions.Fn = buildPatFunction(
    pattern = this,
    cps = cps,
    maxLookAhead = maxLookAhead,
    outside = outside,
    strict = strict
) { event, lookAhead ->
    event.value.materialize(event, lookAhead)
}

@JvmName("asFnDecoded")
fun <A> PatPattern<A>.asFn(
    cps: Number = 1.0,
    maxLookAhead: Number = 1.0,
    outside: Number = 0.0,
    strict: Boolean = true,
    atomDecoder: (PatScheduledEvent<A>, Double) -> PatFunctionAtom?
): Functions.Fn = buildPatFunction(
    pattern = this,
    cps = cps,
    maxLookAhead = maxLookAhead,
    outside = outside,
    strict = strict,
    atomDecoder = atomDecoder
)

private fun <A> buildPatFunction(
    pattern: PatPattern<A>,
    cps: Number,
    maxLookAhead: Number,
    outside: Number,
    strict: Boolean,
    atomDecoder: (PatScheduledEvent<A>, Double) -> PatFunctionAtom?
): Functions.Fn {
    val cpsValue = cps.toDouble().coerceAtLeast(1e-6)
    val lookAhead = maxLookAhead.toDouble().coerceAtLeast(1e-6)
    val field = PatFunctionField(
        pattern = pattern,
        cps = cpsValue,
        outside = outside.toDouble(),
        maxLookAhead = lookAhead,
        strict = strict,
        atomDecoder = atomDecoder
    )
    val domain = Functions.Domain(0.0, Double.POSITIVE_INFINITY)
    return Functions.Fn(
        roi = domain,
        domain = domain,
        name = "pat-fn"
    ) { x ->
        field.eval(x)
    }
}

fun gaussianAtom(value: Number, sigma: Number, weight: Number = 1.0): PatFunctionAtomSpec =
    gaussianAtom(sigma = sigma, weight = weight) { value.toDouble() }

fun gaussianAtom(
    sigma: Number,
    weight: Number = 1.0,
    valueAt: PatFunctionAtomScope.(x: Double) -> Double
): PatFunctionAtomSpec {
    val safeSigma = sigma.toDouble().coerceAtLeast(1e-6)
    val weightScale = weight.toDouble()
    return symmetricSpec(
        radiusFor = { maxLookAhead -> min(safeSigma * 3.0, maxLookAhead.coerceAtLeast(1e-6)) },
        weightScale = weightScale,
        valueAt = valueAt
    ) { distance, _ ->
        val z = distance / safeSigma
        exp(-0.5 * z * z)
    }
}

fun tentAtom(value: Number, radius: Number, weight: Number = 1.0): PatFunctionAtomSpec =
    tentAtom(radius = radius, weight = weight) { value.toDouble() }

fun tentAtom(
    radius: Number,
    weight: Number = 1.0,
    valueAt: PatFunctionAtomScope.(x: Double) -> Double
): PatFunctionAtomSpec {
    val radiusValue = radius.toDouble().coerceAtLeast(1e-6)
    return symmetricSpec(
        radiusFor = { maxLookAhead -> min(radiusValue, maxLookAhead.coerceAtLeast(1e-6)) },
        weightScale = weight.toDouble(),
        valueAt = valueAt
    ) { distance, actualRadius ->
        (1.0 - distance / actualRadius).coerceAtLeast(0.0)
    }
}

fun boxAtom(value: Number, radius: Number, weight: Number = 1.0): PatFunctionAtomSpec =
    boxAtom(radius = radius, weight = weight) { value.toDouble() }

fun boxAtom(
    radius: Number,
    weight: Number = 1.0,
    valueAt: PatFunctionAtomScope.(x: Double) -> Double
): PatFunctionAtomSpec {
    val radiusValue = radius.toDouble().coerceAtLeast(1e-6)
    return symmetricSpec(
        radiusFor = { maxLookAhead -> min(radiusValue, maxLookAhead.coerceAtLeast(1e-6)) },
        weightScale = weight.toDouble(),
        valueAt = valueAt
    ) { _, _ -> 1.0 }
}

fun wendlandAtom(value: Number, radius: Number, weight: Number = 1.0): PatFunctionAtomSpec =
    wendlandAtom(radius = radius, weight = weight) { value.toDouble() }

fun wendlandAtom(
    radius: Number,
    weight: Number = 1.0,
    valueAt: PatFunctionAtomScope.(x: Double) -> Double
): PatFunctionAtomSpec {
    val radiusValue = radius.toDouble().coerceAtLeast(1e-6)
    return symmetricSpec(
        radiusFor = { maxLookAhead -> min(radiusValue, maxLookAhead.coerceAtLeast(1e-6)) },
        weightScale = weight.toDouble(),
        valueAt = valueAt
    ) { distance, actualRadius ->
        val r = (distance / actualRadius).coerceIn(0.0, 1.0)
        val oneMinusR = 1.0 - r
        oneMinusR * oneMinusR * oneMinusR * oneMinusR * (4.0 * r + 1.0)
    }
}

fun splineAtom(value: Number, radius: Number, weight: Number = 1.0): PatFunctionAtomSpec =
    splineAtom(radius = radius, weight = weight) { value.toDouble() }

fun splineAtom(
    radius: Number,
    weight: Number = 1.0,
    valueAt: PatFunctionAtomScope.(x: Double) -> Double
): PatFunctionAtomSpec {
    val radiusValue = radius.toDouble().coerceAtLeast(1e-6)
    return symmetricSpec(
        radiusFor = { maxLookAhead -> min(radiusValue, maxLookAhead.coerceAtLeast(1e-6)) },
        weightScale = weight.toDouble(),
        valueAt = valueAt
    ) { distance, actualRadius ->
        val r = (distance / actualRadius).coerceIn(0.0, 1.0)
        val oneMinusR2 = (1.0 - r * r).coerceAtLeast(0.0)
        oneMinusR2 * oneMinusR2
    }
}

fun envAtom(value: Number, attack: Number, release: Number, weight: Number = 1.0): PatFunctionAtomSpec =
    envAtom(attack = attack, release = release, weight = weight) { value.toDouble() }

fun envAtom(
    attack: Number,
    release: Number,
    weight: Number = 1.0,
    valueAt: PatFunctionAtomScope.(x: Double) -> Double
): PatFunctionAtomSpec {
    val safeAttack = attack.toDouble().coerceAtLeast(1e-6)
    val safeRelease = release.toDouble().coerceAtLeast(1e-6)
    val weightScale = weight.toDouble()
    return PatFunctionAtomSpec { event, _ ->
        val scope = PatFunctionAtomScope(event, maxLookAhead = 0.0)
        val hold = event.duration.coerceAtLeast(0.0)
        val attackEnd = event.start + safeAttack
        val holdEnd = attackEnd + hold
        val end = holdEnd + safeRelease
        PatFunctionAtom(
            start = event.start,
            end = end,
            valueAt = { x -> scope.valueAt(x) },
            weightAt = { x ->
                val env = when {
                    x <= attackEnd -> ((x - event.start) / safeAttack).coerceIn(0.0, 1.0)
                    x <= holdEnd -> 1.0
                    else -> (1.0 - (x - holdEnd) / safeRelease).coerceIn(0.0, 1.0)
                }
                env * weightScale
            }
        )
    }
}

fun adsrAtom(
    value: Number,
    attack: Number,
    decay: Number,
    sustain: Number,
    release: Number,
    weight: Number = 1.0
): PatFunctionAtomSpec = adsrAtom(
    attack = attack,
    decay = decay,
    sustain = sustain,
    release = release,
    weight = weight
) { value.toDouble() }

fun adsrAtom(
    attack: Number,
    decay: Number,
    sustain: Number,
    release: Number,
    weight: Number = 1.0,
    valueAt: PatFunctionAtomScope.(x: Double) -> Double
): PatFunctionAtomSpec {
    val safeAttack = attack.toDouble().coerceAtLeast(1e-6)
    val safeDecay = decay.toDouble().coerceAtLeast(1e-6)
    val sustainLevel = sustain.toDouble().coerceIn(0.0, 1.0)
    val safeRelease = release.toDouble().coerceAtLeast(1e-6)
    val weightScale = weight.toDouble()
    return PatFunctionAtomSpec { event, _ ->
        val scope = PatFunctionAtomScope(event, maxLookAhead = 0.0)
        val hold = event.duration.coerceAtLeast(0.0)
        val attackEnd = event.start + safeAttack
        val decayEnd = attackEnd + safeDecay
        val sustainEnd = decayEnd + hold
        val end = sustainEnd + safeRelease
        PatFunctionAtom(
            start = event.start,
            end = end,
            valueAt = { x -> scope.valueAt(x) },
            weightAt = { x ->
                val env = when {
                    x <= attackEnd -> ((x - event.start) / safeAttack).coerceIn(0.0, 1.0)
                    x <= decayEnd -> {
                        val t = ((x - attackEnd) / safeDecay).coerceIn(0.0, 1.0)
                        1.0 + (sustainLevel - 1.0) * t
                    }
                    x <= sustainEnd -> sustainLevel
                    else -> {
                        val t = ((x - sustainEnd) / safeRelease).coerceIn(0.0, 1.0)
                        sustainLevel * (1.0 - t)
                    }
                }
                env * weightScale
            }
        )
    }
}

fun decodePatFunctionAtom(event: PatScheduledEvent<String>, maxThreeSigma: Double): PatFunctionAtom? =
    decodePatFunctionAtomCore(event, maxThreeSigma, null)

fun decodePatFunctionAtomWithBindings(
    event: PatScheduledEvent<String>,
    maxThreeSigma: Double,
    bindingResolver: (String) -> Double
): PatFunctionAtom? = decodePatFunctionAtomCore(event, maxThreeSigma, bindingResolver)

private fun decodePatFunctionAtomCore(
    event: PatScheduledEvent<String>,
    maxThreeSigma: Double,
    bindingResolver: ((String) -> Double)?
): PatFunctionAtom? {
    val text = event.value.trim()
    val shape = text.substringBefore(":("  , "")
    if (shape.isEmpty() || !text.endsWith(")")) return null
    val body = text.substring(shape.length + 2, text.length - 1)
    val values = parsePatFunctionNumbers(body, text, bindingResolver)
    return when (shape) {
        "g" -> gaussianPatFunctionAtom(event, values, maxThreeSigma, text)
        "tent" -> tentPatFunctionAtom(event, values, maxThreeSigma, text)
        "box" -> boxPatFunctionAtom(event, values, maxThreeSigma, text)
        "wendland" -> wendlandPatFunctionAtom(event, values, maxThreeSigma, text)
        "spline" -> splinePatFunctionAtom(event, values, maxThreeSigma, text)
        "env" -> envelopePatFunctionAtom(event, values, text)
        "adsr" -> adsrPatFunctionAtom(event, values, text)
        else -> null
    }
}

fun decodeGaussianPatFunctionAtom(event: PatScheduledEvent<String>, maxThreeSigma: Double): PatFunctionAtom? {
    return decodePatFunctionAtom(event, maxThreeSigma)?.takeIf { event.value.trim().startsWith("g:(") }
}

private fun parsePatFunctionNumbers(
    body: String,
    source: String,
    bindingResolver: ((String) -> Double)? = null
): List<Double> =
    body.split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { token ->
            if (token.startsWith("%") && token.length > 1 && bindingResolver != null) {
                bindingResolver(token.substring(1))
            } else {
                token.toDoubleOrNull()
                    ?: throw IllegalArgumentException("Invalid pat function number `$token` in `$source`")
            }
        }

private fun parseValueWeight(
    values: List<Double>,
    source: String,
    minTailCount: Int,
    headName: String
): Triple<Double, List<Double>, Double> {
    if (values.size !in (minTailCount + 1)..(minTailCount + 2)) {
        throw IllegalArgumentException("Expected $headName with ${minTailCount + 1} or ${minTailCount + 2} values in `$source`")
    }
    val value = values.first()
    val tailEnd = 1 + minTailCount
    val tail = values.subList(1, tailEnd)
    val weight = values.getOrElse(tailEnd) { 1.0 }
    return Triple(value, tail, weight)
}

private fun gaussianPatFunctionAtom(
    event: PatScheduledEvent<String>,
    values: List<Double>,
    maxThreeSigma: Double,
    source: String
): PatFunctionAtom {
    val (value, tail, weight) = parseValueWeight(values, source, minTailCount = 1, headName = "g:(value,sigma[,weight])")
    return gaussianAtom(value = value, sigma = tail[0], weight = weight).materialize(event, maxThreeSigma)
}

private fun tentPatFunctionAtom(
    event: PatScheduledEvent<String>,
    values: List<Double>,
    maxLookAhead: Double,
    source: String
): PatFunctionAtom {
    val (value, tail, weight) = parseValueWeight(values, source, minTailCount = 1, headName = "tent:(value,radius[,weight])")
    return tentAtom(value = value, radius = tail[0], weight = weight).materialize(event, maxLookAhead)
}

private fun boxPatFunctionAtom(
    event: PatScheduledEvent<String>,
    values: List<Double>,
    maxLookAhead: Double,
    source: String
): PatFunctionAtom {
    val (value, tail, weight) = parseValueWeight(values, source, minTailCount = 1, headName = "box:(value,radius[,weight])")
    return boxAtom(value = value, radius = tail[0], weight = weight).materialize(event, maxLookAhead)
}

private fun wendlandPatFunctionAtom(
    event: PatScheduledEvent<String>,
    values: List<Double>,
    maxLookAhead: Double,
    source: String
): PatFunctionAtom {
    val (value, tail, weight) = parseValueWeight(values, source, minTailCount = 1, headName = "wendland:(value,radius[,weight])")
    return wendlandAtom(value = value, radius = tail[0], weight = weight).materialize(event, maxLookAhead)
}

private fun splinePatFunctionAtom(
    event: PatScheduledEvent<String>,
    values: List<Double>,
    maxLookAhead: Double,
    source: String
): PatFunctionAtom {
    val (value, tail, weight) = parseValueWeight(values, source, minTailCount = 1, headName = "spline:(value,radius[,weight])")
    return splineAtom(value = value, radius = tail[0], weight = weight).materialize(event, maxLookAhead)
}

private fun envelopePatFunctionAtom(event: PatScheduledEvent<String>, values: List<Double>, source: String): PatFunctionAtom {
    val (value, tail, weight) = parseValueWeight(values, source, minTailCount = 2, headName = "env:(value,attack,release[,weight])")
    return envAtom(value = value, attack = tail[0], release = tail[1], weight = weight).materialize(event, 0.0)
}

private fun adsrPatFunctionAtom(event: PatScheduledEvent<String>, values: List<Double>, source: String): PatFunctionAtom {
    val (value, tail, weight) = parseValueWeight(
        values,
        source,
        minTailCount = 4,
        headName = "adsr:(value,attack,decay,sustain,release[,weight])"
    )
    return adsrAtom(
        value = value,
        attack = tail[0],
        decay = tail[1],
        sustain = tail[2],
        release = tail[3],
        weight = weight
    ).materialize(event, 0.0)
}

private fun symmetricSpec(
    radiusFor: (maxLookAhead: Double) -> Double,
    weightScale: Double,
    valueAt: PatFunctionAtomScope.(x: Double) -> Double,
    kernel: (distance: Double, actualRadius: Double) -> Double
): PatFunctionAtomSpec = PatFunctionAtomSpec { event, maxLookAhead ->
    val scope = PatFunctionAtomScope(event, maxLookAhead)
    val radius = radiusFor(maxLookAhead).coerceAtLeast(1e-6)
    val center = event.start
    PatFunctionAtom(
        start = center - radius,
        end = center + radius,
        valueAt = { x -> scope.valueAt(x) },
        weightAt = { x ->
            val distance = abs(x - center)
            if (distance > radius) 0.0 else kernel(distance, radius) * weightScale
        }
    )
}

private fun mix64(x: Long): Long {
    var z = x - 7046029254386353131L
    z = (z xor (z ushr 30)) * -4658895280553007687L
    z = (z xor (z ushr 27)) * -7723592293110705685L
    return z xor (z ushr 31)
}

private fun unitFromHash(x: Long): Double {
    val bits = mix64(x) ushr 11
    return bits.toDouble() / (1L shl 53).toDouble()
}
