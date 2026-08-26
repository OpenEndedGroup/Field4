@file:afield.CompletionCategory("Patterns")
package afield.four.synth

import afield.Box
import afield.stdlib.FnGraphEdge
import afield.stdlib.FnGraphNode
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

 The `pat` graph op
 -----------------

 A string-atom pattern does not just *evaluate* as an `Fn`, it appears in the
 `Fn` graph as a node of its own — `op = "pat"` — so a rhythm is a first-class
 citizen of the rewrite/fit/evolve machinery rather than an opaque lambda that
 happens to produce numbers. See [scanPatAtomSlots] for what gets hoisted.

 - `strings["source"]` is the pattern text, with the node's *current* numbers
   spliced back in, so a fitted gesture is a string you can paste into a box.
 - every numeric position of every string atom becomes a role-tagged child
   (`atom0.sigma`, `atom1.attack`, …), and `cps` is one too. Those children are
   `param` leaves, which is exactly what `extractParameters` / `fitGradient`
   treat as θ — so Adam tunes kernel widths, atom values and tempo directly.
 - `numbers` carries `range` and `outside`, and `booleans` carries `strict`.
   These describe the *evaluator*, not the gesture, so they stay out of θ
   deliberately: a gradient has no business wandering into a cache bound.

 A parameter child is read at each atom's own onset rather than once at build
 time, so grafting a real signal into a role (rather than leaving the constant
 leaf there) gives a kernel width that drifts across the piece.

 Not every pattern can carry the node, and the ones that cannot fall back to
 the opaque `Fn` this file has always produced:

 - the typed-atom and custom-`atomDecoder` surfaces (2. above) hold user
   lambdas, and a lambda cannot describe how to rebuild itself;
 - a `%binding` in a numeric position resolves against live box state at
   decode time, so the result is not a function of `x` alone. `fitGradient`
   samples non-monotonically and would be fitting a moving target, so a source
   that really uses a binding stays opaque. A binding *resolver* that the text
   never calls on is harmless, and does not cost the node.
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

    /**
     * The largest `x` an already-dropped atom still reached, so `eval` knows when it has genuinely
     * forgotten something and has to start the pattern again.
     *
     * The evaluator streams — it unrolls the pattern forwards and drops atoms it has passed — but
     * plenty of real callers sample *backwards*: `blur` reads `x ± 3σ`, `derivative` reads
     * `x ± step`, `convolve` sweeps a whole kernel, and `fitGradient` samples a fixed grid in no
     * particular order. Resetting on every backwards step (which is what comparing against the
     * previous `x` did) made each of those re-unroll the pattern from cycle zero, once per output
     * sample. Retaining one look-ahead of history instead means a backwards probe inside that
     * window is answered from the cache, and the reset is kept for the case that actually needs
     * it: an `x` older than anything still held.
     */
    private var prunedUpTo = Double.NEGATIVE_INFINITY

    fun eval(x: Double): Double {
        if (!x.isFinite() || x < 0.0) return outside
        if (x <= prunedUpTo) reset()
        ensureUnrolled(x + maxLookAhead)

        var numerator = 0.0
        var denominator = 0.0
        for (atom in cachedAtoms) {
            if (!atom.contains(x)) continue
            val weight = atom.safeWeightAt(x)
            if (weight == 0.0) continue
            numerator += atom.safeValueAt(x) * weight
            denominator += weight
        }
        // Prune after reading, and only behind the retained window, so the atoms this call needed
        // are still there for a caller working its way back up to `x` again.
        pruneBefore(x - maxLookAhead)
        return if (abs(denominator) <= 1e-12) outside else numerator / denominator
    }

    private fun reset() {
        iterator = pattern.scheduled(cps = cps, clock = null).iterator()
        cachedAtoms.clear()
        generatedUntil = Double.NEGATIVE_INFINITY
        prunedUpTo = Double.NEGATIVE_INFINITY
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
            // Record how far forward the atom we are about to lose still had influence: that, not
            // the threshold, is the oldest `x` this field can no longer answer honestly.
            prunedUpTo = max(prunedUpTo, cachedAtoms.removeFirst().end)
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

/**
 * The library's own string-atom decoder, as a named type rather than a lambda.
 *
 * Being able to *recognise* the built-in decoder is what lets `asFn` decide whether the result can
 * carry a `pat` graph node: a caller-supplied `atomDecoder` is an arbitrary lambda with arbitrary
 * state, so a graph could not describe how to rebuild it, while this one is fully described by the
 * pattern text plus the hoisted numbers. See the file header.
 */
private class PatBuiltinStringDecoder(
    val bindingResolver: ((String) -> Double)?
) : (PatScheduledEvent<String>, Double) -> PatFunctionAtom? {
    override fun invoke(event: PatScheduledEvent<String>, maxLookAhead: Double): PatFunctionAtom? =
        decodePatFunctionAtomCore(event, maxLookAhead, bindingResolver)
}

private fun defaultStringAtomDecoder(): (PatScheduledEvent<String>, Double) -> PatFunctionAtom? {
    val box = Box.context.get()
    return if (box != null) {
        PatBuiltinStringDecoder { name -> resolveBindingFromBox(box, name) }
    } else {
        PatBuiltinStringDecoder(null)
    }
}

fun patFn(
    source: String,
    cps: Number = 1.0,
    range: Number = 1.0,
    outside: Number = 0.0,
    strict: Boolean = true,
    atomDecoder: (PatScheduledEvent<String>, Double) -> PatFunctionAtom? = defaultStringAtomDecoder()
): Functions.Fn = buildStringPatFunction(
    pattern = pat(source),
    sourceText = source,
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
): Functions.Fn = buildStringPatFunction(
    pattern = pat(source),
    sourceText = source,
    cps = cps,
    range = range,
    outside = outside,
    strict = strict,
    atomDecoder = PatBuiltinStringDecoder(bindingResolver)
)

fun PatPattern<String>.asFn(
    cps: Number = 1.0,
    range: Number = 1.0,
    outside: Number = 0.0,
    strict: Boolean = true,
    atomDecoder: (PatScheduledEvent<String>, Double) -> PatFunctionAtom? = defaultStringAtomDecoder()
): Functions.Fn = buildStringPatFunction(
    pattern = this,
    sourceText = null,
    cps = cps,
    range = range,
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
): Functions.Fn = buildStringPatFunction(
    pattern = this,
    sourceText = null,
    cps = cps,
    range = range,
    outside = outside,
    strict = strict,
    atomDecoder = PatBuiltinStringDecoder(bindingResolver)
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
        domain = domain,
        name = "pat-fn"
    ) { x ->
        field.eval(x)
    }
}

