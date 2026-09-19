package afield.examples.stdlib

import afield.stdlib.LambdaTerms
import afield.stdlib.Term
import kotlin.random.Random

/**
 * A seeded generator of **closed** λ-terms for property-testing `afield.stdlib.LambdaTerms`, in two families whose
 * distributions are a printed deliverable rather than a footnote.
 *
 * The thing being hunted is the one `dev_docs/INTERACTION_NETS_LAMBDA.md` admits it has not proved: that
 * `Lambda.FAN_LABEL` is enough bookkeeping without levels. That failure mode needs *nested* duplication — a fan
 * duplicated by another fan — so a generator that merely makes many terms is the wrong generator. Both families here
 * are weighted toward it, and both report how much of it they actually achieved, measured as
 * `LambdaTerms.compile(t).fans` rather than asserted from the weights.
 *
 * ### Family T — simply typed, strongly normalising by construction
 *
 * [typedTerms] generates by *target type*. Every simply-typed λ-term is strongly normalising, so a term from this
 * family that exhausts a reduction budget is a **bug and not a sample** — which is what makes the budget assertion in
 * the harness meaningful rather than a filter. Termination is therefore guaranteed structurally and the budget is only
 * there to catch the case where that guarantee is wrong.
 *
 * Three things are deliberate:
 *
 * - **A preamble of binders.** Generation starts under `λ`s whose types are drawn from [PREAMBLE_POOL], always
 *   including one of base type and one of type `o→o`. Without that, a target of base type in the empty context has no
 *   inhabitant at all and the generator has nowhere to go.
 * - **Redexes are injected, not hoped for.** A type-directed generator naturally produces long normal forms, which
 *   compile to nets with nothing to reduce. [Production.REDEX] builds `(λx:A. body) arg` at a random `A` from
 *   [REDEX_TYPE_POOL], weighted toward arrow types so that what gets substituted is itself a function.
 * - **Duplication bias.** [pickHead] weights a variable up when it has already been used on this path, so a binder
 *   collects several occurrences and `LambdaTerms.compile` builds a *tree* of fans for it rather than a bare wire.
 *   Nesting follows from `A` being an arrow type: the duplicated subterm is a function that itself duplicates.
 *
 * ### Family D — duplicator soup, untyped
 *
 * [soupTerms] applies combinators drawn from [SOUP_POOL], which is weighted toward `W = λx y. x y y` (the pure
 * duplicator), `S`, and Church numerals — numeral applied to numeral is exponentiation, and it nests duplication
 * faster than anything Family T reaches. This family is **not** strongly normalising: `(λx. x x) (λx. x x)` is one
 * unlucky draw away. Non-terminating samples are counted and reported as their own column by the harness; they are
 * never dropped, because "how many diverged" is part of the population and a silent drop is how a population becomes
 * a number nobody can reproduce.
 *
 * Every entry point takes a seed and returns terms in a deterministic order, so a counterexample is named by
 * `(seed, index)` and can be regenerated.
 */
object LambdaTermGenerator {

    // =========================
    // Simple types
    // =========================

    /** A simple type over the single base type `o`. Only needed by Family T, and only to keep it terminating. */
    sealed interface Ty {
        /** The base type, printed `o`. */
        data object Base : Ty

        /** [from]`→`[to]. */
        data class Arrow(val from: Ty, val to: Ty) : Ty
    }

    /** `o` — the one base type. */
    val O: Ty = Ty.Base

    /** [a]`→`[b]. */
    fun arr(a: Ty, b: Ty): Ty = Ty.Arrow(a, b)

    /** A type as text: `(o→o)→o→o`, arrows to the right. */
    fun showTy(t: Ty): String = when (t) {
        is Ty.Base -> "o"
        is Ty.Arrow -> (if (t.from is Ty.Arrow) "(${showTy(t.from)})" else showTy(t.from)) + "→" + showTy(t.to)
    }

    /** The result of applying a value of [t] to [n] arguments, or null if [t] does not take that many. */
    private fun resultAfter(t: Ty, n: Int): Ty? {
        var here = t
        repeat(n) {
            val a = here as? Ty.Arrow ?: return null
            here = a.to
        }
        return here
    }

    /** The argument types a value of [t] consumes when applied to [n] arguments. */
    private fun argsOf(t: Ty, n: Int): List<Ty> {
        val out = ArrayList<Ty>(n)
        var here = t
        repeat(n) {
            val a = here as Ty.Arrow
            out += a.from
            here = a.to
        }
        return out
    }

