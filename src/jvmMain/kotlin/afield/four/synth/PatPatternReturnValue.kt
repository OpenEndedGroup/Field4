@file:afield.CompletionCategory("Patterns")
package afield.four.synth

import afield.*
import kotlinx.coroutines.*
import kotlin.math.absoluteValue

@RunsAt(returnValueRegistration, priority = 11)
fun registerPatPatternReturnValue() {
    ReturnValueRegistry.handlers += handler@{ inside, returnValue, strand, generation, launchEpoch, notify ->
        val spec = returnValue as? PatResponseSpec<*> ?: return@handler false

        @Suppress("UNCHECKED_CAST")
        val typed = spec as PatResponseSpec<Any?>

        CoroutineScope(Dispatchers.Default + Box.boxContextElement(inside)).launch {
            val job = coroutineContext[Job]!!
            if (!inside.registerLaunchJob(job, launchEpoch)) {
                notify(false, strand, generation)
                return@launch
            }
            notify(true, strand, generation)

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

                var peekedEvent: PatScheduledEvent<Any?>? = null

                while (isActive && inside.runGeneration[strand] == generation) {
                    // Load bucket of simultaneous events
                    val bucket = mutableListOf<PatScheduledEvent<Any?>>()
                    var bucketTime: Double? = null

                    // Start with peeked event if we have one
                    if (peekedEvent != null) {
                        bucketTime = peekedEvent.start
                        bucket.add(peekedEvent)
                        peekedEvent = null
                    }

                    // Load more events at the same time
                    while (iterator.hasNext()) {
                        val event = iterator.next()
                        if (scope.now > event.start + 1e-6) continue

                        if (bucketTime == null) {
                            bucketTime = event.start
                            bucket.add(event)
                        } else if ((event.start - bucketTime).absoluteValue < 1e-9) {
                            bucket.add(event)
                        } else {
                            // Different time: save for next iteration
                            peekedEvent = event
                            break
                        }
                    }

                    if (bucket.isEmpty()) break

                    val eventTime = bucketTime!!
                    waitUntil(
                        target = eventTime - typed.ahead,
                        scope = scope,
                        inside = inside,
                        strand = strand,
                        generation = generation
                    )
                    if (!isActive || inside.runGeneration[strand] != generation) break

                    // Dispatch all simultaneous events in this bucket
                    for (event in bucket) {
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
                }
            } finally {
                inside.jobs.remove(job)
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