// ── `pat` as a first-class graph op ───────────────────────────────────────────────────────

/**
 * The numeric positions each string-atom shape carries, in payload order.
 *
 * These names are the role suffixes the graph node uses, so `graphText` reads `atom0.sigma` rather
 * than `atom0.1` and a rewrite rule can say which knob it means. The trailing `weight` is optional
 * in the text, so an atom contributes as many leaves as it actually wrote: two for `g:(0,0.1)`,
 * three for `g:(0,0.1,0.7)`.
 */
private val PAT_ATOM_NUMBER_ROLES: Map<String, List<String>> = mapOf(
    "g" to listOf("value", "sigma", "weight"),
    "tent" to listOf("value", "radius", "weight"),
    "box" to listOf("value", "radius", "weight"),
    "wendland" to listOf("value", "radius", "weight"),
    "spline" to listOf("value", "radius", "weight"),
    "env" to listOf("value", "attack", "release", "weight"),
    "adsr" to listOf("value", "attack", "decay", "sustain", "release", "weight")
)

/**
 * One numeric position of one string atom, lifted out of the pattern text into a graph leaf.
 *
 * [atomOffset] is where the atom token starts in the source, which is the stable key back to it:
 * it is what `PatSourceSpan.start` reports for that token, so it survives every transform the
 * pattern is put through afterwards — `fast`, `rev`, `stutter` and `chop` all carry spans along.
 * [start] and [endExclusive] bound the literal itself, for splicing a fitted value back into the
 * displayed source.
 */
private class PatAtomSlot(
    val shape: String,
    val atomOffset: Int,
    val payloadIndex: Int,
    val role: String,
    val literal: Double,
    val start: Int,
    val endExclusive: Int
)

/** The parameter children of one atom, plus the shape they were scanned from. */
private class PatAtomOverride(
    val shape: String,
    val parameters: List<Functions.Fn>
)

/** The atom-token characters, exactly as `MiniPat.readAtom` reads them — see [scanPatAtomSlots]. */
private fun isPatAtomChar(ch: Char): Boolean =
    ch.isLetterOrDigit() || ch == '_' || ch == '-' || ch == ':' || ch == '.' || ch == '+' || ch == '\''

/** The index of the `)` closing the payload that opens at [open], matched by depth, or -1. */
private fun patAtomPayloadEnd(source: String, open: Int): Int {
    var depth = 0
    var index = open
    while (index < source.length) {
        when (source[index]) {
            '(' -> depth++
            ')' -> {
                depth--
                if (depth <= 0) return index
            }
        }
        index++
    }
    return -1
}

