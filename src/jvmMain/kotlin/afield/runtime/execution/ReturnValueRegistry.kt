package afield.runtime.execution

import afield.data.table.div
import afield.data.table.minus
import afield.data.table.rem
import afield.data.table.times
import afield.document.graph.box
import afield.runtime.lifecycle.end
import afield.runtime.lifecycle.rem

import afield.app.diagnostics.ProfileOrigin
import afield.app.notifications.notify
import afield.app.protocol.IsRunning
import afield.app.protocol.Message
import afield.app.protocol.computeIfAbsent
import afield.app.protocol.handlers
import afield.app.protocol.payload
import afield.app.protocol.send
import afield.audio.synth.*
import afield.data.table.get
import afield.document.graph.Box
import afield.document.graph.RunningState
import afield.document.graph.captureLaunchEpoch
import afield.document.graph.isLaunchEpochCurrent
import afield.document.graph.registerEndPhaseJob
import afield.document.graph.registerLaunchJob
import afield.graphics.vulkan.*
import afield.media.stream.*
import afield.media.video.*
import afield.runtime.lifecycle.Frames
import afield.runtime.lifecycle.RunScope
import afield.runtime.lifecycle.RunsAt
import afield.runtime.lifecycle.inMain
import afield.runtime.lifecycle.returnValueRegistration
import afield.runtime.lifecycle.runAll
import afield.runtime.lifecycle.withCurrent
import afield.app.diagnostics.logging.lprintln
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * A handler that can launch a return value from box execution.
 *
 * Return `true` if this handler consumed the value, `false` to let the next handler try.
 */
typealias ReturnValueHandler = (
    inside: Box,
    returnValue: Any,
    strand: String,
    generation: Int,
    launchEpoch: Int,
    notify: (running: Boolean, strand: String, watermark: Int) -> Unit
) -> Boolean

class LaunchBundle internal constructor(val items: List<Any?>) {
    override fun toString() = "launchBundle[of ${items.size}]"
}

fun launches(vararg items: Any?): LaunchBundle = LaunchBundle(items.toList())

operator fun LaunchBundle.plus(other: Any?): LaunchBundle =
    LaunchBundle(buildList {
        addAll(items.flatMap(::flattenLaunchItems))
        addAll(flattenLaunchItems(other))
    })

operator fun Frames.plus(other: Any?): LaunchBundle = launches(this, other)

object ReturnValueRegistry {

    val handlers = mutableListOf<ReturnValueHandler>()

    init {
        runAll(returnValueRegistration)
    }

    fun launch(
        inside: Box,
        returnValue: Any?,
        strand: String = "default",
        notify: (running: Boolean, strand: String, watermark: Int) -> Unit
    ): Boolean {
        val launchEpoch = inside.captureLaunchEpoch()
        return launchPrepared(inside, returnValue, strand, launchEpoch, notify)
    }

    internal fun launchPrepared(
        inside: Box,
        returnValue: Any?,
        strand: String,
        launchEpoch: Int,
        notify: (running: Boolean, strand: String, watermark: Int) -> Unit
    ): Boolean {
        if (!inside.isLaunchEpochCurrent(launchEpoch)) return false
        val generation = inside.runGeneration.incrementAndGet(strand)
        return launchWithGeneration(inside, returnValue, strand, generation, launchEpoch, notify)
    }

    internal fun launchWithGeneration(
        inside: Box,
        returnValue: Any?,
        strand: String,
        generation: Int,
        launchEpoch: Int = inside.captureLaunchEpoch(),
        notify: (running: Boolean, strand: String, watermark: Int) -> Unit
    ): Boolean {
        if (!inside.isLaunchEpochCurrent(launchEpoch)) return false
        if (returnValue == null) {
            notify(false, strand, generation)
            return true
        }
        // Handlers find the run that owns what they register through RunScope.current(): the launch's
        // scope when execution installed one, otherwise the box's ambient run (other languages).
        val scope = RunScope.currentFor(inside)
        return RunScope.withCurrent(scope) {
            if (launchCompositeIfPresent(inside, returnValue, strand, generation, launchEpoch, notify)) return@withCurrent true
            if (dispatch(inside, returnValue, strand, generation, launchEpoch, notify)) return@withCurrent true
            // no handler matched
            notify(false, strand, generation)
            false
        }
    }

