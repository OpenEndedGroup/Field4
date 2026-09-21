@file:afield.CompletionCategory("Audio AI")

package afield.stdlib.noir.audioai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Temporal sketches for source-conditioned generation, with prompt regions, onset events,
 * and parameter curves that together express a generation intent.
 *
 * Build a [GestureBoard] with the `gestureBoard{}` DSL and pass it to [gestureCandidates] to
 * produce a multi-seed [ModelOperation]. Prompt regions are compiled into the model's global text
 * condition; onset events and parameter curves are evaluative: the service scores each generated
 * candidate against them rather than passing them as native model controls.
 *
 * [compileGestureBoard] previews the protocol locally and returns a [GestureCompilation] that lists
 * how each board element is wired to the backend. Example:
 *
 *     val board = gestureBoard(8.0, "acoustic guitar, warm") {
 *         prompt(0.0, 4.0, "sparse, arpeggiated")
 *         event(3.5, "accent")
 *         curve("energy", GestureCurveKind.ACTIVITY) {
 *             point(0.0, 0.2); point(4.0, 0.8); point(8.0, 0.3)
 *         }
 *     }
 *     val operation = gestureCandidates(source, board, seeds = 0L until 4L)
 */

/**
 * The DSL scope marker shared by the two gesture-board builders. It stops a nested `curve{}`
 * block from reaching past its own receiver to call `prompt` or `event` on the board around it.
 */
@DslMarker
annotation class GestureBoardDsl

/**
 * Collects the breakpoints of one curve inside a `curve{}` block. Points are kept in the order
 * they are written, and strictly increasing times, values in `0..1` and a minimum of two points
 * are all checked when the enclosing board is built, not as each point arrives.
 */
@GestureBoardDsl
class GestureCurveBuilder internal constructor(
    private val name: String,
    private val kind: GestureCurveKind,
    private val weight: Double,
) {
    private val points = mutableListOf<GesturePoint>()

    /** Adds one breakpoint to the curve: [timeSeconds] along the board, [value] in `0..1`. */
    fun point(timeSeconds: Number, value: Number) {
        points += GesturePoint(timeSeconds.toDouble(), value.toDouble())
    }

    internal fun build(): GestureCurve = GestureCurve(name, kind, points.toList(), weight)
}

/**
 * The receiver of a `gestureBoard{}` block: prompt regions, events and curves accumulate here in
 * the order they are written, and the finished board is validated once, when the block returns.
 */
@GestureBoardDsl
class GestureBoardBuilder internal constructor(
    private val durationSeconds: Double,
    private val basePrompt: String,
) {
    private val prompts = mutableListOf<GesturePromptRegion>()
    private val events = mutableListOf<GestureEvent>()
    private val curves = mutableListOf<GestureCurve>()
    /**
     * Free-form JSON that travels with the board to the service. Neither validation nor
     * compilation reads it, so it is somewhere to record where a sketch came from.
     */
    var metadata: JsonObject = JsonObject(emptyMap())

    /**
     * Adds a timed text region. Protocol version 1 has no timed text condition, so
     * [compileGestureBoard] folds each region into the one global prompt, worded
     * `from As to Bs: text`, in the order the regions were written; a region has to fit inside
     * the board's duration and its [weight] has to lie in `(0, 10]`.
     */
    fun prompt(startSeconds: Number, durationSeconds: Number, text: String, weight: Number = 1.0) {
        prompts += GesturePromptRegion(
            startSeconds.toDouble(), durationSeconds.toDouble(), text, weight.toDouble()
        )
    }

    /**
     * Marks a labelled onset at [timeSeconds]. Events are evaluative: nothing is conditioned on
     * them, and a generated candidate is rewarded for an accent near the marked time. [strength]
     * is `0..1` and [weight] is in `(0, 10]`.
     */
    fun event(timeSeconds: Number, label: String, strength: Number = 1.0, weight: Number = 1.0) {
        events += GestureEvent(timeSeconds.toDouble(), label, strength.toDouble(), weight.toDouble())
    }

    /**
     * Opens a named lane of one [GestureCurveKind] and collects its points. Names have to be
     * unique within a board and a curve needs at least two points. Like an event, a curve is
     * measured against the generated waveform rather than sent as a model control, and the
     * pinned contract has four kinds to measure against: density, activity, register and
     * brightness.
     */
    fun curve(
        name: String,
        kind: GestureCurveKind,
        weight: Number = 1.0,
        block: GestureCurveBuilder.() -> Unit,
    ) {
        curves += GestureCurveBuilder(name, kind, weight.toDouble()).apply(block).build()
    }

    internal fun build(): GestureBoard = validateGestureBoard(
        GestureBoard(durationSeconds, basePrompt, prompts.toList(), events.toList(), curves.toList(), metadata)
    )
}

