package afield.animation.keyframes

import afield.study.source.SourceBackedFunction

/**
 * Numeric keyframes over an ordered group. A lane holds a number or a source-backed `(Int) -> Number`.
 * Keys keep those rules in the ordinary versioned key store; evaluating a rule never replaces it
 * with its samples. Index zero is the first member, independent of how a UI numbers its labels.
 *
 * Register lanes, then use [scope] for capture, persistence and the keyframe browser. Applying a key
 * resolves and validates the entire group before [commit] runs. Source hydration happens here, on
 * the caller's thread, so apply/prepare keys on a control thread rather than a graphics callback.
 */
class OrderedKeyframeGroup(
    private val count: () -> Int,
    val scope: KeyframeScope = KeyframeScope(),
    private val sourceBoxId: String = "",
    private val commit: (Resolved) -> Unit,
) {
    /** An immutable resolved snapshot; callers cannot mutate its backing values. */
    class Resolved internal constructor(private val lanes: Map<String, List<Double>>, val size: Int) {
        operator fun get(lane: String, index: Int): Double = lanes.getValue(lane)[index]
        fun values(lane: String): List<Double> = lanes.getValue(lane).toList()
        val names: Set<String> get() = lanes.keys.toSet()
    }

    private data class Lane(val validate: (Double) -> Boolean)
    private val lanes = linkedMapOf<String, Lane>()
    private var rules = linkedMapOf<String, Any?>()
    private var validateGroup: (Resolved) -> Unit = {}
    private val batchId = "ordered-group-${System.identityHashCode(this)}"
    var lastResolved: Resolved? = null
        private set

    /** Add a lane and its domain check. A failed check reports the lane, zero-based index and value. */
    fun lane(name: String, initial: Any, validate: (Double) -> Boolean = { true }): OrderedKeyframeGroup = apply {
        require(name.isNotBlank() && name !in lanes) { "Duplicate or empty ordered-group lane: $name" }
        requireRule(name, initial)
        lanes[name] = Lane(validate)
        rules[name] = initial
        scope.target(name, { rules[name] }) { error("Ordered-group lanes must apply as a batch") }
        scope.interpolators[name] = { a, b, t -> blendRule(a, b, List(count()) { t }, sourceBoxId) }
        scope.applicationBatch(batchId, lanes.keys.toSet()) { incoming ->
            val proposed = LinkedHashMap(rules).apply { putAll(incoming) }
            val resolved = resolveRules(proposed)
            val apply: () -> Unit = {
                commit(resolved)
                rules = proposed
                lastResolved = resolved
            }
            apply
        }
    }

    /** Validate relationships between lanes, after each lane has passed its own checks. */
    fun validate(check: (Resolved) -> Unit): OrderedKeyframeGroup = apply { validateGroup = check }

    /** Current authored rules, as an ordinary native key. */
    fun capture(): KeyframeScope.Key = scope.Key(LinkedHashMap(rules))

    /** Resolve a key without changing the current rules or the sketch. Missing lanes retain current rules. */
    fun resolve(key: KeyframeScope.Key = capture()): Resolved =
        resolveRules(LinkedHashMap(rules).apply { putAll(key.values.filterKeys { it in lanes }) })

    /** Atomically validate the group, then invoke its one commit callback through the native scope. */
    fun apply(key: KeyframeScope.Key = capture()) { scope.Key(LinkedHashMap(key.values))() }

    /** Set a constant or source-backed rule using the same validation/application path as a saved key. */
    operator fun set(name: String, rule: Any) {
        require(name in lanes) { "Unknown ordered-group lane: $name" }
        apply(scope.Key(name to rule))
    }

    /**
     * Pointwise blend, with independent progress per index. Generated rules retain both source
     * expressions and the progress samples, so capturing mid-transition is also durable. Endpoints
     * keep their own owning boxes, even when the two rules came from different boxes. No compiler
     * runs here for live rules. Restored endpoints prepare once before their first evaluation.
     */
    fun blend(a: KeyframeScope.Key, b: KeyframeScope.Key, progress: (Int) -> Double): KeyframeScope.Key {
        val size = count().also { require(it > 0) }
        val amounts = List(size) { index -> progress(index).also { require(it.isFinite()) { "Non-finite blend at index $index" } } }
        return scope.Key(lanes.keys.associateWith { name ->
            blendRule(a.values[name] ?: rules.getValue(name)!!, b.values[name] ?: rules.getValue(name)!!, amounts, sourceBoxId)
        }.toMutableMap())
    }

    /** Crossfade in seconds, delayed by [staggerSeconds] per member. Zero duration makes ordered cuts. */
    fun transition(a: KeyframeScope.Key, b: KeyframeScope.Key, seconds: Double,
                   durationSeconds: Double, staggerSeconds: Double = 0.0): KeyframeScope.Key {
        require(seconds.isFinite() && durationSeconds.isFinite() && durationSeconds >= 0 &&
            staggerSeconds.isFinite() && staggerSeconds >= 0)
        return blend(a, b) { index ->
            val elapsed = seconds - index * staggerSeconds
            if (durationSeconds == 0.0) { if (elapsed >= 0) 1.0 else 0.0 }
            else (elapsed / durationSeconds).coerceIn(0.0, 1.0)
        }
    }

    private fun resolveRules(proposed: Map<String, Any?>): Resolved {
        val size = count().also { require(it > 0) { "Ordered group must contain at least one member" } }
        val result = lanes.mapValues { (name, lane) ->
            val rule = proposed[name]
            requireRule(name, rule)
            List(size) { index ->
                try {
                    val value = evaluate(rule!!, index)
                    require(value.isFinite() && lane.validate(value)) { "value $value is outside its domain" }
                    value
                } catch (failure: Exception) {
                    throw IllegalArgumentException("Ordered keyframe '$name', index $index: ${failure.message}", failure)
                }
            }
        }
        return Resolved(result, size).also { validateGroup(it) }
    }

    private fun requireRule(name: String, rule: Any?) {
        require(rule is Number || rule is SourceBackedFunction<*, *>) {
            "Ordered keyframe '$name' needs a number or sourceBlock { index: Int -> number }; plain lambdas cannot be saved"
        }
    }

    companion object {
        /** Import sampled settings: equal values become a constant, differences a durable lookup rule. */
        fun sampledRule(boxId: String, values: List<Double>): Any {
            require(values.isNotEmpty() && values.all { it.isFinite() }) { "Samples must be finite and nonempty" }
            if (values.distinct().size == 1) return values.first()
            require(boxId.isNotBlank()) { "A sampled rule needs its owning box id" }
            val copy = values.toList()
            return SourceBackedFunction<Int, Double>(boxId,
                "run { val samples = doubleArrayOf(${copy.joinToString()}); " +
                    "val rule: (Int) -> Double = { index: Int -> samples[index] }; rule }") { copy[it] }
        }

        @Suppress("UNCHECKED_CAST")
        private fun evaluate(rule: Any, index: Int): Double = when (rule) {
            is Number -> rule.toDouble()
            is SourceBackedFunction<*, *> -> {
                val result = (rule as SourceBackedFunction<Int, Any?>)(index)
                require(result is Number) { "function returned ${result?.javaClass?.simpleName ?: "null"}, expected Number" }
                result.toDouble()
            }
            else -> error("Unsupported ordered rule")
        }

        private fun quoted(text: String): String = buildString {
            append('"')
            text.forEach { c ->
                append(when (c) {
                    '\\' -> "\\\\"
                    '"' -> "\\\""
                    '$' -> "\\$"
                    '\n' -> "\\n"
                    '\r' -> "\\r"
                    '\t' -> "\\t"
                    else -> if (c.code < 32) "\\u%04x".format(c.code) else c.toString()
                })
            }
            append('"')
        }

        private fun callable(rule: Any): String = when (rule) {
            is Number -> "{ _: Int -> ${rule.toDouble().also { require(it.isFinite()) }} }"
            is SourceBackedFunction<*, *> ->
                "afield.study.source.SourceBackedFunction<Int, Any?>(${quoted(rule.boxId)}, ${quoted(rule.source)})"
            else -> error("Unsupported ordered rule")
        }

        private fun blendRule(a: Any, b: Any, progress: List<Double>, fallbackOwner: String): Any {
            require(progress.isNotEmpty() && progress.all { it.isFinite() })
            if (progress.all { it == 0.0 }) return a
            if (progress.all { it == 1.0 }) return b
            if (a is Number && b is Number && progress.distinct().size == 1)
                return a.toDouble() * (1 - progress.first()) + b.toDouble() * progress.first()
            val owner = fallbackOwner.ifBlank {
                (a as? SourceBackedFunction<*, *>)?.boxId ?: (b as? SourceBackedFunction<*, *>)?.boxId.orEmpty()
            }
            require(owner.isNotBlank()) { "A staggered constant blend needs sourceBoxId for durable generated source" }
            // Construct endpoint callables once when restoring the outer function, retaining each
            // endpoint's own preamble/receiver. Never construct or compile them per group member.
            val source = "run { val left = ${callable(a)}; val right = ${callable(b)}; " +
                "val amounts = doubleArrayOf(${progress.joinToString()}); " +
                "val blended: (Int) -> Double = { index: Int -> val t = amounts[index]; " +
                "val a = (left(index) as Number).toDouble(); val b = (right(index) as Number).toDouble(); " +
                "a * (1.0 - t) + b * t }; blended }"
            return SourceBackedFunction<Int, Double>(owner, source) { index ->
                evaluate(a, index) * (1 - progress[index]) + evaluate(b, index) * progress[index]
            }
        }
    }
}