    /** The coroutine context element of the run a handler is dispatched for, if any. */
    internal fun currentRunElement(): kotlin.coroutines.CoroutineContext =
        RunScope.current()?.element ?: kotlin.coroutines.EmptyCoroutineContext

    internal fun dispatch(
        inside: Box,
        returnValue: Any,
        strand: String,
        generation: Int,
        launchEpoch: Int,
        notify: (running: Boolean, strand: String, watermark: Int) -> Unit
    ): Boolean {
        for (handler in handlers) {
            if (handler(inside, returnValue, strand, generation, launchEpoch, notify)) return true
        }
        return false
    }

    private fun launchCompositeIfPresent(
        inside: Box,
        returnValue: Any,
        strand: String,
        generation: Int,
        launchEpoch: Int,
        notify: (running: Boolean, strand: String, watermark: Int) -> Unit
    ): Boolean {
        val items = when (returnValue) {
            is LaunchBundle -> flattenLaunchItems(returnValue)
            is Iterable<*> -> flattenLaunchItems(returnValue)
            else -> return false
        }.filterNotNull()

        if (items.isEmpty()) {
            notify(false, strand, generation)
            return true
        }

        val activeChildren = AtomicInteger(0)
        val launchedAny = AtomicBoolean(false)
        val dispatchComplete = AtomicBoolean(false)
        val runningNotified = AtomicBoolean(false)
        val compositeNotify: (Boolean, String, Int) -> Unit = composite@{ running: Boolean, _: String, _: Int ->
            if (running) return@composite
            activeChildren.updateAndGet { current -> if (current > 0) current - 1 else 0 }
            if (
                dispatchComplete.get() &&
                activeChildren.get() == 0 &&
                launchedAny.get() &&
                runningNotified.compareAndSet(true, false)
            ) {
                notify(false, strand, generation)
            }
        }

        for (item in items) {
            activeChildren.incrementAndGet()
            val consumed = dispatch(inside, item, strand, generation, launchEpoch, compositeNotify)
            if (consumed) {
                launchedAny.set(true)
            } else {
                activeChildren.decrementAndGet()
                lprintln(
                    "warn,execution",
                    "No return-value handler for composite launch item `${item::class.qualifiedName ?: item::class.simpleName ?: item}`"
                )
            }
        }

        dispatchComplete.set(true)
        if (launchedAny.get() && activeChildren.get() > 0) {
            if (runningNotified.compareAndSet(false, true)) {
                notify(true, strand, generation)
            }
        } else {
            notify(false, strand, generation)
        }
        return true
    }
}

private fun flattenLaunchItems(value: Any?): List<Any?> = when (value) {
    is LaunchBundle -> value.items.flatMap(::flattenLaunchItems)
    is Iterable<*> -> value.flatMap(::flattenLaunchItems)
    else -> listOf(value)
}

private fun MutableMap<String, Int>.incrementAndGet(name: String): Int {
    this[name] = computeIfAbsent(name) { 0 } + 1
    return this[name]!!
}

@RunsAt(returnValueRegistration, priority = 0)
fun registerSequenceReturnValue() {
    ReturnValueRegistry.handlers += handler@{ inside, returnValue, strand, generation, launchEpoch, notify ->
        if (returnValue !is Sequence<*>) return@handler false
        val a = returnValue.iterator()
        inMain(Box.boxContextElement(inside) + ProfileOrigin(inside, strand) + ReturnValueRegistry.currentRunElement()) {
            if (!inside.isLaunchEpochCurrent(launchEpoch)) {
                notify(false, strand, generation)
                return@inMain
            }
            notify(true, strand, generation)
            while (inside.isLaunchEpochCurrent(launchEpoch) && inside.runGeneration[strand] == generation && a.hasNext()) {
                a.next()
                yield()
            }
            notify(false, strand, generation)
        }
        true
    }
}

