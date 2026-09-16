@file:afield.CompletionCategory("Patterns")
/**
 * The step between `pat("bd sd").respond { … }` and a pattern that is actually playing: the
 * return-value handler that recognises a [PatResponseSpec] coming back from a box and runs it.
 *
 * `respond` is a builder. It captures the pattern, the cycles-per-second, the clock and the
 * callback into a spec and returns, having started nothing. This file is the other half, and the
 * arrangement is what makes a responder behave like every other launchable value in Field: it runs
 * because the box's last expression is one, it can be bundled with `+`, it can be wrapped in
 * `.onBeat`, and stopping the box stops it.
 *
 * The loop it runs is short, and three of its rules shape what a callback sees:
 *
 * - **Events that share a start time go out together.** The iterator is drained into a bucket of
 *   events within a nanosecond of each other, the wait happens once, and then the whole bucket is
 *   dispatched. A chord is a chord rather than a queue of near-simultaneous notes.
 * - **Events already in the past are dropped, not played late.** An event whose start has gone by
 *   is skipped, which is how a pattern joined mid-flight, or one whose callback overran, catches
 *   up instead of falling further behind.
 * - **`ahead` shifts the callback, not the schedule.** The wait targets `start - ahead`, so a
 *   callback that has to buy latency somewhere else, a sample player with its own scheduling
 *   queue, gets the event early and still knows its real time from `event.start`.
 *
 * Dispatch is on the run loop unless `dispatchOnMain = false`, so a callback may touch boxes,
 * properties and graphics without hopping threads itself.
 */

package afield.four.synth

import afield.*
import kotlinx.coroutines.*
import kotlin.math.absoluteValue

/**
 * Teaches box execution about [PatResponseSpec]. Called once during return-value registration, not
 * from box code.
 *
 * The coroutine it launches is registered as one of the box's jobs and re-checks the box's run
 * generation on every pass, including from inside the wait, so a re-run or a stop ends the pattern
 * within a poll of the wait loop rather than leaving it playing under a superseded generation.
 */
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