/**
 * Finds every hoistable number in [source], or **null** if this text cannot carry a graph node.
 *
 * A lexical scan rather than a second parser, because it has to agree with
 * `MiniPat.readAtom` about where an atom token *starts*, because that offset is the key the decoder
 * later matches against `PatSourceSpan.start`. Sharing `readAtom`'s own token rule
 * ([isPatAtomChar], balanced payload parens) is what makes that agreement structural rather than
 * hopeful — in particular a shape name only counts at a token boundary, so the `g` in `bdg:(0,1)`
 * is not mistaken for a Gaussian.
 *
 * Scanning the text, rather than the events, also means an atom that only plays on some cycles
 * (inside `<a b>`, or behind a `?`) still gets its leaves. Returns the slots in ascending text
 * order, which is the order the node's children — and therefore θ — are in.
 *
 * Null means "leave this one opaque", for a reason the caller cannot fix by trying harder:
 * a `%binding` in a numeric position (resolved from live state, so not a function of x), or a
 * payload that is not a plain number, or more numbers than the shape has roles.
 */
private fun scanPatAtomSlots(source: String): List<PatAtomSlot>? {
    val slots = mutableListOf<PatAtomSlot>()
    var atomIndex = 0
    var index = 0
    while (index < source.length) {
        if (index > 0 && isPatAtomChar(source[index - 1])) {
            index++
            continue
        }
        val shape = PAT_ATOM_NUMBER_ROLES.keys.firstOrNull { source.startsWith("$it:(", index) }
        if (shape == null) {
            index++
            continue
        }
        val open = index + shape.length + 1
        val close = patAtomPayloadEnd(source, open)
        if (close < 0) return null
        val roles = PAT_ATOM_NUMBER_ROLES.getValue(shape)
        var cursor = open + 1
        var position = 0
        for (token in source.substring(open + 1, close).split(',')) {
            val trimmed = token.trim()
            if (trimmed.isNotEmpty()) {
                if (position >= roles.size) return null
                val value = trimmed.toDoubleOrNull() ?: return null
                val literalStart = cursor + token.indexOf(trimmed)
                slots += PatAtomSlot(
                    shape = shape,
                    atomOffset = index,
                    payloadIndex = position,
                    role = "atom$atomIndex.${roles[position]}",
                    literal = value,
                    start = literalStart,
                    endExclusive = literalStart + trimmed.length
                )
                position++
            }
            cursor += token.length + 1
        }
        atomIndex++
        index = close + 1
    }
    return slots
}

/**
 * The pattern text with each hoisted number replaced by its parameter's current value.
 *
 * This is what `strings["source"]` holds, and the reason the node carries the text at all: after a
 * fit, the node's source *is* a pattern you can paste back into a box and hear. Rendering goes
 * through `Double.toString`, which is round-trip exact, rather than through `patFormatNumber`'s
 * nine decimal places — a finite-difference step can be smaller than that, and a display that
 * rounded it away would make a converging fit look stalled. A non-finite parameter prints as
 * `NaN`: unpasteable, but accurate about what the fit reached, and the evaluator sanitises it
 * separately (see `PatFunctionAtom.safeWeightAt`).
 */
private fun splicePatSource(
    sourceText: String,
    slots: List<PatAtomSlot>,
    parameters: List<Functions.Fn>
): String {
    if (slots.isEmpty()) return sourceText
    val out = StringBuilder()
    var cursor = 0
    slots.forEachIndexed { index, slot ->
        val value = parameters.getOrNull(index)?.evalSafe(0.0, slot.literal, Functions.EdgeMode.CLAMP) ?: slot.literal
        out.append(sourceText, cursor, slot.start)
        out.append(value.toString())
        cursor = slot.endExclusive
    }
    out.append(sourceText, cursor, sourceText.length)
    return out.toString()
}

/**
 * The decoder the graph path runs on: shapes and rhythm from the text, numbers from the leaves.
 *
 * Each atom's numbers are read from its parameter children at the atom's **own onset**, not once at
 * build time. For the `param`/`const` leaves a fit produces that is the same thing, but it means
 * grafting a real signal into a role gives a kernel width or an atom value that drifts across the
 * piece — the graph layer buying the pattern layer something it could not say before.
 *
 * Two guards keep an override from ever landing on the wrong atom. The span must come from the very
 * text that was scanned, so a `stack` of two separately-parsed strings cannot have one string's
 * offsets read against the other's; and the shape must still be the one that was scanned, so a
 * `map` that rewrote an event's text falls back to that text's own literals rather than feeding an
 * ADSR's numbers to a Gaussian.
 */
