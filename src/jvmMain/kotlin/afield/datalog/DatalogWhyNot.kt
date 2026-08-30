package afield.datalog

import afield.ToHTML

/**
 * Why a goal has *no* answers — the other half of [Datalog.why].
 *
 * A query that comes back empty gives no reason, and a reader with no reason guesses. There is a
 * recorded instance of exactly that: a workspace transcript from 2026-08-29 has a path query return
 * silently empty at 16:18:28, a guess at the operator three seconds later, and the right answer only
 * at 16:18:51. Twenty-three seconds spent on a question the system could have answered.
 *
 * The answer this gives is the one a person actually wants: **walk the conjunction, and say which
 * literal took the answer set to zero, and show a binding that got that far.**
 * For a single derived atom it does the same walk once per rule that could have produced it, and
 * shows the attempt that got furthest — the "closest match", which is what a reader is looking for
 * when they ask why something is missing.
 *
 * ```
 * whynot path(a, e)   not in the model
 *   via path(X, Y) :- edge(X, Y)
 *       1  edge(a, e)                     0 answers   <- nothing survives here
 *   via path(X, Z) :- path(X, Y), edge(Y, Z)
 *       1  path(a, Y)                     3 answers
 *       2  edge(Y, e)                     0 answers   <- nothing survives here
 *       a binding that got this far: Y = b
 * ```
 *
 * **It counts with the real evaluator, not a second one.** Each prefix of the conjunction becomes a
 * synthesised rule and is put through [DatalogEngine], so the numbers are the engine's own and
 * cannot drift from what a query would answer. The cost is one solve per prefix, paid because this
 * is an interactive question asked about a goal with a handful of literals, and because a
 * why-not that disagreed with the query it is explaining would be worse than no why-not.
 */
class DlWhyNot(
    val goal: String,
    /** True when the goal *does* have answers — ask [Datalog.why] instead. */
    val present: Boolean,
    val steps: List<DlNarrowing>,
    /**
     * One binding that survived the last step that had any answers — an example of how far the
     * conjunction got before it emptied. Empty when nothing survived even the first literal.
     */
    val witness: Map<String, Any?>,
    /** One entry per rule that could have produced a single-atom goal, best first. */
    val attempts: List<DlWhyNot> = emptyList(),
    /** Which rule this attempt belongs to; null for the top-level goal. */
    val rule: DlRule? = null,
    val note: String? = null,
) : ToHTML {

    /** The first step whose answer count is zero — the literal that emptied the conjunction. */
    val culprit: DlNarrowing? get() = steps.firstOrNull { it.answers == 0 }

    /** How many literals held before the walk stopped. What "got furthest" is measured on. */
    val depth: Int get() = steps.count { (it.answers ?: 0) > 0 }

    fun render(indent: String = ""): String = buildString {
        if (rule == null) {
            append(indent).append("whynot ").append(goal).append("   ")
            append(if (present) "is in the model — ask #why instead" else note ?: "no answers")
        } else {
            append(indent).append("via ").append(rule.toString().removeSuffix("."))
            note?.let { append("   ").append(it) }
        }
        val width = steps.maxOfOrNull { it.literal.length } ?: 0
        steps.forEach { step ->
            append("\n").append(indent).append("    ")
            append(step.step.toString().padStart(2)).append("  ")
            append(step.literal.padEnd(width)).append("  ")
            append(step.describe())
            if (step === culprit) append("   <- nothing survives here")
        }
        if (witness.isNotEmpty()) {
            append("\n").append(indent).append("    a binding that got this far: ")
            append(witness.entries.joinToString(", ") { "${it.key} = ${dlFormat(it.value)}" })
        }
        attempts.forEach { append("\n").append(it.render("$indent  ")) }
    }

    override fun toString() = render()

    /**
     * The same walk as HTML — an indented tree in the house colours, matching [DlDerivation.toHTML]
     * rather than a [afield.Table], because a why-not is a shape too: the attempt that got furthest
     * is the answer, and a flat table hides which attempt each row belongs to.
     */
    override fun toHTML(): String = buildString {
        append("<div style=\"font:12px/1.5 'Iosevka Web',monospace;color:$DL_VALUE_COLOUR\">")
        append("<div style='color:$DL_KEY_COLOUR;margin-bottom:5px'>whynot " + escapeDlHtml(goal) + "</div>")
        appendNode(this@DlWhyNot, 0)
        append("</div>")
    }

    private fun StringBuilder.appendNode(node: DlWhyNot, depth: Int) {
        val heading = when {
            node.rule != null -> "via " + node.rule.toString().removeSuffix(".")
            node.present -> "is in the model — ask why instead"
            else -> node.note
        }
        heading?.let {
            append("<div style='padding-left:" + (depth * 18) + "px;color:$DL_MUTED_COLOUR'>")
            append(escapeDlHtml(it)).append("</div>")
        }
        val culprit = node.culprit
        node.steps.forEach { step ->
            val colour = if (step === culprit) DL_FAIL_COLOUR else DL_VALUE_COLOUR
            append("<div style='padding-left:" + ((depth + 1) * 18) + "px'>")
            append("<span style='color:$colour'>" + escapeDlHtml(step.literal) + "</span>")
            append("<span style='color:$DL_MUTED_COLOUR'>&nbsp;&nbsp;" + escapeDlHtml(step.describe()) + "</span>")
            append("</div>")
        }
        if (node.witness.isNotEmpty()) {
            append("<div style='padding-left:" + ((depth + 1) * 18) + "px;color:$DL_MUTED_COLOUR'>")
            append("a binding that got this far: ")
            append(escapeDlHtml(node.witness.entries.joinToString(", ") { "${it.key} = ${dlFormat(it.value)}" }))
            append("</div>")
        }
        node.attempts.forEach { appendNode(it, depth + 1) }
    }
}

