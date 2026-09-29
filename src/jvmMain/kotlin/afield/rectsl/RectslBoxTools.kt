package afield.rectsl

import afield.Box
import afield.ai.core.aiTool
import afield.four.Renderer
import afield.four.modules.fromContext
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.math.roundToInt

/**
 * # RectSL studio tools
 *
 * A batteries-included tool set a document can hand to its AI4 agents: syntax-check RectSL,
 * and render it in the document's live afield.four window, returning captured frames to the
 * conversation. It is also the reference example of the [aiTool] DSL applied to a real,
 * multi-frame, GPU-backed task.
 *
 * A box registers the set by running this once in its `code` (idempotent — re-running replaces):
 * ```kotlin
 * registerRectslStudioTools()
 * ```
 * The agent then sees `rectsl.check` and `rectsl.run`. `{{ … }}` escapes in the RectSL source are
 * compiled against *this box's* Kotlin engine, so they can read the box's live state (ki-vars,
 * `thisBox` members).
 */
fun Box.registerRectslStudioTools() {
    aiTool("rectsl.check") {
        describe("Compile a RectSL program and report syntax/compile diagnostics, without rendering.")
        val source by param<String>("The RectSL program text")
        action {
            val result = RectAnimationOracle.compile(source())
            if (result.ok) mapOf("ok" to true, "message" to "RectSL compiles cleanly.")
            else toolError(result.diagnostics)
        }
    }

    aiTool("rectsl.run") {
        describe(
            "Compile and render a RectSL program in this document's afield.four window, capturing " +
                "frames as images returned to the conversation. {{ }} escapes may reference this box's state."
        )
        val source by param<String>("The RectSL program text")
        val pixelsPerMeter by param<Double>("output resolution in pixels per wall-metre", default = 100.0)
        val fps by param<Double>("frames per second to sample", default = 8.0)
        val maxImages by param<Int>("maximum number of frames to return", default = 6)
        action {
            val check = RectAnimationOracle.compile(source())
            if (!check.ok) return@action toolError(check.diagnostics)
            val frames = try {
                thisBox.renderRectslToImages(source(), pixelsPerMeter(), fps(), maxImages())
            } catch (t: Throwable) {
                return@action toolError("Render failed: ${t.message ?: t::class.java.simpleName}")
            }
            if (frames.isEmpty()) toolError("Render produced no frames.")
            else images(frames, "RectSL render — ${frames.size} frame(s)")
        }
    }
}

/**
 * Render [source] in [renderer] (the document's live renderer by default) and return the captured
 * frames as `data:image/jpeg;base64,…` URLs, subsampled to at most [maxImages].
 *
 * `{{ … }}` escapes are prewarmed on **this box's** engine, off the runloop (see
 * [prewarmRectSLEscapes]) — so they can reference the box's live state and the non-reentrant
 * graphics runloop never has to compile Kotlin mid-render. The compiled [SceneProgram] (with those
 * escapes bound) is then handed to [RectSLRenderRunner], not the raw source.
 */
suspend fun Box.renderRectslToImages(
    source: String,
    pixelsPerMeter: Double = 100.0,
    fps: Double = 8.0,
    maxImages: Int = 6,
    renderer: Renderer = fromContext(),
    configure: RectSLRenderSettings.() -> Unit = {}
): List<String> {
    val precompiled = withContext(ki.compilationDispatcher) { prewarmRectSLEscapes(source, ki) }
    val compiled = compileRectSL(source, engine = ki, precompiledEscapes = precompiled)
    val program = compiled.programOrNull
        ?: throw RectLangException("RectSL compile failed:\n" + compiled.diagnostics.joinToString("\n"))

    val dir = Files.createTempDirectory("rectsl-tool-$id")
    val settings = RectSLRenderSettings(
        pixelsPerMeter = pixelsPerMeter,
        fps = fps,
        outputDirectory = dir,
        renderer = renderer,
    ).apply {
        includeFinalFrame = true
    }.apply(configure)

    val result = RectSLRenderRunner.renderJpgSequence(program, settings)
    return subsampleEvenly(result.files, maxImages).map { path ->
        "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(Files.readAllBytes(path))
    }
}

/** Pick at most [max] items spread evenly across [items] (always including first and last). */
private fun <T> subsampleEvenly(items: List<T>, max: Int): List<T> {
    if (max <= 0 || items.size <= max) return items
    val step = (items.size - 1).toDouble() / (max - 1).toDouble()
    return (0 until max)
        .map { items[(it * step).roundToInt().coerceIn(0, items.size - 1)] }
        .distinct()
}
