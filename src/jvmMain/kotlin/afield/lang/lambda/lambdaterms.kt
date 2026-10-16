@file:Suppress("MemberVisibilityCanBePrivate", "unused")
@file:afield.runtime.completion.CompletionCategory("Visualization")

package afield.lang.lambda

import afield.animation.keyframes.h
import afield.data.collections.get
import afield.data.collections.invoke
import afield.data.collections.m
import afield.data.collections.times
import afield.document.context.why
import afield.document.graph.minusAssign
import afield.document.graph.named
import afield.document.graph.plusAssign
import afield.document.properties.compareTo
import afield.document.workspace.file
import afield.geometry.mesh.bounds
import afield.geometry.mesh.toFLines
import afield.geometry.spatial.toFLines
import afield.graphics.render.rangeTo
import afield.graphics.text.toFLines
import afield.lang.stacksl.h
import afield.math.autodiff.compareTo
import afield.math.autodiff.div
import afield.math.autodiff.exp
import afield.math.autodiff.mean
import afield.math.autodiff.minus
import afield.math.autodiff.plus
import afield.math.autodiff.round
import afield.math.autodiff.times
import afield.math.linalg.f
import afield.math.linalg.invoke
import afield.math.linalg.remAssign
import afield.math.linalg.set
import afield.math.linalg.times
import afield.math.linalg.x
import afield.math.units.div
import afield.math.units.minus
import afield.math.units.plus
import afield.math.units.times
import afield.media.image.close
import afield.media.image.open
import afield.media.image.toFLines
import afield.runtime.execution.end
import afield.runtime.execution.go
import afield.runtime.lifecycle.once
import afield.simulation.agents.step
import afield.simulation.agents.toFLines
import afield.simulation.automata.step
import afield.ui.output.h

import afield.audio.synth.*
import afield.data.collections.s
import afield.data.graph.ActivationOrder
import afield.data.graph.Lambda
import afield.data.graph.Net
import afield.data.graph.Node
import afield.data.graph.NodeState
import afield.data.graph.Port
import afield.data.graph.Reducer
import afield.data.graph.net
import afield.data.graph.rules
import afield.document.workspace.path
import afield.graphics.render.label
import afield.graphics.text.layout
import afield.graphics.vulkan.buffers.add
import afield.math.control.params
import afield.math.linalg.Vec2
import afield.math.linalg.arg
import afield.math.linalg.i
import afield.math.linalg.vec
import afield.runtime.execution.start
/**
 * λ-terms as a front end to the interaction nets in `interactionnets.kt`: text in, a reduced net out, a term back.
 *
 * The shortest useful thing is `LambdaTerms.normalise`, which runs parse, compile, reduce and readback in one call:
 *
 *     val run = LambdaTerms.normalise("(\\m n f x. m f (n f x)) (\\f x. f x) (\\f x. f (f x))")
 *     run.printed   // "λa. λb. a (a (a b))", Church three, from add one two
 *     run.steps     // how many reactions it took
 *
 * Two public names sit behind that. [Term] is the syntax tree, with `Var`, `Lam` and `App` nested inside it so that
 * `Term.Lam("x", Term.Var("x"))` reads as what it is and none of the three short names leaks into `afield.lang.lambda`.
 * [LambdaTerms] is everything done to one: parsing, printing, compilation to the [Lambda] agent vocabulary, readback,
 * and the reference evaluator the net's answer is checked against.
 *
 * Every answer here is computed twice. The net is one route; `LambdaTerms.referenceNormalOrder` is the other, and
 * reduces by capture-avoiding substitution with no nets and no sharing anywhere in it, so the two routes share no
 * machinery beyond the term type. `LambdaTerms.corpus` is the set of closed terms both are run over.
 *
 * The file lands on the Visualization page because a finished run keeps the [Net] it reduced, and `Net.toFLines`
 * draws that net with its active pairs emphasised.
 */

/**
 * A λ-term: a variable, an abstraction, or an application.
 *
 * Three nested classes, so `Term.Lam("x", Term.Var("x"))` reads as what it is and none of the three short names
 * leaks into `afield.lang.lambda` on its own. [LambdaTerms.parse] builds one from text and [toString] prints it back.
 *
 * Structural `equals`, since these are data classes — but **compare terms with [LambdaTerms.alphaEquivalent]**,
 * because `λx. x` and `λy. y` are not `==` and a readback invents its own binder names.
 */
sealed interface Term {
    /** A variable occurrence, bound or free. The name is the source name, or one a readback invented. */
    data class Var(val name: String) : Term {
        override fun toString(): String = LambdaTerms.format(this)
    }

    /** An abstraction: `λ`[param]`.` [body]. */
    data class Lam(val param: String, val body: Term) : Term {
        override fun toString(): String = LambdaTerms.format(this)
    }

    /** An application of [fn] to [arg]. Applications associate to the left, so `f a b` is `App(App(f, a), b)`. */
    data class App(val fn: Term, val arg: Term) : Term {
        override fun toString(): String = LambdaTerms.format(this)
    }
}

