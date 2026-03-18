package afield.four.synth

import afield.*
import kotlinx.coroutines.*

@RunsAt(returnValueRegistration, priority = 11)
fun registerPatPatternReturnValue() {
    ReturnValueRegistry.handlers += handler@{ inside, returnValue, strand, generation, notify ->
        val spec = returnValue as? PatResponseSpec<*> ?: return@handler false

        @Suppress("UNCHECKED_CAST")
        val typed = spec as PatResponseSpec<Any?>

        CoroutineScope(Dispatchers.Default + Box.boxContextElement(inside)).launch {
            notify(true, strand, generation)
            val job = coroutineContext[Job]!!
            inside.jobs += job

            val scope = PatResponseScope(
                box = inside,
                pattern = typed.pattern,
                cps = typed.cps,
                clock = typed.clock,
                ahead = typed.ahead,
                launchInstant = java.time.Instant.now()
            )

            try {
                val iterator = typed.pattern.scheduled(
                    cps = typed.cps,
                    fromCycle = typed.fromCycle,
                    clock = typed.clock
                ).iterator()

                while (isActive && inside.runGeneration[strand] == generation && iterator.hasNext()) {
                    val event = iterator.next()
                    if (scope.now > event.start + 1e-6) continue
                    waitUntil(
                        target = event.start - typed.ahead,
                        scope = scope,
                        inside = inside,
                        strand = strand,
                        generation = generation
                    )
                    if (!isActive || inside.runGeneration[strand] != generation) break

                    if (typed.dispatchOnMain) {
                        withContext(Runloop.runLoop.main + Box.boxContextElement(inside)) {
                            Box.withBoxContextNow(inside) {
                                typed.callback(scope, event)
                            }
                        }
                    } else {
                        Box.withBoxContextNow(inside) {
                            typed.callback(scope, event)
                        }
                    }
                }
            } finally {
                notify(false, strand, generation)
            }
        }
        true
    }
}

private suspend fun waitUntil(
    target: Double,
    scope: PatResponseScope<*>,
    inside: Box,
    strand: String,
    generation: Int
) {
    waitForPatTime(
        target = target,
        now = { scope.now },
        isRunning = scope.clock?.let { { it.isRunning } },
        shouldContinue = { inside.runGeneration[strand] == generation }
    )
}
