package afield.datalog

import afield.Table
import afield.ToHTML

/**
 * Datalog for Field — a small deductive database you can point at your own data, or at the live box
 * graph (see `DatalogBoxes.kt`), or write directly in a box (see [afield.datalog.DatalogExecution]).
 *
 * ```kotlin
 * val d = Datalog()
 * d.add("""
 *     edge(a, b).  edge(b, c).  edge(c, d).
 *     path(X, Y) :- edge(X, Y).
 *     path(X, Z) :- path(X, Y), edge(Y, Z).
 * """)
 * println(d.query("path(a, Z)").table())
 * println(d.why("path(a, d)"))
 * ```
 *
 * Facts can equally come from Kotlin, which is the point of having it here rather than shelling out
 * to a real Datalog:
 *
 * ```kotlin
 * d.facts("emp", staff) { listOf(it.name, it.department, it.salary) }
 * d.query("dept(D), S = sum P : { emp(_, D, P) }")
 * ```
 *
 * The model is recomputed lazily and cached until the program changes; [solve] is cheap to call
 * repeatedly, [add] invalidates.
 */
class Datalog {

    private val ruleList = ArrayList<DlRule>()
    private val ruleKeys = HashSet<DlRule>()
    private val edb = LinkedHashMap<String, DlRelation>()
    private val queryList = ArrayList<DlQuery>()
    private var cached: DlSolution? = null
    private var queryCounter = 0

    val rules: List<DlRule> get() = ruleList
    val queries: List<DlQuery> get() = queryList

    /** Base relations exactly as given — before any rule has fired. */
    val facts: Map<String, DlRelation> get() = edb

    // ── building ────────────────────────────────────────────────────────────────────────────────

    /**
     * Parse [source] and merge it in. Ground facts become base relations, everything else a rule;
     * `?-` goals are recorded in [queries] but not run.
     *
     * Re-adding a rule that is already present is a no-op, because a live box re-executes its whole
     * program every time you hit run and a program that doubled in size each time would be useless.
     * The returned [DlUnit] is what this call contributed, which is how the box language finds the
     * queries and `#directives` it should act on.
     */
    fun add(source: String): DlUnit = addParsed(DatalogParser.parse(source))

    /**
     * Merge an already-parsed unit. The box language parses first so it can rewrite a bare
     * non-ground atom — `path(a, Z)` typed on its own — into a query before it reaches [addRule],
     * which would rightly reject it as a fact that is not ground.
     */
    fun addParsed(unit: DlUnit): DlUnit {
        unit.rules.forEach { rule ->
            if (rule.isFact && rule.head.terms.all { it is DlConst }) {
                addFactRow(rule.head.pred, rule.head.terms.map { dlNormalize((it as DlConst).value) })
            } else if (ruleKeys.add(rule)) {
                ruleList.add(rule)
                cached = null
            }
        }
        queryList.addAll(unit.queries)
        return unit
    }

    /** Merge one rule (or ground fact). */
    fun addRule(rule: DlRule): Datalog {
        addParsed(DlUnit(rules = listOf(rule)))
        return this
    }

    /** Add one fact. Values go through [dlNormalize], so `fact("age", "ada", 36)` stores a `Long`. */
    fun fact(pred: String, vararg args: Any?): Datalog {
        addFactRow(pred, args.map { dlNormalize(it) })
        return this
    }

    /** Add many rows at once. */
    fun facts(pred: String, rows: Iterable<Iterable<Any?>>): Datalog {
        rows.forEach { row -> addFactRow(pred, row.map { dlNormalize(it) }) }
        return this
    }

    /** Add many rows by projecting Kotlin objects into columns. */
    fun <T> facts(pred: String, items: Iterable<T>, columns: (T) -> List<Any?>): Datalog {
        items.forEach { item -> addFactRow(pred, columns(item).map { dlNormalize(it) }) }
        return this
    }

    private fun addFactRow(pred: String, row: List<Any?>) {
        val relation = edb.getOrPut(pred) { DlRelation(pred, row.size) }
        if (relation.arity != row.size) {
            throw DlException("'$pred' has arity ${relation.arity}, but this fact has ${row.size} columns")
        }
        if (relation.add(row, 0)) cached = null
    }

    /** Forget everything — facts, rules, queries, and the cached model. */
    fun reset() {
        ruleList.clear()
        ruleKeys.clear()
        edb.clear()
        queryList.clear()
        cached = null
    }

    /** Drop a single relation's base facts (rules that derive into it are untouched). */
    fun clearFacts(pred: String) {
        if (edb.remove(pred) != null) cached = null
    }

    // ── solving ─────────────────────────────────────────────────────────────────────────────────

    /** The saturated model. Cached until the program changes. */
    fun solve(): DlSolution = cached ?: DatalogEngine(ruleList, edb).run().also { cached = it }