/**
 * A λ-calculus front end for `interactionnets.kt`: a [Term] syntax you can type, a compiler from a term to a [Net], a
 * readback from a reduced net to a term, and — the part that makes the rest checkable — an ordinary substitution
 * evaluator to compare the net's answer against.
 *
 * The shortest useful thing is [normalise]:
 *
 * ```
 * val run = LambdaTerms.normalise("(\\m n f x. m f (n f x)) (\\f x. f x) (\\f x. f (f x))")
 * run.printed   // "λa. λb. a (a (a b))" — Church three, from add one two
 * run.steps     // how many reactions it took
 * ```
 *
 * The three pieces, separately:
 *
 * - [parse] reads `\x. x x` or `λx. x x`, with `\x y. e` as sugar for `\x. \y. e`, application to the left, and
 *   parentheses. [format] prints it back, and round-trips.
 * - [compile] turns a [Term] into a net over the [Lambda] vocabulary: an `APP` per application, a `LAM` per
 *   abstraction, a **tree of labelled `DUP` fans** per variable used more than once, an `ERASE` per binder whose
 *   variable is never used, and one `ROOT` holding the answer. [readback] walks a reduced net back to a term.
 * - [referenceNormalOrder] is the oracle: leftmost-outermost reduction to full normal form by capture-avoiding
 *   substitution, with no nets and no sharing anywhere in it. It exists in order to disagree with the net.
 *
 * Terms are compared with [alphaEquivalent], not with `==`, because a readback invents its own binder names —
 * [deBruijnKey] is the canonical form both sides go through, and is the thing worth printing when a comparison fails.
 *
 * ### What this gets right, and what it does not
 *
 * The sharing is Lambdascope's (van Oostrom, van de Looij and Zwitserlood, 2004): optimal and correct. Fans and
 * scope delimiters carry levels, and `Lambda.rules()` annihilates two of one kind and level and commutes the rest,
 * raising a level as it passes an abstraction or a delimiter at or below it. Against [referenceNormalOrder], the
 * corpus, the four `LambdaSoundDocs.CONFLUENCE_PROBES` and every generated simply-typed term agree; on some untyped
 * terms [readback] cannot place a fan and marks it `⋈` (see `dev_docs/INTERACTION_NETS_LAMBDA.md`). [Run.steps]
 * counts every reaction, delimiter moves included, so it is not a count of β-steps.
 *
 * An open term compiles too: each *occurrence* of a free variable becomes its own [Lambda.VAR] agent, deliberately
 * unshared, carrying its name's index in its state ([FREE_VARIABLE]). `dup-var` copies that state into the fresh
 * agents it builds, so a duplicated free variable still reads back by name. [corpus] is closed throughout, and
 * asserts that it is.
 */
object LambdaTerms {

    // =========================
    // Building terms in Kotlin
    // =========================

    /** `Term.Var(name)`, for when [parse] is more ceremony than the term is worth. */
    fun v(name: String): Term = Term.Var(name)

    /** `λ`[params]`.` [body], right-nested — `lam("x", "y", body = v("x"))` is `λx. λy. x`. */
    fun lam(vararg params: String, body: Term): Term = params.foldRight(body) { p, acc -> Term.Lam(p, acc) }

    /** [fn] applied to each of [args] in turn, left-associated — `app(f, a, b)` is `f a b`. */
    fun app(fn: Term, vararg args: Term): Term = args.fold(fn) { acc, a -> Term.App(acc, a) }

    // =========================
    // Printing
    // =========================

    /**
     * A term as source text, with the fewest parentheses that still parse back to the same term: `λa. λb. a (a b)`.
     *
     * Round-trips exactly — `parse(format(t)) == t`, not merely α-equivalent, since the printer never renames.
     * [Term.toString] calls this, so a term interpolates into a message readably.
     */
    fun format(term: Term): String = layout(term, functionPosition = false, argumentPosition = false)

    private fun layout(term: Term, functionPosition: Boolean, argumentPosition: Boolean): String = when (term) {
        is Term.Var -> term.name
        // An abstraction's body runs as far right as it can, so anything at all to its right forces brackets.
        is Term.Lam -> {
            val inner = "λ${term.param}. " + layout(term.body, functionPosition = false, argumentPosition = false)
            if (functionPosition || argumentPosition) "($inner)" else inner
        }
        // Application associates left, so the function side never needs brackets and the argument side always does.
        is Term.App -> {
            val inner = layout(term.fn, functionPosition = true, argumentPosition = false) +
                " " + layout(term.arg, functionPosition = false, argumentPosition = true)
            if (argumentPosition) "($inner)" else inner
        }
    }

    // =========================
    // Parsing
    // =========================

    /** What [parse] throws, carrying the character [offset] it gave up at. */
    class ParseFailure(message: String, val offset: Int) : IllegalArgumentException("$message (at offset $offset)")

    /**
     * Reads a term.
     *
     * The syntax: `\` or `λ` starts an abstraction, one or more binder names follow, then `.`, then the body, which
     * extends as far right as it can. Application is juxtaposition and associates to the left. Parentheses group.
     * Names are letters, digits, `_` and `'`, so `f`, `x1` and `n'` are each one name. Whitespace, newlines included,
     * is insignificant.
     *
     * `\x y. y x` and `λx. λy. y x` parse to the same term.
     *
     * @throws ParseFailure on anything it cannot read, naming the offset
     */
    fun parse(source: String): Term {
        val parser = Parser(source)
        val term = parser.term()
        parser.skipSpace()
        if (!parser.atEnd()) throw ParseFailure("Unexpected '${parser.peek()}'", parser.at)
        return term
    }

    private class Parser(val src: String) {
        var at = 0

        fun atEnd(): Boolean = at >= src.length

        fun peek(): Char = src[at]

        fun skipSpace() {
            while (at < src.length && src[at].isWhitespace()) at++
        }

        fun term(): Term {
            skipSpace()
            if (atEnd()) throw ParseFailure("Expected a term, found end of input", at)
            return if (startsAbstraction()) abstraction() else application()
        }

        fun startsAbstraction(): Boolean = !atEnd() && (peek() == '\\' || peek() == 'λ')

        fun abstraction(): Term {
            at++ // past the λ marker
            val params = ArrayList<String>()
            while (true) {
                skipSpace()
                if (atEnd()) throw ParseFailure("Expected '.' to close the binders", at)
                if (peek() == '.') break
                params += name()
            }
            if (params.isEmpty()) throw ParseFailure("A λ needs at least one binder", at)
            at++ // past the '.'
            return params.foldRight(term()) { p, acc -> Term.Lam(p, acc) }
        }