private fun patGraphAtomDecoder(
    sourceText: String,
    overrides: Map<Int, PatAtomOverride>
): (PatScheduledEvent<String>, Double) -> PatFunctionAtom? = { event, maxLookAhead ->
    val text = event.value.trim()
    val parts = patFunctionAtomParts(text)
    if (parts == null) {
        null
    } else {
        val (shape, body) = parts
        val span = event.sourceSpan
        val override = if (span != null && span.source == sourceText) overrides[span.start] else null
        val values = if (override != null && override.shape == shape) {
            override.parameters.map { it.evalSafe(event.start, 0.0, Functions.EdgeMode.CLAMP) }
        } else {
            parsePatFunctionNumbers(body, text, null)
        }
        buildPatFunctionAtom(event, shape, values, maxLookAhead, text)
    }
}

/**
 * Builds the graph-bearing `Fn` for a string-atom pattern: the `op = "pat"` node of the file header.
 *
 * [parameters] is `cps` followed by one entry per slot, in slot order, which is both the node's
 * child order and θ's order — `mapGraphNumbers` walks children in order, so the *i*-th leaf it
 * reads is the *i*-th one it writes. The recipe rebuilds from the **pattern object**, never by
 * re-parsing the spliced text: a caller may have handed us `pat("g:(0,0.1)").fast(2)`, whose
 * behaviour the text alone does not describe, and rebuilding from text would drop the
 * `fast(2)`.
 *
 * `range`, `outside` and `strict` ride as scalar metadata rather than as leaves. They configure the
 * evaluator — a look-ahead bound, a no-coverage fallback, an error policy — and a gradient
 * wandering into a cache bound would be a bug dressed as a feature. `cps` is a leaf because tempo
 * genuinely is part of the gesture; note that it is clamped positive, so a fit that pushes it
 * through zero sees a plateau rather than a mirror image.
 */
private fun buildGraphPatFn(
    pattern: PatPattern<String>,
    sourceText: String,
    slots: List<PatAtomSlot>,
    parameters: List<Functions.Fn>,
    range: Double,
    outside: Double,
    strict: Boolean
): Functions.Fn {
    val cpsFn = parameters.firstOrNull() ?: with(Functions) { 1.0.c }
    // A rewrite may have pruned a child; fall back to the literal the text still carries rather
    // than to a silent zero.
    val slotFns = slots.mapIndexed { index, slot ->
        parameters.getOrNull(index + 1) ?: with(Functions) { slot.literal.c }
    }
    val overrides = slots.indices
        .groupBy { slots[it].atomOffset }
        .mapValues { (_, indices) ->
            val ordered = indices.sortedBy { slots[it].payloadIndex }
            PatAtomOverride(slots[ordered.first()].shape, ordered.map { slotFns[it] })
        }

    val field = PatFunctionField(
        pattern = pattern,
        cps = cpsFn.evalSafe(0.0, 1.0, Functions.EdgeMode.CLAMP).coerceAtLeast(1e-6),
        outside = outside,
        maxLookAhead = range,
        strict = strict,
        atomDecoder = patGraphAtomDecoder(sourceText, overrides)
    )
    val node = FnGraphNode(
        op = "pat",
        label = "pat",
        children = buildList {
            add(FnGraphEdge("cps", cpsFn))
            slots.forEachIndexed { index, slot -> add(FnGraphEdge(slot.role, slotFns[index])) }
        },
        numbers = mapOf("range" to range, "outside" to outside),
        strings = mapOf("source" to splicePatSource(sourceText, slots, slotFns)),
        booleans = mapOf("strict" to strict),
        tags = setOf("pat"),
        rebuild = { rebuilt ->
            buildGraphPatFn(pattern, sourceText, slots, rebuilt, range, outside, strict)
        }
    )
    return Functions.Fn(
        domain = Functions.Domain(0.0, Double.POSITIVE_INFINITY),
        name = "pat-fn",
        graph = node
    ) { x ->
        field.eval(x)
    }
}

/**
 * The text a pattern's atoms were parsed from, read off the source spans its events carry.
 *
 * `PatPattern` is a query function and keeps no metadata, but every atom the mini-notation compiler
 * makes carries a [PatSourceSpan] back into the string it came from, and transforms carry those
 * along. Four cycles is enough to find one for anything that plays at all; `silence`, and a pattern
 * built from Kotlin values rather than text, have no span and get no graph node.
 */