    /**
     * Evaluate a goal — a body conjunction, exactly as it would be written on the right of `:-`.
     *
     * Rows come back sorted, because set semantics leave the order arbitrary and an arbitrary order
     * that changes between runs is a bad thing to print next to a box.
     */
    fun query(goal: String): DlRows {
        val body = DatalogParser.parseGoal(goal)
        val vars = dlOutputVarsOf(body).filter { !it.isAnonymous }
        val pred = "__query_${++queryCounter}"
        val rule = DlRule(DlAtom(pred, vars), body, goal)
        val solution = DatalogEngine(ruleList + rule, edb).run()
        val rows = solution.rows(pred).sortedWith { a, b -> compareRows(a, b) }
        return DlRows(goal, vars.map { it.name }, rows, solution.warnings)
    }

    /** True when the goal has at least one solution. */
    fun ask(goal: String): Boolean = query(goal).rows.isNotEmpty()

    /** All rows of one relation in the current model, sorted. */
    fun rowsOf(pred: String): List<DlRow> =
        solve().rows(pred).sortedWith { a, b -> compareRows(a, b) }

    private fun compareRows(a: DlRow, b: DlRow): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val c = dlCompareValues(a[i], b[i])
            if (c != 0) return c
        }
        return a.size.compareTo(b.size)
    }

    // ── provenance ──────────────────────────────────────────────────────────────────────────────

    /**
     * Why is this fact true? Returns a derivation tree, or null if the fact is not in the model.
     *
     * The search is top-down over the finished model, bounded by the round numbers the fixpoint
     * records: a premise must have been derived *strictly earlier* than its conclusion, so
     * a proof can never lean on the thing it is proving, and the recursion always terminates. A goal
     * with variables is answered about the first matching fact.
     */
    fun why(goal: String): DlDerivation? {
        val literals = DatalogParser.parseGoal(goal)
        val atom = literals.singleOrNull() as? DlAtom
            ?: throw DlException("'#why' takes a single positive atom, e.g. '#why path(a, d)'")
        if (atom.negated) throw DlException("'#why' cannot explain a negated goal")
        val solution = solve()
        val relation = solution[atom.pred] ?: return null

        val ground = atom.terms.all { it is DlConst }
        val target: DlRow = if (ground) {
            atom.terms.map { dlNormalize((it as DlConst).value) }
        } else {
            val pattern = arrayOfNulls<Any?>(atom.arity)
            var mask = 0
            atom.terms.forEachIndexed { i, term ->
                if (term is DlConst) {
                    pattern[i] = dlNormalize(term.value)
                    mask = mask or (1 shl i)
                }
            }
            relation.lookup(pattern, mask).minWithOrNull { a, b -> compareRows(a, b) } ?: return null
        }
        if (!relation.contains(target)) return null
        return derive(atom.pred, target, solution, IntArray(1) { MAX_PROOF_STEPS })
    }

    private fun factText(pred: String, row: DlRow): String =
        if (row.isEmpty()) pred else pred + "(" + row.joinToString(", ") { dlSourceFormat(it) } + ")"

    private fun derive(pred: String, row: DlRow, solution: DlSolution, budget: IntArray): DlDerivation? {
        val relation = solution[pred] ?: return null
        if (!relation.contains(row)) return null
        val text = factText(pred, row)
        val round = relation.roundOf(row)
        if (round == 0) return DlDerivation(text, null, emptyList())
        if (budget[0]-- <= 0) return DlDerivation(text, null, emptyList(), note = "proof search truncated")

        for (rule in ruleList) {
            if (rule.head.pred != pred || rule.head.arity != row.size) continue
            val bindings = HashMap<String, Any?>()
            var headMatches = true
            rule.head.terms.forEachIndexed { i, term ->
                if (!headMatches) return@forEachIndexed
                when (term) {
                    is DlConst -> if (dlNormalize(term.value) != row[i]) headMatches = false
                    is DlVar -> {
                        val existing = bindings[term.name]
                        if (existing != null && existing != row[i]) headMatches = false else bindings[term.name] = row[i]
                    }
                }
            }
            if (!headMatches) continue
            val premises = satisfy(rule.body, bindings, solution, round, budget) ?: continue
            return DlDerivation(text, rule, premises)
        }
        return DlDerivation(text, null, emptyList(), note = "derived in round $round; no proof reconstructed")
    }

    /**
     * Find one assignment satisfying [remaining] under [bindings], and the derivations of the
     * positive premises it used. Literals are picked in the same "whatever is evaluable now" order
     * the planner uses; aggregates make the search give up, which is what the `note` on the
     * resulting [DlDerivation] reports.
     */
    private fun satisfy(
        remaining: List<DlLiteral>,
        bindings: Map<String, Any?>,
        solution: DlSolution,
        beforeRound: Int,
        budget: IntArray,
    ): List<DlDerivation>? {
        if (remaining.isEmpty()) return emptyList()
        if (budget[0]-- <= 0) return null

        fun varsBound(arith: DlArith): Boolean {
            var ok = true
            fun walk(a: DlArith) {
                when (a) {
                    is DlAValue -> (a.term as? DlVar)?.let { if (!bindings.containsKey(it.name)) ok = false }
                    is DlABinary -> { walk(a.left); walk(a.right) }
                    is DlAUnary -> walk(a.operand)
                    is DlACall -> a.args.forEach { walk(it) }
                }
            }
            walk(arith)
            return ok
        }

        // Mirrors the planner's ordering: evaluable tests first, then bindings, then anti-joins,
        // then scans. A literal that cannot be evaluated yet must score 99 — scoring an unevaluable
        // comparison *below* a positive atom made `satisfy` pick `V > 5` before the `cell(C, V)`
        // that binds V, fail, and report a real fact as unproven.
        fun compareKind(literal: DlCompare): Int {
            if (varsBound(literal.left) && varsBound(literal.right)) return 0
            if (literal.op != "=") return 99
            val leftVar = (literal.left as? DlAValue)?.term as? DlVar
            val rightVar = (literal.right as? DlAValue)?.term as? DlVar
            if (leftVar != null && !bindings.containsKey(leftVar.name) && varsBound(literal.right)) return 1
            if (rightVar != null && !bindings.containsKey(rightVar.name) && varsBound(literal.left)) return 1
            return 99
        }

        var chosen = -1
        var chosenKind = 99
        remaining.forEachIndexed { i, literal ->
            val kind = when (literal) {
                is DlCompare -> compareKind(literal)
                is DlAtom ->
                    if (!literal.negated) 2
                    else if (literal.terms.all { it is DlConst || bindings.containsKey((it as DlVar).name) }) 0
                    else 99
                is DlAggregate -> 99
            }
            if (kind < chosenKind) { chosen = i; chosenKind = kind }
        }
        if (chosen < 0 || chosenKind >= 99) return null

        val literal = remaining[chosen]
        val rest = remaining.toMutableList().also { it.removeAt(chosen) }

        when (literal) {
            is DlCompare -> {
                // `X = expr` where X is still free is a binding here too.
                val leftVar = (literal.left as? DlAValue)?.term as? DlVar
                val rightVar = (literal.right as? DlAValue)?.term as? DlVar
                if (literal.op == "=" && leftVar != null && !bindings.containsKey(leftVar.name) && varsBound(literal.right)) {
                    val next = HashMap(bindings)
                    next[leftVar.name] = dlEvalArith(literal.right) { bindings[it] }
                    return satisfy(rest, next, solution, beforeRound, budget)
                }
                if (literal.op == "=" && rightVar != null && !bindings.containsKey(rightVar.name) && varsBound(literal.left)) {
                    val next = HashMap(bindings)
                    next[rightVar.name] = dlEvalArith(literal.left) { bindings[it] }
                    return satisfy(rest, next, solution, beforeRound, budget)
                }
                if (!varsBound(literal.left) || !varsBound(literal.right)) return null
                val ok = dlCompareOp(
                    literal.op,
                    dlEvalArith(literal.left) { bindings[it] },
                    dlEvalArith(literal.right) { bindings[it] },
                )
                return if (ok) satisfy(rest, bindings, solution, beforeRound, budget) else null
            }
            is DlAtom -> {
                if (literal.negated) {
                    val row = literal.terms.map { term ->
                        when (term) {
                            is DlConst -> dlNormalize(term.value)
                            is DlVar -> bindings[term.name]
                        }
                    }
                    val present = solution[literal.pred]?.contains(row) == true
                    return if (present) null else satisfy(rest, bindings, solution, beforeRound, budget)
                }
                val relation = solution[literal.pred] ?: return null
                val pattern = arrayOfNulls<Any?>(relation.arity)
                var mask = 0
                literal.terms.forEachIndexed { i, term ->
                    if (i >= relation.arity) return@forEachIndexed
                    val value = when (term) {
                        is DlConst -> dlNormalize(term.value)
                        is DlVar -> if (bindings.containsKey(term.name)) bindings[term.name] else return@forEachIndexed
                    }
                    pattern[i] = value
                    mask = mask or (1 shl i)
                }
                val candidates = relation.lookup(pattern, mask)
                    .filter { relation.roundOf(it) < beforeRound }
                    .sortedBy { relation.roundOf(it) }
                for (candidate in candidates) {
                    val next = HashMap(bindings)
                    var ok = true
                    literal.terms.forEachIndexed { i, term ->
                        if (!ok) return@forEachIndexed
                        if (term is DlVar) {
                            val existing = next[term.name]
                            if (existing != null && existing != candidate[i]) ok = false else next[term.name] = candidate[i]
                        }
                    }
                    if (!ok) continue
                    val premise = derive(literal.pred, candidate, solution, budget) ?: continue
                    val tail = satisfy(rest, next, solution, beforeRound, budget) ?: continue
                    return listOf(premise) + tail
                }
                return null
            }
            is DlAggregate -> return null
        }
    }

    // ── rendering ───────────────────────────────────────────────────────────────────────────────

    /** The program as source you could paste back in. */
    fun program(): String = buildString {
        edb.values.forEach { relation ->
            relation.rows().forEach { row ->
                append(relation.name)
                if (row.isNotEmpty()) append(row.joinToString(", ", "(", ")") { dlSourceFormat(it) })
                append(".\n")
            }
        }
        ruleList.forEach { append(it.toString()).append('\n') }
    }

    override fun toString(): String =
        "Datalog(${edb.values.sumOf { it.size }} facts, ${ruleList.size} rules)"

    companion object {
        /** A ceiling on proof search, so `#why` on a pathological program answers rather than hangs. */
        const val MAX_PROOF_STEPS = 200_000
    }
}