        fun application(): Term {
            var acc = atom() ?: throw ParseFailure("Expected a term", at)
            while (true) {
                skipSpace()
                // An abstraction in argument position swallows the rest of the input, so it is the last operand.
                if (startsAbstraction()) return Term.App(acc, abstraction())
                acc = Term.App(acc, atom() ?: return acc)
            }
        }

        /** One atom, or null where the next character cannot start one — which is how [application] knows to stop. */
        fun atom(): Term? {
            skipSpace()
            if (atEnd()) return null
            return when {
                peek() == '(' -> {
                    at++
                    val inner = term()
                    skipSpace()
                    if (atEnd() || peek() != ')') throw ParseFailure("Expected ')'", at)
                    at++
                    inner
                }
                isNameChar(peek()) -> Term.Var(name())
                else -> null
            }
        }

        fun name(): String {
            skipSpace()
            val start = at
            while (at < src.length && isNameChar(src[at])) at++
            if (at == start) {
                val found = if (atEnd()) "end of input" else "'${peek()}'"
                throw ParseFailure("Expected a name, found $found", at)
            }
            return src.substring(start, at)
        }

        fun isNameChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '\''
    }

    // =========================
    // α-equivalence
    // =========================

    /**
     * The term's de Bruijn form as a string: `λλ(#1 (#1 #0))` for Church two, with free variables as `@name`.
     *
     * Two terms are α-equivalent exactly when these strings are equal, which is all [alphaEquivalent] is. Worth
     * printing when an oracle comparison fails, because it shows *where* the two shapes diverged and not merely that
     * they did.
     */
    fun deBruijnKey(term: Term): String = key(term, emptyList())

    // `bound` is innermost-binder-first because [key] prepends, which is exactly the de Bruijn index.
    private fun key(term: Term, bound: List<String>): String = when (term) {
        is Term.Var -> bound.indexOf(term.name).let { if (it >= 0) "#$it" else "@${term.name}" }
        is Term.Lam -> "λ" + key(term.body, listOf(term.param) + bound)
        is Term.App -> "(" + key(term.fn, bound) + " " + key(term.arg, bound) + ")"
    }

    /** True when two terms differ only in the names of their bound variables. Compare terms with this, not with `==`. */
    fun alphaEquivalent(a: Term, b: Term): Boolean = deBruijnKey(a) == deBruijnKey(b)

    // =========================
    // The oracle
    // =========================

    /** What [referenceNormalOrder] throws when its budget runs out — which, for a diverging term, is the only outcome. */
    class ReductionBudgetExceeded(val steps: Int) :
        IllegalStateException("Normal-order reduction did not terminate within $steps beta-steps")

    /**
     * What [referenceNormalOrder] throws when the terms it is building get too big, which a β-step budget does not
     * bound: one substitution can nearly double a term, so a reduction can exhaust memory hundreds of steps inside a
     * budget of thousands.
     *
     * Measured 2026-09-19: a generated term took 3.3 GB and was still going, 300 β-steps into a 300-step budget. The
     * two budgets are different questions — "is this term diverging" and "is answering it affordable" — and only the
     * first one had an answer before.
     */
    class IntermediateTermTooLarge(val nodes: Int) :
        IllegalStateException("Normal-order reduction built more than $nodes intermediate term nodes")

    /**
     * The **oracle**: [term]'s normal form under leftmost-outermost (normal-order) reduction, by capture-avoiding
     * substitution.
     *
     * No nets, no sharing and no cleverness — two copies of one redex are reduced once each, which is the entire
     * point of having this. Normal order rather than applicative order because it reaches a normal form whenever one
     * exists, so a disagreement with the net is a bug in the net rather than a difference of strategy.
     *
     * Two budgets, because they answer different questions. [maxSteps] is "did this term diverge". [maxWorkNodes] is
     * "was answering it affordable": every substitution's result is charged against it, so it bounds the *work* and
     * therefore the memory, which a step count does not — an unshared reducer's intermediate terms can grow
     * geometrically while the step counter is still in the low hundreds. Both defaults are loose enough that the
     * 25-term [corpus] never sees either.
     *
     * @throws ReductionBudgetExceeded after [maxSteps] β-steps
     * @throws IntermediateTermTooLarge after [maxWorkNodes] nodes of substitution output
     */
    fun referenceNormalOrder(term: Term, maxSteps: Int = 1_000_000, maxWorkNodes: Int = 40_000_000): Term =
        normalForm(term, intArrayOf(maxSteps), maxSteps, intArrayOf(maxWorkNodes), maxWorkNodes)

    private fun normalForm(term: Term, fuel: IntArray, budget: Int, work: IntArray, workBudget: Int): Term =
        when (val head = weakHead(term, fuel, budget, work, workBudget)) {
            is Term.Var -> head
            is Term.Lam -> Term.Lam(head.param, normalForm(head.body, fuel, budget, work, workBudget))
            // `head.fn` is already in weak head normal form and is not an abstraction, so this cannot loop.
            is Term.App -> Term.App(
                normalForm(head.fn, fuel, budget, work, workBudget),
                normalForm(head.arg, fuel, budget, work, workBudget)
            )
        }

    private fun weakHead(term: Term, fuel: IntArray, budget: Int, work: IntArray, workBudget: Int): Term {
        var current = term
        while (true) {
            if (current !is Term.App) return current
            val fn = weakHead(current.fn, fuel, budget, work, workBudget)
            if (fn !is Term.Lam) return Term.App(fn, current.arg)
            if (fuel[0]-- <= 0) throw ReductionBudgetExceeded(budget)
            current = substitute(fn.body, fn.param, current.arg)
            // Charged after the fact rather than predicted: the size of a substitution's result is not a function of
            // its inputs' sizes alone, since a variable may occur any number of times in the body.
            work[0] -= sizeOf(current)
            if (work[0] < 0) throw IntermediateTermTooLarge(workBudget)
        }
    }

