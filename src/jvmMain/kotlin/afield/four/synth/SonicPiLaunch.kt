@file:afield.CompletionCategory("Audio")
package afield.four.synth

import afield.Box
import afield.ReturnValueRegistry
import afield.RunsAt
import afield.isLaunchEpochCurrent
import afield.registerLaunchJob
import afield.returnValueRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The handle Field holds while Sonic Pi is playing, and the registration that ties it to a box's
 * lifecycle.
 *
 * `SonicPi.kt` sends the code and returns a [SonicPiLaunch] naming the Sonic Pi job that resulted;
 * this file is what makes returning one from a box mean anything. `registerSonicPiLaunchReturnValue`
 * adds a handler to the return-value registry which runs a coroutine registered as one of the box's
 * jobs, reports the box as running while Sonic Pi says the job is alive and this launch is still
 * the box's current one, and stops the job on the way out. That stop runs `NonCancellable`,
 * because the usual reason for reaching it is that the box was stopped and the stop still has to
 * get to Sonic Pi.
 *
 * `awaitJobId` settles which job a launch refers to: it waits for an id
 * different from the one that was current when the code was sent, so a re-running box cannot
 * mistake the job it just replaced for its own.
 *
 * The handler polls the job every 50 ms, which is also the rate the sounding lines are sampled at
 * to decorate the box while `showActivity` is set.
 */

private const val SONIC_PI_LAUNCH_POLL_MILLIS = 50L
private const val SONIC_PI_JOB_START_TIMEOUT_MILLIS = 15_000L

/**
 * A Sonic Pi run that Field owns the way it owns any other launch.
 *
 * Returned by [sonicPi]. The code has already been sent by the time you hold one of these — this is
 * a handle on the resulting Sonic Pi *job*, not a description of work still to do. Its reason for
 * existing is the box lifecycle: return one as a box's last expression and the box stays lit for as
 * long as the music runs, and stopping the box stops the music.
 *
 * That mapping is the natural one for a `live_loop`, which never finishes on its own. Without it a
 * box would flash green for a few milliseconds, go idle, and leave a drum loop playing with nothing
 * in the UI admitting responsibility for it.
 */
class SonicPiLaunch internal constructor(
    val sonicPi: SonicPi,
    val workspace: String,
    private val priorJobId: Int?,
    /** Box line on which the Sonic Pi source starts; see [inferSonicPiLineOffset]. */
    val lineOffset: Int = 0,
    /** Highlight the lines that are sounding while this plays. See [sonicPiActivityDecorations]. */
    var showActivity: Boolean = true
) {
    /**
     * The Sonic Pi job id for this launch, once `/run/started` has named it.
     *
     * Waits for an id *different* from the one that was current when the code was sent, so a
     * re-running box cannot mistake the job it just replaced for its own.
     */
    suspend fun awaitJobId(timeoutMs: Long = SONIC_PI_JOB_START_TIMEOUT_MILLIS): Int? {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            val current = sonicPi.jobIdFor(workspace)
            if (current != null && current != priorJobId) return current
            delay(SONIC_PI_LAUNCH_POLL_MILLIS / 2)
        }
        return sonicPi.jobIdFor(workspace)?.takeIf { it != priorJobId }
    }

    val jobId: Int?
        get() = sonicPi.jobIdFor(workspace)?.takeIf { it != priorJobId }

    val isPlaying: Boolean
        get() = jobId?.let { sonicPi.isJobAlive(it) } ?: false

    /** Lines that have triggered sound in this run. See [SonicPi.activity]. */
    fun activity(): Map<Int, Long> = sonicPi.activity(workspace)

    /** Stop this run's Sonic Pi job, leaving any other box's music alone. */
    fun stop() {
        jobId?.let { sonicPi.stop(it) }
    }

    override fun toString(): String = synthPrettyObject(
        this,
        "workspace" to workspace,
        "jobId" to jobId,
        "playing" to isPlaying,
        "activeLines" to activity().keys.sorted()
    )
}

/**
 * Teach Field that a [SonicPiLaunch] is something a box can be running.
 *
 * The shape is [registerBeatLaunchReturnValue]'s: register a coroutine with the box so
 * `Box.stopTrackedJobs` (what the stop button reaches through `KotlinExecution.stop`) can cancel it,
 * report the box as running for as long as the job lives, and do the actual stopping in a `finally`
 * so cancellation and natural completion take the same path.
 */
@RunsAt(returnValueRegistration, priority = 10)
fun registerSonicPiLaunchReturnValue() {
    ReturnValueRegistry.handlers += handler@{ inside, returnValue, strand, generation, launchEpoch, notify ->
        val spec = returnValue as? SonicPiLaunch ?: return@handler false

        CoroutineScope(Dispatchers.Default + Box.boxContextElement(inside)).launch {
            val job = currentCoroutineContext()[Job]!!
            if (!inside.registerLaunchJob(job, launchEpoch)) {
                // Superseded before we even started; the newer launch owns the music now.
                notify(false, strand, generation)
                return@launch
            }
            notify(true, strand, generation)

            var jobId: Int? = null
            try {
                jobId = spec.awaitJobId()
                if (jobId == null) {
                    println("SonicPi: launch never saw a job start for workspace '${spec.workspace}'")
                    return@launch
                }
                while (isActive &&
                    inside.runGeneration[strand] == generation &&
                    inside.isLaunchEpochCurrent(launchEpoch) &&
                    spec.sonicPi.isJobAlive(jobId)
                ) {
                    // The poll is already the right rate to sample activity at: fast enough to look
                    // live, slow enough not to push a decoration set per note.
                    if (spec.showActivity) {
                        applySonicPiActivity(inside, spec.sonicPi, spec.workspace, spec.lineOffset)
                    }
                    delay(SONIC_PI_LAUNCH_POLL_MILLIS)
                }
            } catch (t: Throwable) {
                // A launch handler that fails silently leaves a box looking idle while the music
                // plays on, which is the exact confusion this class exists to prevent.
                if (t !is kotlinx.coroutines.CancellationException) {
                    println("SonicPi: launch handler for '${spec.workspace}' failed: $t")
                }
                throw t
            } finally {
                // NonCancellable because this is the point of the whole exercise: we are usually
                // here *because* we were cancelled, and the stop still has to go out.
                val toStop = jobId
                if (toStop != null) {
                    withContext(NonCancellable) {
                        if (spec.sonicPi.isJobAlive(toStop)) spec.sonicPi.stop(toStop)
                    }
                }
                if (spec.showActivity) clearSonicPiActivity(inside)
                inside.jobs.remove(job)
                notify(false, strand, generation)
            }
        }
        true
    }
}
