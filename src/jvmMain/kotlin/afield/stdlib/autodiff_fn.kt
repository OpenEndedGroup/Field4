@file:Suppress("MemberVisibilityCanBePrivate")
@file:afield.CompletionCategory("Function")

package afield.stdlib

import kotlin.math.absoluteValue

/**
 * Differentiating the `Fn` graph itself — the bridge from `autodiff.kt`'s scalar tape to the
 * graphs `Functions` builds.
 *
 * [Functions.Fn] is a closure over `Double`, so it cannot be differentiated by overloading its
 * arithmetic. But every `Fn` the stdlib builds also carries an inspectable [FnGraphNode]: the op
 * name, the role-tagged children, the scalar metadata. This file **re-interprets that structure in
 * [AD] arithmetic**, so the exact derivative of a graph's output with respect to its numeric leaves
 * — the same θ-vector [extractParameters] reads and [applyParameters] writes — falls out of one
 * backward sweep instead of `2n` extra evaluations.
 *
 * ```kotlin
 * with(Functions) {
 *     val f = sin(1.5, amplitude = 0.8, offset = 0.2)
 *     f.gradientAt(0.3)          // d f(0.3) / d theta, exact, in leaf order
 * }
 * ```
 *
 * ## Which ops, and the agreement check
 *
 * The interpreter covers the smooth algebraic core — see [FnAutoDiff.differentiableOps]. That is
 * a *subset*: the piecewise oscillators (`saw`, `square`), the table-backed
 * interpolants, the noise generators and everything with baked point data are not here, and
 * [FnAutoDiff.supports] answers correctly for any graph.
 *
 * A second interpreter can silently compute the derivative of a *different function* from the one
 * `eval` computes, so the two are compared instead of assumed to match: [FnAutoDiff.agrees]
 * re-evaluates the interpreter against [Functions.Fn.eval] on the caller's own sample grid and
 * reports the worst disagreement, and the fitters run that check before switching over. A mismatch
 * demotes the run to finite differences rather than returning a confident wrong gradient.
 *
 * ## Ops indexed by leaf, not by node
 *
 * θ-indices are assigned by exactly the traversal [Functions.Fn.mapGraphNumbers] uses — post-order
 * DFS over `node.children` in order, one index per node carrying `numbers["value"]` — so index *i*
 * here is the same leaf as index *i* in [extractParameters]. [FnAutoDiff.leafIndices] is that map,
 * keyed by the child-index path, and a test asserts the two agree leaf for leaf.
 */

/** How a fit obtains the gradient of its loss with respect to a graph's numeric leaves. */
enum class FnGradientMode {
    /**
     * Central finite differences, and the default. Works on **any** graph and costs `2n` extra
     * loss evaluations per step.
     */
    FINITE_DIFFERENCE,

    /**
     * Reverse-mode AD through the graph: one loss evaluation and one backward sweep per step,
     * exact. Throws if the graph or the metric is outside the supported set, so an explicit
     * request never silently degrades.
     */
    EXACT,

    /** Exact where it applies, and falls back to finite differences, without saying so, where it does not. */
    AUTO
}

/**
 * The op vocabulary the [AD] interpreter understands, plus the agreement checks around it.
 *
 * Everything that reasons about *which* ops can be differentiated consults this rather than keeping
 * a private list — the same discipline [OpRegistry] enforces for the op vocabulary as a whole. Every
 * name here is also in [OpRegistry.knownOps]; a test asserts it.
 */
object FnAutoDiff {

    /**
     * The ops the gradient can handle, lower-cased — **derived**, not restated: every op with an
     * [FnFormulas] entry, plus the two numeric-leaf ops that are θ rather than a formula.
     *
     * A subset of the vocabulary. What is *out* and why is declared op by op in
     * [FnNonDifferentiable], and `FnOpCoverageTest` fails if an op in [OpRegistry] appears in
     * neither this set nor that one — which is what stops the gap growing silently.
     */
    val differentiableOps: Set<String> = FnFormulas.ops + setOf("const", "param")

    /** True when every node of [fn]'s graph is in [differentiableOps]. */
    fun supports(fn: Functions.Fn): Boolean = unsupportedOps(fn).isEmpty()