    /** Binder types the preamble draws from. `o` and `o→o` are always present besides these. */
    private val PREAMBLE_POOL: List<Ty> = listOf(
        arr(O, O),
        arr(O, arr(O, O)),
        arr(arr(O, O), arr(O, O)),
        arr(arr(O, O), O),
        O
    )

    /**
     * Types a [Production.REDEX] may substitute at, weighted by repetition in the list.
     *
     * `o→o` and `(o→o)→o→o` appear more than once on purpose: a substituted *function* is what makes the duplication
     * nest, and `(o→o)→o→o` is the Church-numeral type, where the duplicated thing duplicates.
     */
    private val REDEX_TYPE_POOL: List<Ty> = listOf(
        arr(O, O),
        arr(O, O),
        arr(O, O),
        arr(arr(O, O), arr(O, O)),
        arr(arr(O, O), arr(O, O)),
        arr(O, arr(O, O)),
        O
    )

    private class Binding(val name: String, val type: Ty) {
        /** How many occurrences of this binder the generator has emitted so far — the duplication bias reads it. */
        var uses: Int = 0
    }

    private enum class Production { LAM, HEAD, REDEX }

    /**
     * [count] closed simply-typed terms from [seed].
     *
     * @param sizeFloor smallest node budget a term is generated with
     * @param sizeCeiling largest; each term draws its own budget uniformly from the band, so the population spans
     * sizes rather than clustering at one
     * @param duplicationBias extra weight given to a variable that has already been used on this path, in
     * [pickHead]'s weighting. 0.0 is uniform choice; the default is tuned so that the *measured* fan counts the
     * harness prints are not mostly zero
     */
    fun typedTerms(
        seed: Long,
        count: Int,
        sizeFloor: Int = 6,
        sizeCeiling: Int = 26,
        duplicationBias: Double = 4.0
    ): List<Term> {
        require(count > 0) { "count must be positive" }
        require(sizeFloor in 1..sizeCeiling) { "size band $sizeFloor..$sizeCeiling is empty" }
        val rng = Random(seed)
        val out = ArrayList<Term>(count)
        while (out.size < count) {
            val size = rng.nextInt(sizeFloor, sizeCeiling + 1)
            val term = onePreambleTerm(rng, size, duplicationBias)
            // Closedness is a contract of the construction, not a filter — but it is cheap to check and a generator
            // that quietly emits an open term would make every readback comparison downstream meaningless.
            check(LambdaTerms.freeVariablesOf(term).isEmpty()) { "generator produced an open term: $term" }
            out += term
        }
        return out
    }

    private fun onePreambleTerm(rng: Random, size: Int, duplicationBias: Double): Term {
        // Always a base-typed binder and an `o→o` binder, so that no target type is uninhabited in this context.
        val types = ArrayList<Ty>(listOf(O, arr(O, O)))
        repeat(rng.nextInt(0, 3)) { types += PREAMBLE_POOL[rng.nextInt(PREAMBLE_POOL.size)] }
        types.shuffle(rng)
        val ctx = types.mapIndexed { i, t -> Binding("p$i", t) }
        val body = gen(rng, if (rng.nextInt(4) == 0) arr(O, O) else O, ctx, size, duplicationBias)
        return ctx.foldRight(body) { b, acc -> Term.Lam(b.name, acc) }
    }

    private fun gen(rng: Random, target: Ty, ctx: List<Binding>, size: Int, bias: Double): Term {
        val heads = headCandidates(target, ctx)
        if (size <= 1) {
            // Out of budget: the cheapest thing that has the right type. A zero-argument head is a bare variable.
            val bare = heads.filter { it.second == 0 }
            if (bare.isNotEmpty()) return useVar(pickHead(rng, bare, bias).first)
            if (target is Ty.Arrow) return lamOf(rng, target, ctx, 1, bias)
            return applyHead(rng, pickHead(rng, heads, bias), ctx, 1, bias)
        }

        val weights = ArrayList<Pair<Production, Int>>()
        if (target is Ty.Arrow) weights += Production.LAM to 4
        if (heads.isNotEmpty()) weights += Production.HEAD to 7
        // A redex costs at least four nodes; below that the budget cannot pay for both halves.
        if (size >= 6) weights += Production.REDEX to 5
        if (weights.isEmpty()) return lamOf(rng, target as Ty.Arrow, ctx, size, bias)

        return when (weighted(rng, weights)) {
            Production.LAM -> lamOf(rng, target as Ty.Arrow, ctx, size, bias)
            Production.HEAD -> applyHead(rng, pickHead(rng, heads, bias), ctx, size, bias)
            Production.REDEX -> redex(rng, target, ctx, size, bias)
        }
    }

