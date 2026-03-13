package afield

import afield.logger.lprintln
import kotlinx.coroutines.*

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
        if (returnValue == null) {
            notify(false, strand, generation)
            return
        }
        for (handler in handlers) {
            if (handler(inside, returnValue, strand, generation, notify)) return
        }
        // no handler matched
        notify(false, strand, generation)
    }
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
        true
    }
}
