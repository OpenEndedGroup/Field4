package afield.datalog

/*
FIELD_TEST
id: datalog.core.smoke
runner: jvm-main
entrypoint: afield.datalog.DatalogSmokeTest
tags: [smoke, datalog, language]
timeoutSec: 120
expect:
  exitCode: 0
  stdoutContains:
    - "Datalog smoke passed"
*/
/**
 * Smoke for [afield.datalog] — the deductive-database language.
 *
 * Like `afield.graphquery`'s smoke this is a plain `jvm-main` entrypoint, which it can only be
 * because the engine touches no `Runloop`, starts no threads and opens no devices, so the JVM exits
 * naturally (see the `smoke-tests-natural-exit` convention). The live box-graph bridge
 * (`DatalogBoxes.kt`) and the box language live outside what this file imports.
 *
 * The case that matters most is [testSemiNaiveAgreesWithBfs]: the incremental fixpoint has to produce
 * exactly the transitive closure an independent breadth-first search produces, over a random graph
 * with cycles. Everything else here is a semantics fence; that one is the correctness proof.
 */
object DatalogSmokeTest {

    @JvmStatic
    fun main(args: Array<String>) {
        check(runTests()) { "Datalog smoke tests failed" }
    }

    fun runTests(): Boolean = try {
        println("Running Datalog smoke...")
        testFactsAndRules()
        testRecursionShapes()
        testRepeatedAndAnonymousVariables()
        testNegation()
        testStratificationRejection()
        testArithmetic()
        testNumericNormalisation()
        testAggregates()
        testSafetyErrors()
        testQueryApi()
        testProvenance()
        testAggregateProvenance()
        testCrossStratumProvenance()
        testEveryAggregateOpProves()
        testWhyNot()
        testKotlinFacts()
        testIdempotentAdd()
        testParserSurface()
        testSemiNaiveAgreesWithBfs()
        testObjectGraphBridge()
        testScale()
        println("Datalog smoke passed")
        true
    } catch (t: Throwable) {
        t.printStackTrace()
        false
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    private fun expectFailure(what: String, contains: String, body: () -> Unit) {
        val thrown = try {
            body()
            null
        } catch (e: DlException) {
            e
        }
        check(thrown != null) { "$what: expected a DlException, none thrown" }
        check(thrown.message?.contains(contains) == true) {
            "$what: expected the error to mention '$contains', got '${thrown.message}'"
        }
    }

    private fun rowsText(rows: DlRows): String =
        rows.rows.joinToString("; ") { row -> row.joinToString(",") { dlFormat(it) } }

    // ── cases ───────────────────────────────────────────────────────────────────────────────────

    private fun testFactsAndRules() {
        val d = Datalog()
        d.add(
            """
            edge(a, b). edge(b, c). edge(c, d).
            path(X, Y) :- edge(X, Y).
            path(X, Z) :- path(X, Y), edge(Y, Z).
            """
        )
        val reachable = d.query("path(a, Z)")
        check(rowsText(reachable) == "b; c; d") { "transitive closure from a: got ${rowsText(reachable)}" }
        check(reachable.columns == listOf("Z")) { "column names: ${reachable.columns}" }
        check(d.ask("path(a, d)")) { "a should reach d" }
        check(!d.ask("path(d, a)")) { "d should not reach a" }
        // A bare lowercase identifier is a string, so both spellings are the same fact.
        check(d.ask("edge(\"a\", b)")) { "quoted and bare constants must agree" }
    }

    private fun testRecursionShapes() {
        // Non-linear recursion: both body atoms are in the same stratum, so semi-naive has to run a
        // delta variant per recursive position or it will stop early.
        val d = Datalog()
        d.add(
            """
            edge(1, 2). edge(2, 3). edge(3, 4). edge(4, 5).
            path(X, Y) :- edge(X, Y).
            path(X, Z) :- path(X, Y), path(Y, Z).
            """
        )
        check(d.query("path(1, Z)").size == 4) { "non-linear closure from 1: ${rowsText(d.query("path(1, Z)"))}" }
        check(d.ask("path(1, 5)")) { "non-linear recursion missed the far end" }

        // Mutual recursion across two predicates in one stratum.
        val m = Datalog()
        m.add(
            """
            step(1, 2). step(2, 3). step(3, 4). step(4, 5). step(5, 6).
            even(X) :- start(X).
            start(1).
            odd(Y) :- even(X), step(X, Y).
            even(Y) :- odd(X), step(X, Y).
            """
        )
        check(rowsText(m.query("odd(X)")) == "2; 4; 6") { "mutual recursion odd: ${rowsText(m.query("odd(X)"))}" }
        check(rowsText(m.query("even(X)")) == "1; 3; 5") { "mutual recursion even: ${rowsText(m.query("even(X)"))}" }

        // A cycle must terminate rather than spin.
        val c = Datalog()
        c.add(
            """
            edge(a, b). edge(b, a).
            path(X, Y) :- edge(X, Y).
            path(X, Z) :- path(X, Y), edge(Y, Z).
            """
        )
        check(c.query("path(X, Y)").size == 4) { "cyclic closure should be all 4 pairs" }
    }

    private fun testRepeatedAndAnonymousVariables() {
        val d = Datalog()
        d.add(
            """
            p(1, 1). p(1, 2). p(2, 2). p(3, 4).
            loop(X) :- p(X, X).
            hasOut(X) :- p(X, _).
            """
        )
        check(rowsText(d.query("loop(X)")) == "1; 2") { "repeated variable in one atom: ${rowsText(d.query("loop(X)"))}" }
        check(rowsText(d.query("hasOut(X)")) == "1; 2; 3") { "anonymous variable: ${rowsText(d.query("hasOut(X)"))}" }
        // Two anonymous variables must not be forced equal to each other.
        check(d.ask("p(_, _)")) { "two anonymous holes should be independent" }
        val pairs = d.query("p(_, _)")
        check(pairs.columns.isEmpty()) { "anonymous variables must not appear as query columns" }
    }

    private fun testNegation() {
        val d = Datalog()
        d.add(
            """
            node(a). node(b). node(c).
            edge(a, b).
            path(X, Y) :- edge(X, Y).
            path(X, Z) :- path(X, Y), edge(Y, Z).
            isolated(X) :- node(X), !path(X, _), !path(_, X).
            """
        )
        check(rowsText(d.query("isolated(X)")) == "c") { "negation: ${rowsText(d.query("isolated(X)"))}" }

        // `not` spelled out, and negation over a computed relation.
        val n = Datalog()
        n.add(
            """
            val(a, 1). val(b, 5). val(c, 9).
            big(X) :- val(X, V), V > 4.
            small(X) :- val(X, _), not big(X).
            """
        )
        check(rowsText(n.query("small(X)")) == "a") { "not-keyword negation: ${rowsText(n.query("small(X)"))}" }
    }

    private fun testStratificationRejection() {
        val d = Datalog()
        d.add(
            """
            p(1).
            q(X) :- p(X), !r(X).
            r(X) :- p(X), !q(X).
            """
        )
        expectFailure("mutual negation", "not stratifiable") { d.solve() }

        val a = Datalog()
        a.add(
            """
            item(1). item(2).
            total(N) :- N = count : { total(_) }.
            """
        )
        expectFailure("aggregate over itself", "not stratifiable") { a.solve() }
    }

    private fun testArithmetic() {
        val d = Datalog()
        d.add(
            """
            val(a, 3). val(b, 10). val(c, 7).
            doubled(X, D) :- val(X, V), D = V * 2.
            big(X, D) :- doubled(X, D), D >= 14.
            label(X, L) :- val(X, V), L = upper(X) + ":" + V.
            odd(X) :- val(X, V), V mod 2 = 1.
            """
        )
        check(rowsText(d.query("big(X, D)")) == "b,20; c,14") { "arithmetic bind + compare: ${rowsText(d.query("big(X, D)"))}" }
        check(rowsText(d.query("label(a, L)")) == "A:3") { "string concat: ${rowsText(d.query("label(a, L)"))}" }
        check(rowsText(d.query("odd(X)")) == "a; c") { "mod: ${rowsText(d.query("odd(X)"))}" }
        // `=` compares once its left side is bound, rather than re-binding it.
        check(d.ask("val(a, V), V = 3")) { "'=' against a bound variable must compare" }
        check(!d.ask("val(a, V), V = 4")) { "'=' against a bound variable must be able to fail" }
        // A builtin call standing alone is a test.
        check(rowsText(d.query("val(X, _), contains(X, \"a\")")) == "a") { "bare builtin call as a test" }
        check(d.query("V = abs(0 - 6) + max(1, 2)").value() == 8L) { "function calls: ${d.query("V = abs(0-6) + max(1,2)").value()}" }
    }

    private fun testNumericNormalisation() {
        val d = Datalog()
        d.add(
            """
            size(disk, 2).
            half(X, H) :- size(X, S), H = S * 2.0 / 2.0.
            matched(X) :- half(X, H), size(X, H).
            """
        )
        check(d.ask("matched(disk)")) { "an integral Double must join with a Long" }
        check(d.query("H = 7 / 2").value() == 3.5) { "non-integral division stays a Double" }
        check(d.query("H = 7 div 2").value() == 3L) { "integer division" }
    }

    private fun testAggregates() {
        val d = Datalog()
        d.add(
            """
            dept(eng). dept(art). dept(empty).
            emp(ada, eng, 100). emp(bob, eng, 60). emp(cyd, art, 80).
            headcount(D, N) :- dept(D), N = count : { emp(_, D, _) }.
            payroll(D, S)   :- dept(D), S = sum P : { emp(_, D, P) }.
            top(D, M)       :- dept(D), M = max P : { emp(_, D, P) }.
            avg(D, A)       :- dept(D), A = mean P : { emp(_, D, P) }.
            roster(D, L)    :- dept(D), L = collect E : { emp(E, D, _) }.
            """
        )
        check(rowsText(d.query("headcount(D, N)")) == "art,1; empty,0; eng,2") {
            "count groups: ${rowsText(d.query("headcount(D, N)"))}"
        }
        check(rowsText(d.query("payroll(D, S)")) == "art,80; empty,0; eng,160") {
            "sum groups: ${rowsText(d.query("payroll(D, S)"))}"
        }
        // max/mean over an empty group have no value, so the rule simply does not fire there.
        check(rowsText(d.query("top(D, M)")) == "art,80; eng,100") { "max: ${rowsText(d.query("top(D, M)"))}" }
        check(rowsText(d.query("avg(D, A)")) == "art,80; eng,80") { "mean: ${rowsText(d.query("avg(D, A)"))}" }
        check(rowsText(d.query("roster(eng, L)")) == "[ada, bob]") { "collect: ${rowsText(d.query("roster(eng, L)"))}" }

        // An aggregate with no outer grouping variable folds the whole relation.
        check(d.query("N = count : { emp(_, _, _) }").value() == 3L) { "ungrouped count" }
        check(d.query("S = sum P : { emp(_, _, P) }").value() == 240L) { "ungrouped sum" }
    }

    private fun testSafetyErrors() {
        expectFailure("unbound head variable", "never bound by the body") {
            val d = Datalog(); d.add("p(1). q(X, Y) :- p(X)."); d.solve()
        }
        expectFailure("unsafe negation", "bound by a positive body literal") {
            // Y is bound nowhere and is mentioned twice, so it is not a wildcard either.
            val d = Datalog(); d.add("p(1, 2). q(X) :- !p(X, Y), !p(Y, X)."); d.solve()
        }
        expectFailure("arity clash", "arity") {
            val d = Datalog(); d.add("p(1). p(1, 2)."); d.solve()
        }
        expectFailure("non-ground fact", "must be ground") {
            val d = Datalog(); d.add("p(X)."); d.solve()
        }
        expectFailure("parse error", "expected") {
            Datalog().add("p(1) :- .")
        }
    }

    private fun testQueryApi() {
        val d = Datalog()
        d.add("age(ada, 36). age(bob, 41). age(cyd, 29).")
        val rows = d.query("age(N, A), A > 30")
        check(rows.columns == listOf("N", "A")) { "columns: ${rows.columns}" }
        check(rows.column("N") == listOf("ada", "bob")) { "column(): ${rows.column("N")}" }
        check(rows.asMaps().first()["A"] == 36L) { "asMaps(): ${rows.asMaps()}" }
        check(rows.table().lines().first().startsWith("N")) { "table header: ${rows.table()}" }
        check(d.query("age(nobody, A)").table() == "(no rows)") { "empty table rendering" }
        check(d.query("age(ada, 36)").table() == "yes") { "ground goal renders as yes" }
        check(d.query("age(ada, 99)").table() == "no") { "failing ground goal renders as no" }
        // Sorting is part of the contract — two runs must print identically.
        check(rowsText(d.query("age(N, A)")) == rowsText(d.query("age(N, A)"))) { "query order must be stable" }
        check(rowsText(d.query("age(N, _)")) == "ada; bob; cyd") { "rows are sorted" }
    }

    private fun testProvenance() {
        val d = Datalog()
        d.add(
            """
            edge(a, b). edge(b, c). edge(c, d).
            path(X, Y) :- edge(X, Y).
            path(X, Z) :- path(X, Y), edge(Y, Z).
            """
        )
        val why = d.why("path(a, d)")
        check(why != null) { "path(a, d) should have a derivation" }
        val text = why.render()
        check(text.startsWith("path(a, d)")) { "derivation root: $text" }
        // Every leaf of the proof must be a stated edge, and the proof must mention the chain.
        check(text.contains("edge(c, d)")) { "derivation should use edge(c, d):\n$text" }
        check(text.contains("edge(a, b)")) { "derivation should bottom out at edge(a, b):\n$text" }
        check(!text.contains("no proof reconstructed")) { "derivation should be complete:\n$text" }

        // A proof has to survive a rule whose body mixes atoms with tests: the search has to run the
        // atom that binds the variable *before* the comparison that reads it.
        val filtered = Datalog()
        filtered.add(
            """
            cell(a1, 3). cell(a2, 4). cell(a3, 10).
            over(C) :- cell(C, V), V > 5.
            """
        )
        val overWhy = filtered.why("over(a3)")
        check(overWhy != null) { "over(a3) should have a derivation" }
        check(overWhy.premises.singleOrNull()?.fact == "cell(a3, 10)") {
            "the proof should name the fact that satisfied the test:\n${overWhy.render()}"
        }
        check(!overWhy.render().contains("no proof reconstructed")) { overWhy.render() }

        val base = d.why("edge(a, b)")
        check(base?.rule == null && base?.premises?.isEmpty() == true) { "a base fact is its own proof" }
        check(d.why("path(d, a)") == null) { "a non-fact has no derivation" }

        // A proof may only lean on strictly earlier rounds, so a cyclic program still terminates.
        val cyclic = Datalog()
        cyclic.add(
            """
            edge(a, b). edge(b, a).
            path(X, Y) :- edge(X, Y).
            path(X, Z) :- path(X, Y), edge(Y, Z).
            """
        )
        val loop = cyclic.why("path(a, a)")
        check(loop != null && !loop.render().contains("truncated")) { "cyclic proof: ${loop?.render()}" }
    }

    /**
     * A fact derived through an aggregate is justified by the tuples that contributed to it, plus
     * the arithmetic that combined them. Until this landed `#why` answered such a fact with
     * "no proof reconstructed", which is the one thing a reader cannot check by eye.
     *
     * The oracle here is deliberately **set equality on the group**, not "the proof mentions ada".
     * An existence check passes on a group that also swept in `cy` from the other department — which
     * is precisely the failure that would make an aggregate proof worse than an apology.
     */
    private fun testAggregateProvenance() {
        val d = Datalog()
        d.add(
            """
            emp(ada, sales, 100). emp(bob, sales, 200). emp(cy, eng, 50).
            dept(sales). dept(eng).
            payroll(D, S) :- dept(D), S = sum P : { emp(_, D, P) }.
            """
        )
        // Premise: the model really does contain the fact whose proof is under test, and it really
        // was derived (not stated). If either stops being true this test must fail loudly rather
        // than quietly assert nothing.
        check(d.ask("payroll(sales, 300)")) { "premise: payroll(sales, 300) should be in the model" }
        check(d.solve()["payroll"]!!.roundOf(listOf("sales", 300L)) > 0) {
            "premise: payroll(sales, 300) must be derived, not stated"
        }

        val why = d.why("payroll(sales, 300)")
        check(why != null) { "payroll(sales, 300) should have a derivation" }
        val text = why.render()
        check(!text.contains("no proof reconstructed")) { "an aggregate must now be proved:\n$text" }

        val aggregateNodes = proofNodes(why).filter { it.kind == DlPremiseKind.AGGREGATE }
        check(aggregateNodes.size == 1) { "exactly one aggregate step:\n$text" }
        val group = aggregateNodes.single().premises.map { it.fact }.toSet()
        check(group == setOf("emp(ada, sales, 100)", "emp(bob, sales, 200)")) {
            "the group must be exactly the sales rows, no more and no fewer: $group\n$text"
        }
        check(aggregateNodes.single().because().contains("sum over 2 tuples = 300")) {
            "the aggregate step must state the arithmetic:\n$text"
        }
        check(text.contains("contributes P = 100") && text.contains("contributes P = 200")) {
            "each tuple must say what it contributed:\n$text"
        }
        // Counterfactual, floored at a number rather than "differs": the old answer was a single
        // node with an apology. A real proof of this fact has the head, the `dept` premise, the
        // aggregate step and both group members — at least five nodes — and is not empty.
        check(text.isNotEmpty()) { "the rendered proof must be non-empty" }
        check(proofNodes(why).size >= 5) { "a reconstructed aggregate proof has ≥5 nodes:\n$text" }

        // min/max name their witness, which is most of what "why is the max 200" means.
        val m = Datalog()
        m.add(
            """
            emp(ada, sales, 100). emp(bob, sales, 200). emp(cy, sales, 150).
            dept(sales).
            best(D, S) :- dept(D), S = max P : { emp(_, D, P) }.
            """
        )
        check(m.ask("best(sales, 200)")) { "premise: best(sales, 200) should be in the model" }
        val maxWhy = m.why("best(sales, 200)")
        check(maxWhy != null) { "best(sales, 200) should have a derivation" }
        val maxText = maxWhy.render()
        check(maxText.contains("emp(bob, sales, 200)") && maxText.contains("the max")) {
            "max must label the tuple that was the max:\n$maxText"
        }
        check(maxText.contains("emp(ada, sales, 100)")) {
            "the other tuples are part of the proof too — nothing bigger exists:\n$maxText"
        }

        // A ledger rather than a spot check: *every* row the aggregate rule derived must prove, and a
        // total that is not in the model must not. One group quietly reconstructed for the wrong
        // department would show up here as an apology, and a spot check on `sales` would not see it.
        val ledger = Datalog()
        ledger.add(
            """
            emp(ada, sales, 100). emp(bob, sales, 200). emp(cy, eng, 50). emp(di, eng, 70).
            emp(el, art, 5).
            dept(sales). dept(eng). dept(art).
            payroll(D, S) :- dept(D), S = sum P : { emp(_, D, P) }.
            """
        )
        val totals = ledger.rowsOf("payroll").associate { it[0] as String to it[1] }
        check(totals == mapOf("sales" to 300L, "eng" to 120L, "art" to 5L)) {
            "premise: the totals themselves must be right before their proofs mean anything: $totals"
        }
        totals.forEach { (dept, total) ->
            val proof = ledger.why("payroll($dept, $total)")
            check(proof != null) { "payroll($dept, $total) should have a derivation" }
            check(!proof.render().contains("no proof reconstructed")) {
                "every derived total must prove, not just the first:\n${proof.render()}"
            }
            val members = proofNodes(proof).filter { it.kind == DlPremiseKind.AGGREGATE }
                .single().premises.map { it.fact }
            check(members.size == ledger.rowsOf("emp").count { it[1] == dept }) {
                "the group for $dept has the wrong size: $members"
            }
        }
        check(ledger.why("payroll(sales, 299)") == null) { "a total that is not in the model has no proof" }
    }

    /**
     * Negation and aggregation both open a stratum, and rounds used to restart at 1 in each one, so
     * `Datalog.why`'s "a premise is strictly older than its conclusion" filter threw away every
     * candidate across a stratum boundary and reported a real fact as unproven. Rounds are global
     * now; this is the fence.
     */
    private fun testCrossStratumProvenance() {
        val d = Datalog()
        d.add(
            """
            edge(a, b). edge(b, c). edge(c, d).
            path(X, Y) :- edge(X, Y).
            path(X, Z) :- path(X, Y), edge(Y, Z).
            indirect(X, Y) :- path(X, Y), !edge(X, Y).
            """
        )
        // Premise: the boundary is actually exercised — `path(a, d)` needs more than one round, so a
        // per-stratum counter really would have put it above `indirect(a, d)`.
        val pathRound = d.solve()["path"]!!.roundOf(listOf("a", "d"))
        check(pathRound >= 2) { "premise: path(a, d) should take several rounds, took $pathRound" }
        check(d.ask("indirect(a, d)")) { "premise: indirect(a, d) should be in the model" }

        val why = d.why("indirect(a, d)")
        check(why != null) { "indirect(a, d) should have a derivation" }
        val text = why.render()
        check(!text.contains("no proof reconstructed")) { "a proof across a stratum boundary:\n$text" }
        check(text.contains("path(a, d)")) { "the proof must descend into path:\n$text" }
        check(text.contains("edge(a, b)")) { "and bottom out at stated edges:\n$text" }
        // The negated literal is half the reason the rule fired; a proof that omits it is silent
        // about the half a reader is least able to reconstruct.
        val absences = proofNodes(why).filter { it.kind == DlPremiseKind.ABSENCE }
        check(absences.size == 1 && absences.single().fact == "!edge(a, d)") {
            "the negated literal must appear as its own step: ${absences.map { it.fact }}\n$text"
        }

        // `!p(X, _)` is an existential: the search has to ask whether *any* row matches.
        val e = Datalog()
        e.add(
            """
            node(a). node(b). node(c). edge(a, b).
            leaf(X) :- node(X), !edge(X, _).
            """
        )
        check(rowsText(e.query("leaf(X)")) == "b; c") { "premise: leaves are b and c" }
        val leafWhy = e.why("leaf(c)")
        check(leafWhy != null && !leafWhy.render().contains("no proof reconstructed")) {
            "an existentially negated literal must be provable:\n${leafWhy?.render()}"
        }
        check(e.why("leaf(a)") == null) { "leaf(a) is not in the model, so it has no proof" }
    }

    /**
     * A **bidirectional ledger over every aggregate operator**, rather than one case per operator
     * somebody remembers to add.
     *
     * The expected values are a map keyed by operator, and the test asserts *set equality* between
     * its keys and [DatalogParser.aggregateOps] in both directions. A seventh operator added to the
     * language fails here until it is given a proof case; an operator deleted fails here too, rather
     * than quietly yielding an empty loop — which is how a hand-maintained list of six goes green
     * over a real gap.
     *
     * The oracle per operator is exact string equality on the aggregate step's label, so it pins the
     * group size *and* the value in one assertion. Anything weaker passes on a proof that reports
     * the right number over the wrong group.
     */
    private fun testEveryAggregateOpProves() {
        val expected = mapOf(
            "count" to "2",
            "sum" to "300",
            "min" to "100",
            "max" to "200",
            "mean" to "150",
            "collect" to "[100, 200]",
        )
        check(expected.keys == DatalogParser.aggregateOps) {
            "the ledger and the language must agree on the operator set: " +
                "${expected.keys} vs ${DatalogParser.aggregateOps}"
        }

        val covered = LinkedHashSet<String>()
        expected.forEach { (op, value) ->
            val subgoal = if (op == "count") "N = count : { emp(_, D, _) }" else "N = $op P : { emp(_, D, P) }"
            val d = Datalog()
            d.add(
                """
                emp(ada, sales, 100). emp(bob, sales, 200). emp(cy, eng, 50).
                dept(sales). dept(eng).
                agg(D, N) :- dept(D), $subgoal.
                """
            )
            // Premise: both groups fired, and the row under test was derived rather than stated.
            val rows = d.rowsOf("agg")
            check(rows.size == 2) { "$op: premise — one row per department, got $rows" }
            val row = rows.first { it[0] == "sales" }
            check(d.solve()["agg"]!!.roundOf(row) > 0) { "$op: premise — agg(sales, …) must be derived" }

            val proof = d.why("agg(sales, N)")
            check(proof != null) { "$op: agg(sales, …) should have a derivation" }
            val text = proof.render()
            check(!text.contains("no proof reconstructed")) { "$op: must be proved:\n$text" }

            val node = proofNodes(proof).single { it.kind == DlPremiseKind.AGGREGATE }
            check(node.because() == "aggregate: $op over 2 tuples = $value") {
                "$op: the aggregate step must state the group size and the value: ${node.because()}"
            }
            check(node.premises.map { it.fact }.toSet() ==
                setOf("emp(ada, sales, 100)", "emp(bob, sales, 200)")) {
                "$op: the group must be exactly the sales rows: ${node.premises.map { it.fact }}"
            }
            // `count` has no `over` expression, so its members contribute their existence and
            // nothing else. Every other operator must say what each tuple gave it.
            check(node.premises.all { (it.contribution != null) == (op != "count") }) {
                "$op: contribution presence must follow whether the operator has an expression:\n$text"
            }
            covered.add(op)
        }
        check(covered == DatalogParser.aggregateOps) {
            "every operator must have been exercised: $covered"
        }
    }

    /**
     * A silent empty answer gives no reason, and a reader with no reason guesses. `whyNot` walks the
     * conjunction and names the literal that took the answer set to zero.
     *
     * The oracle is **set equality on the step ledger**, not "the report mentions edge": a walk that
     * silently dropped a literal, or invented one, passes an existence check and fails this.
     */
    private fun testWhyNot() {
        val d = Datalog()
        d.add("cell(a1, 3). cell(a2, 4). cell(a3, 10). colour(a1, red).")

        // Premise: the goal really does come back empty, and its first literal really does not.
        check(d.query("cell(C, V), V > 50").isEmpty) { "premise: the goal must have no answers" }
        check(d.query("cell(C, V)").size == 3) { "premise: cell has three rows" }

        val flat = d.whyNot("cell(C, V), V > 50")
        check(!flat.present) { "the goal has no answers, so present must be false" }
        check(flat.steps.map { it.literal } == listOf("cell(C, V)", "V > 50")) {
            "every literal gets exactly one step, in order: ${flat.steps.map { it.literal }}"
        }
        check(flat.steps[0].answers == 3) { "the first literal has three answers: ${flat.steps[0].answers}" }
        check(flat.culprit?.literal == "V > 50") { "the test is the culprit: ${flat.culprit?.literal}" }
        check(flat.witness == mapOf("C" to "a1", "V" to 3L)) {
            "and one binding that got as far as the test is shown: ${flat.witness}"
        }

        // A goal that *does* have answers must say so rather than inventing a failure.
        val fine = d.whyNot("cell(C, V), V > 5")
        check(fine.present && fine.culprit == null) { "a satisfiable goal is not a why-not" }

        // A single derived atom is walked once per rule that could have produced it, best first.
        val g = Datalog()
        g.add(
            """
            edge(a, b). edge(b, c). edge(c, d).
            path(X, Y) :- edge(X, Y).
            path(X, Z) :- path(X, Y), edge(Y, Z).
            """
        )
        check(!g.ask("path(a, e)")) { "premise: path(a, e) must be absent" }
        val why = g.whyNot("path(a, e)")
        val text = why.render()
        check(why.attempts.size == 2) { "both rules for path/2 are attempts:\n$text" }
        // Best first: the recursive rule got one literal in, the base rule got none. A report that
        // led with the rule that failed immediately would bury the informative half.
        val best = why.attempts.first()
        check(best.depth == 1) { "the closest attempt satisfied one literal, not ${best.depth}:\n$text" }
        check(best.steps.map { it.literal } == listOf("path(a, Y)", "edge(Y, e)")) {
            "the recursive body, with the head's constants substituted: ${best.steps.map { it.literal }}"
        }
        check(best.culprit?.literal == "edge(Y, e)") { "the second literal is what fails:\n$text" }
        check(best.witness == mapOf("Y" to "b")) { "the witness is deterministic: ${best.witness}" }
        check(why.attempts.last().depth == 0) { "the base rule fails on its only literal:\n$text" }
        check(text.contains("nothing survives here")) { "the report must point at the failure:\n$text" }
        check(text.isNotEmpty() && text.lines().size >= 6) {
            "a two-rule why-not is at least six lines; got ${text.lines().size}:\n$text"
        }

        // A predicate nothing defines is a different answer from a predicate with no matching rows.
        val unknown = g.whyNot("reachable(a, b)")
        check(unknown.note?.contains("nothing defines reachable/2") == true) {
            "an undefined predicate must say so: ${unknown.note}"
        }

        // A test written before the atom that binds it is not "zero answers", it is "not yet askable"
        // — and saying which is the whole content of the answer.
        val early = d.whyNot("V > 50, cell(C, V)")
        check(early.steps.first().answers == null) {
            "an unevaluable prefix is null, not 0: ${early.steps.first().answers}"
        }
        check(early.steps.first().describe().contains("not evaluable")) { early.render() }
    }

    /** Every node of a proof, root first. */
    private fun proofNodes(d: DlDerivation): List<DlDerivation> =
        listOf(d) + d.premises.flatMap { proofNodes(it) }

    private fun testKotlinFacts() {
        data class Staff(val name: String, val dept: String, val pay: Int)

        val staff = listOf(Staff("ada", "eng", 100), Staff("bob", "eng", 60), Staff("cyd", "art", 80))
        val d = Datalog()
        d.facts("emp", staff) { listOf(it.name, it.dept, it.pay) }
        d.facts("dept", listOf(listOf("eng"), listOf("art")))
        d.fact("budget", "eng", 150)
        d.add("over(D) :- budget(D, B), S = sum P : { emp(_, D, P) }, S > B.")
        check(rowsText(d.query("over(D)")) == "eng") { "kotlin-sourced facts: ${rowsText(d.query("over(D)"))}" }
        check(d.query("emp(N, art, P)").rows.single() == listOf("cyd", 80L)) { "Int columns normalise to Long" }
    }

    private fun testIdempotentAdd() {
        val d = Datalog()
        val program = """
            edge(a, b).
            path(X, Y) :- edge(X, Y).
            path(X, Z) :- path(X, Y), edge(Y, Z).
        """
        d.add(program)
        d.add(program)
        d.add(program)
        check(d.rules.size == 2) { "re-adding the same program must not duplicate rules: ${d.rules.size}" }
        check(d.query("path(X, Y)").size == 1) { "re-adding must not change the model" }
    }

    private fun testParserSurface() {
        val d = Datalog()
        d.add(
            """
            % a percent comment
            // a slash comment
            /* a
               block comment */
            quoted("hello, world").          % commas and spaces inside a string
            escaped("a\"b").
            negative(-4).
            floaty(1.5).
            flag(true).
            ready.                            % a nullary predicate
            greeting(X) :- quoted(X).
            """
        )
        check(d.ask("quoted(\"hello, world\")")) { "string with a comma" }
        check(d.ask("escaped(\"a\\\"b\")")) { "escaped quote" }
        check(d.query("negative(N)").value() == -4L) { "negative literal: ${d.query("negative(N)").value()}" }
        check(d.query("floaty(N)").value() == 1.5) { "float literal" }
        check(d.ask("flag(true)")) { "boolean constant" }
        check(d.ask("ready")) { "nullary predicate" }
        check(d.ask("greeting(\"hello, world\")")) { "rule over a string fact" }

        val unit = DatalogParser.parse("?- path(a, Z).\n#reset\n")
        check(unit.queries.size == 1) { "a ?- goal is recorded as a query" }
        check(unit.directives.singleOrNull()?.name == "reset") { "directives are parsed: ${unit.directives}" }

        // `#datalog` is the embedded-region header `datalog.js` looks for. The parser has to accept
        // and drop it, or the same text could not be both highlighted in a Kotlin """…""" string
        // and run — which is the whole point of the tag.
        val tagged = DatalogParser.parse("#datalog\nedge(a, b).\npath(X, Y) :- edge(X, Y).\n")
        check(tagged.directives.isEmpty()) { "#datalog should be dropped, got ${tagged.directives}" }
        check(tagged.rules.size == 2) { "the program after the header did not parse: ${tagged.rules}" }
        val tagWithComment = Datalog()
        tagWithComment.add("\n\n  #datalog v1 — reachability\nedge(a, b).\n")
        check(tagWithComment.ask("edge(a, b)")) { "a #datalog header with trailing text and blank lines" }
    }

    /**
     * The differential case: the incremental fixpoint against an independent BFS closure over a
     * random cyclic graph. If semi-naive ever drops a delta variant, or a stratum runs before its
     * inputs are saturated, these two stop agreeing.
     */
    private fun testSemiNaiveAgreesWithBfs() {
        val random = java.util.Random(20260829L)
        val nodes = 60
        val edges = LinkedHashSet<Pair<Int, Int>>()
        repeat(200) { edges.add(random.nextInt(nodes) to random.nextInt(nodes)) }

        val d = Datalog()
        d.facts("edge", edges.map { listOf(it.first, it.second) })
        d.add(
            """
            path(X, Y) :- edge(X, Y).
            path(X, Z) :- path(X, Y), edge(Y, Z).
            """
        )
        val fromDatalog = d.query("path(X, Y)").rows.map { (it[0] as Long).toInt() to (it[1] as Long).toInt() }.toSet()

        val adjacency = HashMap<Int, MutableList<Int>>()
        edges.forEach { (a, b) -> adjacency.getOrPut(a) { ArrayList() }.add(b) }
        val fromBfs = HashSet<Pair<Int, Int>>()
        (0 until nodes).forEach { start ->
            val seen = HashSet<Int>()
            val queue = ArrayDeque<Int>()
            adjacency[start]?.forEach { queue.add(it) }
            while (queue.isNotEmpty()) {
                val n = queue.removeFirst()
                if (!seen.add(n)) continue
                fromBfs.add(start to n)
                adjacency[n]?.forEach { queue.add(it) }
            }
        }
        check(fromDatalog == fromBfs) {
            "semi-naive closure disagrees with BFS: datalog has ${fromDatalog.size}, BFS has ${fromBfs.size}, " +
                "missing ${(fromBfs - fromDatalog).take(5)}, extra ${(fromDatalog - fromBfs).take(5)}"
        }

        // The same check with a non-linear rule, which exercises the per-position delta variants.
        val nonLinear = Datalog()
        nonLinear.facts("edge", edges.map { listOf(it.first, it.second) })
        nonLinear.add(
            """
            path(X, Y) :- edge(X, Y).
            path(X, Z) :- path(X, Y), path(Y, Z).
            """
        )
        val fromNonLinear = nonLinear.query("path(X, Y)").rows
            .map { (it[0] as Long).toInt() to (it[1] as Long).toInt() }.toSet()
        check(fromNonLinear == fromBfs) {
            "non-linear closure disagrees with BFS by ${(fromBfs - fromNonLinear).size + (fromNonLinear - fromBfs).size} pairs"
        }
    }

    /**
     * The `afield.graphquery` bridge: an object graph as facts, and the alias join that is the
     * reason for having it.
     *
     * Safe to run from a `jvm-main` entrypoint because the graphquery *core* touches no `Runloop`
     * either — the same property that lets `graphquery.core.smoke` be one.
     */
    private fun testObjectGraphBridge() {
        class Osc(var freq: Float, var gain: Float, val label: String)

        val shared = Osc(220f, 0.8f, "shared")

        @Suppress("unused")
        class Rig(val carrier: Osc, val modulator: Osc, val spare: Osc, var title: String)

        val rig = Rig(shared, Osc(440f, 0.2f, "mod"), shared, "rig")

        val d = Datalog()
        d.addObjectGraph(rig)
        d.add(dlObjectGraphRules)

        // The walk reached the three oscillators and their fields.
        check(d.ask("slot(\"carrier/freq\")")) { "the walk should reach carrier/freq" }
        check(d.query("value(\"carrier/freq\", V)").value() == 220L) {
            "scalar values: ${d.query("value(\"carrier/freq\", V)").value()}"
        }
        check(d.ask("writable(\"carrier/freq\")")) { "a var is writable" }
        check(!d.ask("writable(\"carrier/label\")")) { "a val is not writable" }

        // The edge relation follows the walk, and the root is nobody's child.
        check(d.query("link(\"<root>\", C, _)").size == 4) {
            "the root should have four children: ${d.query("link(\"<root>\", C, _)").rows}"
        }
        check(!d.ask("link(_, \"<root>\", _)")) { "the root must not be its own child" }
        check(d.ask("link(\"carrier\", \"carrier/freq\", \"freq\")")) { "edges carry the segment name" }