    /**
     * The distinct ops in [fn] that the interpreter does not know, in first-seen order — the
     * diagnostic behind the `EXACT` mode's error message.
     */
    fun unsupportedOps(fn: Functions.Fn): List<String> {
        val out = LinkedHashSet<String>()
        fun walk(f: Functions.Fn) {
            val node = f.graphNode()
            if (!node.numbers.containsKey("value") && node.op.lowercase() !in differentiableOps) out += node.op
            for (edge in node.children) walk(edge.fn)
        }
        walk(fn)
        return out.toList()
    }

    /**
     * The θ-index of every numeric leaf, keyed by its child-index path from the root.
     *
     * The traversal is post-order DFS over `node.children` in declaration order, firing on any node
     * carrying `numbers["value"]` — literally the order [Functions.Fn.mapGraphNumbers] visits, which
     * is what makes index *i* here the same leaf as index *i* in [extractParameters].
     */
    fun leafIndices(fn: Functions.Fn): Map<List<Int>, Int> {
        val out = LinkedHashMap<List<Int>, Int>()
        val path = ArrayList<Int>()
        var counter = 0
        fun walk(f: Functions.Fn) {
            val node = f.graphNode()
            node.children.forEachIndexed { childIndex, edge ->
                path.add(childIndex)
                walk(edge.fn)
                path.removeAt(path.size - 1)
            }
            if (node.numbers.containsKey("value")) out[path.toList()] = counter++
        }
        walk(fn)
        return out
    }

    /** The worst absolute disagreement between the [AD] interpreter's value and [Functions.Fn.eval]
     *  across [samples]. Zero-length input reports 0. */
    fun worstValueDisagreement(fn: Functions.Fn, samples: DoubleArray): Double {
        if (samples.isEmpty()) return 0.0
        val theta = fn.extractParameters()
        val indices = leafIndices(fn)
        var worst = 0.0
        val tape = ADTape()
        val params = tape.variables(theta)
        for (x in samples) {
            // Either side can throw — Fn.clamp with an inverted range, an op whose x-domain window is
            // not recoverable from the node. A throw is a disagreement, not a crash: reporting +inf
            // demotes the run to finite differences, which is always available.
            val direct = runCatching { fn.eval(x) }.getOrNull() ?: return Double.POSITIVE_INFINITY
            val interpreted = runCatching {
                interpret(fn, tape.constant(x), params, indices, emptyList()).value
            }.getOrNull() ?: return Double.POSITIVE_INFINITY
            if (!direct.isFinite() && !interpreted.isFinite()) continue
            val delta = (direct - interpreted).absoluteValue / (1.0 + direct.absoluteValue)
            if (delta > worst || delta.isNaN()) worst = delta
        }
        return worst
    }

    /**
     * Whether the interpreter reproduces [Functions.Fn.eval] on [samples] to within [tolerance].
     *
     * The guard the fitters run before trusting an exact gradient. A second interpreter that
     * computes a *slightly different function* would otherwise return a confident wrong
     * derivative; this makes that failure loud and local.
     */
    fun agrees(fn: Functions.Fn, samples: DoubleArray, tolerance: Double = 1e-9): Boolean =
        supports(fn) && worstValueDisagreement(fn, samples) <= tolerance

    /**
     * Re-evaluates one node in [AD] arithmetic by walking its [FnFormulas] entry.
     *
     * There is **no per-op code here**. The only thing this function knows about ops is
     * that a node carrying `numbers["value"]` is a θ leaf; everything else is looked up in the one
     * formula table that `Fn.eval` is pinned against, and walked by [evalFormulaAD]. That is what
     * makes adding a differentiable op a one-line change with no second transcription to get wrong.
     *
     * [x] is itself tracked, so an x-domain transform ([FnExpr.AtX]) composes with a θ-dependent
     * offset without a special case.
     */
    internal fun interpret(
        fn: Functions.Fn,
        x: AD,
        theta: List<AD>,
        indices: Map<List<Int>, Int>,
        path: List<Int>
    ): AD {
        val node = fn.graphNode()
        if (node.numbers.containsKey("value")) {
            val index = indices[path] ?: error("no theta index for leaf at path $path")
            return theta[index]
        }
        val formula = FnFormulas.of(node.op)
            ?: error("op '${node.op}' is not differentiable; see FnAutoDiff.differentiableOps")
        return evalFormulaAD(
            formula,
            x,
            alpha = { at -> alphaOf(fn, at) },
            childValue = { role, at ->
                val position = node.children.indexOfFirst { it.role == role }
                require(position >= 0) { "op ${node.op} is missing its '$role' child" }
                interpret(node.children[position].fn, at, theta, indices, path + position)
            }
        )
    }