/** The accent a failing literal takes — the one line a reader is looking for. */
internal const val DL_FAIL_COLOUR = "#f88"

/**
 * One step of a why-not walk: a literal, and how many answers the conjunction had once it was
 * added. [answers] is null when the prefix could not be evaluated on its own — a comparison written
 * before the atom that binds its variable, most often, which is itself worth being told.
 */
class DlNarrowing(val step: Int, val literal: String, val answers: Int?) {
    fun describe(): String = when (answers) {
        null -> "not evaluable this early — nothing has bound its variables yet"
        0 -> "0 answers"
        1 -> "1 answer"
        else -> "$answers answers"
    }
}

/**
 * Explain why [goal] has no answers.
 *
 * An extension rather than a method so that the whole why-not layer sits in one file and the
 * fixpoint's file does not grow a second personality; it needs nothing private from [Datalog].
 *
 * The walk is in the order the goal was *written*, not the order the planner picks, because the
 * question is about the reader's conjunction and the reader is looking at their own text. That does
 * mean a prefix can be unevaluable where the whole goal is fine — reported as such, since "your
 * test comes before the atom that binds it" is a useful thing to hear.
 */
fun Datalog.whyNot(goal: String): DlWhyNot {
    val literals = DatalogParser.parseGoal(goal)
    if (query(goal).rows.isNotEmpty()) {
        return DlWhyNot(goal, present = true, steps = emptyList(), witness = emptyMap())
    }

    val (steps, witness) = narrowingOf(literals)

    // A single derived atom deserves the walk once per rule that could have made it: "which body
    // atom failed" is unanswerable at the level of the atom itself, whose count is simply zero.
    val single = literals.singleOrNull() as? DlAtom
    val attempts = ArrayList<DlWhyNot>()
    if (single != null && !single.negated) {
        val candidates = rules.filter { it.head.pred == single.pred && it.head.arity == single.arity }
        if (candidates.isEmpty() && solve()[single.pred] == null) {
            // The step list would say "the atom you asked about has 0 answers", which is a restatement
            // of the question. The note is the whole answer here.
            return DlWhyNot(
                goal,
                present = false,
                steps = emptyList(),
                witness = emptyMap(),
                note = "nothing defines ${single.pred}/${single.arity} — no facts and no rules",
            )
        }
        candidates.forEach { rule ->
            val bindings = unifyHead(rule.head, single)
            if (bindings == null) {
                attempts.add(
                    DlWhyNot(
                        goal, present = false, steps = emptyList(), witness = emptyMap(),
                        rule = rule, note = "cannot match this head",
                    )
                )
                return@forEach
            }
            val body = rule.body.map { dlSubstitute(it, bindings) }
            val (bodySteps, bodyWitness) = narrowingOf(body)
            attempts.add(
                DlWhyNot(goal, present = false, steps = bodySteps, witness = bodyWitness, rule = rule)
            )
        }
        // Best first: the attempt that satisfied the most literals is the closest match, and the
        // closest match is what a reader is asking for.
        attempts.sortByDescending { it.depth }
    }

    val note = when {
        attempts.isNotEmpty() -> "not in the model; ${attempts.size} rule(s) could have produced it"
        steps.any { it.answers == 0 } -> "no answers"
        else -> "no answers, and no single literal is empty — the join is what fails"
    }
    // With per-rule attempts in hand the top-level walk is a single step reading "the atom you
    // asked about has 0 answers" — a restatement of the question, and noise above the answer.
    return DlWhyNot(
        goal,
        present = false,
        steps = if (attempts.isEmpty()) steps else emptyList(),
        witness = if (attempts.isEmpty()) witness else emptyMap(),
        attempts = attempts,
        note = note,
    )
}

