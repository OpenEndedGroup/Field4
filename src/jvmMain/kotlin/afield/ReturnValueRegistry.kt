package afield

import afield.logger.lprintln
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
    ) {
        val generation = inside.runGeneration.incrementAndGet(strand)
        launchWithGeneration(inside, returnValue, strand, generation, notify)
    }

    internal fun launchWithGeneration(
        inside: Box,
        returnValue: Any?,
        strand: String,
        generation: Int,
        notify: (running: Boolean, strand: String, watermark: Int) -> Unit
    ): Boolean {
        if (returnValue == null) {
            notify(false, strand, generation)
            return true
        }
        if (launchCompositeIfPresent(inside, returnValue, strand, generation, notify)) return true
        if (dispatch(inside, returnValue, strand, generation, notify)) return true
        // no handler matched
        notify(false, strand, generation)
        return false
    }

    internal fun dispatch(
        inside: Box,
        returnValue: Any,
        strand: String,
        generation: Int,
        notify: (running: Boolean, strand: String, watermark: Int) -> Unit
    ): Boolean {
        for (handler in handlers) {
            if (handler(inside, returnValue, strand, generation, notify)) return true
        }
        return false
    }

    private fun launchCompositeIfPresent(
        inside: Box,
        returnValue: Any,
        strand: String,
        generation: Int,
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
            val consumed = dispatch(inside, item, strand, generation, compositeNotify)
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
    ReturnValueRegistry.handlers += handler@{ inside, returnValue, strand, generation, notify ->
        if (returnValue !is Sequence<*>) return@handler false
        val a = returnValue.iterator()
        inMain {
            notify(true, strand, generation)
            while (inside.runGeneration[strand] == generation && a.hasNext()) {
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
    ReturnValueRegistry.handlers += handler@{ inside, returnValue, strand, generation, notify ->
        if (returnValue !is Frames) return@handler false
        returnValue.shouldend = {
            inside.runGeneration[strand] != generation
        }
        val original = returnValue.atEnd
        returnValue.atEnd = {
            original()
            notify(false, strand, generation)
        }
        inMain {
            Box.withBoxContext(inside) {
            notify(true, strand, generation)
            returnValue.job = kotlin.coroutines.coroutineContext[Job]!!
            inside.jobs += returnValue.job!!
            try {
                returnValue.body()
            } catch (e: CancellationException) {
            } catch (t: Throwable) {
                lprintln("error,execution", t.stackTraceToString())
                var firstLine = -1
                t.stackTrace.forEach { e ->
                    if (firstLine == -1 && e.fileName.contains(inside.id)) {
                        firstLine = e.lineNumber
                    }
                }
                inside.error(t.message ?: "", line = firstLine)
            } finally {
                notify(false, strand, generation)
            }
            }
        }
        true
    }
}
