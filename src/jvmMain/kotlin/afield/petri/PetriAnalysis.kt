@file:Suppress("MemberVisibilityCanBePrivate", "unused")
@file:afield.CompletionCategory("Visualization")

package afield.petri

import afield.four.synth.PatQ

/*
 What the net can tell you about itself.

 Petri nets come with about fifty years of theory attached, and almost all of it answers questions
 a live coder actually has. Two of them are worth the code:

 **"How long is this?"** A *T-invariant* is a bag of firings that returns the net to the marking it
 started from. It is, precisely, a loop — and a net's minimal T-invariants are its natural musical
 periods, which is a strange and good thing to be able to compute. A net built from a five-step
 ring against a three-step ring does not have to be told its bar is fifteen; it knows.

 [tInvariants] answers this structurally, from the incidence matrix alone, with no notion of time
 or of the initial marking. [PetriRun.lasso] answers it empirically, by running the net until it
 repeats, and gives you a duration in cycles. The two agree — [PetriNet.checkLoopIsInvariant] is
 the assertion that they do, and it is worth keeping around because when it fails, the engine and
 the theory disagree and one of them is wrong.

 **"Is this about to wedge?"** A net that starves is silent, and finding out at the wrong moment is
 what makes live coding frightening. [willDeadlock] runs the net forward and tells you before you
 commit the change.

 The honest limits, stated once
 ------------------------------

 - Invariants are structural. They see arc weights and nothing else: **inhibitor and read arcs are
   invisible to them**, because neither changes the marking and the incidence matrix is a record of
   change. A net whose behaviour turns on an inhibitor arc will have invariants that describe a net
   it is not. [usesTestArcs] is how you find out, and the analysis says so itself.

 - Recurrence is detected on [PetriMarking.signature], which compares colours by `toString`. Two
   different patterns both render as `PatPattern(...)`, so a net that swaps one rhythm for another
   looks, to the loop detector, like a net that did nothing. That is usually what you want from a
   *structural* period and occasionally not what you want at all.

 - Everything here is bounded. A net with an unbounded place has infinitely many markings and no
   loop to find; the answer is null rather than a hang.
*/

// ── the incidence matrix ───────────────────────────────────────────────────────────────────

/**
 * The net's incidence matrix: `C[place][transition]` is the net change in that place's token count
 * when that transition fires once.
 *
 * Rows are [PetriNet.placeNames] in order, columns are [PetriNet.transitionNames] in order. Read
 * and inhibitor arcs contribute nothing, because neither moves a token — see the file header.
 */
fun PetriNet.incidence(): Array<IntArray> {
    val placeIndex = placeNames.withIndex().associate { (index, name) -> name to index }
    val matrix = Array(places.size) { IntArray(transitions.size) }
    for ((column, transition) in transitions.withIndex()) {
        for (arc in transition.inputs) {
            if (arc.kind != PetriArcKind.NORMAL) continue
            placeIndex[arc.place]?.let { matrix[it][column] -= arc.weight }
        }
        for (arc in transition.outputs) {
            placeIndex[arc.place]?.let { matrix[it][column] += arc.weight }
        }
    }
    return matrix
}

/** True when any arc is a read or inhibitor arc — the ones the incidence matrix cannot see. */
val PetriNet.usesTestArcs: Boolean
    get() = transitions.any { transition -> transition.inputs.any { it.kind != PetriArcKind.NORMAL } }

/** The incidence matrix as a readable grid, transitions across the top. */
fun PetriNet.incidenceTable(): String {
    val matrix = incidence()
    val width = maxOf(6, (placeNames.maxOfOrNull { it.length } ?: 0) + 1)
    val columns = transitionNames.map { maxOf(3, it.length) + 1 }
    return buildString {
        append("".padEnd(width))
        transitionNames.forEachIndexed { index, name -> append(name.padStart(columns[index])) }
        append('\n')
        placeNames.forEachIndexed { row, place ->
            append(place.padEnd(width))
            matrix[row].forEachIndexed { column, value ->
                append((if (value > 0) "+$value" else value.toString()).padStart(columns[column]))
            }
            append('\n')
        }
    }
}

// ── invariants ─────────────────────────────────────────────────────────────────────────────

/**
 * The net's **minimal T-invariants**: bags of firings that leave the marking exactly as they found
 * it.
 *
 * Each result maps a transition name to how many times it fires; transitions not in the map do not
 * fire at all. Minimal means no result's support is a strict subset of another's, so what you get
 * back is the net's irreducible loops rather than every combination of them.
 *
 * Musically this is the net's period structure. An eight-step ring returns one invariant firing
 * each of its eight transitions once. A net holding two rings returns two invariants, one per ring,
 * and the fact that they are *separate* invariants is the statement that the rings are independent
 * — which is exactly when they can be given different tempi without the net falling apart.
 *
 * Computed by the Farkas / Colom–Silva elimination: augment the transposed incidence matrix with an
 * identity, eliminate one place at a time by combining positive and negative rows, and prune
 * non-minimal supports at every stage so the intermediate sets stay small.
 */
