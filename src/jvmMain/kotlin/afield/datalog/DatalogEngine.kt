package afield.datalog

import afield.Table
import afield.ToHTML

/**
 * Storage and evaluation: indexed relations, a safety-directed body planner, stratification, and a
 * semi-naive fixpoint.
 *
 * Three invariants hold the thing together, and each one buys a feature:
 *
 *  - **Rows are planned, not interpreted.** Each rule is compiled once into a list of [DlStep]s over
 *    integer variable slots, and the planner decides statically whether each argument position binds
 *    or filters. Nothing consults a "is this variable bound yet" map at join time.
 *  - **Facts derived in a round are not visible until the round ends.** New rows go to a pending
 *    buffer and are merged at the end of the iteration, so every fact's [DlRelation.roundOf] is
 *    strictly greater than the rounds of the facts that derived it. That is what makes
 *    [Datalog.why] terminate without an occurs-check hack: a proof may only descend to
 *    strictly earlier rounds. The counter is *global*, not per-stratum, so the same ordering holds
 *    across a stratum boundary and a proof may descend through negation and aggregation too.
 *  - **Negation and aggregation are stratified.** Both are "negative" edges in the dependency graph;
 *    a negative edge inside a strongly-connected component is rejected up front with the cycle
 *    spelled out, rather than producing an answer that depends on evaluation order.
 */

/** A tuple. Elements are already [dlNormalize]d. */
typealias DlRow = List<Any?>

/**
 * One predicate's extension, with lazily-built hash indices.
 *
 * Indices are keyed by the bitmask of bound argument positions and are maintained *incrementally* on
 * insert, because a relation is inserted into throughout a fixpoint and rebuilding per round turned
 * transitive closure quadratic in an early version.
 */
class DlRelation(val name: String, val arity: Int) {
    private val rowSet = LinkedHashSet<DlRow>()
    private val rounds = HashMap<DlRow, Int>()
    private val indices = HashMap<Int, HashMap<DlRow, MutableList<DlRow>>>()

    val size: Int get() = rowSet.size
    fun rows(): Collection<DlRow> = rowSet
    fun contains(row: DlRow): Boolean = rowSet.contains(row)

    /** The fixpoint iteration this row first appeared in; 0 for facts stated in the source. */
    fun roundOf(row: DlRow): Int = rounds[row] ?: 0

    fun add(row: DlRow, round: Int = 0): Boolean {
        if (!rowSet.add(row)) return false
        rounds[row] = round
        for ((mask, index) in indices) {
            index.getOrPut(key(row, mask)) { ArrayList() }.add(row)
        }
        return true
    }

    /** Rows matching [pattern] at the positions set in [mask]. */
    fun lookup(pattern: Array<Any?>, mask: Int): Collection<DlRow> {
        if (mask == 0) return rowSet
        val index = indices.getOrPut(mask) {
            val built = HashMap<DlRow, MutableList<DlRow>>()
            rowSet.forEach { built.getOrPut(key(it, mask)) { ArrayList() }.add(it) }
            built
        }
        return index[key(pattern, mask)] ?: emptyList()
    }

    private fun key(row: List<Any?>, mask: Int): DlRow {
        val out = ArrayList<Any?>(Integer.bitCount(mask))
        for (i in 0 until arity) if ((mask shr i) and 1 == 1) out.add(row[i])
        return out
    }

    private fun key(row: Array<Any?>, mask: Int): DlRow {
        val out = ArrayList<Any?>(Integer.bitCount(mask))
        for (i in 0 until arity) if ((mask shr i) and 1 == 1) out.add(row[i])
        return out
    }

    override fun toString() = "$name/$arity (${rowSet.size} rows)"
}

// ── compiled plans ──────────────────────────────────────────────────────────────────────────────

internal sealed interface DlArg
internal class DlArgConst(val value: Any?) : DlArg
internal class DlArgBind(val slot: Int) : DlArg
internal class DlArgTest(val slot: Int) : DlArg
/** A "don't care" position under negation: `!path(X, _)` asks whether *any* row matches. */
internal object DlArgWild : DlArg

internal sealed interface DlStep
internal class DlScan(val pred: String, val args: List<DlArg>, val recursiveOrdinal: Int) : DlStep
internal class DlAntiJoin(val pred: String, val args: List<DlArg>) : DlStep
internal class DlTest(val op: String, val left: DlArith, val right: DlArith) : DlStep
internal class DlAssign(val slot: Int, val expr: DlArith) : DlStep
internal class DlAgg(val slot: Int, val op: String, val over: DlArith?, val sub: List<DlStep>) : DlStep

internal class DlPlan(
    val rule: DlRule,
    val slotCount: Int,
    val slotIndex: Map<String, Int>,
    val steps: List<DlStep>,
    val headArgs: List<DlArg>,
    val recursiveScanCount: Int,
)

// ── value helpers ───────────────────────────────────────────────────────────────────────────────

/**
 * A total order over Datalog values, so `<` never throws and `min`/`max`/`collect` are deterministic
 * across runs (which the smoke test depends on, and a user comparing two query outputs does too).
 * Across kinds the order is null < boolean < number < string < anything else by class name.
 */
fun dlCompareValues(a: Any?, b: Any?): Int {
    fun rank(v: Any?): Int = when (v) {
        null -> 0
        is Boolean -> 1
        is Number -> 2
        is String -> 3
        else -> 4
    }
    val ra = rank(a)
    val rb = rank(b)
    if (ra != rb) return ra.compareTo(rb)
    return when (ra) {
        0 -> 0
        1 -> (a as Boolean).compareTo(b as Boolean)
        2 -> {
            val da = (a as Number).toDouble()
            val db = (b as Number).toDouble()
            if (a is Long && b is Long) a.compareTo(b) else da.compareTo(db)
        }
        3 -> (a as String).compareTo(b as String)
        else -> {
            val ca = a!!::class.java.name.compareTo(b!!::class.java.name)
            if (ca != 0) ca else a.toString().compareTo(b.toString())
        }
    }
}