@RunsAt(returnValueRegistration, priority = 1)
fun registerFramesReturnValue() {
    ReturnValueRegistry.handlers += handler@{ inside, returnValue, strand, generation, launchEpoch, notify ->
        if (returnValue !is Frames) return@handler false
        val scope = RunScope.currentFor(inside)
        returnValue.shouldend = {
            inside.runGeneration[strand] != generation
        }
        val original = returnValue.atEnd
        returnValue.atEnd = {
            original()
        }
        // `a + b` forwards up to three end phases to these hooks: report "ending" once per stop and
        // drop the end fence only when the last of them has finished.
        val activeEndPhases = HashSet<Job>()
        returnValue.onAtEndStart = onAtEndStart@{ endJob ->
            // The end phase registers against the epoch *now*, not `launchEpoch`: stop bumps the
            // epoch and it is stop's cancellation that creates this job. See registerEndPhaseJob.
            if (!inside.registerEndPhaseJob(endJob)) return@onAtEndStart
            val first = synchronized(activeEndPhases) { activeEndPhases.isEmpty().also { activeEndPhases.add(endJob) } }
            if (!first) return@onAtEndStart
            val endingGeneration = inside.runGeneration[strand] ?: (generation + 1)
            returnValue.shouldCancelAtEnd = {
                inside.runGeneration[strand] != endingGeneration
            }
            // Only claim the running state if a newer launch has not already taken this strand. A
            // stop followed immediately by a relaunch — what the docs capture's `restartTarget`
            // does — runs this end phase alongside the new launch, and reporting the *old*
            // generation as "ending" would leave the strand looking stopped while it runs, because
            // the matching removal below keys on that older generation.
            val holder = inside.currentRunningStates[strand]
            if (holder == null || holder.watermark <= generation) {
                inside.currentRunningStates[strand] = RunningState(generation, "ending")
                send(Message("is-running").payload(IsRunning(inside.id, true, generation, strand, "ending")))
            }
        }
        returnValue.onAtEndFinish = { endJob ->
            inside.jobs.remove(endJob)
            val last = synchronized(activeEndPhases) { activeEndPhases.remove(endJob) && activeEndPhases.isEmpty() }
            if (last) returnValue.shouldCancelAtEnd = { false }
        }
        inMain(Box.boxContextElement(inside) + ProfileOrigin(inside, strand) + ReturnValueRegistry.currentRunElement()) {
            Box.withBoxContext(inside) {
                val job = kotlin.coroutines.coroutineContext[Job]!!
                returnValue.job = job
                if (!inside.registerLaunchJob(job, launchEpoch)) {
                    notify(false, strand, generation)
                    return@withBoxContext
                }
                // A replaced run must cancel waits too, not only the next frame() boundary.
                scope?.ownJob(job)
                notify(true, strand, generation)
                try {
                    // Supersession closes the old scope after scheduling us. Give its cancelled
                    // body a turn to run synchronous finally cleanup before touching shared state.
                    kotlinx.coroutines.yield()
                    returnValue.body()
                } catch (e: CancellationException) {
                } catch (t: Throwable) {
                    lprintln("error,execution", t.stackTraceToString())
                    var fallbackFirstLine = -1
                    var firstLineInThisBox = -1
                    t.stackTrace.forEach { e ->
                        if (fallbackFirstLine == -1) {
                            fallbackFirstLine = e.lineNumber
                        }
                        if (e.fileName?.contains(inside.id) == true && firstLineInThisBox == -1) {
                            firstLineInThisBox = e.lineNumber
                        }
                    }
                    val firstLine = if (firstLineInThisBox != -1) firstLineInThisBox else fallbackFirstLine
                    val message = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.name
                    inside.error(message, line = firstLine)
                } finally {
                    inside.jobs.remove(job)
                    if (inside.currentRunningStates[strand]?.watermark == generation) {
                        inside.currentRunningStates.remove(strand)
                    }
                    notify(false, strand, generation)
                }
            }
        }
        true
    }
}