fun PetriNet.tInvariants(maxRows: Int = 4_096): List<Map<String, Int>> {
    val matrix = incidence()
    val transposed = Array(transitions.size) { column -> IntArray(places.size) { row -> matrix[row][column] } }
    return petriSemiflows(transposed, maxRows).map { flow ->
        transitionNames.withIndex().filter { flow[it.index] != 0L }.associate { it.value to flow[it.index].toInt() }
    }
}

/**
 * The net's **minimal P-invariants**: weighted sums of token counts that never change, whatever
 * fires.
 *
 * The companion of [tInvariants], and the same computation on the untransposed matrix. Where a
 * T-invariant is a loop in time, a P-invariant is a conservation law in space: "these three places
 * always hold exactly one token between them" is a P-invariant, and it is the formal way of saying
 * that a ring has exactly one thing walking round it. A net that has lost a P-invariant you thought
 * it had is a net with a leak.
 */
fun PetriNet.pInvariants(maxRows: Int = 4_096): List<Map<String, Int>> =
    petriSemiflows(incidence(), maxRows).map { flow ->
        placeNames.withIndex().filter { flow[it.index] != 0L }.associate { it.value to flow[it.index].toInt() }
    }

/**
 * Minimal non-negative integer solutions of `rows · x = 0`, where `x` indexes the *columns*.
 *
 * The Farkas algorithm: carry an identity block alongside the matrix so that eliminating the
 * matrix columns leaves the coefficients that did it. Support-minimality pruning after every
 * elimination is what keeps this from exploding — without it the intermediate row count is
 * exponential in earnest rather than in principle.
 */
private fun petriSemiflows(rows: Array<IntArray>, maxRows: Int): List<LongArray> {
    if (rows.isEmpty()) return emptyList()
    val columns = rows[0].size
    val count = rows.size
    val width = columns + count

    // Row i starts as [ matrix row i | e_i ].
    var working = rows.mapIndexed { index, row ->
        LongArray(width) { position ->
            if (position < columns) row[position].toLong() else if (position - columns == index) 1L else 0L
        }
    }

    for (column in 0 until columns) {
        val zero = working.filter { it[column] == 0L }
        val positive = working.filter { it[column] > 0L }
        val negative = working.filter { it[column] < 0L }
        val next = ArrayList<LongArray>(zero)
        outer@ for (plus in positive) {
            for (minus in negative) {
                val a = -minus[column]
                val b = plus[column]
                val combined = LongArray(width) { position -> plus[position] * a + minus[position] * b }
                normaliseByGcd(combined)
                next += combined
                if (next.size > maxRows) break@outer
            }
        }
        working = pruneToMinimalSupports(next, columns, width)
        if (working.isEmpty()) return emptyList()
    }

    return working
        .map { it.copyOfRange(columns, width) }
        .filter { flow -> flow.any { it != 0L } && flow.all { it >= 0L } }
        .distinctBy { it.toList() }
        .let { pruneFlowsToMinimalSupports(it) }
}

private fun normaliseByGcd(row: LongArray) {
    var divisor = 0L
    for (value in row) divisor = gcd(divisor, Math.abs(value))
    if (divisor > 1L) for (index in row.indices) row[index] /= divisor
}

private tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)

/** Drops rows whose coefficient support strictly contains another row's, and exact duplicates. */
private fun pruneToMinimalSupports(rows: List<LongArray>, columns: Int, width: Int): List<LongArray> {
    val unique = rows.distinctBy { it.toList() }
    val supports = unique.map { row -> (columns until width).filter { row[it] != 0L }.toSet() }
    return unique.filterIndexed { index, _ ->
        val mine = supports[index]
        mine.isNotEmpty() && supports.indices.none { other ->
            other != index && supports[other].isNotEmpty() && mine.containsAll(supports[other]) &&
                (supports[other].size < mine.size || (supports[other] == mine && other < index))
        }
    }
}

private fun pruneFlowsToMinimalSupports(flows: List<LongArray>): List<LongArray> {
    val supports = flows.map { flow -> flow.indices.filter { flow[it] != 0L }.toSet() }
    return flows.filterIndexed { index, _ ->
        val mine = supports[index]
        supports.indices.none { other ->
            other != index && mine.containsAll(supports[other]) &&
                (supports[other].size < mine.size || (supports[other] == mine && other < index))
        }
    }
}

// ── period ─────────────────────────────────────────────────────────────────────────────────

/**
 * How long the net takes to come back round to where it started, in cycles, or null if it does not
 * within [maxSteps].
 *
 * Measured, not derived: the net is run until its state recurs. That is the number to hand a
 * scheduler, because it accounts for the timing semantics, the initial marking and the conflict
 * policy — none of which a structural invariant knows about.
 */
fun PetriNet.period(maxSteps: Int = 4_096): PatQ? = run().lasso(maxSteps)?.duration