private fun asNumber(value: Any?, context: String): Number = when (value) {
    is Number -> value
    is Boolean -> if (value) 1L else 0L
    is String -> value.toLongOrNull() ?: value.toDoubleOrNull()
        ?: throw DlException("$context: '$value' is not a number")
    null -> throw DlException("$context: null is not a number")
    else -> throw DlException("$context: ${value::class.java.simpleName} is not a number")
}

/**
 * Combine an aggregate's group into its one value. `solutions` is how many times the subgoal
 * succeeded (which is what `count` reports) and `values` are the evaluations of the `over`
 * expression, one per solution — empty for `count`, which has no `over`.
 *
 * Top-level rather than a method on [DatalogEngine] because [Datalog.why] recomputes an aggregate
 * from the group it reconstructed and then checks the answer against the fact in the model. Two
 * implementations of `sum` that agreed on the day they were written would be a proof that lies
 * later; there is one.
 *
 * Returning null means "no value" — `min`/`max`/`mean` of nothing — which makes the enclosing rule
 * not fire, and makes a reconstructed proof of such a fact impossible, correctly.
 */
fun dlAggregateValue(op: String, solutions: Int, values: List<Any?>): Any? = when (op) {
    "count" -> solutions.toLong()
    "sum" -> if (values.isEmpty()) 0L else values.fold(0.0 to true) { (acc, allLong), v ->
        (acc + asNumber(v, "sum").toDouble()) to (allLong && v is Long)
    }.let { (total, allLong) -> if (allLong) dlNormalize(total) else total }
    "min" -> values.minWithOrNull { a, b -> dlCompareValues(a, b) }
    "max" -> values.maxWithOrNull { a, b -> dlCompareValues(a, b) }
    "mean" -> if (values.isEmpty()) null
    else dlNormalize(values.sumOf { asNumber(it, "mean").toDouble() } / values.size)
    "collect" -> values.sortedWith { a, b -> dlCompareValues(a, b) }
    else -> throw DlException("unknown aggregate '$op'")
}

private fun truthy(value: Any?): Boolean = when (value) {
    is Boolean -> value
    null -> false
    is Number -> value.toDouble() != 0.0
    is String -> value.isNotEmpty()
    else -> true
}

// ── the engine ──────────────────────────────────────────────────────────────────────────────────

/**
 * Evaluates a set of rules against a set of base facts. Owned by [Datalog]; not usually built
 * directly.
 */