    /**
     * Re-evaluates one node in plain `Double` arithmetic by walking the *same* [FnFormulas] entry —
     * the reference the agreement test compares against [Functions.Fn.eval] bit for bit.
     *
     * If this and `eval` ever disagree, one of them is wrong and `FnFormulaAgreementTest` says so at
     * test time. Nothing in production calls this; it exists to be the pin.
     */
    fun evalByFormula(fn: Functions.Fn, x: Double): Double {
        val node = fn.graphNode()
        node.numbers["value"]?.let { return it }
        val formula = FnFormulas.of(node.op)
            ?: error("op '${node.op}' has no formula; see FnFormulas")
        return evalFormula(
            formula,
            x,
            alpha = { at -> alphaOfDouble(fn, at) },
            childValue = { role, at ->
                val position = node.children.indexOfFirst { it.role == role }
                require(position >= 0) { "op ${node.op} is missing its '$role' child" }
                evalByFormula(node.children[position].fn, at)
            }
        )
    }

    /**
     * The normalised position of [x] within [fn]'s own domain — the tracked twin of
     * `Domain.finiteSweep().alphaOf(x)`, which `ramp` and the window ops sample.
     *
     * Only defined for a **finite** domain. `Functions` falls back to a plot-safe preview window for an
     * unbounded one, and that fallback is private to `Functions` (`Functions.kt:3148`); rather than
     * guess at it, this throws and lets [worstValueDisagreement] demote the run to finite differences.
     */
    private fun alphaOf(fn: Functions.Fn, x: AD): AD {
        val domain = requireFiniteShapeDomain(fn)
        val length = domain.length
        if (length == 0.0) return x.tape.constant(0.0)
        return (x - domain.min) / length
    }

    /** [alphaOf] in plain `Double`, for [evalByFormula]. */
    private fun alphaOfDouble(fn: Functions.Fn, x: Double): Double {
        val domain = requireFiniteShapeDomain(fn)
        val length = domain.length
        if (length == 0.0) return 0.0
        return (x - domain.min) / length
    }

    private fun requireFiniteShapeDomain(fn: Functions.Fn): Functions.Domain {
        val domain = fn.domain
        require(domain.isFinite) {
            "the x-window of this op is $domain, which is not finite; the exact path only handles a " +
                "bounded shape domain"
        }
        return domain
    }
}

/**
 * The exact value and gradient of `f(x)` with respect to this graph's numeric leaves, in the same
 * order [extractParameters] reads them.
 *
 * ```kotlin
 * with(Functions) { (sin(1.5, amplitude = 0.8) + 0.2).gradientAt(0.3) }
 * ```
 *
 * @throws IllegalStateException when the graph contains an op outside [FnAutoDiff.differentiableOps].
 */
fun Functions.Fn.gradientAt(x: Double): ADGradient {
    val theta = extractParameters()
    val indices = FnAutoDiff.leafIndices(this)
    val tape = ADTape()
    val params = tape.variables(theta)
    val output = FnAutoDiff.interpret(this, tape.constant(x), params, indices, emptyList())
    val adjoint = tape.backward(output)
    return ADGradient(output.value, DoubleArray(theta.size) { adjoint[params[it].index] })
}

/**
 * The exact value and gradient, with respect to the numeric leaves, of any scalar [objective] built
 * from this graph's values at [samples] — the general primitive the exact fitters ride on.
 *
 * The whole loss is built on **one** tape: the graph is interpreted once per sample, the resulting
 * [AD] list is reduced by [objective], and a single backward sweep produces all `n` derivatives.
 * [theta] replaces the graph's current leaf values, so this is a pure function of θ and can be
 * handed straight to [minimizeWithGradient].
 *
 * ```kotlin
 * val loss = fn.valueAndGradOverSamples(theta, xs) { ys ->
 *     sqrt(mean(ys.mapIndexed { i, y -> (y - targets[i]).sq() }))
 * }
 * ```
 */