    private fun lamOf(rng: Random, target: Ty.Arrow, ctx: List<Binding>, size: Int, bias: Double): Term {
        val b = Binding("v${ctx.size}", target.from)
        return Term.Lam(b.name, gen(rng, target.to, ctx + b, size - 1, bias))
    }

    /**
     * `(λx:A. body) arg` at a freshly drawn `A`.
     *
     * The body is generated with the fresh binder's [Binding.uses] pre-loaded, which is [pickHead]'s signal for "this
     * one has been used already" and therefore the whole duplication bias pointed at exactly the variable that is
     * about to be substituted. That is the shape that makes `compile` build a fan tree over the argument.
     */
    private fun redex(rng: Random, target: Ty, ctx: List<Binding>, size: Int, bias: Double): Term {
        val argType = REDEX_TYPE_POOL[rng.nextInt(REDEX_TYPE_POOL.size)]
        val b = Binding("d${ctx.size}", argType)
        b.uses = 1
        val bodySize = (size * 2) / 3
        val body = gen(rng, target, ctx + b, bodySize.coerceAtLeast(2), bias)
        val arg = gen(rng, argType, ctx, (size - bodySize - 1).coerceAtLeast(1), bias)
        return Term.App(Term.Lam(b.name, body), arg)
    }

    /** Every `(binding, argument count)` whose application reaches [target], at up to three arguments. */
    private fun headCandidates(target: Ty, ctx: List<Binding>): List<Pair<Binding, Int>> {
        val out = ArrayList<Pair<Binding, Int>>()
        for (b in ctx) for (n in 0..3) if (resultAfter(b.type, n) == target) out += b to n
        return out
    }

    /**
     * One head, weighted so that a binder already used on this path is picked again.
     *
     * A uniform choice over the context produces terms whose binders are each used once, which compile to nets with
     * no fans at all — a population that cannot possibly exhibit the bug being hunted. `1.0 + bias` per prior use,
     * so the bias is a multiplier on repetition and not a hard rule.
     */
    private fun pickHead(rng: Random, candidates: List<Pair<Binding, Int>>, bias: Double): Pair<Binding, Int> {
        val weights = candidates.map { 1.0 + bias * it.first.uses }
        var pick = rng.nextDouble() * weights.sum()
        for (i in candidates.indices) {
            pick -= weights[i]
            if (pick <= 0.0) return candidates[i]
        }
        return candidates.last()
    }

    private fun useVar(b: Binding): Term {
        b.uses += 1
        return Term.Var(b.name)
    }

    private fun applyHead(
        rng: Random,
        head: Pair<Binding, Int>,
        ctx: List<Binding>,
        size: Int,
        bias: Double
    ): Term {
        val (b, n) = head
        var acc: Term = useVar(b)
        if (n == 0) return acc
        val each = ((size - 1) / n).coerceAtLeast(1)
        for (t in argsOf(b.type, n)) acc = Term.App(acc, gen(rng, t, ctx, each, bias))
        return acc
    }

    private fun <T> weighted(rng: Random, options: List<Pair<T, Int>>): T {
        var pick = rng.nextInt(options.sumOf { it.second })
        for ((value, w) in options) {
            pick -= w
            if (pick < 0) return value
        }
        return options.last().first
    }

    // =========================
    // Family D — duplicator soup
    // =========================

    /**
     * The closed combinators Family D applies to one another, as source text, weighted by repetition.
     *
     * `W` is the pure duplicator and appears three times; the Church numerals appear because a numeral applied to a
     * numeral is exponentiation, which is the cheapest nested duplication there is. `\x. x x` is in the pool once and
     * once only: it is the ingredient that makes the family non-normalising, and the harness needs *some* diverging
     * draws to have a column to report, but a pool full of them would be a population of timeouts.
     */
    val SOUP_POOL: List<Pair<String, String>> = listOf(
        "I" to "(\\x. x)",
        "K" to "(\\x y. x)",
        "S" to "(\\x y z. x z (y z))",
        "W" to "(\\x y. x y y)",
        "W" to "(\\x y. x y y)",
        "W" to "(\\x y. x y y)",
        "B" to "(\\x y z. x (y z))",
        "C" to "(\\x y z. x z y)",
        "zero" to "(\\f x. x)",
        "one" to "(\\f x. f x)",
        "two" to "(\\f x. f (f x))",
        "two" to "(\\f x. f (f x))",
        "three" to "(\\f x. f (f (f x)))",
        "succ" to "(\\n f x. f (n f x))",
        "add" to "(\\m n f x. m f (n f x))",
        "mul" to "(\\m n f. m (n f))",
        "selfapp" to "(\\x. x x)"
    )