    // Nodes in a term. Iterative rather than recursive: the terms this is asked about are exactly the oversized ones,
    // and a recursive count on a term deep enough to matter is a StackOverflowError instead of an answer.
    private fun sizeOf(term: Term): Int {
        var n = 0
        val stack = ArrayDeque<Term>()
        stack.addLast(term)
        while (stack.isNotEmpty()) {
            n += 1
            when (val t = stack.removeLast()) {
                is Term.Var -> {}
                is Term.Lam -> stack.addLast(t.body)
                is Term.App -> {
                    stack.addLast(t.fn)
                    stack.addLast(t.arg)
                }
            }
        }
        return n
    }

    /**
     * `body[name := value]`, renaming any binder of [body] that would otherwise capture a free variable of [value].
     *
     * Public because it is the one piece of the oracle worth testing on its own: the capture case is the only place a
     * substitution evaluator is ever wrong, and `substitute(parse("λy. x y"), "x", parse("y"))` is that test.
     */
    fun substitute(body: Term, name: String, value: Term): Term =
        // `value` does not change as the walk descends, so its free variables are computed once here rather than at
        // every abstraction on the way down. That is not a micro-optimisation: recomputing it per Lam makes one
        // substitution cost O(|body| x |value|), which is why a reduction whose *step* count is bounded could still
        // run for minutes. Measured 2026-09-19 on the generated corpus in `stdlib.lambda-terms.property`.
        substituteUnder(body, name, value, freeVariablesOf(value))

    private fun substituteUnder(body: Term, name: String, value: Term, valueFree: Set<String>): Term = when (body) {
        is Term.Var -> if (body.name == name) value else body
        is Term.App -> Term.App(
            substituteUnder(body.fn, name, value, valueFree),
            substituteUnder(body.arg, name, value, valueFree)
        )
        is Term.Lam -> when {
            body.param == name -> body // shadowed: nothing inside refers to the outer `name`
            body.param in valueFree -> {
                val taken = valueFree + freeVariablesOf(body.body) + name
                val fresh = freshName(body.param, taken)
                // The renaming pass is a substitution in its own right, and `Term.Var(fresh)` has exactly one free
                // name, so it goes through the same entry point rather than reusing `valueFree`.
                Term.Lam(fresh, substituteUnder(substitute(body.body, body.param, Term.Var(fresh)), name, value, valueFree))
            }
            else -> Term.Lam(body.param, substituteUnder(body.body, name, value, valueFree))
        }
    }

    /** The names occurring free in [term]. Empty for a closed term, which is what [corpus] asserts of every entry. */
    fun freeVariablesOf(term: Term): Set<String> {
        // Accumulated into one set rather than built by `setOf` plus union per node: the union form allocates a fresh
        // HashSet at every application, which dominates the cost on the large intermediate terms a normal-order
        // reduction passes through. `bound` is the binders in scope, so a shadowed name is not reported free.
        val out = LinkedHashSet<String>()
        collectFree(term, ArrayList(), out)
        return out
    }

    private fun collectFree(term: Term, bound: MutableList<String>, out: MutableSet<String>) {
        when (term) {
            is Term.Var -> if (term.name !in bound) out += term.name
            is Term.Lam -> {
                bound.add(term.param)
                collectFree(term.body, bound, out)
                bound.removeAt(bound.size - 1)
            }
            is Term.App -> {
                collectFree(term.fn, bound, out)
                collectFree(term.arg, bound, out)
            }
        }
    }

    private fun freshName(base: String, taken: Set<String>): String {
        var i = 0
        while (true) {
            val candidate = if (i == 0) "$base'" else "$base'$i"
            if (candidate !in taken) return candidate
            i++
        }
    }

    // =========================
    // Compiling to a net
    // =========================

    /** The id [compile] gives the single `ROOT` agent, so [readback] can still find it after the net is rewritten. */
    const val ROOT_ID: String = "root"

    /**
     * A [Term] as a net, plus the little the compiler had to remember in order to read it back.
     *
     * [net] holds exactly one [Lambda.ROOT], whose principal port faces the term's value, and is ready for a
     * [Reducer] over [Lambda.rules].
     */
    class CompiledTerm internal constructor(
        /** The net. One `ROOT`, and nothing reduced yet. */
        val net: Net,
        /** Agent id of each free-variable occurrence, mapped to the name it came from. Empty for a closed term. */
        val freeVariables: Map<String, String>,
        /** How many labelled fans were built — the term's sharing structure as one number. */
        val fans: Int,
        /** How many `ERASE` agents were built — one per binder whose variable is never used. */
        val erasers: Int,
        /** The free variables' names, in first-seen order; a `VAR` agent's [FREE_VARIABLE] indexes this. */
        val freeNames: List<String> = emptyList(),
    ) {
        /** The `ROOT` agent holding the answer. */
        val root: Node get() = net.node(ROOT_ID)
    }

