@file:afield.CompletionCategory("Function")

package afield.stdlib

import afield.Box
import afield.PreparedSnippet
import afield.logger.lprintln
import java.util.concurrent.ConcurrentHashMap
import kotlin.script.experimental.api.ResultValue

/**
 * The bridge that gives the string rewrite DSL a real Kotlin right-hand side.
 *
 * FunctionRewriteDsl.kt parses `@{...}` expressions but never compiles them; this file supplies
 * the `FnRewriteOptions.kotlinNumberContext` hook it leaves open, compiling each expression as a
 * `FnRewriteNumberContext -> Any?` lambda inside a live box's coding context so a snippet can read
 * `value`, `op`, `parentOp`, `depth` and the rest as plain properties.
 * [SourceBackedFnRewriteNumberExpression] stores only source plus a box id and hydrates on first
 * use; [SourceBackedFnRewriteNumberEvaluator] caches one per distinct expression string. The entry
 * points are [Functions.Fn.rewriteKotlin], [Functions.Fn.previewRewriteKotlin],
 * [Functions.Fn.rewriteKotlinChecked] and the [Box.rewriteFn] / [Box.previewRewriteFn] receiver
 * forms — each is the corresponding plain-DSL call with that evaluator wired in.
 *
 * Hydration requires the originating box to still be loaded and the compiled lambda to return a
 * `Number?`; anything else throws, naming the box or the offending source. When
 * there is no box in scope no evaluator is installed at all, so Kotlin right-hand sides are left
 * unapplied and the compiler-free `rewrite(...)` path is unaffected.
 */

/**
 * Source-backed Kotlin evaluator for `@{...}` fragments inside the Fn rewrite DSL.
 *
 * The evaluator mirrors [SourceBackedBlock]: it stores only source and a box id,
 * then compiles the source in that box's live-coding context when first needed.
 * The compiled value is a `FnRewriteNumberContext -> Any?` lambda, so snippets
 * can refer to context properties directly:
 *
 * ```
 * sin.frequency => = @{value * 2.0}
 * role=amplitude => *= @{if (parentOp == "fbm") 0.5 else 1.0}
 * ```
 */
class SourceBackedFnRewriteNumberExpression(
    val boxId: String,
    val source: String,
    private val block: ((FnRewriteNumberContext) -> Any?)? = null
) : (FnRewriteNumberContext) -> Double? {
    @Volatile
    private var hydratedBlock: ((FnRewriteNumberContext) -> Any?)? = null

    override fun invoke(context: FnRewriteNumberContext): Double? {
        val result = block?.invoke(context) ?: run {
            val delegate = hydratedBlock ?: synchronized(this) {
                hydratedBlock ?: hydrate().also { hydratedBlock = it }
            }
            delegate(context)
        }
        return coerceResult(result)
    }

    private fun hydrate(): (FnRewriteNumberContext) -> Any? {
        val box = sourceBackedBlockBox(boxId)
            ?: throw IllegalStateException("Cannot hydrate Fn rewrite Kotlin; box '$boxId' is not loaded.")

        val snippet = compileLambda(box)
        val result = snippet.evalAndWait(
            implicits = listOf(box),
            onEvalException = { throw it }
        )

        val value = when (result) {
            is ResultValue.Value -> result.value
            is ResultValue.Error -> throw result.error
            null -> throw IllegalStateException("Fn rewrite Kotlin in box '$boxId' produced no lambda.")
            else -> throw IllegalStateException("Fn rewrite Kotlin in box '$boxId' did not evaluate to a lambda: $result")
        }

        val function = value as? Function1<*, *>
            ?: throw IllegalStateException(
                "Fn rewrite Kotlin in box '$boxId' evaluated to ${value?.javaClass?.name ?: "null"}, not Function1."
            )

        @Suppress("UNCHECKED_CAST")
        return { context -> (function as Function1<Any?, *>).invoke(context) }
    }

    private fun compileLambda(box: Box): PreparedSnippet {
        val lambdaSource = """
            { ctx: afield.stdlib.FnRewriteNumberContext ->
                with(ctx) {
                    $source
                }
            }
        """.trimIndent()

        val expanded = runCatching { box.ki.applyMacros(box, lambdaSource, skipMacros = setOf("sourceBlock")) }
            .getOrElse {
                lprintln(
                    "warning,fn-rewrite",
                    "Macro expansion failed for Fn rewrite Kotlin in '${box.name}' (${box.id}): ${it.message}"
                )
                lambdaSource
            }

        return box.ki.compileSnippetAndWait(
            expanded,
            implicits = listOf(box),
            onCompileError = { diagnostics ->
                diagnostics.forEach { lprintln("error,fn-rewrite", "compile: ${it.message} ${it.location}") }
            }
        ) ?: throw IllegalStateException("Could not compile Fn rewrite Kotlin in box '${box.name}' (${box.id}).")
    }

    private fun coerceResult(result: Any?): Double? =
        when (result) {
            null -> null
            is Number -> result.toDouble()
            else -> throw IllegalStateException(
                "Fn rewrite Kotlin expression '${source.take(80).replace('\n', ' ')}' returned " +
                    "${result::class.simpleName ?: result.javaClass.name}, not Number?"
            )
        }

    override fun toString(): String =
        "SourceBackedFnRewriteNumberExpression(boxId=$boxId, source=${source.take(80).replace('\n', ' ')})"
}