fun Functions.Fn.valueAndGradOverSamples(
    theta: DoubleArray,
    samples: DoubleArray,
    objective: (List<AD>) -> AD
): ADGradient {
    val indices = FnAutoDiff.leafIndices(this)
    require(indices.size == theta.size) {
        "theta has ${theta.size} entries but the graph has ${indices.size} numeric leaves"
    }
    val tape = ADTape()
    val params = tape.variables(theta)
    val values = samples.map { FnAutoDiff.interpret(this, tape.constant(it), params, indices, emptyList()) }
    val output = objective(values)
    val adjoint = tape.backward(output)
    return ADGradient(output.value, DoubleArray(theta.size) { adjoint[params[it].index] })
}

// ---------------------------------------------------------------------------------------------
// Differentiable metrics
// ---------------------------------------------------------------------------------------------

/**
 * The [AD] counterpart of [FnMetric]: a distance between a target and a candidate that can itself
 * be differentiated, so a fit can descend it exactly.
 *
 * [FnMetric] takes three `DoubleArray`s and returns a `Double`, which is un-differentiable by
 * construction — a lambda over primitives has no derivative to recover. This interface takes the
 * candidate as tracked [AD] values instead. The companion supplies AD forms of the metrics whose
 * `FnMetric` twins are the common defaults, and [FnAdMetric.of] maps one to the other where the
 * correspondence is exact.
 *
 * Like [FnMetric], **lower is better** and non-finite target samples are skipped.
 */
fun interface FnAdMetric {
    /** The tracked distance of [candidate] from [target] over the grid [xs]. */
    fun distance(xs: DoubleArray, target: DoubleArray, candidate: List<AD>): AD

    companion object {
        /**
         * Root-mean-square error, exactly matching [FnMetric.rmse] on finite data:
         * `sqrt(mean((candidate - target)²))`.
         */
        val rmse: FnAdMetric = FnAdMetric { _, target, candidate ->
            val terms = candidate.indices.filter { target[it].isFinite() }.map { (candidate[it] - target[it]).sq() }
            require(terms.isNotEmpty()) { "no jointly finite sample to score" }
            sqrt(mean(terms))
        }

        /** Mean absolute error, matching [FnMetric.mae]. Kinked at zero residual; see the
         *  even-split convention in `autodiff.kt`. */
        val mae: FnAdMetric = FnAdMetric { _, target, candidate ->
            val terms = candidate.indices.filter { target[it].isFinite() }.map { abs(candidate[it] - target[it]) }
            require(terms.isNotEmpty()) { "no jointly finite sample to score" }
            mean(terms)
        }

        /** The log-cosh loss, matching [FnMetric.logCosh] — smooth everywhere, robust to outliers. */
        val logCosh: FnAdMetric = FnAdMetric { _, target, candidate ->
            val ln2 = kotlin.math.ln(2.0)
            val terms = candidate.indices.filter { target[it].isFinite() }.map {
                val a = abs(candidate[it] - target[it])
                a + ln(1.0 + exp(-2.0 * a)) - ln2
            }
            require(terms.isNotEmpty()) { "no jointly finite sample to score" }
            mean(terms)
        }

        /** The mean squared error — not an [FnMetric] default, but the objective whose gradient is
         *  best conditioned near the optimum, and the natural one to hand to L-BFGS. */
        val mse: FnAdMetric = FnAdMetric { _, target, candidate ->
            val terms = candidate.indices.filter { target[it].isFinite() }.map { (candidate[it] - target[it]).sq() }
            require(terms.isNotEmpty()) { "no jointly finite sample to score" }
            mean(terms)
        }

        /**
         * The [FnAdMetric] that exactly reproduces [metric], or null when there is no exact
         * correspondence.
         *
         * Matching is by **identity** against the singleton metrics in [FnMetric.Companion], which
         * is strict: `FnMetric.lp(2.0)` builds a fresh instance that is numerically
         * identical to [FnMetric.rmse] but is not recognised, and the caller is told to pass an
         * explicit [FnAdMetric] rather than being given a gradient of something subtly else.
         */
        fun of(metric: FnMetric): FnAdMetric? = when {
            metric === FnMetric.rmse -> rmse
            metric === FnMetric.mae -> mae
            metric === FnMetric.logCosh -> logCosh
            else -> null
        }
    }
}

