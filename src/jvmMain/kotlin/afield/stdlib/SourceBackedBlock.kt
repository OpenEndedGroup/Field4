@file:afield.CompletionCategory("Internal")

package afield.stdlib

import afield.Box
import afield.documents
import afield.sessions
import afield.logger.lprintln
import kotlin.script.experimental.api.ResultValue

/**
 * A callable block that keeps the Kotlin source used to create it.
 *
 * The `sourceBlock { ... }` macro creates these with both a live lambda and the
 * block body source. Persisted copies rehydrate by evaluating [source] inside
 * the original box context.
 *
 * **Do not invoke a rehydrating block from inside its own box's running code.** [hydrate] compiles
 * and evaluates a snippet in [boxId]'s engine, and a nested evaluation there replays that engine's
 * earlier snippet — which is the box body doing the invoking. It therefore re-enters, once per
 * level, until the stack gives out: measured at 327 levels and 355 seconds on
 * `dev_docs/agents/transcripts/keyframe_code_blend.yaml` before that box was split in two. A block
 * that arrived from a keyframe file or from [afield.stdlib.CodeBlendPlan.runtimeSource] is a
 * rehydrating one. Invoke it from a frame loop, a control callback, or another box — anywhere except
 * the body that built it. A block the `sourceBlock` macro made in *this* execution carries a live
 * lambda and never hydrates, so it is safe to call immediately.
 */
class SourceBackedBlock<out T>(
    val boxId: String,
    val source: String,
    private val block: (() -> T)? = null
) : () -> T {
    @Volatile
    private var hydratedBlock: (() -> Any?)? = null

    override fun invoke(): T {
        block?.let { return it() }

        val delegate = hydratedBlock ?: synchronized(this) {
            hydratedBlock ?: hydrate().also { hydratedBlock = it }
        }

        @Suppress("UNCHECKED_CAST")
        return delegate.invoke() as T
    }

    private fun hydrate(): () -> Any? {
        val box = sourceBackedBlockBox(boxId)
            ?: throw IllegalStateException("Cannot hydrate source-backed block; box '$boxId' is not loaded.")

        val snippet = compileLambda(box)
        val result = snippet.evalAndWait(
            implicits = listOf(box),
            onEvalException = { throw it }
        )

        val value = when (result) {
            is ResultValue.Value -> result.value
            is ResultValue.Error -> throw result.error
            null -> throw IllegalStateException("Source-backed block in box '$boxId' produced no lambda.")
            else -> throw IllegalStateException("Source-backed block in box '$boxId' did not evaluate to a lambda: $result")
        }

        val function = value as? Function0<*>
            ?: throw IllegalStateException("Source-backed block in box '$boxId' evaluated to ${value?.javaClass?.name ?: "null"}, not Function0.")

        return { function.invoke() }
    }

    private fun compileLambda(box: Box): afield.PreparedSnippet {
        val lambdaSource = "{\n$source\n}"
        val expanded = runCatching { box.ki.applyMacros(box, lambdaSource, skipMacros = setOf("sourceBlock")) }
            .getOrElse {
                lprintln("warning,source-block", "Macro expansion failed for source-backed block in '${box.name}' (${box.id}): ${it.message}")
                lambdaSource
            }

        return box.ki.compileSnippetAndWait(
            expanded,
            implicits = listOf(box),
            onCompileError = { diagnostics ->
                diagnostics.forEach { lprintln("error,source-block", "compile: ${it.message} ${it.location}") }
            }
        ) ?: throw IllegalStateException("Could not compile source-backed block in box '${box.name}' (${box.id}).")
    }

    override fun toString(): String = "SourceBackedBlock(boxId=$boxId, source=${source.take(80).replace('\n', ' ')})"
}

fun <T> sourceBlock(block: () -> T): SourceBackedBlock<T> =
    SourceBackedBlock(Box.context.get()?.id ?: "", "", block)

fun <T> sourceBackedBlock(boxId: String, source: String): SourceBackedBlock<T> =
    SourceBackedBlock(boxId, source)

fun sourceBackedBlockBox(boxId: String): Box? {
    val current = Box.context.get()
    if (current?.id == boxId) return current
    current?.inside?.boxes?.get(boxId)?.let { return it }
    documents.values.firstNotNullOfOrNull { it.boxes[boxId] }?.let { return it }
    return sessions.values.firstNotNullOfOrNull { it.boxes[boxId] }
}