        // Identity, which is the point: `carrier` and `spare` are one object under two names, and
        // `modulator` is a different one. Equality would call all three the same shape.
        val aliases = d.query("alias(A, B)").rows.map { "${it[0]}=${it[1]}" }.sorted()
        check(aliases.any { it == "carrier=spare" }) { "carrier and spare are the same object: $aliases" }
        check(aliases.none { it.contains("modulator=") && it.contains("carrier") }) {
            "modulator is a different object: $aliases"
        }
        // The aliasing runs all the way down: the shared oscillator's fields alias too.
        check(d.ask("alias(\"carrier/freq\", \"spare/freq\")").not()) {
            "a scalar is not an object, so two Floats must not alias"
        }

        // Reachability the glob cannot express.
        val below = d.query("reaches(\"carrier\", P)").column("P")
        check(below.containsAll(listOf("carrier/freq", "carrier/gain", "carrier/label"))) {
            "reaches should follow the walk: $below"
        }
        check(d.query("reaches(\"<root>\", P)").size >= 12) {
            "everything is reachable from the root: ${d.query("reaches(\"<root>\", P)").size}"
        }

        // A scoped pattern loads less, and the facts say so.
        val scoped = Datalog()
        scoped.addObjectGraph(rig, "carrier/*")
        check(scoped.query("slot(P)").size < d.query("slot(P)").size) {
            "a narrow pattern should load fewer slots"
        }
    }

    /**
     * Indices, not the fixpoint, are what make this bearable: the closure of a 400-link chain is
     * 80k facts, which an unindexed nested-loop join does not finish in a smoke test's budget.
     */
    private fun testScale() {
        val chain = (1 until 400).map { listOf(it, it + 1) }
        val d = Datalog()
        d.facts("edge", chain)
        d.add(
            """
            path(X, Y) :- edge(X, Y).
            path(X, Z) :- path(X, Y), edge(Y, Z).
            """
        )
        val started = System.nanoTime()
        val solution = d.solve()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        val expected = 399 * 400 / 2
        check(solution.rows("path").size == expected) {
            "chain closure should be $expected facts, got ${solution.rows("path").size}"
        }
        println("  chain closure: $expected facts in ${elapsedMs}ms")
        check(elapsedMs < 30_000) { "chain closure took ${elapsedMs}ms" }
    }
}
