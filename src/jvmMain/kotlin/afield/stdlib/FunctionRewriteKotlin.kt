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
 * unapplied; the compiler-free `rewrite(...)` path never needs one.
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
    /**
     * The box whose live-coding context the source will be compiled in. Empty when there was no box in
     * scope at construction, which makes the first call fail rather than fall back to anything.
     */
    val boxId: String,
    /**
     * The Kotlin source of the right-hand side, already stripped of its `@{...}` wrapper. Compiled inside
     * a `with(context)` block, so it reads the [FnRewriteNumberContext] members as bare names, and its last
     * expression is the result.
     */
    val source: String,
    private val block: ((FnRewriteNumberContext) -> Any?)? = null
) : (FnRewriteNumberContext) -> Double? {
    @Volatile
    private var hydratedBlock: ((FnRewriteNumberContext) -> Any?)? = null

    /**
     * Evaluates the expression for one matched node, returning the new number or null to leave the value
     * alone.
     *
     * The first call compiles, under a lock, and later calls reuse the compiled lambda. A result that is
     * neither null nor a `Number` is a hard error naming the offending source — a snippet ending in a
     * `println` or an assignment throws rather than being ignored in silence. A live block is called directly
     * and its null is returned as-is, so returning null from one really does mean "leave this value alone";
     * only the *absence* of a block sends the call down the source-hydration path.
     */
    override fun invoke(context: FnRewriteNumberContext): Double? {
        // A live block returning null means "leave this value alone", so only the absence of a
        // block sends us to the source; an elvis here would hydrate an empty `source` instead.
        block?.let { return coerceResult(it.invoke(context)) }

        val delegate = hydratedBlock ?: synchronized(this) {
            hydratedBlock ?: hydrate().also { hydratedBlock = it }
        }
        return coerceResult(delegate(context))
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

    /** The box id and the first 80 characters of the source, newlines flattened — safe to log. */
    override fun toString(): String =
        "SourceBackedFnRewriteNumberExpression(boxId=$boxId, source=${source.take(80).replace('\n', ' ')})"
}

/**
 * The evaluator a whole rewrite runs through: one per box, compiling and caching a
 * [SourceBackedFnRewriteNumberExpression] per distinct expression string it is asked for.
 *
 * The cache is keyed on the source text alone — the node is passed in at call time — so one `@{...}` action
 * is compiled once no matter how many occurrences it matches. It is not, however, shared between rewrites:
 * `rewriteKotlin` and friends build a fresh evaluator per call, so a spec re-run in an animation loop
 * recompiles its expressions every time. That compile is a real snippet compile in the box's engine, not a
 * cheap parse; hoist the rewrite out of the loop, or keep one evaluator of your own and pass it through
 * [FnRewriteOptions].
 */
class SourceBackedFnRewriteNumberEvaluator(
    /** The box every expression will be compiled in. */
    val boxId: String
) : (FnRewriteNumberContext) -> Double? {
    private val expressions = ConcurrentHashMap<String, SourceBackedFnRewriteNumberExpression>()

    /** Compiles [FnRewriteNumberContext.expression] if this is the first sighting of it, then evaluates it. */
    override fun invoke(context: FnRewriteNumberContext): Double? =
        expressions.computeIfAbsent(context.expression) {
            SourceBackedFnRewriteNumberExpression(boxId = boxId, source = it)
        }.invoke(context)
}

/**
 * Wraps a snippet of Kotlin source as a rewrite-number evaluator bound to [box], compiled lazily on first
 * use.
 *
 * The way to build one evaluator by hand — to reuse across several rewrites, or to hand a persisted
 * expression back its box. Nothing is compiled or even checked here, so a syntax error surfaces at the first
 * matched node, and a null [box] produces an object that throws on first use rather than one that silently
 * does nothing.
 */
fun fnRewriteNumberExpression(
    source: String,
    box: Box? = Box.context.get()
): SourceBackedFnRewriteNumberExpression =
    SourceBackedFnRewriteNumberExpression(boxId = box?.id ?: "", source = source)

/**
 * A rewrite-number evaluator written as an ordinary Kotlin lambda over [FnRewriteNumberContext], with no
 * compilation involved — the typed alternative to a string of source.
 *
 * The block runs directly, so it can close over anything in scope, and returning null from it means "leave
 * this value alone" — the node is visited, nothing is written, and the walk carries on. Returning the current
 * `value` instead is equivalent but records a change.
 */
fun fnRewriteNumber(block: FnRewriteNumberContext.() -> Number?): SourceBackedFnRewriteNumberExpression =
    SourceBackedFnRewriteNumberExpression(
        boxId = Box.context.get()?.id ?: "",
        source = "",
        block = { context -> context.block() }
    )

/**
 * [Functions.Fn.rewrite] with the live box's Kotlin evaluator wired in — the version to use in Field code,
 * and the only one where a `@{...}` right-hand side actually runs.
 *
 * The snippets are compiled in [box]'s coding context, so they see its imports, its top-level values and its
 * helper functions. When no box can be found (the default looks one up in `Box.context`) no evaluator is
 * installed at all and Kotlin actions are silently skipped, leaving the rest of the spec to apply — quiet by
 * design, and worth remembering when a rewrite run outside a box appears to do nothing. As forgiving about
 * malformed rules as [Functions.Fn.rewrite]; see [rewriteKotlinChecked] for the strict form.
 */
fun Functions.Fn.rewriteKotlin(
    spec: String,
    preserveSharing: Boolean = false,
    box: Box? = Box.context.get()
): Functions.Fn = rewrite(spec, kotlinRewriteOptions(preserveSharing, box))

/**
 * [Functions.Fn.previewRewrite] with the live box's Kotlin evaluator wired in: the same rewrite
 * `rewriteKotlin` performs, but keeping the diagnostics, matched paths and no-ops.
 *
 * The one to read before committing, because it is the only place the quiet failures of this path become
 * visible — an expression that returned the value unchanged shows up as `matched/no-op`, and an unmatched
 * selector as an unmatched rule. Preview and commit compile in the same box, so they see the same live state.
 */
fun Functions.Fn.previewRewriteKotlin(
    spec: String,
    preserveSharing: Boolean = false,
    box: Box? = Box.context.get()
): FnRewritePreview = previewRewrite(spec, kotlinRewriteOptions(preserveSharing, box))

/**
 * [Functions.Fn.rewriteChecked] with the live box's Kotlin evaluator wired in — throws for a spec whose
 * parse produced any error, rather than applying whatever rules happened to parse.
 *
 * Throws `IllegalArgumentException` with the diagnostics attached. It checks the *rewrite language* only:
 * the Kotlin inside `@{...}` is compiled lazily at the first node that matches, so a snippet that does not
 * compile, or returns something that is not a number, throws from there and not from here.
 */
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

/**
 * [Functions.Fn.rewriteKotlin] with the box named explicitly instead of looked up: `box.rewriteFn(f, spec)`.
 *
 * For code that is not running inside the box whose context the snippets should compile in — a helper on
 * another thread, or an agent driving a specific box — where `Box.context` would be empty or wrong.
 */
fun Box.rewriteFn(
    fn: Functions.Fn,
    spec: String,
    preserveSharing: Boolean = false
): Functions.Fn =
    fn.rewriteKotlin(spec, preserveSharing = preserveSharing, box = this)

/**
 * [Functions.Fn.previewRewriteKotlin] with the box named explicitly: the reporting form of [rewriteFn], for
 * the same off-context callers.
 */
fun Box.previewRewriteFn(
    fn: Functions.Fn,
    spec: String,
    preserveSharing: Boolean = false
): FnRewritePreview =
    fn.previewRewriteKotlin(spec, preserveSharing = preserveSharing, box = this)