    /**
     * Compiles [term] to a net over the [Lambda] vocabulary.
     *
     * The translation is the usual one, in the orientation this file's rules expect. An application becomes an `APP`
     * whose principal port faces its function, `aux(1)` holds its argument and `aux(0)` faces the context — which is
     * what `beta-linear` reads. An abstraction becomes a `LAM` whose principal faces the context, `aux(0)` is the
     * binder and `aux(1)` the body.
     *
     * The interesting case is a variable, because an interaction net has no notion of a wire that forks. A binder used
     * *once* is a single wire from `LAM.aux(0)` to the use. Used *n* times it is a right-nested tree of `n - 1`
     * level-0 [Lambda.DUP] fans. On its way to the binder, an occurrence passes a level-0 [Lambda.DELIM] for every
     * abstraction between the two (every enclosing one, for a free variable): Lambdascope's scopes. Never used, it is a
     * [Lambda.ERASE]: the binder still has to go somewhere, and an interaction net may not simply drop a wire.
     *
     * Positions are seeded by syntactic depth so the net is legible before any [Net.relax] has run. That matters for a
     * figure and matters not at all for a headless reduction.
     */
    fun compile(term: Term): CompiledTerm {
        val frees = LinkedHashMap<String, String>()
        val freeNames = ArrayList<String>()
        var fans = 0
        var erasers = 0
        var delimiters = 0
        var placed = 0

        val built = net {
            // A binder's uses collect here while its body is compiled; shadowing works by save-and-restore.
            val scope = HashMap<String, MutableList<Port>>()
            // The parameters of the abstractions enclosing the term being built, innermost last.
            val enclosing = ArrayList<String>()

            fun spot(depth: Int): Vec2 {
                placed += 1
                return vec(((placed % 9) - 4) * 92.0, depth * 82.0 - 220.0)
            }

            // Wires a binder port to its uses: an ERASE for none, a bare wire for one, a fan tree for more.
            fun spread(source: Port, uses: List<Port>, depth: Int) {
                var remaining = uses
                var from = source
                var at = depth
                while (remaining.size > 1) {
                    fans += 1
                    val fan = node(Lambda.DUP, at = spot(at), state = Lambda.fan(0))
                    connect(from, fan.principal)
                    connect(fan.aux(0), remaining[0])
                    from = fan.aux(1)
                    remaining = remaining.drop(1)
                    at += 1
                }
                if (remaining.size == 1) {
                    connect(from, remaining[0])
                } else {
                    erasers += 1
                    val sink = node(Lambda.ERASE, at = spot(at), state = NodeState(energy = 0.5))
                    connect(from, sink.principal)
                }
            }

            fun build(t: Term, up: Port, depth: Int) {
                when (t) {
                    is Term.Var -> {
                        // Lambdascope: one level-0 scope delimiter for every abstraction between the occurrence
                        // and its binder (every enclosing one, for a free variable), inner port toward the use.
                        val bound = enclosing.lastIndexOf(t.name)
                        val crossed = if (bound >= 0) enclosing.size - 1 - bound else enclosing.size
                        var up = up
                        repeat(crossed) {
                            delimiters += 1
                            val scopeDelimiter = node(Lambda.DELIM, at = spot(depth), state = Lambda.fan(0))
                            connect(up, scopeDelimiter.aux(0))
                            up = scopeDelimiter.principal
                        }
                        val uses = scope[t.name]
                        if (uses != null) {
                            // A bound occurrence contributes a loose end; its binder wires it up on the way out.
                            uses += up
                        } else {
                            val index = freeNames.indexOf(t.name).takeIf { it >= 0 }
                                ?: freeNames.size.also { freeNames += t.name }
                            val free = node(
                                Lambda.VAR, at = spot(depth),
                                state = NodeState(energy = 0.8, scalars = linkedMapOf(FREE_VARIABLE to index.toDouble())),
                            )
                            frees[free.id] = t.name
                            connect(up, free.principal)
                        }
                    }

                    is Term.Lam -> {
                        val lam = node(Lambda.LAM, at = spot(depth), state = NodeState(energy = 1.2))
                        connect(up, lam.principal)
                        val shadowed = scope.put(t.param, ArrayList())
                        enclosing += t.param
                        build(t.body, lam.aux(1), depth + 1)
                        enclosing.removeAt(enclosing.lastIndex)
                        val uses = scope.remove(t.param) ?: ArrayList()
                        if (shadowed != null) scope[t.param] = shadowed
                        spread(lam.aux(0), uses, depth + 1)
                    }

                    is Term.App -> {
                        val ap = node(Lambda.APP, at = spot(depth), state = NodeState(energy = 1.0))
                        connect(up, ap.aux(0))
                        build(t.fn, ap.principal, depth + 1)
                        build(t.arg, ap.aux(1), depth + 1)
                    }
                }
            }

            val root = node(Lambda.ROOT, id = ROOT_ID, at = vec(0.0, -320.0), state = NodeState(energy = 0.6))
            build(term, root.principal, 0)
        }

        return CompiledTerm(built, frees, fans, erasers, freeNames)
    }

    /** [compile], on source text — `compile("\\x. x x")`. */
    fun compileSource(source: String): CompiledTerm = compile(parse(source))

    // =========================
    // Reading back
    // =========================