class DatalogEngine(
    private val rules: List<DlRule>,
    private val base: Map<String, DlRelation>,
) {
    private val arity = HashMap<String, Int>()
    private val db = LinkedHashMap<String, DlRelation>()
    private val warningList = ArrayList<String>()

    val warnings: List<String> get() = warningList

    /** Predicates that appear only in rule bodies and are never given facts or a rule — likely typos. */
    private val undefined = LinkedHashSet<String>()

    fun run(): DlSolution {
        collectArities()
        // Copy, never alias: a rule may extend a predicate that also has stated facts, and writing
        // derived rows into the caller's relation would make a second solve() start from the first
        // one's answer.
        base.forEach { (name, relation) ->
            val copy = DlRelation(name, relation.arity)
            relation.rows().forEach { copy.add(it, 0) }
            db[name] = copy
        }
        arity.forEach { (name, n) -> db.getOrPut(name) { DlRelation(name, n) } }

        val strata = stratify()
        var round = 1
        strata.forEach { stratum -> round = evaluateStratum(stratum, round) }

        if (undefined.isNotEmpty()) {
            warningList.add(
                "no facts or rules for: " + undefined.sorted().joinToString(", ") { "$it/${arity[it]}" }
            )
        }
        return DlSolution(db, rules, warningList.toList())
    }

    // ── arity checking ──────────────────────────────────────────────────────────────────────────

    private fun collectArities() {
        base.forEach { (name, relation) -> arity[name] = relation.arity }
        val defined = HashSet<String>(base.keys)

        fun note(atom: DlAtom, isHead: Boolean) {
            val existing = arity[atom.pred]
            if (existing != null && existing != atom.arity) {
                throw DlException(
                    "'${atom.pred}' is used with arity ${atom.arity} here but arity $existing elsewhere"
                )
            }
            arity[atom.pred] = atom.arity
            if (isHead) defined.add(atom.pred)
        }

        fun walkBody(body: List<DlLiteral>) {
            body.forEach { literal ->
                when (literal) {
                    is DlAtom -> note(literal, false)
                    is DlAggregate -> walkBody(literal.body)
                    is DlCompare -> Unit
                }
            }
        }

        rules.forEach { rule ->
            note(rule.head, true)
            walkBody(rule.body)
        }
        arity.keys.forEach { pred -> if (pred !in defined) undefined.add(pred) }
    }

    // ── stratification ──────────────────────────────────────────────────────────────────────────

    private fun bodyDependencies(body: List<DlLiteral>, out: MutableList<Pair<String, Boolean>>, forceNegative: Boolean) {
        body.forEach { literal ->
            when (literal) {
                is DlAtom -> out.add(literal.pred to (forceNegative || literal.negated))
                // An aggregate must see its whole extension before it can be summed, so everything it
                // reads has to be finished — the same requirement negation has.
                is DlAggregate -> bodyDependencies(literal.body, out, true)
                is DlCompare -> Unit
            }
        }
    }

    private fun stratify(): List<List<DlRule>> {
        val heads = rules.map { it.head.pred }.toSet()
        val edges = HashMap<String, MutableList<Pair<String, Boolean>>>() // from -> [(to, negative)]
        rules.forEach { rule ->
            val deps = ArrayList<Pair<String, Boolean>>()
            bodyDependencies(rule.body, deps, false)
            deps.forEach { (pred, negative) ->
                if (pred in heads) edges.getOrPut(pred) { ArrayList() }.add(rule.head.pred to negative)
            }
        }

        val components = tarjan(heads, edges)
        val componentOf = HashMap<String, Int>()
        components.forEachIndexed { i, comp -> comp.forEach { componentOf[it] = i } }

        // A negative edge inside a component is a rule that needs its own (or a mutually recursive)
        // predicate to be complete before it can start. Report it rather than pick an order.
        edges.forEach { (from, targets) ->
            targets.forEach { (to, negative) ->
                if (negative && componentOf[from] == componentOf[to]) {
                    val cycle = components[componentOf[from]!!].sorted().joinToString(" -> ")
                    throw DlException(
                        "not stratifiable: negation or aggregation inside the recursive cycle [$cycle]. " +
                            "'$to' depends negatively on '$from', which depends on '$to'."
                    )
                }
            }
        }

        // Kahn over the condensation, so a stratum only runs once everything it reads is saturated.
        val indegree = IntArray(components.size)
        val outgoing = HashMap<Int, MutableSet<Int>>()
        edges.forEach { (from, targets) ->
            targets.forEach { (to, _) ->
                val a = componentOf[from]!!
                val b = componentOf[to]!!
                if (a != b && outgoing.getOrPut(a) { LinkedHashSet() }.add(b)) indegree[b]++
            }
        }
        val queue = ArrayDeque<Int>()
        indegree.indices.forEach { if (indegree[it] == 0) queue.add(it) }
        val order = ArrayList<Int>()
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            order.add(n)
            outgoing[n]?.forEach { m -> if (--indegree[m] == 0) queue.add(m) }
        }
        if (order.size != components.size) throw DlException("internal error: stratification cycle")

        val byComponent = HashMap<Int, MutableList<DlRule>>()
        rules.forEach { rule -> byComponent.getOrPut(componentOf[rule.head.pred]!!) { ArrayList() }.add(rule) }
        return order.mapNotNull { byComponent[it] }
    }

    /** Iterative Tarjan — recursion here would blow the stack on a large generated program. */
    private fun tarjan(nodes: Set<String>, edges: Map<String, List<Pair<String, Boolean>>>): List<List<String>> {
        val index = HashMap<String, Int>()
        val low = HashMap<String, Int>()
        val onStack = HashSet<String>()
        val stack = ArrayDeque<String>()
        val out = ArrayList<List<String>>()
        var counter = 0

        nodes.forEach { start ->
            if (start in index) return@forEach
            val work = ArrayDeque<Pair<String, Int>>()
            work.addLast(start to 0)
            index[start] = counter
            low[start] = counter
            counter++
            stack.addLast(start)
            onStack.add(start)
            while (work.isNotEmpty()) {
                val (node, edgeIndex) = work.removeLast()
                val successors = edges[node]?.map { it.first }?.filter { it in nodes } ?: emptyList()
                if (edgeIndex < successors.size) {
                    work.addLast(node to edgeIndex + 1)
                    val next = successors[edgeIndex]
                    if (next !in index) {
                        index[next] = counter
                        low[next] = counter
                        counter++
                        stack.addLast(next)
                        onStack.add(next)
                        work.addLast(next to 0)
                    } else if (next in onStack) {
                        low[node] = minOf(low[node]!!, index[next]!!)
                    }
                } else {
                    if (low[node] == index[node]) {
                        val component = ArrayList<String>()
                        while (true) {
                            val w = stack.removeLast()
                            onStack.remove(w)
                            component.add(w)
                            if (w == node) break
                        }
                        out.add(component)
                    }
                    work.lastOrNull()?.let { (parent, _) -> low[parent] = minOf(low[parent]!!, low[node]!!) }
                }
            }
        }
        return out
    }

    // ── planning ────────────────────────────────────────────────────────────────────────────────

    private fun plan(rule: DlRule, sameStratum: Set<String>): DlPlan {
        val occurrences = countVariableOccurrences(rule)
        val slots = LinkedHashMap<String, Int>()
        fun slotOf(v: DlVar) = slots.getOrPut(v.name) { slots.size }
        val bound = HashSet<Int>()

        fun arithVars(a: DlArith, out: MutableList<DlVar>) {
            when (a) {
                is DlAValue -> (a.term as? DlVar)?.let { out.add(it) }
                is DlABinary -> { arithVars(a.left, out); arithVars(a.right, out) }
                is DlAUnary -> arithVars(a.operand, out)
                is DlACall -> a.args.forEach { arithVars(it, out) }
            }
        }

        fun arithReady(a: DlArith): Boolean {
            val vars = ArrayList<DlVar>()
            arithVars(a, vars)
            return vars.all { slots[it.name]?.let(bound::contains) == true }
        }

        // A negated atom is safe once every variable is either bound, or appears nowhere else in
        // the rule — the second kind is an existential wildcard, which is what `!path(X, _)` means.
        fun atomReady(atom: DlAtom): Boolean = atom.terms.filterIsInstance<DlVar>().all {
            slots[it.name]?.let(bound::contains) == true || occurrences[it.name] == 1
        }

        fun boundScore(atom: DlAtom): Int = atom.terms.count { term ->
            term is DlConst || (term is DlVar && slots[term.name]?.let(bound::contains) == true)
        }

        fun argsFor(atom: DlAtom, bindNew: Boolean): List<DlArg> {
            val seen = HashSet<Int>()
            return atom.terms.map { term ->
                when (term) {
                    is DlConst -> DlArgConst(dlNormalize(term.value))
                    is DlVar -> {
                        if (!bindNew && occurrences[term.name] == 1 && slots[term.name]?.let(bound::contains) != true) {
                            DlArgWild
                        } else {
                            val slot = slotOf(term)
                            if (slot in bound || slot in seen) {
                                DlArgTest(slot)
                            } else {
                                seen.add(slot)
                                if (bindNew) DlArgBind(slot) else DlArgTest(slot)
                            }
                        }
                    }
                }
            }
        }

        val steps = ArrayList<DlStep>()
        val remaining = rule.body.toMutableList()
        var recursiveScans = 0

        while (remaining.isNotEmpty()) {
            // Cheap filters first, then anti-joins, then the most-constrained scan, aggregates last.
            var pickIndex = -1
            var pickKind = 99
            var pickScore = Int.MIN_VALUE
            remaining.forEachIndexed { i, literal ->
                val (kind, score) = when (literal) {
                    is DlCompare -> {
                        val assignTarget = assignTargetOf(literal, slots, bound)
                        when {
                            assignTarget != null -> 1 to 0
                            arithReady(literal.left) && arithReady(literal.right) -> 0 to 0
                            else -> 99 to 0
                        }
                    }
                    is DlAtom ->
                        if (literal.negated) (if (atomReady(literal)) 2 else 99) to 0
                        else 3 to boundScore(literal)
                    is DlAggregate ->
                        if (remaining.none { it is DlAtom && !it.negated }) 4 to 0 else 99 to 0
                }
                if (kind < pickKind || (kind == pickKind && score > pickScore)) {
                    pickIndex = i
                    pickKind = kind
                    pickScore = score
                }
            }
            if (pickIndex < 0 || pickKind == 99) {
                val stuck = remaining.first()
                val unboundNames = dlVarsOf(listOf(stuck))
                    .filter { slots[it.name]?.let(bound::contains) != true }
                    .map { it.name }
                    .distinct()
                throw DlException(
                    "unsafe rule '${rule.head.pred}': '${dlLiteralText(stuck)}' needs " +
                        unboundNames.joinToString(", ") + " to be bound by a positive body literal first"
                )
            }

            when (val literal = remaining.removeAt(pickIndex)) {
                is DlCompare -> {
                    val target = assignTargetOf(literal, slots, bound)
                    if (target != null) {
                        val (variable, expr) = target
                        val slot = slotOf(variable)
                        steps.add(DlAssign(slot, expr))
                        bound.add(slot)
                    } else {
                        steps.add(DlTest(literal.op, literal.left, literal.right))
                    }
                }
                is DlAtom -> {
                    if (literal.negated) {
                        steps.add(DlAntiJoin(literal.pred, argsFor(literal, bindNew = false)))
                    } else {
                        val args = argsFor(literal, bindNew = true)
                        args.forEach { if (it is DlArgBind) bound.add(it.slot) }
                        val ordinal = if (literal.pred in sameStratum) recursiveScans++ else -1
                        steps.add(DlScan(literal.pred, args, ordinal))
                    }
                }
                is DlAggregate -> {
                    val targetSlot = slotOf(literal.target)
                    if (targetSlot in bound) {
                        throw DlException(
                            "aggregate target '${literal.target.name}' is already bound; use a fresh variable"
                        )
                    }
                    // The inner body is planned in the *same* slot space: a variable the outer rule has
                    // already bound is a grouping key inside, and one it has not is local to the aggregate.
                    val innerSteps = ArrayList<DlStep>()
                    val innerRemaining = ArrayList(literal.body)
                    val innerBound = HashSet(bound)
                    planInner(rule, literal, innerRemaining, innerSteps, slots, innerBound, ::slotOf, occurrences)
                    literal.over?.let { over ->
                        val vars = ArrayList<DlVar>()
                        arithVars(over, vars)
                        val missing = vars.filter { slots[it.name]?.let(innerBound::contains) != true }
                        if (missing.isNotEmpty()) {
                            throw DlException(
                                "aggregate over ${dlArithText(over)} uses unbound " +
                                    missing.joinToString(", ") { it.name }
                            )
                        }
                    }
                    steps.add(DlAgg(targetSlot, literal.op, literal.over, innerSteps))
                    bound.add(targetSlot)
                }
            }
        }

        val headArgs = rule.head.terms.map { term ->
            when (term) {
                is DlConst -> DlArgConst(dlNormalize(term.value))
                is DlVar -> {
                    val slot = slots[term.name]
                    if (slot == null || slot !in bound) {
                        throw DlException(
                            if (rule.isFact) "a fact must be ground, but '${rule.head.pred}' contains the variable ${term.name}"
                            else "unsafe rule: '${term.name}' appears in the head of '${rule.head.pred}' but is never bound by the body"
                        )
                    }
                    DlArgTest(slot)
                }
            }
        }

        return DlPlan(rule, slots.size, LinkedHashMap(slots), steps, headArgs, recursiveScans)
    }

    /** Plan an aggregate's subgoal. Same rules as a rule body, minus recursion and nested aggregates. */
    private fun planInner(
        rule: DlRule,
        aggregate: DlAggregate,
        remaining: MutableList<DlLiteral>,
        steps: MutableList<DlStep>,
        slots: LinkedHashMap<String, Int>,
        bound: MutableSet<Int>,
        slotOf: (DlVar) -> Int,
        occurrences: Map<String, Int>,
    ) {
        fun arithVars(a: DlArith, out: MutableList<DlVar>) {
            when (a) {
                is DlAValue -> (a.term as? DlVar)?.let { out.add(it) }
                is DlABinary -> { arithVars(a.left, out); arithVars(a.right, out) }
                is DlAUnary -> arithVars(a.operand, out)
                is DlACall -> a.args.forEach { arithVars(it, out) }
            }
        }
        fun arithReady(a: DlArith): Boolean {
            val vars = ArrayList<DlVar>()
            arithVars(a, vars)
            return vars.all { slots[it.name]?.let(bound::contains) == true }
        }
        while (remaining.isNotEmpty()) {
            var pickIndex = -1
            var pickKind = 99
            remaining.forEachIndexed { i, literal ->
                val kind = when (literal) {
                    is DlCompare ->
                        if (assignTargetOf(literal, slots, bound) != null) 1
                        else if (arithReady(literal.left) && arithReady(literal.right)) 0
                        else 99
                    is DlAtom ->
                        if (!literal.negated) 3
                        else if (literal.terms.filterIsInstance<DlVar>().all {
                                slots[it.name]?.let(bound::contains) == true || occurrences[it.name] == 1
                            }
                        ) 2
                        else 99
                    is DlAggregate -> throw DlException("aggregates cannot be nested (in '${rule.head.pred}')")
                }
                if (kind < pickKind) { pickIndex = i; pickKind = kind }
            }
            if (pickIndex < 0 || pickKind == 99) {
                throw DlException(
                    "unsafe aggregate in '${rule.head.pred}': '${dlLiteralText(remaining.first())}' has unbound variables"
                )
            }
            when (val literal = remaining.removeAt(pickIndex)) {
                is DlCompare -> {
                    val target = assignTargetOf(literal, slots, bound)
                    if (target != null) {
                        val slot = slotOf(target.first)
                        steps.add(DlAssign(slot, target.second))
                        bound.add(slot)
                    } else {
                        steps.add(DlTest(literal.op, literal.left, literal.right))
                    }
                }
                is DlAtom -> {
                    val seen = HashSet<Int>()
                    val args = literal.terms.map { term ->
                        when (term) {
                            is DlConst -> DlArgConst(dlNormalize(term.value))
                            is DlVar -> {
                                if (literal.negated && occurrences[term.name] == 1 &&
                                    slots[term.name]?.let(bound::contains) != true
                                ) {
                                    DlArgWild
                                } else {
                                    val slot = slotOf(term)
                                    if (slot in bound || slot in seen) DlArgTest(slot)
                                    else if (literal.negated) DlArgTest(slot)
                                    else { seen.add(slot); DlArgBind(slot) }
                                }
                            }
                        }
                    }
                    if (literal.negated) {
                        steps.add(DlAntiJoin(literal.pred, args))
                    } else {
                        args.forEach { if (it is DlArgBind) bound.add(it.slot) }
                        steps.add(DlScan(literal.pred, args, -1))
                    }
                }
                is DlAggregate -> throw DlException("aggregates cannot be nested")
            }
        }
        // The aggregate contributes no bindings to the enclosing rule other than its target.
        aggregate.body.let { }
    }

    /** `X = expr` is a binding when X is a variable that nothing has bound yet. */
    private fun assignTargetOf(
        compare: DlCompare,
        slots: Map<String, Int>,
        bound: Set<Int>,
    ): Pair<DlVar, DlArith>? {
        if (compare.op != "=") return null
        fun unboundVar(a: DlArith): DlVar? {
            val term = (a as? DlAValue)?.term as? DlVar ?: return null
            val slot = slots[term.name]
            return if (slot == null || slot !in bound) term else null
        }
        fun ready(a: DlArith): Boolean {
            val vars = ArrayList<DlVar>()
            fun walk(x: DlArith) {
                when (x) {
                    is DlAValue -> (x.term as? DlVar)?.let { vars.add(it) }
                    is DlABinary -> { walk(x.left); walk(x.right) }
                    is DlAUnary -> walk(x.operand)
                    is DlACall -> x.args.forEach { walk(it) }
                }
            }
            walk(a)
            return vars.all { slots[it.name]?.let(bound::contains) == true }
        }
        unboundVar(compare.left)?.let { if (ready(compare.right)) return it to compare.right }
        unboundVar(compare.right)?.let { if (ready(compare.left)) return it to compare.left }
        return null
    }

    // ── semi-naive fixpoint ─────────────────────────────────────────────────────────────────────

    private fun relation(pred: String): DlRelation =
        db.getOrPut(pred) { DlRelation(pred, arity[pred] ?: 0) }

    /**
     * Saturate one stratum, numbering the facts it derives from [firstRound] upwards. Returns the
     * round the *next* stratum should start at.
     *
     * The counter is threaded through rather than restarted per stratum, and that is load-bearing
     * rather than tidy. [Datalog.why] only accepts a premise whose round is strictly lower than its
     * conclusion's, which is what makes proof search terminate without an occurs-check. With a
     * per-stratum counter a rule in a later stratum got round 1 while the predicate it reads sat in
     * an earlier stratum at round 5, every candidate premise was filtered out, and `#why` reported a
     * real fact as unproven — the visible symptom of a rule with negation or an aggregate in its
     * body, since those are exactly the constructs that create a stratum boundary. Strata are
     * evaluated in topological order, so a global counter makes "older stratum ⇒ lower round" true
     * by construction.
     */
    private fun evaluateStratum(stratum: List<DlRule>, firstRound: Int): Int {
        val preds = stratum.map { it.head.pred }.toSet()
        val plans = stratum.map { plan(it, preds) }
        var round = firstRound
        var delta: Map<String, DlRelation>? = null

        while (true) {
            val pending = ArrayList<Pair<String, DlRow>>()
            plans.forEach { plan ->
                val emit: (DlRow) -> Unit = { row -> pending.add(plan.rule.head.pred to row) }
                if (delta == null) {
                    // First pass: everything, no delta.
                    execute(plan, plan.steps, arrayOfNulls(plan.slotCount), 0, null, -1, emit)
                } else if (plan.recursiveScanCount == 0) {
                    // Non-recursive rules are saturated after the first pass.
                } else {
                    for (ordinal in 0 until plan.recursiveScanCount) {
                        execute(plan, plan.steps, arrayOfNulls(plan.slotCount), 0, delta, ordinal, emit)
                    }
                }
            }

            // Merge only now, so every new fact's round is strictly above its premises' rounds.
            val added = LinkedHashMap<String, DlRelation>()
            pending.forEach { (pred, row) ->
                if (relation(pred).add(row, round)) {
                    added.getOrPut(pred) { DlRelation(pred, arity[pred] ?: row.size) }.add(row, round)
                }
            }
            if (added.isEmpty()) return round + 1
            delta = added
            round++
            // Measured from this stratum's first round, so the ceiling stays "rounds spent on one
            // fixpoint" now that the counter is global.
            if (round - firstRound >= MAX_ROUNDS) {
                throw DlException("fixpoint did not converge after $MAX_ROUNDS rounds — is a rule generating new values?")
            }
        }
    }

    /**
     * Run the plan from [stepIndex], calling [emit] for each complete solution.
     *
     * [deltaOrdinal] selects which recursive scan reads the delta relation instead of the full one;
     * -1 means "no delta", used for the first pass and for aggregate subgoals.
     */
    private fun execute(
        plan: DlPlan,
        steps: List<DlStep>,
        env: Array<Any?>,
        stepIndex: Int,
        delta: Map<String, DlRelation>?,
        deltaOrdinal: Int,
        emit: (DlRow) -> Unit,
    ) {
        if (stepIndex == steps.size) {
            if (steps === plan.steps) emit(plan.headArgs.map { arg -> valueOf(arg, env) })
            else emit(emptyList())
            return
        }
        when (val step = steps[stepIndex]) {
            is DlScan -> {
                val source = if (step.recursiveOrdinal >= 0 && step.recursiveOrdinal == deltaOrdinal) {
                    delta?.get(step.pred) ?: return
                } else {
                    db[step.pred] ?: return
                }
                var mask = 0
                val pattern = arrayOfNulls<Any?>(source.arity)
                step.args.forEachIndexed { i, arg ->
                    if (i >= source.arity) return@forEachIndexed
                    when (arg) {
                        is DlArgConst -> { pattern[i] = arg.value; mask = mask or (1 shl i) }
                        is DlArgTest -> { pattern[i] = env[arg.slot]; mask = mask or (1 shl i) }
                        is DlArgBind -> Unit
                        // Only negation produces wildcards; a positive scan binds instead.
                        DlArgWild -> Unit
                    }
                }
                // A repeated variable inside one atom (`p(X, X)`) is a DlArgTest against a slot this
                // very scan is about to bind, so it cannot be part of the index key — filter it below.
                val selfTests = ArrayList<Pair<Int, Int>>()
                step.args.forEachIndexed { i, arg ->
                    if (arg is DlArgTest && step.args.take(i).any { it is DlArgBind && it.slot == arg.slot }) {
                        mask = mask and (1 shl i).inv()
                        val bindPosition = step.args.indexOfFirst { it is DlArgBind && it.slot == arg.slot }
                        selfTests.add(i to bindPosition)
                    }
                }
                outer@ for (row in source.lookup(pattern, mask)) {
                    for ((i, j) in selfTests) if (row[i] != row[j]) continue@outer
                    val saved = ArrayList<Pair<Int, Any?>>(step.args.size)
                    step.args.forEachIndexed { i, arg ->
                        if (arg is DlArgBind) {
                            saved.add(arg.slot to env[arg.slot])
                            env[arg.slot] = row[i]
                        }
                    }
                    execute(plan, steps, env, stepIndex + 1, delta, deltaOrdinal, emit)
                    saved.forEach { (slot, value) -> env[slot] = value }
                }
            }
            is DlAntiJoin -> {
                val source = db[step.pred]
                if (source != null) {
                    var mask = 0
                    val pattern = arrayOfNulls<Any?>(source.arity)
                    step.args.forEachIndexed { i, arg ->
                        if (i >= source.arity) return@forEachIndexed
                        when (arg) {
                            is DlArgConst -> { pattern[i] = arg.value; mask = mask or (1 shl i) }
                            is DlArgTest -> { pattern[i] = env[arg.slot]; mask = mask or (1 shl i) }
                            is DlArgBind -> { pattern[i] = env[arg.slot]; mask = mask or (1 shl i) }
                            DlArgWild -> Unit
                        }
                    }
                    if (source.lookup(pattern, mask).isNotEmpty()) return
                }
                execute(plan, steps, env, stepIndex + 1, delta, deltaOrdinal, emit)
            }
            is DlTest -> {
                val left = evalArith(step.left, env, plan)
                val right = evalArith(step.right, env, plan)
                if (dlCompareOp(step.op, left, right)) execute(plan, steps, env, stepIndex + 1, delta, deltaOrdinal, emit)
            }
            is DlAssign -> {
                val saved = env[step.slot]
                env[step.slot] = evalArith(step.expr, env, plan)
                execute(plan, steps, env, stepIndex + 1, delta, deltaOrdinal, emit)
                env[step.slot] = saved
            }
            is DlAgg -> {
                val collected = ArrayList<Any?>()
                var solutions = 0
                execute(plan, step.sub, env, 0, null, -1) {
                    solutions++
                    step.over?.let { collected.add(evalArith(it, env, plan)) }
                }
                val value = dlAggregateValue(step.op, solutions, collected) ?: return
                val saved = env[step.slot]
                env[step.slot] = value
                execute(plan, steps, env, stepIndex + 1, delta, deltaOrdinal, emit)
                env[step.slot] = saved
            }
        }
    }

    private fun valueOf(arg: DlArg, env: Array<Any?>): Any? = when (arg) {
        is DlArgConst -> arg.value
        is DlArgBind -> env[arg.slot]
        is DlArgTest -> env[arg.slot]
        DlArgWild -> throw DlException("internal error: a wildcard argument has no value")
    }

    /** How many times each variable is mentioned anywhere in the rule, head included. */
    private fun countVariableOccurrences(rule: DlRule): Map<String, Int> {
        val counts = HashMap<String, Int>()
        fun bump(v: DlVar) { counts[v.name] = (counts[v.name] ?: 0) + 1 }
        fun arith(a: DlArith) {
            when (a) {
                is DlAValue -> (a.term as? DlVar)?.let(::bump)
                is DlABinary -> { arith(a.left); arith(a.right) }
                is DlAUnary -> arith(a.operand)
                is DlACall -> a.args.forEach { arith(it) }
            }
        }
        fun walk(literals: List<DlLiteral>) {
            literals.forEach { literal ->
                when (literal) {
                    is DlAtom -> literal.terms.forEach { t -> (t as? DlVar)?.let(::bump) }
                    is DlCompare -> { arith(literal.left); arith(literal.right) }
                    is DlAggregate -> { bump(literal.target); literal.over?.let { arith(it) }; walk(literal.body) }
                }
            }
        }
        rule.head.terms.forEach { t -> (t as? DlVar)?.let(::bump) }
        walk(rule.body)
        return counts
    }

    private fun evalArith(arith: DlArith, env: Array<Any?>, plan: DlPlan): Any? =
        dlEvalArith(arith) { name ->
            env[plan.slotIndex[name] ?: throw DlException("unbound variable $name")]
        }

    companion object {
        const val MAX_ROUNDS = 10_000
    }
}