/** The net's lasso: its run-in, its loop, and the firing counts of one turn. See [PetriRun.lasso]. */
fun PetriNet.settle(maxSteps: Int = 4_096): PetriLasso? = run().lasso(maxSteps)

/**
 * Checks that the loop the net actually runs is a genuine T-invariant — that firing each transition
 * as often as one turn of the loop does really returns the marking to where it was.
 *
 * This is the bridge between the two halves of this file, and the reason to keep it: the engine
 * computes the loop by *running*, the incidence matrix computes it by *algebra*, and if they ever
 * disagree then one of them has a bug. Returns null when they agree or when there is nothing to
 * compare, and a description of the discrepancy when they do not.
 *
 * Nets with read or inhibitor arcs are exempt: the incidence matrix cannot see those arcs, so the
 * comparison would be against the wrong net.
 */
fun PetriNet.checkLoopIsInvariant(maxSteps: Int = 4_096): String? {
    if (usesTestArcs) return null
    val lasso = settle(maxSteps) ?: return null
    val matrix = incidence()
    val counts = transitionNames.map { lasso.firingCounts[it] ?: 0 }
    if (counts.all { it == 0 }) return null
    for ((row, place) in placeNames.withIndex()) {
        val net = matrix[row].indices.sumOf { column -> matrix[row][column] * counts[column] }
        if (net != 0) {
            return "one turn of the loop changes '$place' by $net, so it is not a T-invariant " +
                "(firings: ${lasso.firingCounts})"
        }
    }
    return null
}

// ── deadlock ───────────────────────────────────────────────────────────────────────────────

/**
 * Does this net stop? The check to run before committing a live edit.
 *
 * Runs the net forward under its own semantics and reports whether it reaches a marking that
 * enables nothing with nothing in flight. A net that finds its loop is safe; a net that neither
 * loops nor stops within [within] steps is reported as safe too, because it is still going.
 *
 * This is the deterministic question — "does *this* net, configured *this* way, wedge?" — not the
 * classical one about whether some interleaving could. For a live coder the deterministic question
 * is the useful one, because the conflict policy is part of the instrument.
 */
fun PetriNet.willDeadlock(within: Int = 4_096): Boolean {
    val run = run()
    run.lasso(within)
    return run.halt == PetriHalt.DEADLOCK
}

/**
 * The marking the net stops in, or null if it does not stop within [within] steps.
 *
 * Useful in a way `willDeadlock` is not: it tells you *which* places are starved, which is usually
 * enough to see the missing arc.
 */
fun PetriNet.deadlockMarking(within: Int = 4_096): PetriMarking? {
    val run = run()
    run.lasso(within)
    if (run.halt != PetriHalt.DEADLOCK) return null
    return run.steps(Int.MAX_VALUE).lastOrNull()?.after ?: initial
}

/**
 * Transitions that never fire, however long the net runs.
 *
 * The most common thing wrong with a net that sounds thinner than it reads: an arc pointing at the
 * wrong place, and a whole voice silently doing nothing.
 */
fun PetriNet.deadTransitions(within: Int = 2_048): List<String> {
    val run = run()
    run.lasso(within)
    val fired = run.steps(Int.MAX_VALUE).flatMap { step -> step.firings.map { it.transition } }.toSet()
    return transitionNames.filter { it !in fired }
}

// ── one report ─────────────────────────────────────────────────────────────────────────────

/**
 * Everything above, in a paragraph — the thing to print at a net while writing it.
 *
 *     println(net.report())
 */
fun PetriNet.report(maxSteps: Int = 4_096): String = buildString {
    append("${places.size} places, ${transitions.size} transitions, $timing at grid $grid\n")
    append("marking  $initial\n")

    val lasso = settle(maxSteps)
    when {
        willDeadlock(maxSteps) -> {
            append("DEADLOCK at ${deadlockMarking(maxSteps)}\n")
        }
        lasso != null -> {
            append("period   ${lasso.duration} cycles (${lasso.loopSteps} steps")
            if (lasso.tailSteps > 0) append(", after a ${lasso.tailSteps}-step run-in")
            append(")\n")
        }
        else -> append("period   not found within $maxSteps steps\n")
    }

    val dead = deadTransitions(maxSteps)
    if (dead.isNotEmpty()) append("never fires: ${dead.joinToString(", ")}\n")

    val invariants = tInvariants()
    if (invariants.isEmpty()) append("no T-invariants: nothing here returns to where it started\n")
    else {
        append("T-invariants (${invariants.size}):\n")
        for (invariant in invariants) {
            append("  " + invariant.entries.sortedBy { it.key }.joinToString(" + ") {
                if (it.value == 1) it.key else "${it.value}*${it.key}"
            } + "\n")
        }
    }
    if (usesTestArcs) {
        append("note: this net uses read or inhibitor arcs, which the invariants cannot see\n")
    }
    checkLoopIsInvariant(maxSteps)?.let { append("WARNING: $it\n") }
}