class SourceBackedFnRewriteNumberEvaluator(
    val boxId: String
) : (FnRewriteNumberContext) -> Double? {
    private val expressions = ConcurrentHashMap<String, SourceBackedFnRewriteNumberExpression>()

    override fun invoke(context: FnRewriteNumberContext): Double? =
        expressions.computeIfAbsent(context.expression) {
            SourceBackedFnRewriteNumberExpression(boxId = boxId, source = it)
        }.invoke(context)
}

fun fnRewriteNumberExpression(
    source: String,
    box: Box? = Box.context.get()
): SourceBackedFnRewriteNumberExpression =
    SourceBackedFnRewriteNumberExpression(boxId = box?.id ?: "", source = source)

fun fnRewriteNumber(block: FnRewriteNumberContext.() -> Number?): SourceBackedFnRewriteNumberExpression =
    SourceBackedFnRewriteNumberExpression(
        boxId = Box.context.get()?.id ?: "",
        source = "",
        block = { context -> context.block() }
    )

fun Functions.Fn.rewriteKotlin(
    spec: String,
    preserveSharing: Boolean = false,
    box: Box? = Box.context.get()
): Functions.Fn = rewrite(spec, kotlinRewriteOptions(preserveSharing, box))

fun Functions.Fn.previewRewriteKotlin(
    spec: String,
    preserveSharing: Boolean = false,
    box: Box? = Box.context.get()
): FnRewritePreview = previewRewrite(spec, kotlinRewriteOptions(preserveSharing, box))

fun Functions.Fn.rewriteKotlinChecked(
    spec: String,
    preserveSharing: Boolean = false,
    box: Box? = Box.context.get()
): Functions.Fn = rewriteChecked(spec, kotlinRewriteOptions(preserveSharing, box))

private fun kotlinRewriteOptions(preserveSharing: Boolean, box: Box?): FnRewriteOptions =
    FnRewriteOptions(
        preserveSharing = preserveSharing,
        kotlinNumberContext = box?.let { SourceBackedFnRewriteNumberEvaluator(it.id) }
    )

fun Box.rewriteFn(
    fn: Functions.Fn,
    spec: String,
    preserveSharing: Boolean = false
): Functions.Fn =
    fn.rewriteKotlin(spec, preserveSharing = preserveSharing, box = this)

fun Box.previewRewriteFn(
    fn: Functions.Fn,
    spec: String,
    preserveSharing: Boolean = false
): FnRewritePreview =
    fn.previewRewriteKotlin(spec, preserveSharing = preserveSharing, box = this)