    /**
     * Walks a net back to a term, starting from the [Lambda.ROOT] agent's principal port.
     *
     * Binders are given fresh names (`a`, `b`, … then `v26`), so a readback is only ever [alphaEquivalent] to the term
     * you put in.
     *
     * The part that is not a plain tree walk is the fans. A normal form can still carry them, and a fan is not a node
     * of the term, it is a *bracket* on the path through it. So the walk carries a **stack of fan choices**. Arriving
     * at a fan from its auxiliary *i* means "the thing here is copy *i* of what the fan's principal points at": push
     * the choice and keep going through the principal. Arriving at a fan's *principal* is the matching close bracket:
     * take the most recent choice made at a fan of the same duplication, and descend into that branch. Which fans are
     * one duplication is what Lambdascope's levels say, read through [Scopes]: a level names a scope, and the walk
     * keeps the scopes it is in as it passes abstractions and delimiters. With no such choice pending the answer is
     * `⋈`; on some untyped terms the walk's scope bookkeeping is not enough and that happens.
     *
     * What it will not do is invent structure that is not there, and the six marks it can leave instead are all
     * things to fail a test over rather than paper over:
     *
     * | mark | what it means |
     * | --- | --- |
     * | `⊥` | an `ERASE` sat in a term position |
     * | `·` | a port with no wire at all |
     * | `⋈` | a fan's *principal* port sat in a term position with no matching fan choice on the stack |
     * | `∞` | the walk came back to a port already on its own path, with the same stack |
     * | `…` | the walk ran past [maxNodes], or past [maxDepth] frames |
     * | `?`*id* | a variable occurrence whose binder is not open on the walk's own path |
     *
     * The last one is the closedness invariant, and it is a mark rather than an assertion. **A readback of a closed
     * term is closed**: an occurrence lies inside its binder's body, so the binder is on the path the walk took to
     * reach it, and a lookup that misses is a net whose wiring is not a term. Reporting it in the answer rather than
     * throwing keeps `readback` total — a caller gets a term it can print, with the defect at the position that has
     * it — and is the same contract the other five marks already offer. [marksOf] is the check.
     *
     * @param freeVariables from [CompiledTerm.freeVariables]; a `VAR` or `CONST` agent absent from it reads back as
     * its own agent id
     * @throws IllegalStateException if the net has no `ROOT`
     */
    fun readback(
        net: Net,
        freeVariables: Map<String, String> = emptyMap(),
        maxNodes: Int = 200_000,
        maxDepth: Int = 3_000
    ): Term {
        val root = net.nodes.firstOrNull { it.id == ROOT_ID && it.type == Lambda.ROOT }
            ?: net.nodes.firstOrNull { it.type == Lambda.ROOT }
            ?: error("Net has no ROOT agent to read back from")
        return walk(
            net,
            root.principal,
            HashMap(),
            intArrayOf(0),
            freeVariables,
            intArrayOf(maxNodes),
            HashSet(),
            ArrayDeque(),
            maxDepth
        )
    }

    /**
     * [readback], on what [compile] returned — so the free-variable names come along by themselves, including on
     * the copies `dup-var` made of one, which carry its [FREE_VARIABLE] index rather than an id the map knows.
     */
    fun readback(compiled: CompiledTerm, maxNodes: Int = 200_000, maxDepth: Int = 3_000): Term {
        val names = HashMap(compiled.freeVariables)
        for (node in compiled.net.nodes) {
            if (node.type != Lambda.VAR || node.id in names) continue
            val index = node.state.scalars[FREE_VARIABLE]?.toInt() ?: continue
            compiled.freeNames.getOrNull(index)?.let { names[node.id] = it }
        }
        return readback(compiled.net, names, maxNodes, maxDepth)
    }

    /** The [NodeState.scalars] key a free variable's [CompiledTerm.freeNames] index lives under. */
    const val FREE_VARIABLE: String = "freeVariable"

    /** The five single-glyph marks of [readback]'s table. `?`*id* is the sixth and is matched by prefix. */
    val MARKS: Set<String> = setOf("⊥", "·", "⋈", "∞", "…")

    /**
     * Every [readback] mark in [term], as the glyphs of [readback]'s table — empty exactly when the answer is a term
     * the reducer is prepared to stand behind.
     *
     * This is the check to write next to a [normalise], and it is not the same question as "did it finish".
     * `Run.finished` says the reduction did not run out of reactions; this says the *readback* found a term where the
     * net was. Both can be true and the answer still wrong — the net's sharing is correct on simply-typed terms and
     * not in general — but neither being true means the answer is not one.
     *
     * A `?`*id* collapses to `"?"`, because the agent id after it identifies the offending port rather than the kind
     * of failure, and a node type the walk has no case for reports as its `type:port`.
     */
    fun marksOf(term: Term): Set<String> = when (term) {
        is Term.Var -> when {
            term.name in MARKS -> setOf(term.name)
            term.name.startsWith("?") -> setOf("?")
            ":" in term.name -> setOf(term.name.substringBefore(':') + ":port")
            else -> emptySet()
        }
        is Term.Lam -> marksOf(term.body)
        is Term.App -> marksOf(term.fn) + marksOf(term.arg)
    }