/**
 * A query result: named columns and sorted rows.
 *
 * A goal with no variables answers `yes`/`no` — [rows] is one empty row or none — which is why
 * [table] special-cases the zero-column shape rather than printing an empty frame.
 */
class DlRows(
    val goal: String,
    val columns: List<String>,
    val rows: List<DlRow>,
    val warnings: List<String> = emptyList(),
) : Iterable<DlRow>, ToHTML {

    override fun iterator(): Iterator<DlRow> = rows.iterator()

    val size: Int get() = rows.size
    val isEmpty: Boolean get() = rows.isEmpty()

    /** The single value of a single-column, single-row result — the common "just tell me" case. */
    fun value(): Any? = rows.firstOrNull()?.firstOrNull()

    fun column(name: String): List<Any?> {
        val i = columns.indexOf(name)
        if (i < 0) throw DlException("no column '$name' in [${columns.joinToString(", ")}]")
        return rows.map { it[i] }
    }

    fun asMaps(): List<Map<String, Any?>> = rows.map { row -> columns.zip(row).toMap() }

    /** A monospaced table, for a box's output area or a terminal. */
    fun table(limit: Int = 200): String {
        if (columns.isEmpty()) return if (rows.isEmpty()) "no" else "yes"
        if (rows.isEmpty()) return "(no rows)"
        val shown = rows.take(limit)
        val widths = columns.indices.map { i ->
            maxOf(columns[i].length, shown.maxOf { dlFormat(it.getOrNull(i)).length })
        }
        return buildString {
            columns.forEachIndexed { i, name -> append(name.padEnd(widths[i])); if (i < columns.size - 1) append("  ") }
            append('\n')
            widths.forEachIndexed { i, w -> append("-".repeat(w)); if (i < widths.size - 1) append("  ") }
            shown.forEach { row ->
                append('\n')
                columns.indices.forEach { i ->
                    append(dlFormat(row.getOrNull(i)).padEnd(if (i == columns.size - 1) 0 else widths[i]))
                    if (i < columns.size - 1) append("  ")
                }
            }
            if (rows.size > shown.size) append("\n… ${rows.size - shown.size} more rows")
        }
    }

    override fun toString() = table()

    /**
     * The same answer as [table], as HTML — so `display("main", d.query(…))` in a box shows a real
     * table rather than a wall of monospace.
     *
     * A goal with no variables has no columns to draw, so it renders as the one word it means.
     * [afield.Table] does the drawing, which is what keeps a Datalog answer looking like every
     * other table in Field rather than like Datalog.
     */
    override fun toHTML(): String {
        if (columns.isEmpty()) {
            val answer = if (rows.isEmpty()) "no" else "yes"
            return Table(listOf("?- $goal"), title = null).also { it.showHeader = false }.row(answer).toHTML()
        }
        val table = Table(columns, title = "?- $goal   (${rows.size} row${if (rows.size == 1) "" else "s"})")
        table.maxRows = 24
        columns.forEach { table.color(it, DL_VALUE_COLOUR) }
        columns.firstOrNull()?.let { table.color(it, DL_KEY_COLOUR) }
        if (warnings.isNotEmpty()) table.note = warnings.joinToString("; ")
        return table.rows(rows.map { row -> row.map { dlFormat(it) } }).toHTML()
    }
}

/** The accent the first column takes, matching `afield.graphquery`'s path column. */
internal const val DL_KEY_COLOUR = "#8cf"
internal const val DL_VALUE_COLOUR = "#ccc"
internal const val DL_MUTED_COLOUR = "#888"
