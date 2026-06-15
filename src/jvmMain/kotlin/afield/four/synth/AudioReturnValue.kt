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

@RunsAt(returnValueRegistration, priority = 10)
fun registerAudioReturnValue() {
    ReturnValueRegistry.handlers += handler@{ inside, returnValue, strand, generation, notify ->
        val spec = when (returnValue) {
            is AudioPatchSpec -> returnValue
            is Signal -> AudioPatchSpec.from(returnValue)
            is StereoRenderable -> AudioPatchSpec.from(returnValue)
            else -> return@handler false
        }

        val handle = FieldAudioEngine.shared().register(spec)

        inMain {
            notify(true, strand, generation)
            val job = kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]!!
            inside.jobs += job
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
                notify(false, strand, generation)
            }
        }
        true
    }
}