/**
 * The builtin functions of [DatalogParser.functions]. Kept out of the engine class so the parser,
 * the engine and the box language's hover documentation all name the same list.
 */
fun dlCallFunction(name: String, args: List<Any?>): Any? {
    fun num(i: Int): Double = when (val v = args.getOrNull(i)) {
        is Number -> v.toDouble()
        is Boolean -> if (v) 1.0 else 0.0
        is String -> v.toDoubleOrNull() ?: throw DlException("$name: '$v' is not a number")
        else -> throw DlException("$name: expected a number as argument ${i + 1}")
    }
    fun text(i: Int): String = dlFormat(args.getOrNull(i))
    fun arity(n: Int) {
        if (args.size != n) throw DlException("$name takes $n argument(s), got ${args.size}")
    }
    return when (name) {
        "abs" -> { arity(1); dlNormalize(Math.abs(num(0))) }
        "sign" -> { arity(1); dlNormalize(Math.signum(num(0))) }
        "sqrt" -> { arity(1); dlNormalize(Math.sqrt(num(0))) }
        "floor" -> { arity(1); dlNormalize(Math.floor(num(0))) }
        "ceil" -> { arity(1); dlNormalize(Math.ceil(num(0))) }
        "round" -> { arity(1); dlNormalize(Math.rint(num(0))) }
        "pow" -> { arity(2); dlNormalize(Math.pow(num(0), num(1))) }
        "exp" -> { arity(1); dlNormalize(Math.exp(num(0))) }
        "log" -> { arity(1); dlNormalize(Math.log(num(0))) }
        "sin" -> { arity(1); dlNormalize(Math.sin(num(0))) }
        "cos" -> { arity(1); dlNormalize(Math.cos(num(0))) }
        "tan" -> { arity(1); dlNormalize(Math.tan(num(0))) }
        "atan2" -> { arity(2); dlNormalize(Math.atan2(num(0), num(1))) }
        "hypot" -> { arity(2); dlNormalize(Math.hypot(num(0), num(1))) }
        "min" -> { arity(2); if (dlCompareValues(args[0], args[1]) <= 0) args[0] else args[1] }
        "max" -> { arity(2); if (dlCompareValues(args[0], args[1]) >= 0) args[0] else args[1] }
        "len" -> { arity(1); (args[0] as? List<*>)?.size?.toLong() ?: text(0).length.toLong() }
        "upper" -> { arity(1); text(0).uppercase() }
        "lower" -> { arity(1); text(0).lowercase() }
        "trim" -> { arity(1); text(0).trim() }
        "cat" -> args.joinToString("") { dlFormat(it) }
        "substr" -> {
            if (args.size !in 2..3) throw DlException("substr takes 2 or 3 arguments")
            val s = text(0)
            val from = num(1).toInt().coerceIn(0, s.length)
            val to = if (args.size == 3) (from + num(2).toInt()).coerceIn(from, s.length) else s.length
            s.substring(from, to)
        }
        "indexOf" -> { arity(2); text(0).indexOf(text(1)).toLong() }
        "contains" -> { arity(2); text(0).contains(text(1)) }
        "startsWith" -> { arity(2); text(0).startsWith(text(1)) }
        "endsWith" -> { arity(2); text(0).endsWith(text(1)) }
        "split" -> { arity(2); text(0).split(text(1)) }
        "num" -> { arity(1); dlNormalize(num(0)) }
        "str" -> { arity(1); dlFormat(args[0]) }
        "int" -> { arity(1); dlNormalize(Math.floor(num(0)).toLong()) }
        "not" -> { arity(1); !truthy(args[0]) }
        else -> throw DlException("unknown function '$name'")
    }
}

