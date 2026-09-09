@file:afield.CompletionCategory("Internal")

package afield.stdlib

import afield.Box
import afield.documents
import afield.sessions
import afield.logger.lprintln
import kotlin.script.experimental.api.ResultValue

/**
 * Box ids whose stored source is being hydrated on this thread, by **any** route.
 *
 * Shared with [SourceBackedFnRewriteNumberExpression] on purpose rather than one set each: both
 * routes hydrate by evaluating a snippet on a box's own engine, so a `SourceBackedBlock` invoked from
 * a body that a `@{...}` hydration is already re-running is the same recursion arriving by two doors,
 * and one set catches the mixed case that two would let through.
 *
 * A `ThreadLocal` because the recursion is entirely within one thread — hydration evaluates on the
 * box's engine, that re-runs the body, and the body hydrates again, all on the caller's stack. Two
 * boxes hydrating on two threads are not each other's problem.
 */
internal val hydratingBoxes: ThreadLocal<MutableSet<String>> =
    ThreadLocal.withInitial { mutableSetOf<String>() }

/**
 * Thrown when a rehydrating [SourceBackedBlock] would be evaluated in a box whose body is already
 * running — the failure that was 327 nested body runs and 355 seconds before it was guarded.
 *
 * [SourceBackedBlock.hydrate] evaluates a snippet on [SourceBackedBlock.boxId]'s engine, which
 * re-instantiates that engine's snippet chain; the box body is in that chain, so it runs again — and
 * invokes the block again, once per level, until the stack goes. Detected on the *second* hydration
 * for the box rather than the first, which costs one extra body run and in exchange never refuses the
 * legitimate cases (a frame loop or a control callback, where the body has finished and the hydration
 * is not nested inside another).
 *
 * The message names the ways out, because none of them is guessable from a stack trace. Same shape and
 * the same remedy as [FnRewriteSelfBoxReentry]; see `dev_docs/FN_REWRITE_SELF_BOX_REENTRY.md`.
 */
class SourceBackedBlockSelfBoxReentry(box: Box, source: String) : IllegalStateException(
    "This source-backed block is evaluating its stored source in box '${box.name}' (${box.id}) — the " +
        "box that is running it, whose body it therefore re-runs, once per invocation, until the stack " +
        "overflows. Source: '${source.take(80).replace('\n', ' ')}'. " +
        "Invoke it somewhere that is not this box's own body: " +
        "(1) from another box — construct it with that box's id, `SourceBackedBlock<Any?>(\"other_box\", source)`, " +
        "and invoke it there, which is the split `dev_docs/agents/transcripts/keyframe_code_blend.yaml` uses; " +
        "(2) from a frame loop or a control callback, after this body has finished; or " +
        "(3) not at all — a block the `sourceBlock { }` macro made in *this* execution carries a live " +
        "lambda, never hydrates, and is safe to call immediately. " +
        "See dev_docs/FN_REWRITE_SELF_BOX_REENTRY.md."
)

/**
 * A callable block that keeps the Kotlin source used to create it.
 *
 * The `sourceBlock { ... }` macro creates these with both a live lambda and the
 * block body source. Persisted copies rehydrate by evaluating [source] inside
 * the original box context.
 *
 * **Do not invoke a rehydrating block from inside its own box's running code.** [hydrate] compiles
 * and evaluates a snippet on [boxId]'s engine, and asking that engine to evaluate while it is
 * already mid-evaluation **re-runs the box body that is running** — which hydrates again, once per
 * level, until the stack gives out. Measured at 327 nested runs of one body and 355 seconds on
 * `dev_docs/agents/transcripts/keyframe_code_blend.yaml` before that box was split in two, off a
 * single compile of the body: the body was re-*evaluated*, not recompiled.
 *
 * **Guarded since 2026-09-09:** the second hydration for a box on one thread throws
 * [SourceBackedBlockSelfBoxReentry], whose message names the ways out. That breaks the recursion, so
 * the body runs twice rather than 327 times — but twice is still once too many, and anything the body
 * does before the invocation happens twice, so the split below remains the shape to write.
 *
 * Hydrating into a box that is **idle** is fine and does not re-run anything, which is what the
 * split in that transcript relies on — `kfb_kotlin_run` builds a block whose box is `kfb_kotlin` and
 * invokes it, and `kfb_kotlin`'s body runs exactly once. A block that arrived from a keyframe file or
 * from [afield.stdlib.CodeBlendPlan.runtimeSource] is a rehydrating one; invoke it from a frame loop,
 * a control callback, or another box. A block the `sourceBlock` macro made in *this* execution
 * carries a live lambda and never hydrates, so it is safe to call immediately.
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

        // Refuse the second hydration for this box on this thread — see [SourceBackedBlockSelfBoxReentry].
        // Logged as well as thrown: the throw lands inside a *re-entrant* run of the box body, and
        // whatever instantiates the snippet chain absorbs it without reporting, so the throw alone is
        // invisible to the person whose box just ran twice.
        if (!hydratingBoxes.get().add(boxId)) {
            val reentry = SourceBackedBlockSelfBoxReentry(box, source)
            lprintln("error,source-block", reentry.message ?: "Source-backed block re-entered its own box")
            throw reentry
        }
        try {
            return hydrateInBox(box)
        } finally {
            hydratingBoxes.get().remove(boxId)
        }
    }

    private fun hydrateInBox(box: Box): () -> Any? {
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
