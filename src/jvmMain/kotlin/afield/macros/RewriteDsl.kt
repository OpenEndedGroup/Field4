@file:afield.CompletionCategory("Workspace")

package afield.macros

/**
 * The rule-authoring surface for the pattern-rewrite layer (see [PatternRewrite]).
 *
 * These declarations are *erased*: a `rewrite { … }` block is collected and deleted from the
 * source by [PatternRewrite.expand] **before** the K2 REPL ever compiles it, so none of these
 * functions is ever actually called at run time. They exist only so a rule authored in a live box
 *   - parses,
 *   - is importable (`import afield.macros.*`), and
 *   - reads sensibly (the names `rewrite` / `from` / `to` / `Expr` resolve to something).
 *
 * The whole point (see [PatternRewrite]) is that a rule body never needs to *type-check* — only
 * parse. PSI parsing runs no name resolution, so `from { max(x, y) }` parses into a valid tree even
 * though `max`, `x`, `y` resolve to nothing. If one of these functions is somehow reached at run
 * time it means expansion did not happen, which is a bug — hence the throw.
 *
 * Surface:
 * ```
 * rewrite { x: Expr, y: Expr ->
 *     from { max(x, y) }
 *     to   { if (x >= y) x else y }
 * }
 * ```
 * The lambda's value parameters (`x: Expr, y: Expr`) are the metavariable declarations: inside
 * `from`/`to`, a name reference is a hole iff its name is in that parameter set, and its declared
 * type names the [MetaKind]. No `unquote`/`$`/`,` escape is needed — the known metavar set *is* the
 * escape.
 */

/** The kind of syntax a metavariable is allowed to bind. Used for early rejection + readability. */
sealed interface MetaKind

/** Any expression (`KtExpression`). The common case. */
object Expr : MetaKind

/** A simple name reference (`KtNameReferenceExpression`). */
object Ident : MetaKind

/** A statement-position element. Treated like [Expr] in v1. */
object Stmt : MetaKind

/** A type reference (`KtTypeReference`). */
object TypeRef : MetaKind

/**
 * A variadic hole: binds a *run* of call arguments (zero or more). Only meaningful inside an
 * argument list. In `from` it consumes the matching arguments; in `to` it expands to them,
 * comma-separated. Example:
 * ```
 * rewrite { first: Expr, rest: Vararg ->
 *     from { log(first, rest) }
 *     to { logger.info(first, rest) }
 * }
 * log(tag, a, b, c)   →   logger.info(tag, a, b, c)
 * ```
 */
object Vararg : MetaKind

private fun erased(what: String): Nothing =
    throw MacroRegistry.MacroExpansionProblem(
        "`$what` is expanded at macro time and must never be called directly — " +
                "a live call means the surrounding `rewrite {}` block was not collected/deleted before compile"
    )

/**
 * Declare a rewrite rule. Collected and deleted before compilation; see [PatternRewrite].
 *
 * The lambda's parameters declare the metavariables; its body must contain exactly one [from] call
 * and one [to] call. The parameter *types* ([Expr]/[Ident]/[Stmt]/[TypeRef]) are read textually by
 * the collector, so exact functional-type conformance of this signature is irrelevant — the block
 * parses and is stripped regardless.
 */
fun rewrite(rule: () -> Unit): Unit = erased("rewrite")

/**
 * Declare a *named* rewrite rule, so a [strategy] block can refer to it by [name]. Otherwise
 * identical to [rewrite] — same `from`/`to`/`where`/`provide`/`ai` body, same erase-before-compile
 * treatment. The name is read textually by the collector (a string literal), so — like the metavar
 * types — it never needs to type-check.
 * ```
 * rule("negate") { x: Expr -> from { neg(x) }; to { -x } }
 * strategy { repeat("negate") }
 * ```
 */
fun rule(name: String, rule: () -> Unit): Unit = erased("rule")

/**
 * A controllable term-rewriting strategy over the box's named [rule]s (see [PatternRewrite.Strat]).
 *
 * With NO `strategy {}` block the engine keeps its historical behaviour: a **global fixpoint** that
 * fires every rule at every match, everywhere, until nothing changes. A `strategy {}` block replaces
 * that with an explicit control program — only rules it names fire, in the order and at the sites it
 * dictates. The body is a single strategy expression built from the combinators below; it is erased
 * and blanked before compilation, so it only needs to parse.
 * ```
 * rule("unwrap") { x: Expr -> from { f(x) }; to { g(x) } }
 * strategy { bottomup("unwrap") }     // rewrite the innermost f(...) only
 * ```
 * Fail-closed: an unknown rule name or a malformed program rewrites NOTHING and logs a diagnostic.
 */
fun strategy(block: () -> Unit): Unit = erased("strategy")

/** Opaque result of a strategy combinator. Erased — a strategy block never actually runs. */
class Strategy internal constructor()

private fun erasedStrategy(what: String): Strategy = erased(what)

/** Fire rule [name] at the single first (outermost) match — at most once. */
fun once(name: String): Strategy = erasedStrategy("once")

/** Fire rule [name] at the single outermost (shallowest) match site. */
fun topdown(name: String): Strategy = erasedStrategy("topdown")

/**
 * Fire rule [name] at the single innermost (deepest) match site — differs from [topdown] on nested
 * matches (given `f(f(a))`, `topdown` rewrites the outer call, `bottomup` the inner one).
 */
fun bottomup(name: String): Strategy = erasedStrategy("bottomup")

/** Run [s] once; if it does not apply, fall back to identity. Never fails. (Spelled `attempt`
 *  because `try` is a Kotlin hard keyword and cannot be a call name.) */
