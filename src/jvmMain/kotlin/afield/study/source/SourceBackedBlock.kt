@file:afield.runtime.completion.CompletionCategory("Internal")

package afield.study.source

import afield.animation.keyframes.CodeBlendPlan
import afield.data.collections.s
import afield.data.collections.times
import afield.document.graph.minusAssign
import afield.document.graph.plusAssign
import afield.document.properties.compareTo
import afield.document.workspace.file
import afield.graphics.render.rangeTo
import afield.graphics.shader.callable
import afield.math.autodiff.compareTo
import afield.math.autodiff.div
import afield.math.autodiff.minus
import afield.math.autodiff.plus
import afield.math.autodiff.times
import afield.math.functions.FnRewriteSelfBoxReentry
import afield.math.functions.SourceBackedFnRewriteNumberExpression
import afield.math.linalg.remAssign
import afield.math.linalg.set
import afield.math.linalg.times
import afield.math.units.div
import afield.math.units.minus
import afield.math.units.plus
import afield.math.units.times
import afield.media.image.split
import afield.runtime.lifecycle.once

import afield.audio.synth.*
import afield.data.collections.get
import afield.data.collections.invoke
import afield.geometry.mesh.expanded
import afield.graphics.vulkan.buffers.add
import afield.math.linalg.invoke
import afield.document.graph.Box
import afield.app.server.documents
import afield.app.server.sessions
import afield.app.diagnostics.logging.lprintln
import kotlin.script.experimental.api.ResultValue

/**
 * A callable block that keeps the Kotlin source it was written as, and can be rebuilt from that
 * source after it has been saved.
 *
 * The `sourceBlock` macro makes a [SourceBackedBlock] holding both a live lambda and its source. A
 * block restored from storage has only the source and rebuilds itself by evaluating it on its box's
 * engine. Doing that while the same box's code is running would run that code again, so it is
 * refused with [SourceBackedBlockSelfBoxReentry] or [SourceBackedBlockDuringOwnBody], each of whose
 * messages says where to invoke the block instead.
 */

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
 * A saved expression asked to hydrate on its own box's engine while that box's body is running.
 * Evaluating it there would run the body again, so it is an error, raised before anything runs twice.
 * A [SourceBackedBlock] no longer raises it (it hydrates on a side engine instead); the `@{...}`
 * route of `rewriteKotlin` still does.
 */
class SourceBackedBlockDuringOwnBody(box: Box, source: String) : IllegalStateException(
    "This saved block (source '${source.take(80).replace('\n', ' ')}') belongs to box '${box.name}', whose " +
        "code is running now, and hydrating it there would run that code again. Invoke it from a frame loop " +
        "or a control callback, after the box's code has finished, or from another box."
)

/**
 * A callable block that keeps the Kotlin source used to create it.
 *
 * The `sourceBlock { ... }` macro creates these with both a live lambda and the
 * block body source. Persisted copies rehydrate by evaluating [source] inside
 * the original box context, on first call.
 *
 * A block that arrived from a keyframe file or from
 * [afield.animation.keyframes.CodeBlendPlan.runtimeSource] is a rehydrating one, and can be called
 * from anywhere, including its own box's running code. Hydrating on [boxId]'s own engine while that
 * engine is mid-evaluation **re-runs the box body that is running** (327 nested runs and 355 seconds,
 * measured on `dev_docs/agents/transcripts/keyframe_code_blend.yaml`), so while the body runs the
 * block is compiled on a separate engine for that box instead: the box as receiver and Field's
 * standard imports, which is all a self-contained source such as a blend needs. A block that names
 * the body's own declarations cannot hydrate there; call it once the body has finished.
 *
 * The second hydration for a box on one thread still throws [SourceBackedBlockSelfBoxReentry], the
 * guard against any other route into the same recursion. A block the `sourceBlock` macro made in
 * *this* execution carries a live lambda and never hydrates.
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

        // While this box's body is running, its own engine would run the body again; compile on a side engine instead.
        if (afield.runtime.execution.BoxBodyRun.isRunning(boxId)) return hydrateOnSideEngine(box)
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

    /**
     * Hydrates on [sideEngineFor] the box rather than its own engine, for a block invoked from inside
     * that box's running body. The block's source is self-contained (a blend's is fully qualified), so
     * it needs the box as receiver and the standard imports, not the body's own declarations.
     */
    private fun hydrateOnSideEngine(box: Box): () -> Any? {
        val engine = sideEngineFor(box)
        val errors = mutableListOf<String>()
        val result = synchronized(engine) {
            engine.execAndWait(
                "{\n$source\n}",
                onCompileError = { d -> d.forEach { errors += "${it.message} ${it.location ?: ""}".trim() } },
                onEvalError = { d -> d.forEach { errors += it.message } },
                onEvalException = { throw it },
                implicits = listOf(box),
            )
        }
        if (errors.isNotEmpty()) {
            errors.forEach { lprintln("error,source-block", "compile: $it") }
            throw IllegalStateException("Could not compile source-backed block in box '${box.name}' (${box.id}): ${errors.first()}")
        }
        val value = (result as? ResultValue.Value)?.value
        val function = value as? Function0<*>
            ?: throw IllegalStateException("Source-backed block in box '$boxId' evaluated to ${value?.javaClass?.name ?: result}, not Function0.")
        return { function.invoke() }
    }

    private fun compileLambda(box: Box): afield.runtime.kotlin.PreparedSnippet {
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


/**
 * One engine per box for blocks hydrated while that box's body runs (see [SourceBackedBlock]): the box
 * as implicit receiver and Field's standard imports, separate from the box's own engine so nothing
 * there re-enters. Made on first need; dropped with the box.
 */
private val sideEngines = java.util.WeakHashMap<Box, afield.runtime.kotlin.KotlinInterface2>()

private fun sideEngineFor(box: Box): afield.runtime.kotlin.KotlinInterface2 = synchronized(sideEngines) {
    sideEngines.getOrPut(box) {
        afield.runtime.kotlin.KotlinInterface2(
            implicitReceivers = listOf(kotlin.script.experimental.api.KotlinType(Box::class)),
            filePrefix = "SBB_${box.id.filter { it.isLetterOrDigit() }.take(12)}_",
        ).also { it.execAndWait(afield.document.graph.intro, { }, { }, { throw it }, listOf(box)) }
    }
}

fun <T> sourceBackedBlock(boxId: String, source: String): SourceBackedBlock<T> =
    SourceBackedBlock(boxId, source)

fun sourceBackedBlockBox(boxId: String): Box? {
    val current = Box.context.get()
    if (current?.id == boxId) return current
    current?.inside?.boxes?.get(boxId)?.let { return it }
    documents.values.firstNotNullOfOrNull { it.boxes[boxId] }?.let { return it }
    return sessions.values.firstNotNullOfOrNull { it.boxes[boxId] }
}