    /**
     * [count] closed untyped applications over [SOUP_POOL], from [seed].
     *
     * Each is a left-nested application of two to five operands, where an operand is a pool entry or — with
     * probability one in three — a parenthesised group generated the same way one level down. That nesting is the
     * point: `mul two (three two)` reaches a duplication-of-a-duplication that Family T's size band does not.
     *
     * **Not strongly normalising.** The caller must run these under a budget and report the overruns.
     */
    fun soupTerms(seed: Long, count: Int, maxDepth: Int = 2): List<Term> {
        require(count > 0) { "count must be positive" }
        val rng = Random(seed)
        return List(count) { LambdaTerms.parse(soupSource(rng, maxDepth)) }
    }

    private fun soupSource(rng: Random, depthLeft: Int): String {
        val operands = rng.nextInt(2, 6)
        val parts = ArrayList<String>(operands)
        repeat(operands) {
            parts += if (depthLeft > 0 && rng.nextInt(3) == 0) "(" + soupSource(rng, depthLeft - 1) + ")"
            else SOUP_POOL[rng.nextInt(SOUP_POOL.size)].second
        }
        return parts.joinToString(" ")
    }

    // =========================
    // Describing a population
    // =========================

    /**
     * What a generated population actually is, so that a claim about it carries its own evidence.
     *
     * [fanHistogram] is the one that matters: it is `LambdaTerms.compile(t).fans` per term, *measured*, and it is the
     * only honest answer to "is this generator reaching the duplication the bug needs".
     */
    class Population(
        val label: String,
        val seed: Long,
        val terms: List<Term>
    ) {
        /** de Bruijn keys, so "how many of these are actually different terms" has an answer. */
        val distinctKeys: Int by lazy { terms.map { LambdaTerms.deBruijnKey(it) }.toSet().size }

        /** Node count per term — the size distribution, as `min..max` and a mean. */
        val sizes: List<Int> by lazy { terms.map { nodeCount(it) } }

        /** λ-nesting depth per term. */
        val depths: List<Int> by lazy { terms.map { lambdaDepth(it) } }

        /** Fans `compile` builds per term. Zero means the term shares nothing and teaches this hunt nothing. */
        val fans: List<Int> by lazy { terms.map { LambdaTerms.compile(it).fans } }

        fun summary(): String = buildString {
            append("$label: n=${terms.size} seed=$seed distinct=$distinctKeys")
            append(" size=${sizes.min()}..${sizes.max()} mean=${"%.1f".format(sizes.average())}")
            append(" lambdaDepth=${depths.min()}..${depths.max()} mean=${"%.1f".format(depths.average())}")
            append(" fans=${fans.min()}..${fans.max()} mean=${"%.1f".format(fans.average())}")
            append(" withFans=${fans.count { it > 0 }} with4plus=${fans.count { it >= 4 }}")
        }

        fun fanHistogram(): String {
            val buckets = linkedMapOf("0" to 0, "1" to 0, "2-3" to 0, "4-7" to 0, "8+" to 0)
            for (f in fans) {
                val k = when {
                    f == 0 -> "0"
                    f == 1 -> "1"
                    f <= 3 -> "2-3"
                    f <= 7 -> "4-7"
                    else -> "8+"
                }
                buckets[k] = buckets.getValue(k) + 1
            }
            return buckets.entries.joinToString("  ") { "${it.key}:${it.value}" }
        }
    }

    /** Nodes in [term] — variables, abstractions and applications counted alike. */
    fun nodeCount(term: Term): Int = when (term) {
        is Term.Var -> 1
        is Term.Lam -> 1 + nodeCount(term.body)
        is Term.App -> 1 + nodeCount(term.fn) + nodeCount(term.arg)
    }

    /** The deepest nesting of `λ`s in [term]. */
    fun lambdaDepth(term: Term): Int = when (term) {
        is Term.Var -> 0
        is Term.Lam -> 1 + lambdaDepth(term.body)
        is Term.App -> maxOf(lambdaDepth(term.fn), lambdaDepth(term.arg))
    }
}