fun attempt(s: Any?): Strategy = erasedStrategy("attempt")

/** Iterate [s] to a fixpoint (or at most [n] times). Never fails. */
fun repeat(s: Any?, n: Int): Strategy = erasedStrategy("repeat")

/** Iterate [s] to a fixpoint (unbounded within the engine's iteration cap). Never fails. */
fun repeat(s: Any?): Strategy = erasedStrategy("repeat")

/** Run each strategy in order, threading the result; fails as soon as one fails. See also [then]. */
fun seq(vararg s: Any?): Strategy = erasedStrategy("seq")

/** Run the first alternative that succeeds; fails only if none apply. */
fun choice(vararg s: Any?): Strategy = erasedStrategy("choice")

/** Infix sequencing: `a then b` runs `a`, then `b` on its result (fail-fast). */
infix fun Any?.then(other: Any?): Strategy = erasedStrategy("then")

/** The pattern (left-hand side) of a [rewrite] rule. Its body is quasiquoted, never evaluated. */
fun from(pattern: () -> Unit): Unit = erased("from")

/** The replacement (right-hand side) of a [rewrite] rule. Its body is quasiquoted, never evaluated. */
fun to(replacement: () -> Unit): Unit = erased("to")

/**
 * Procedural unquote: inside a `to {}` body, `unquote { … }` runs Kotlin **at expansion time** and
 * splices the block's `toString()` result into the emitted source **as Kotlin source code**. Each
 * metavariable is available inside the block as a `String` holding its captured source text, so
 * captures can be transformed as strings. The block runs in the box's REPL context, so it can call
 * box-scoped helpers.
 *
 * IMPORTANT: the result is spliced verbatim as *code*, so produce valid Kotlin. To emit a string
 * *value*, produce a quoted literal — e.g. reversing an identifier into a string literal:
 * ```
 * rewrite { x: Ident ->
 *     from { rev(x) }
 *     to { unquote { "\"" + x.reversed() + "\"" } }
 * }
 * rev(hello)   →   "olleh"        // a String literal, not a bare identifier
 * ```
 * (Emitting `unquote { x.reversed() }` would splice the bare token `olleh`, an unresolved reference.)
 *
 * Evaluation is fail-soft: on the execute path a failing block becomes a compiling `error(...)`
 * stand-in with a logged diagnostic; in editor-preview expansions the call becomes `TODO()` (a
 * well-typed placeholder) since user code is not run there.
 */
fun <T> unquote(block: () -> T): T = erased("unquote")

/**
 * An optional preamble contribution. When the rule fires, the declarations in this block are
 * metavar-substituted (no hygiene rename — these are meant to define stable box-level names) and
 * merged into the box preamble under a stable, content-derived key, so repeated runs deduplicate
 * and the segment only re-executes when its content changes (see the `/*prefix*/` "stack mark"
 * idempotency mechanism). Example:
 * ```
 * rewrite { n: Ident ->
 *     from { counter(n) }
 *     provide { var Box.n by Counting() }
 *     to { n.bump() }
 * }
 * ```
 */
fun provide(preamble: () -> Unit): Unit = erased("provide")

/**
 * An optional cached AI request. When the rule fires, the (metavar-substituted) prompt is resolved
 * once against an AI model and the generated code is cached into the box `attic` under a stable
 * key; subsequent runs with the same prompt reuse the cached block with no model call. The `to {}`
 * side forwards to whatever the generated code defines. Example:
 * ```
 * rewrite { x: Expr ->
 *     from { explain(x) }
 *     ai("write fun __explain(v: Any?): String that summarizes ${x}")
 *     to { __explain(x) }
 * }
 * ```
 */
fun ai(prompt: String): Unit = erased("ai")

/**
 * An optional guard: the rule fires only if the (structural) predicate over the captured
 * metavariables holds. The body is a single boolean expression built from the predicates below,
 * combined with `&&`, `||`, `!`, and parentheses — for example:
 * ```
 * rewrite { x: Expr, y: Expr ->
 *     from { swap(x, y) }
 *     where { distinct(x, y) && !isLiteral(x) }
 *     to { swap(y, x) }
 * }
 * ```
 * Guards are evaluated structurally at macro time (no type-checking / no code execution), so only
 * the fixed predicate vocabulary below is understood.
 */
fun where(guard: () -> Boolean): Unit = erased("where")

private fun erasedBool(what: String): Boolean = erased(what)

/** True iff all listed metavariable captures are pairwise structurally distinct. */
fun distinct(vararg metavars: Any?): Boolean = erasedBool("distinct")

/** True iff the two captures are structurally identical. */
fun identical(a: Any?, b: Any?): Boolean = erasedBool("identical")

/** True iff the capture is a literal (numeric/boolean/char constant or a string template). */
fun isLiteral(metavar: Any?): Boolean = erasedBool("isLiteral")

/** True iff the capture is a bare identifier / simple name reference. */
fun isIdentifier(metavar: Any?): Boolean = erasedBool("isIdentifier")

/** True iff the capture is a function or constructor call. */
fun isCall(metavar: Any?): Boolean = erasedBool("isCall")

/**
 * True iff the capture is a call whose callee has the given [calleeName]. Handy for the
 * type-directed coercion idiom — wrap every `f(x)` in a runtime adapter but guard against
 * re-wrapping the adapter you just introduced, so the fixpoint terminates:
 * ```
 * rewrite { x: Expr ->
 *     from { f(x) }
 *     where { !isCallTo(x, "coerce") }
 *     to { f(coerce(::f, SomeType::class, x)) }
 * }
 * ```
 */
fun isCallTo(metavar: Any?, calleeName: String): Boolean = erasedBool("isCallTo")
