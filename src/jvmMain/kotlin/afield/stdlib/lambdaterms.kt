@file:Suppress("MemberVisibilityCanBePrivate", "unused")
@file:afield.CompletionCategory("Visualization")

package afield.stdlib

/**
 * A λ-term: a variable, an abstraction, or an application.
 *
 * Three nested classes, so `Term.Lam("x", Term.Var("x"))` reads as what it is and none of the three short names
 * leaks into `afield.stdlib` on its own. [LambdaTerms.parse] builds one from text and [toString] prints it back.
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
 * The sharing is *correct* but not *maximal*. [compile] mints a distinct [Lambda.FAN_LABEL] for every fan it builds
 * and `Lambda.rules()`'s `fan-fan` annihilates equal labels and commutes different ones, which is enough for the
 * duplications this compiler produces to cross each other without confusing one another's copies. It is not Lamping's
 * optimal reduction: there are no brackets or croissants, so nothing here bounds how many times a fan may be copied.
 * Do not read [Run.steps] as an optimal step count, and see `dev_docs/INTERACTION_NETS_LAMBDA.md` for what was
 * actually measured.
 *
 * Closed terms are the contract. An open term compiles — each *occurrence* of a free variable becomes its own
 * [Lambda.VAR] agent, deliberately unshared, and [CompiledTerm.freeVariables] remembers which name each one came from
 * — but `dup-var` builds fresh agents with fresh ids when it copies one, so a free variable that gets duplicated
 * reads back as an agent id rather than as its name. [corpus] is closed throughout, and asserts that it is.
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
     * The **oracle**: [term]'s normal form under leftmost-outermost (normal-order) reduction, by capture-avoiding
     * substitution.
     *
     * No nets, no sharing and no cleverness — two copies of one redex are reduced once each, which is the entire
     * point of having this. Normal order rather than applicative order because it reaches a normal form whenever one
     * exists, so a disagreement with the net is a bug in the net rather than a difference of strategy.
     *
     * @throws ReductionBudgetExceeded after [maxSteps] β-steps
     */
    fun referenceNormalOrder(term: Term, maxSteps: Int = 1_000_000): Term =
        normalForm(term, intArrayOf(maxSteps), maxSteps)

    private fun normalForm(term: Term, fuel: IntArray, budget: Int): Term =
        when (val head = weakHead(term, fuel, budget)) {
            is Term.Var -> head
            is Term.Lam -> Term.Lam(head.param, normalForm(head.body, fuel, budget))
            // `head.fn` is already in weak head normal form and is not an abstraction, so this cannot loop.
            is Term.App -> Term.App(normalForm(head.fn, fuel, budget), normalForm(head.arg, fuel, budget))
        }

    private fun weakHead(term: Term, fuel: IntArray, budget: Int): Term {
        var current = term
        while (true) {
            if (current !is Term.App) return current
            val fn = weakHead(current.fn, fuel, budget)
            if (fn !is Term.Lam) return Term.App(fn, current.arg)
            if (fuel[0]-- <= 0) throw ReductionBudgetExceeded(budget)
            current = substitute(fn.body, fn.param, current.arg)
        }
    }

    /**
     * `body[name := value]`, renaming any binder of [body] that would otherwise capture a free variable of [value].
     *
     * Public because it is the one piece of the oracle worth testing on its own: the capture case is the only place a
     * substitution evaluator is ever wrong, and `substitute(parse("λy. x y"), "x", parse("y"))` is that test.
     */
    fun substitute(body: Term, name: String, value: Term): Term = when (body) {
        is Term.Var -> if (body.name == name) value else body
        is Term.App -> Term.App(substitute(body.fn, name, value), substitute(body.arg, name, value))
        is Term.Lam -> when {
            body.param == name -> body // shadowed: nothing inside refers to the outer `name`
            body.param in freeVariablesOf(value) -> {
                val taken = freeVariablesOf(value) + freeVariablesOf(body.body) + name
                val fresh = freshName(body.param, taken)
                Term.Lam(fresh, substitute(substitute(body.body, body.param, Term.Var(fresh)), name, value))
            }
            else -> Term.Lam(body.param, substitute(body.body, name, value))
        }
    }

    /** The names occurring free in [term]. Empty for a closed term, which is what [corpus] asserts of every entry. */
    fun freeVariablesOf(term: Term): Set<String> = when (term) {
        is Term.Var -> setOf(term.name)
        is Term.Lam -> freeVariablesOf(term.body) - term.param
        is Term.App -> freeVariablesOf(term.fn) + freeVariablesOf(term.arg)
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
        val erasers: Int
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
     * [Lambda.DUP] fans, each carrying **its own fresh [Lambda.FAN_LABEL]** — distinct labels rather than one per
     * binder, so that no two fans of the same tree can ever meet and wrongly annihilate. Never used, it is a
     * [Lambda.ERASE]: the binder still has to go somewhere, and an interaction net may not simply drop a wire.
     *
     * Positions are seeded by syntactic depth so the net is legible before any [Net.relax] has run. That matters for a
     * figure and matters not at all for a headless reduction.
     */
    fun compile(term: Term): CompiledTerm {
        val frees = LinkedHashMap<String, String>()
        var fans = 0
        var erasers = 0
        var label = 0
        var placed = 0

        val built = net {
            // A binder's uses collect here while its body is compiled; shadowing works by save-and-restore.
            val scope = HashMap<String, MutableList<Port>>()

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
                    label += 1
                    fans += 1
                    val fan = node(Lambda.DUP, at = spot(at), state = Lambda.fan(label))
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
                        val uses = scope[t.name]
                        if (uses != null) {
                            // A bound occurrence contributes a loose end; its binder wires it up on the way out.
                            uses += up
                        } else {
                            val free = node(Lambda.VAR, at = spot(depth), state = NodeState(energy = 0.8))
                            frees[free.id] = t.name
                            connect(up, free.principal)
                        }
                    }

                    is Term.Lam -> {
                        val lam = node(Lambda.LAM, at = spot(depth), state = NodeState(energy = 1.2))
                        connect(up, lam.principal)
                        val shadowed = scope.put(t.param, ArrayList())
                        build(t.body, lam.aux(1), depth + 1)
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

        return CompiledTerm(built, frees, fans, erasers)
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
     * The part that is not a plain tree walk is the fans, and it is the reason [Lambda.FAN_LABEL] exists. A normal
     * form can still carry them — `mul two three` reduces to a net with three fans standing — and a fan is not a
     * node of the term, it is a *bracket* on the path through it. So the walk carries a **stack of fan choices**.
     * Arriving at a fan from its auxiliary *i* means "the thing here is copy *i* of what the fan's principal points
     * at": push `(label, i)` and keep going through the principal. Arriving at a fan's *principal* is the matching
     * close bracket: pop, and if the label agrees, descend into that branch. Without the stack the second case has no
     * answer at all — which copy of a shared subterm a term position means is not local information — and a reduction
     * that left any fan standing reads back as `⋈`.
     *
     * What it will not do is invent structure that is not there, and the five marks it can leave instead are all
     * things to fail a test over rather than paper over:
     *
     * | mark | what it means |
     * | --- | --- |
     * | `⊥` | an `ERASE` sat in a term position |
     * | `·` | a port with no wire at all |
     * | `⋈` | a fan's *principal* port sat in a term position with no matching fan choice on the stack |
     * | `∞` | the walk came back to a port already on its own path, with the same stack |
     * | `…` | the walk ran past [maxNodes], or past [maxDepth] frames |
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

    /** [readback], on what [compile] returned — so the free-variable names come along by themselves. */
    fun readback(compiled: CompiledTerm, maxNodes: Int = 200_000, maxDepth: Int = 3_000): Term =
        readback(compiled.net, compiled.freeVariables, maxNodes, maxDepth)

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
        depthLeft: Int
    ): Term {
        if (fuel[0]-- <= 0 || depthLeft <= 0) return Term.Var("…")
        val peer = net.connected(from) ?: return Term.Var("·")
        val here = peer.id + "|" + fans.joinToString(",") { "${it.first}/${it.second}" }
        if (!path.add(here)) return Term.Var("∞")
        try {
            val node = peer.node
            fun on(port: Port): Term = walk(net, port, binders, next, frees, fuel, path, fans, depthLeft - 1)
            return when {
                node.type == Lambda.LAM && peer.index == 0 -> {
                    val name = binderName(next[0]++)
                    // An occurrence of this variable is a walk that arrives *at* the binder port, from below.
                    binders[node.aux(0).id] = name
                    Term.Lam(name, on(node.aux(1)))
                }
                node.type == Lambda.LAM && peer.index == 1 -> Term.Var(binders[peer.id] ?: "?${peer.id}")
                node.type == Lambda.APP && peer.index == 1 -> Term.App(on(node.principal), on(node.aux(1)))
                // An open bracket: the thing at this term position is copy `branch` of whatever the fan's principal
                // points at, so remember which copy and go and read it.
                node.type == Lambda.DUP && peer.index != 0 -> {
                    val entry = Lambda.fanLabelOf(node) to (peer.index - 1)
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
                    val top = fans.lastOrNull()
                    if (top == null || top.first != Lambda.fanLabelOf(node)) {
                        Term.Var("⋈")
                    } else {
                        fans.removeLast()
                        try {
                            on(node.aux(top.second))
                        } finally {
                            fans.addLast(top)
                        }
                    }
                }
                node.type == Lambda.VAR || node.type == Lambda.CONST -> Term.Var(frees[node.id] ?: node.id)
                node.type == Lambda.ERASE -> Term.Var("⊥")
                else -> Term.Var("${node.type.name.lowercase()}:${peer.index}")
            }
        } finally {
            path.remove(here)
        }
    }

    private fun binderName(i: Int): String = if (i < 26) ('a' + i).toString() else "v$i"

    // =========================
    // The whole pipeline
    // =========================

    /**
     * One normalisation: what went in, what came out, how many reactions it took, and the net it took them in.
     *
     * [steps] is a *reaction* count over [Lambda.rules] — betas, duplications, erasures and fan meetings all counted
     * alike — so it is a property of this rule book and this schedule and not a measure of the term. Compare two step
     * counts only when both columns are stated.
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