/**
 * A live-codeable temporal sketch for source-conditioned generation.
 *
 * Prompt regions are compiled into the backend's global text condition. Curves and
 * events are measured against each generated waveform and become reproducible ranking
 * scores; they are not presented as native MusicGen controls.
 */
fun gestureBoard(
    durationSeconds: Number,
    prompt: String = "",
    block: GestureBoardBuilder.() -> Unit = {},
): GestureBoard {
    val duration = durationSeconds.toDouble()
    require(duration > 0.0) { "gesture duration must be positive" }
    return GestureBoardBuilder(duration, prompt).apply(block).build()
}

/**
 * Checks a board against the version-1 rules and returns it, so it can be used inline. It
 * enforces timing (regions and points inside the duration, curve times strictly increasing),
 * ranges (values and strengths in `0..1`, weights in `(0, 10]`), unique curve names, and that
 * each element's delivery is the one protocol version 1 defines for it. The `gestureBoard{}`
 * builder validates on the way out and every function here that takes a board validates on the
 * way in, so this is the entry point for a board assembled by hand or one that arrived over the
 * wire.
 */
fun validateGestureBoard(board: GestureBoard): GestureBoard {
    require(board.durationSeconds > 0.0) { "gesture duration must be positive" }
    board.promptRegions.forEach {
        require(it.startSeconds >= 0.0 && it.durationSeconds > 0.0) { "prompt region has an invalid range" }
        require(it.startSeconds + it.durationSeconds <= board.durationSeconds + 1e-9) {
            "prompt region '${it.text}' exceeds gesture duration"
        }
        require(it.text.isNotBlank()) { "prompt region text must not be blank" }
        require(it.weight in 0.0..10.0 && it.weight > 0.0) { "prompt region weight must be in (0, 10]" }
        require(it.delivery == GestureDelivery.COMPILED) { "prompt regions are compiled in protocol version 1" }
    }
    board.events.forEach {
        require(it.timeSeconds in 0.0..board.durationSeconds) { "event '${it.label}' exceeds gesture duration" }
        require(it.label.isNotBlank()) { "event label must not be blank" }
        require(it.strength in 0.0..1.0) { "event strength must be between zero and one" }
        require(it.weight in 0.0..10.0 && it.weight > 0.0) { "event weight must be in (0, 10]" }
        require(it.delivery == GestureDelivery.EVALUATIVE) { "events are evaluative in protocol version 1" }
    }
    val names = board.curves.map { it.name }
    require(names.size == names.distinct().size) { "gesture curve names must be unique" }
    board.curves.forEach { curve ->
        require(curve.name.isNotBlank()) { "gesture curve name must not be blank" }
        require(curve.points.size >= 2) { "gesture curve '${curve.name}' needs at least two points" }
        require(curve.weight in 0.0..10.0 && curve.weight > 0.0) { "gesture curve weight must be in (0, 10]" }
        require(curve.delivery == GestureDelivery.EVALUATIVE) { "gesture curves are evaluative in protocol version 1" }
        val times = curve.points.map { it.timeSeconds }
        require(times.zipWithNext().all { (a, b) -> a < b }) {
            "gesture curve '${curve.name}' points must have unique, increasing times"
        }
        curve.points.forEach {
            require(it.timeSeconds in 0.0..board.durationSeconds) { "gesture curve '${curve.name}' exceeds gesture duration" }
            require(it.value in 0.0..1.0) { "gesture values must be between zero and one" }
        }
    }
    return board
}