private fun PatPattern<String>.probeSourceText(): String? {
    for (cycle in 0L..3L) {
        val events = try {
            events(PatArc(PatQ.of(cycle), PatQ.of(cycle + 1)))
        } catch (_: Exception) {
            return null
        }
        events.firstNotNullOfOrNull { it.sourceSpan }?.let { return it.source }
    }
    return null
}

/**
 * The one place the string-atom surfaces converge: graph-bearing when it genuinely can be, opaque
 * when it cannot. The reasons for "cannot" are listed in the file header.
 */
private fun buildStringPatFunction(
    pattern: PatPattern<String>,
    sourceText: String?,
    cps: Number,
    range: Number,
    outside: Number,
    strict: Boolean,
    atomDecoder: (PatScheduledEvent<String>, Double) -> PatFunctionAtom?
): Functions.Fn {
    val text = sourceText ?: pattern.probeSourceText()
    val slots = if (atomDecoder is PatBuiltinStringDecoder && text != null) scanPatAtomSlots(text) else null
    if (text == null || slots == null) {
        return buildPatFunction(
            pattern = pattern,
            cps = cps,
            maxLookAhead = range,
            outside = outside,
            strict = strict,
            atomDecoder = atomDecoder
        )
    }
    return buildGraphPatFn(
        pattern = pattern,
        sourceText = text,
        slots = slots,
        parameters = buildList {
            add(with(Functions) { cps.toDouble().c })
            for (slot in slots) add(with(Functions) { slot.literal.c })
        },
        range = range.toDouble().coerceAtLeast(1e-6),
        outside = outside.toDouble(),
        strict = strict
    )
}

/**
 * Creates a Gaussian-shaped PAT function atom centered on a constant [value].
 *
 * [sigma] controls the spread and [weight] scales this atom when overlapping atoms are averaged.
 */
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

/** Creates a constant-valued box kernel atom with finite [radius] and optional [weight]. */
fun boxAtom(value: Number, radius: Number, weight: Number = 1.0): PatFunctionAtomSpec =
    boxAtom(radius = radius, weight = weight) { value.toDouble() }

/**
 * Creates a box kernel atom whose value is computed from the local event scope.
 *
 * The radius is clamped to a positive extent and capped by the evaluator's look-ahead.
 */
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

/** Decodes a scheduled string event into a pattern-function atom, or `null` for unsupported shapes. */
fun decodePatFunctionAtom(event: PatScheduledEvent<String>, maxThreeSigma: Double): PatFunctionAtom? =
    decodePatFunctionAtomCore(event, maxThreeSigma, null)

/**
 * Decodes a scheduled string event, resolving `%name` placeholders through [bindingResolver].
 */
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
    val (shape, body) = patFunctionAtomParts(text) ?: return null
    return buildPatFunctionAtom(event, shape, parsePatFunctionNumbers(body, text, bindingResolver), maxThreeSigma, text)
}

/** Splits `g:(0,0.12)` into its shape (`g`) and its raw payload (`0,0.12`), or null if it is not an atom. */
private fun patFunctionAtomParts(text: String): Pair<String, String>? {
    val shape = text.substringBefore(":("  , "")
    if (shape.isEmpty() || !text.endsWith(")")) return null
    return shape to text.substring(shape.length + 2, text.length - 1)
}

/**
 * Builds the atom for [shape] from numbers that have already been resolved.
 *
 * Split out from [decodePatFunctionAtomCore] so the shape dispatch has exactly one home: the graph
 * path resolves its numbers from `param` leaves rather than from the text, and must not grow a
 * second copy of this `when` that could drift from it.
 */
private fun buildPatFunctionAtom(
    event: PatScheduledEvent<String>,
    shape: String,
    values: List<Double>,
    maxThreeSigma: Double,
    text: String
): PatFunctionAtom? = when (shape) {
    "g" -> gaussianPatFunctionAtom(event, values, maxThreeSigma, text)
    "tent" -> tentPatFunctionAtom(event, values, maxThreeSigma, text)
    "box" -> boxPatFunctionAtom(event, values, maxThreeSigma, text)
    "wendland" -> wendlandPatFunctionAtom(event, values, maxThreeSigma, text)
    "spline" -> splinePatFunctionAtom(event, values, maxThreeSigma, text)
    "env" -> envelopePatFunctionAtom(event, values, text)
    "adsr" -> adsrPatFunctionAtom(event, values, text)
    else -> null
}

/** Decodes only Gaussian string atoms whose trimmed event text starts with `g:(`. */
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