/**
 * What a premise *is* to the step above it. A proof tree is not homogeneous, and a reader who
 * cannot tell a rule body from an aggregate's group is reading a tree that means several different
 * things in the same shape.
 */
enum class DlPremiseKind {
    /** A fact, or a fact re-derived by a rule — the ordinary case, and the whole tree before this. */
    DERIVED,

    /**
     * The evaluation of an aggregate subgoal. Its premises are the *group* — the tuples that were
     * summed, counted or compared — not the body of a rule.
     */
    AGGREGATE,

    /**
     * One solution of an aggregate subgoal, when that subgoal has more than one atom and so needs a
     * node of its own to hold them together. A single-atom subgoal contributes its atom directly,
     * with a [DlDerivation.contribution].
     */
    GROUP,

    /** A negated body literal that held: the witness is that nothing matches it. No premises. */
    ABSENCE,
}

/**
 * One step of a proof: a fact, the rule that produced it, and the facts that rule consumed.
 *
 * [note] says so when the search could not reconstruct a proof for a fact that is nonetheless in
 * the model. [kind] says what this node is to its parent, and [contribution] what it gave to an
 * aggregate above it — "which tuple was the max" is most of what a person wants from an aggregate
 * proof, and it lives on the tuple rather than in a separate list so it survives any rendering.
 */