/** Immediate local preview of the service's version-1 compilation contract. */
fun compileGestureBoard(
    board: GestureBoard,
    operation: String = "generate.style",
    parameters: JsonObject = JsonObject(emptyMap()),
): GestureCompilation {
    validateGestureBoard(board)
    val fragments = mutableListOf<String>()
    parameters["prompt"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let(fragments::add)
    board.basePrompt.trim().takeIf { it.isNotEmpty() && it !in fragments }?.let(fragments::add)
    board.promptRegions.forEach { region ->
        fragments += "from ${region.startSeconds.compact()}s to ${(region.startSeconds + region.durationSeconds).compact()}s: ${region.text.trim()}"
    }
    val effectivePrompt = fragments.joinToString("; then ")
    val compiled = buildJsonObject {
        parameters.forEach { (key, value) -> put(key, value) }
        if (effectivePrompt.isNotEmpty()) put("prompt", effectivePrompt)
        put("duration_seconds", board.durationSeconds)
        put("gesture_compilation_version", 1)
    }
    val bindings = buildList {
        if (board.basePrompt.isNotBlank()) add(GestureBinding(
            "base prompt", "text", GestureDelivery.DIRECT, "backend text condition",
            "Sent directly as the model's global text condition."
        ))
        board.promptRegions.forEachIndexed { index, region -> add(GestureBinding(
            "prompt ${index + 1}", "prompt_region", region.delivery, "compiled global text condition",
            "Time wording is compiled into the global prompt; it is not a native timed condition."
        )) }
        board.events.forEach { event -> add(GestureBinding(
            event.label, "event", event.delivery, "candidate onset-flux score",
            "The generated waveform is rewarded for an accent near this time."
        )) }
        board.curves.forEach { curve -> add(GestureBinding(
            curve.name, curve.kind.name.lowercase(), curve.delivery,
            "candidate ${curve.kind.name.lowercase()} trajectory score",
            "The generated waveform is measured and ranked against this normalized curve."
        )) }
    }
    return GestureCompilation(board, operation, effectivePrompt, compiled, bindings)
}

/** Source-style generation plus an inspectable gesture sketch and candidate scoring. */
fun gestureCandidates(
    source: AssetRegion,
    board: GestureBoard,
    seeds: LongRange = 0L until 8L,
    backend: String? = null,
    requestedBy: String? = null,
    controls: MusicGenStyleControls = MusicGenStyleControls(),
    forceRegenerate: Boolean = false,
): ModelOperation {
    validateGestureBoard(board)
    val seedList = seeds.toList()
    require(seedList.isNotEmpty()) { "seeds must not be empty" }
    require(source.durationSeconds in 1.5..4.5) {
        "MusicGen-Style source window must be between 1.5 and 4.5 seconds"
    }
    require(controls.styleExcerptSeconds == null || controls.styleExcerptSeconds <= source.durationSeconds) {
        "styleExcerptSeconds cannot exceed the source window"
    }
    return ModelOperation(
        operation = "generate.style",
        inputs = listOf(source),
        parameters = buildJsonObject {
            put("duration_seconds", board.durationSeconds)
            put("use_sampling", controls.useSampling)
            put("top_k", controls.topK)
            put("top_p", controls.topP)
            put("temperature", controls.temperature)
            put("cfg", controls.cfg)
            if (board.basePrompt.isNotBlank() || board.promptRegions.isNotEmpty()) {
                controls.cfgBeta?.let { put("cfg_beta", it) }
            }
            put("two_step_cfg", controls.twoStepCfg)
            put("style_quantization", controls.styleQuantization)
            controls.styleExcerptSeconds?.let { put("style_excerpt_seconds", it) }
            controls.styleDownsampleFactor?.let { put("style_downsample_factor", it) }
            controls.styleEncodecCodebooks?.let { put("style_encodec_codebooks", it) }
        },
        seeds = seedList,
        candidateCount = seedList.size,
        backendPreference = backend,
        requestedBy = requestedBy,
        gestureBoard = board,
        forceRegenerate = forceRegenerate,
    )
}

private fun Double.compact(): String =
    if (this == kotlin.math.floor(this)) toLong().toString() else toString()