/**
 * Count the answers of every prefix of [literals], and keep one binding that survived the last
 * prefix that had any.
 *
 * Each prefix goes through a real [DatalogEngine] run as a synthesised rule, so the counts are the
 * engine's and not a re-implementation's. A prefix that is not safe on its own throws, is caught,
 * and is reported as unevaluable rather than as zero — the difference between "nothing matches" and
 * "you cannot ask this yet" is the whole content of the answer in that case.
 */
private fun Datalog.narrowingOf(literals: List<DlLiteral>): Pair<List<DlNarrowing>, Map<String, Any?>> {
    val steps = ArrayList<DlNarrowing>()
    var witness: Map<String, Any?> = emptyMap()
    var lastSurviving: Map<String, Any?> = emptyMap()

    for (k in 1..literals.size) {
        val prefix = literals.take(k)
        val vars = dlOutputVarsOf(prefix).filter { !it.isAnonymous }
        val rows = answersOfPrefix(prefix, vars)
        steps.add(DlNarrowing(k, dlLiteralText(literals[k - 1]), rows?.size))
        if (rows == null) continue
        if (rows.isEmpty()) {
            witness = lastSurviving
            break
        }
        lastSurviving = vars.map { it.name }.zip(rows.first()).toMap()
    }
    return steps to witness
}

/**
 * The rows of one prefix, sorted, or null when the prefix is not safe on its own.
 *
 * Sorted for the same reason [Datalog.query] sorts: the witness binding is printed next to a box,
 * and a witness that changes between two runs of the same program is a bad thing to print.
 */
private fun Datalog.answersOfPrefix(prefix: List<DlLiteral>, vars: List<DlVar>): List<DlRow>? = try {
    val pred = "__whynot_${WHY_NOT_COUNTER.incrementAndGet()}"
    val rule = DlRule(DlAtom(pred, vars), prefix, null)
    DatalogEngine(rules + rule, facts).run().rows(pred).sortedWith { a, b ->
        var c = 0
        for (i in 0 until minOf(a.size, b.size)) {
            c = dlCompareValues(a[i], b[i])
            if (c != 0) break
        }
        if (c != 0) c else a.size.compareTo(b.size)
    }
} catch (e: DlException) {
    null
}

/**
 * Bind [head]'s variables from [goal]'s constants, or null when the two cannot match at all.
 *
 * A head constant that disagrees with the goal, or one head variable asked to be two different
 * values, means this rule could never have produced the fact — worth saying, and worth not walking.
 */
private fun unifyHead(head: DlAtom, goal: DlAtom): Map<String, Any?>? {
    val bindings = HashMap<String, Any?>()
    head.terms.forEachIndexed { i, term ->
        val other = goal.terms[i]
        if (other !is DlConst) return@forEachIndexed
        val value = dlNormalize(other.value)
        when (term) {
            is DlConst -> if (dlNormalize(term.value) != value) return null
            is DlVar -> {
                if (bindings.containsKey(term.name) && bindings[term.name] != value) return null
                bindings[term.name] = value
            }
        }
    }
    return bindings
}

private val WHY_NOT_COUNTER = java.util.concurrent.atomic.AtomicInteger(0)