    // Port indices, for the `peer.index` tests below: 0 is the principal, and aux(i) is index i + 1.
    //
    // `path` holds the ports on the current walk, keyed together with the fan stack: a shared subterm is legitimately
    // visited more than once, and the *same* port under a *different* stack is a different place in the term, so
    // neither the port nor the stack identifies a loop on its own.
    private fun walk(
        net: Net,
        from: Port,
        binders: MutableMap<String, String>,
        next: IntArray,
        frees: Map<String, String>,
        fuel: IntArray,
        path: MutableSet<String>,
        fans: ArrayDeque<Pair<Double, Int>>,
        depthLeft: Int,
        scopes: Scopes = Scopes(),
    ): Term {
        if (fuel[0]-- <= 0 || depthLeft <= 0) return Term.Var("…")
        val peer = net.connected(from) ?: return Term.Var("·")
        val here = peer.id + "|" + fans.joinToString(",") { "${it.first}/${it.second}" } + "|" + scopes.key()
        if (!path.add(here)) return Term.Var("∞")
        try {
            val node = peer.node
            fun on(port: Port): Term = walk(net, port, binders, next, frees, fuel, path, fans, depthLeft - 1, scopes)
            return when {
                node.type == Lambda.LAM && peer.index == 0 -> {
                    val name = binderName(next[0]++)
                    // An occurrence of this variable is a walk that arrives *at* the binder port, from below.
                    //
                    // Saved on the way in and put back on the way out, which is the same thing `compile` does with
                    // its `shadowed` binding three hundred lines up. `binders` is one map for the whole walk and a
                    // *shared* abstraction is legitimately walked more than once — once per copy, each copy taking
                    // its own fresh name — so a visit that leaves its entry behind redirects every occurrence read
                    // after it to the inner copy's binder. That was a wrong variable in an answer that carried no
                    // mark at all; it is `quiet-wrong-branch` in `afield.examples.stdlib.LambdaPropertyOracleTest`.
                    //
                    // Restoring it also makes the `?` below mean something. `binders` now holds exactly the binders
                    // open on this walk's own path, so a lookup that misses is an occurrence outside its binder's
                    // body — which a net that is a term cannot contain.
                    val binder = node.aux(0).id
                    val shadowed = binders.put(binder, name)
                    // Inside the body, a fan from outside would have passed this abstraction and gone a level up:
                    // the body is a new scope at level 0.
                    val saved = scopes.save()
                    scopes.enter(0.0)
                    try {
                        Term.Lam(name, on(node.aux(1)))
                    } finally {
                        scopes.restore(saved)
                        if (shadowed == null) binders.remove(binder) else binders[binder] = shadowed
                    }
                }
                node.type == Lambda.LAM && peer.index == 1 -> Term.Var(binders[peer.id] ?: "?${peer.id}")
                node.type == Lambda.APP && peer.index == 1 -> Term.App(on(node.principal), on(node.aux(1)))
                // An open bracket: the thing at this term position is copy `branch` of whatever the fan's principal
                // points at, so remember which copy and go and read it.
                node.type == Lambda.DUP && peer.index != 0 -> {
                    val entry = scopes.tokenAt(Lambda.levelOf(node)) to (peer.index - 1)
                    fans.addLast(entry)
                    try {
                        on(node.principal)
                    } finally {
                        fans.removeLast()
                    }
                }
                // The matching close bracket. Which copy this term position means is not local information, and the
                // stack is where it is; a label that does not match the top of the stack means the reduction left a
                // fan standing that the term's shape cannot account for, and that is `⋈` rather than a guess.
                node.type == Lambda.DUP -> {
                    // The most recent pending choice at this fan's level: fans at other levels commute past it.
                    val level = Lambda.levelOf(node)
                    val token = scopes.tokenAt(level)
                    val at = fans.indexOfLast { it.first == token }
                    if (at < 0) {
                        Term.Var("⋈")
                    } else {
                        val entry = fans.removeAt(at)
                        try {
                            on(node.aux(entry.second))
                        } finally {
                            fans.add(at, entry)
                        }
                    }
                }
                // A scope delimiter is not a node of the term: walk through it, inward from its principal and
                // outward from its inner port.
                // Inward, a fan at or above the delimiter's level would go up one; outward, one above it comes down.
                node.type == Lambda.DELIM && peer.index == 0 -> {
                    val saved = scopes.save()
                    scopes.enter(Lambda.levelOf(node))
                    try { on(node.aux(0)) } finally { scopes.restore(saved) }
                }
                node.type == Lambda.DELIM -> {
                    val saved = scopes.save()
                    scopes.leave(Lambda.levelOf(node))
                    try { on(node.principal) } finally { scopes.restore(saved) }
                }
                node.type == Lambda.VAR || node.type == Lambda.CONST -> Term.Var(frees[node.id] ?: node.id)
                node.type == Lambda.ERASE -> Term.Var("⊥")
                else -> Term.Var("${node.type.name.lowercase()}:${peer.index}")
            }
        } finally {
            path.remove(here)
        }
    }

    /**
     * The scopes open at a point of the [readback] walk, which is what a fan's level means there: a fan at level
     * *n* belongs to the *n*-th scope, innermost first. Lambdascope numbers scopes rather than naming them, and a
     * level changes as a fan moves (past an abstraction, or a delimiter at or below its own level, it goes one up),
     * so two fans are halves of one duplication when their levels name the **same scope**, not when they are equal.
     *
     * The walk keeps that list as tokens. Entering an abstraction's body opens a scope at 0; entering through a
     * level-*d* delimiter opens one at *d*; leaving through one closes the scope at *d* and keeps it, and the next
     * scope entered is that one again — a beta's two delimiters are one scope, seen from the body and from the
     * argument. A pending fan choice records the token its fan's level named, and is matched by token.
     */
    internal class Scopes(
        private val open: ArrayList<Int> = ArrayList<Int>().apply { repeat(256) { add(it) } },
        private val left: ArrayList<Pair<Int, Int>> = ArrayList(),
        private var next: Int = 256,
    ) {
        fun tokenAt(level: Double): Double = level.toInt().let { if (it in open.indices) open[it] else -1 - it }.toDouble()
        fun enter(level: Double) {
            val at = left.indexOfLast { it.first == level.toInt() }
            val token = if (at >= 0) left.removeAt(at).second else next++
            open.add(level.toInt().coerceIn(0, open.size), token)
        }
        fun leave(level: Double) {
            val at = level.toInt()
            if (at in open.indices) left += at to open.removeAt(at)
        }
        fun save(): Triple<List<Int>, List<Pair<Int, Int>>, Int> = Triple(open.toList(), left.toList(), next)
        fun restore(saved: Triple<List<Int>, List<Pair<Int, Int>>, Int>) {
            open.clear(); open.addAll(saved.first); left.clear(); left.addAll(saved.second)
        }
        fun key(): String = open.take(12).joinToString(",") + "/" + left.joinToString(",")
    }

    private fun binderName(i: Int): String = if (i < 26) ('a' + i).toString() else "v$i"

    // =========================
    // The whole pipeline
    // =========================