class DlDerivation(
    val fact: String,
    val rule: DlRule?,
    val premises: List<DlDerivation>,
    val note: String? = null,
    val kind: DlPremiseKind = DlPremiseKind.DERIVED,
    val contribution: String? = null,
) : ToHTML {
    fun render(indent: String = ""): String = buildString {
        append(indent).append(fact)
        append("   [").append(because()).append("]")
        premises.forEach { append("\n").append(it.render("$indent    ")) }
    }

    /**
     * What to write beside a derived fact: the whole rule, not just its head.
     *
     * Two recursive rules for one predicate have the same head, so a head-only label made every
     * step of a transitive-closure proof read identically — which is the opposite of what a proof
     * is for.
     *
     * A [contribution] is appended rather than replacing the label, because a group member is often
     * itself derived and losing its rule would cut the proof off one level early.
     */
    fun because(limit: Int = 72): String {
        val head = note ?: rule?.toString()?.removeSuffix(".")?.let {
            if (it.length <= limit) it else it.take(limit - 1) + "…"
        } ?: "fact"
        return if (contribution == null) head else "$head; $contribution"
    }

    override fun toString() = render()

    /**
     * The proof as an indented HTML tree — a fact per line, in the accent colour, with the rule that
     * produced it beside it in grey. Not a [Table]: the shape *is* the answer here, and a table
     * would flatten the one thing worth looking at.
     */
    override fun toHTML(): String = buildString {
        append("<div style=\"font:12px/1.5 'Iosevka Web',monospace;color:$DL_VALUE_COLOUR\">")
        append("<div style='color:$DL_KEY_COLOUR;margin-bottom:5px'>why " + escapeDlHtml(fact) + "</div>")
        appendNode(this@DlDerivation, 0)
        append("</div>")
    }

    private fun StringBuilder.appendNode(node: DlDerivation, depth: Int) {
        // An aggregate step is a different kind of thing from a rule step, so it gets a different
        // hook (Σ) and a different colour. Everything else keeps the elbow it always had, so an
        // existing proof renders byte-identically.
        val hook = when (node.kind) {
            DlPremiseKind.AGGREGATE -> "&Sigma; "
            DlPremiseKind.ABSENCE -> "&empty; "
            else -> "&#8627; "
        }
        val colour = if (node.kind == DlPremiseKind.AGGREGATE) DL_KEY_COLOUR else DL_VALUE_COLOUR
        append("<div style='padding-left:" + (depth * 18) + "px'>")
        if (depth > 0) append("<span style='color:$DL_MUTED_COLOUR'>$hook</span>")
        append("<span style='color:$colour'>" + escapeDlHtml(node.fact) + "</span>")
        append("<span style='color:$DL_MUTED_COLOUR'>&nbsp;&nbsp;[" + escapeDlHtml(node.because()) + "]</span>")
        append("</div>")
        node.premises.forEach { appendNode(it, depth + 1) }
    }
}

