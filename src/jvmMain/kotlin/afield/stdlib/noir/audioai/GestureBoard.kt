@file:afield.CompletionCategory("Audio AI")

package afield.stdlib.noir.audioai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@DslMarker
annotation class GestureBoardDsl

@GestureBoardDsl
class GestureCurveBuilder internal constructor(
    private val name: String,
    private val kind: GestureCurveKind,
    private val weight: Double,
) {
    private val points = mutableListOf<GesturePoint>()

    fun point(timeSeconds: Number, value: Number) {
        points += GesturePoint(timeSeconds.toDouble(), value.toDouble())
    }

    internal fun build(): GestureCurve = GestureCurve(name, kind, points.toList(), weight)
}

@GestureBoardDsl
class GestureBoardBuilder internal constructor(
    private val durationSeconds: Double,
    private val basePrompt: String,
) {
    private val prompts = mutableListOf<GesturePromptRegion>()
    private val events = mutableListOf<GestureEvent>()
    private val curves = mutableListOf<GestureCurve>()
    var metadata: JsonObject = JsonObject(emptyMap())

    fun prompt(startSeconds: Number, durationSeconds: Number, text: String, weight: Number = 1.0) {
        prompts += GesturePromptRegion(
            startSeconds.toDouble(), durationSeconds.toDouble(), text, weight.toDouble()
        )
    }

    fun event(timeSeconds: Number, label: String, strength: Number = 1.0, weight: Number = 1.0) {
        events += GestureEvent(timeSeconds.toDouble(), label, strength.toDouble(), weight.toDouble())
    }

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
 * scores; they are deliberately not presented as native MusicGen controls.
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