    /**
     * One normalisation: what went in, what came out, how many reactions it took, and the net it took them in.
     *
     * [steps] is a *reaction* count over [Lambda.rules] — betas, duplications, erasures and fan meetings all counted
     * alike — so it is not a β-step count and is not comparable to one. Compare two step counts only when both rule
     * books are stated.
     *
     * It is **not** a property of the schedule, which this KDoc said until 2026-09-20. `synth.lambda.sound` reduces
     * all 25 terms of [corpus] under all four reproducible [ActivationOrder]s and gets the same reaction count every
     * time, and the same multiset of rules with it. Interaction nets are strongly confluent — two available
     * reactions are on disjoint pairs, so firing one can only move the other — and a strongly confluent system that
     * terminates is the same length down every path. What the order chooses is which reaction goes first.
     */
    class Run internal constructor(
        /** The term that went in. */
        val term: Term,
        /** What [readback] made of the reduced net. Only ever [alphaEquivalent] to the term's normal form. */
        val result: Term,
        /** Reactions fired. */
        val steps: Int,
        /** The budget this run was given, for telling "reached normal form" from "gave up". */
        val budget: Int,
        /** The reduced net, for a figure or a node count. */
        val net: Net,
        /** How many fans [compile] built for this term. */
        val fans: Int
    ) {
        /** [result] as source text. */
        val printed: String get() = format(result)

        /** False when the reduction hit its step budget, so [result] is a term mid-flight and not a normal form. */
        val finished: Boolean get() = steps < budget

        /** How many agents are left standing. One `ROOT` plus the normal form, when all went well. */
        val remainingNodes: Int get() = net.nodes.size

        override fun toString(): String = "$term  ->  $printed  ($steps steps, $fans fans)"
    }

    /**
     * Compile [term], reduce it over [Lambda.rules] until nothing fires, and read the answer back.
     *
     * [relaxIterations] defaults to **0**, unlike [Reducer]'s own default of 8: the force-directed layout is `O(n²)`
     * per iteration and a normalisation nobody is watching should not pay for a picture. Pass 8 when the net is going
     * into a figure.
     *
     * [order] is a free choice and the answer must not depend on it. That is a property, not a hope, and
     * `stdlib.lambda-terms.smoke` asserts it across four of the six [ActivationOrder]s for every entry of [corpus].
     */
    fun normalise(
        term: Term,
        order: ActivationOrder = ActivationOrder.FIRST_FOUND,
        maxSteps: Int = 200_000,
        relaxIterations: Int = 0
    ): Run {
        val compiled = compile(term)
        val reducer = Reducer(Lambda.rules(), order = order, relaxIterations = relaxIterations)
        val steps = reducer.reduce(compiled.net, maxSteps = maxSteps)
        return Run(term, readback(compiled), steps, maxSteps, compiled.net, compiled.fans)
    }

    /** [normalise], on source text. The one-liner this file's overview opens with. */
    fun normalise(
        source: String,
        order: ActivationOrder = ActivationOrder.FIRST_FOUND,
        maxSteps: Int = 200_000,
        relaxIterations: Int = 0
    ): Run = normalise(parse(source), order, maxSteps, relaxIterations)

    // =========================
    // A named corpus
    // =========================

    /**
     * The named closed terms this front end is checked against, as source text: Church numerals with `succ`, `add`,
     * `mul` and `pred`, Church booleans with `if`, the combinators `S`, `K` and `I`, terms whose reduction duplicates
     * a redex so that sharing has something to do, and terms that erase an argument entirely.
     *
     * A map rather than a list so that a test can assert the *set of names* by equality, which is how a corpus is
     * stopped from quietly shrinking. Every entry is closed — `freeVariablesOf` of every value is empty, and
     * `stdlib.lambda-terms.smoke` asserts that too.
     */
    fun corpus(): Map<String, String> {
        val zero = "(\\f x. x)"
        val one = "(\\f x. f x)"
        val two = "(\\f x. f (f x))"
        val three = "(\\f x. f (f (f x)))"
        val succ = "(\\n f x. f (n f x))"
        val add = "(\\m n f x. m f (n f x))"
        val mul = "(\\m n f. m (n f))"
        val pred = "(\\n f x. n (\\g h. h (g f)) (\\u. x) (\\u. u))"
        val tru = "(\\a b. a)"
        val fls = "(\\a b. b)"
        val cond = "(\\p a b. p a b)"
        val s = "(\\x y z. x z (y z))"
        val k = "(\\x y. x)"
        val i = "(\\x. x)"
        return linkedMapOf(
            "identity" to "\\x. x",
            "k-applied" to "$k $i $two",
            "skk" to "$s $k $k",
            "skk-applied" to "$s $k $k $i",
            "sks-applied" to "$s $k $s $i",
            "church-zero" to zero,
            "church-succ-zero" to "$succ $zero",
            "church-succ-two" to "$succ $two",
            "church-add-1-2" to "$add $one $two",
            "church-add-2-3" to "$add $two $three",
            "church-mul-2-3" to "$mul $two $three",
            "church-mul-3-3" to "$mul $three $three",
            "church-pred-one" to "$pred $one",
            "church-pred-three" to "$pred $three",
            // Numeral applied to numeral is exponentiation the other way round: `three two` is 2^3.
            "church-exp-two-cubed" to "$three $two",
            "bool-true" to tru,
            "bool-if-true" to "$cond $tru $one $two",
            "bool-if-false" to "$cond $fls $one $two",
            "bool-and-applied" to "(\\p q. p q p) $tru $fls",
            // The self-application family: reduction copies a term that is itself a redex, which is where an unshared
            // reducer does the same work twice and a shared one does not.
            "self-apply-identity" to "(\\x. x x) $i",
            "self-apply-redex" to "(\\x. x x) ($i $i)",
            "duplicate-a-redex" to "(\\x. x x) (\\y. ($i $i) y)",
            "share-under-lambda" to "(\\f. \\y. f (f y)) ($i $i)",
            // Erasure: a binder whose variable is never used compiles to an ERASE, and the argument has to vanish.
            "erase-argument" to "$k $i ($mul $two $three)",
            "erase-nested" to "(\\x. \\y. y) $three $one"
        )
    }
}
