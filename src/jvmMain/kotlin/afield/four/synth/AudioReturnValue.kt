@file:afield.CompletionCategory("Synthesis")
/**
 * The step between a box whose last expression is a patch and a patch you can hear.
 *
 * `audio { … }` builds an [AudioPatchSpec] and stops there, holding no device and making no sound.
 * This file is the other half: a return-value handler that recognises an [AudioPatchSpec], a bare
 * [Signal] or a [StereoRenderable] coming back from a box, registers it with [FieldAudioEngine] so
 * it joins the mix, and unregisters it when the box stops. The two bare forms are wrapped with
 * `AudioPatchSpec.from` first, so returning an oscillator and returning a patch built around one
 * reach the same place.
 *
 * What the handler launches is a coroutine that mostly yields. Being cancellable is the work: it is
 * registered as one of the box's jobs, it re-checks the box's run generation on every pass, and it
 * closes the engine handle in a `finally`. A re-run therefore silences the previous patch instead
 * of stacking a second copy of it on top.
 *
 * The same loop redraws the box's inline output. When box execution has marked the returned value's
 * line as an inline summary target, the loop pushes the patch's `summaryHtml`, a waveform and a
 * spectrum drawn from its own rolling trace, back to the editor twelve times a second, beginning
 * 120 ms after the launch.
 */

package afield.four.synth

import afield.*
import kotlinx.coroutines.yield

private const val INLINE_AUDIO_SUMMARY_REFRESH_HZ = 12.0
private const val INLINE_AUDIO_SUMMARY_INITIAL_DELAY_MS = 120L

private fun Box.pushInlineExecutionOutput(output: Output) {
    val normalized = if (output.uid.isBlank()) output.copy(uid = id) else output
    val message = Message("output").payload(normalized)
    inside.sessions.forEach { _send(it, message) }
}

/**
 * Teaches box execution about [AudioPatchSpec], [Signal] and [StereoRenderable]. Called once during
 * return-value registration, not from box code.
 *
 * Handlers are registered in priority order and tried in that order, each returning `false` to pass
 * the value on. This one sits at 10, behind the `Sequence` and `Frames` handlers in `afield` and
 * ahead of the pattern responder.
 */
@RunsAt(returnValueRegistration, priority = 10)
fun registerAudioReturnValue() {
    ReturnValueRegistry.handlers += handler@{ inside, returnValue, strand, generation, launchEpoch, notify ->
        val spec = when (returnValue) {
            is AudioPatchSpec -> returnValue
            is Signal -> AudioPatchSpec.from(returnValue)
            is StereoRenderable -> AudioPatchSpec.from(returnValue)
            else -> return@handler false
        }

        val handle = FieldAudioEngine.shared().register(spec)

        inMain {
            val job = kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]!!
            if (!inside.registerLaunchJob(job, launchEpoch)) {
                handle.close()
                notify(false, strand, generation)
                return@inMain
            }
            notify(true, strand, generation)
            val inlineTarget = spec.inlineSummaryTarget
            val refreshIntervalNs = (1_000_000_000.0 / INLINE_AUDIO_SUMMARY_REFRESH_HZ).toLong().coerceAtLeast(1L)
            var nextInlineRefreshAt = System.nanoTime() + INLINE_AUDIO_SUMMARY_INITIAL_DELAY_MS * 1_000_000L
            try {
                while (inside.runGeneration[strand] == generation) {
                    if (inlineTarget != null && System.nanoTime() >= nextInlineRefreshAt) {
                        inside.pushInlineExecutionOutput(
                            Output(
                                uid = inside.id,
                                line = inlineTarget.line,
                                kind = "normal",
                                property = inlineTarget.property,
                                html = spec.summaryHtml(),
                                annotation = inlineTarget.annotation ?: "",
                                frame = Runloop.runLoop.frame.toInt()
                            )
                        )
                        nextInlineRefreshAt = System.nanoTime() + refreshIntervalNs
                    }
                    yield()
                }
            } finally {
                handle.close()
                inside.jobs.remove(job)
                notify(false, strand, generation)
            }
        }
        true
    }
}