internal fun escapeDlHtml(text: String): String =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/**
 * A computed model: every relation, saturated, plus the rules that made it.
 */
class DlSolution(
    val relations: Map<String, DlRelation>,
    val rules: List<DlRule>,
    val warnings: List<String>,
) : ToHTML {
    operator fun get(pred: String): DlRelation? = relations[pred]

    fun rows(pred: String): Collection<DlRow> = relations[pred]?.rows() ?: emptyList()

    val factCount: Int get() = relations.values.sumOf { it.size }

    /** `pred/arity -> row count`, in insertion order — what `#relations` prints. */
    fun summary(): List<String> = relations.values
        .sortedBy { it.name }
        .map { "${it.name}/${it.arity}  ${it.size}" }

    override fun toString() = "DlSolution(${relations.size} relations, $factCount facts)"

    /** Every relation and how big it got — what `#relations` prints, as a table. */
    override fun toHTML(): String {
        val table = Table(listOf("relation", "arity", "rows"), title = "$factCount facts in ${relations.size} relations")
        table.maxRows = 32
        table.color("relation", DL_KEY_COLOUR)
        table.color("arity", DL_MUTED_COLOUR)
        if (warnings.isNotEmpty()) table.note = warnings.joinToString("; ")
        relations.values.sortedBy { it.name }.forEach { table.row(it.name, it.arity, it.size) }
        return table.toHTML()
    }
}