// ---------------------------------------------------------------------------------------------
// The fitting seam
// ---------------------------------------------------------------------------------------------

/**
 * The even sample grid [FnMetric.lossAgainst] uses, exposed so the exact path samples the *same*
 * points as the finite-difference path and the two are comparable.
 */
fun fnLossGrid(domain: Functions.Domain, samples: Int): DoubleArray {
    val count = samples.coerceIn(2, 8192)
    return DoubleArray(count) { index -> domain.min + domain.length * index / (count - 1).toDouble() }
}

/**
 * An exact `θ → (loss, ∂loss/∂θ)` function for fitting [seed] to [target] under [metric], on the
 * grid [fnLossGrid] — or **null** when the exact path does not apply.
 *
 * This is the object [fitGradient]'s `EXACT` / `AUTO` modes descend, and the reason it returns null
 * rather than throwing is that three separate things have to hold, none of which the caller can
 * reasonably check by hand:
 *
 * 1. every op in [seed] is in [FnAutoDiff.differentiableOps];
 * 2. every sample lies inside [seed]'s own domain, so the loss's `evalSafe(outside = NaN)` and the
 *    interpreter's unguarded evaluation cannot disagree about which samples count;
 * 3. the interpreter actually reproduces [Functions.Fn.eval] to within [tolerance] on that grid —
 *    the guard against silently differentiating a subtly different function.
 *
 * @return the exact objective, or null with no side effects if any of the three fails.
 */
fun exactFitObjective(
    target: Functions.Fn,
    seed: Functions.Fn,
    domain: Functions.Domain = Functions.Domain.DEFAULT,
    samples: Int = 128,
    metric: FnAdMetric = FnAdMetric.rmse,
    tolerance: Double = 1e-9
): ((DoubleArray) -> ADGradient)? {
    if (!FnAutoDiff.supports(seed)) return null
    val xs = fnLossGrid(domain, samples)
    if (!xs.all { seed.domain.containsInclusive(it) }) return null
    if (FnAutoDiff.worstValueDisagreement(seed, xs) > tolerance) return null
    val targetY = DoubleArray(xs.size) { target.evalSafe(xs[it], outside = Double.NaN) }
    if (targetY.none { it.isFinite() }) return null
    return { theta -> seed.valueAndGradOverSamples(theta, xs) { ys -> metric.distance(xs, targetY, ys) } }
}

/**
 * Why the exact gradient path was refused for this `target`/`seed` pair — one human-readable line,
 * or null when it *is* available.
 *
 * The diagnostic behind [FnGradientMode.EXACT]'s error message, and worth calling by hand when a
 * fit you expected to run exactly fell back under [FnGradientMode.AUTO] without saying so.
 */
fun exactFitRefusal(
    target: Functions.Fn,
    seed: Functions.Fn,
    domain: Functions.Domain = Functions.Domain.DEFAULT,
    samples: Int = 128,
    tolerance: Double = 1e-9
): String? {
    val unsupported = FnAutoDiff.unsupportedOps(seed)
    if (unsupported.isNotEmpty()) {
        return "the seed contains non-differentiable ops ${unsupported.joinToString(", ")}; " +
            "differentiable: ${FnAutoDiff.differentiableOps.sorted().joinToString(", ")}"
    }
    val xs = fnLossGrid(domain, samples)
    val outside = xs.count { !seed.domain.containsInclusive(it) }
    if (outside > 0) {
        return "$outside of ${xs.size} loss samples fall outside the seed's own domain ${seed.domain}, " +
            "where the loss sees NaN but the interpreter does not — narrow the fit domain to match"
    }
    val worst = FnAutoDiff.worstValueDisagreement(seed, xs)
    if (worst > tolerance) {
        return "the AD interpreter disagrees with Fn.eval by $worst (> $tolerance) on the loss grid"
    }
    val targetY = DoubleArray(xs.size) { target.evalSafe(xs[it], outside = Double.NaN) }
    if (targetY.none { it.isFinite() }) return "the target has no finite sample on the loss grid"
    return null
}
