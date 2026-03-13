package afield.four.synth

import afield.*
import kotlinx.coroutines.yield

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
            try {
                while (inside.runGeneration[strand] == generation) {
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