/** Evaluate an expression, resolving variables through [lookup]. Shared by the fixpoint and [Datalog.why]. */
fun dlEvalArith(arith: DlArith, lookup: (String) -> Any?): Any? = when (arith) {
    is DlAValue -> when (val term = arith.term) {
        is DlConst -> dlNormalize(term.value)
        is DlVar -> lookup(term.name)
    }
    is DlAUnary -> {
        val v = dlEvalArith(arith.operand, lookup)
        when (arith.op) {
            "-" -> dlNormalize(if (v is Long) -v else -dlAsDouble(v, "negation"))
            else -> throw DlException("unknown unary '${arith.op}'")
        }
    }
    is DlABinary -> {
        val l = dlEvalArith(arith.left, lookup)
        val r = dlEvalArith(arith.right, lookup)
        if (arith.op == "+" && (l is String || r is String)) {
            dlFormat(l) + dlFormat(r)
        } else {
            val bothLong = l is Long && r is Long
            val a = dlAsDouble(l, "'${arith.op}'")
            val b = dlAsDouble(r, "'${arith.op}'")
            when (arith.op) {
                "+" -> if (bothLong) dlNormalize(a.toLong() + b.toLong()) else dlNormalize(a + b)
                "-" -> if (bothLong) dlNormalize(a.toLong() - b.toLong()) else dlNormalize(a - b)
                "*" -> if (bothLong) dlNormalize(a.toLong() * b.toLong()) else dlNormalize(a * b)
                "/" -> {
                    if (b == 0.0) throw DlException("division by zero")
                    dlNormalize(a / b)
                }
                "div" -> {
                    if (b.toLong() == 0L) throw DlException("division by zero")
                    dlNormalize(Math.floorDiv(a.toLong(), b.toLong()))
                }
                "mod" -> {
                    if (b.toLong() == 0L) throw DlException("division by zero")
                    dlNormalize(Math.floorMod(a.toLong(), b.toLong()))
                }
                else -> throw DlException("unknown operator '${arith.op}'")
            }
        }
    }
    is DlACall -> dlCallFunction(arith.fn, arith.args.map { dlEvalArith(it, lookup) })
}

internal fun dlAsDouble(value: Any?, context: String): Double = when (value) {
    is Number -> value.toDouble()
    is Boolean -> if (value) 1.0 else 0.0
    is String -> value.toDoubleOrNull() ?: throw DlException("$context: '$value' is not a number")
    null -> throw DlException("$context: null is not a number")
    else -> throw DlException("$context: ${value::class.java.simpleName} is not a number")
}

/** `=` and `!=` treat 2 and 2.0 as equal; the ordering comparisons use [dlCompareValues]. */
fun dlCompareOp(op: String, left: Any?, right: Any?): Boolean {
    val equal = left == right || (left is Number && right is Number && dlCompareValues(left, right) == 0)
    return when (op) {
        "=" -> equal
        "!=" -> !equal
        "<" -> dlCompareValues(left, right) < 0
        "<=" -> dlCompareValues(left, right) <= 0
        ">" -> dlCompareValues(left, right) > 0
        ">=" -> dlCompareValues(left, right) >= 0
        else -> throw DlException("unknown comparison '$op'")
    }
}
