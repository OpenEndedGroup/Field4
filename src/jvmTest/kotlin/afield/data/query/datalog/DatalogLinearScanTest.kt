package afield.data.query.datalog

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Liveness and linear-scan register allocation in Field's Datalog, after Max Bernstein and Yannis
 * Smaragdakis ("Linear scan register allocation in Datalog", bernsteinbear.com/blog/lsra-datalog).
 * [PROGRAM] is the same text as the "Allocating registers" section of `public/docs/datalog.md`;
 * the tests pin the Wimmer 2010 example's intervals and allocation, and check the declarative scan
 * against a plain imperative linear scan over the same intervals.
 */
class DatalogLinearScanTest {

    private fun solved() = Datalog().apply { add(PROGRAM) }

    @Test
    fun `liveness intervals of the Wimmer example`() {
        val d = solved()
        val got = d.query("interval(V, S, E)").rows.map { "${it[0]} ${it[1]}..${it[2]}" }.sorted()
        assertEquals(EXPECTED_INTERVALS, got)
    }

    @Test
    fun `two registers, r10 and r12 spilled`() {
        val d = solved()
        val regs = d.query("reg_of(V, R)").rows.map { "${it[0]}=${it[1]}" }.sorted()
        assertEquals(EXPECTED_REGS, regs)
        assertEquals(EXPECTED_SPILLED, d.query("spilled(V)").column("V").map { it.toString() }.sorted())
    }

    @Test
    fun `declarative scan agrees with an imperative linear scan`() {
        val d = solved()
        val intervals = d.query("interval(V, S, E)").rows.map {
            Triple(it[0].toString(), (it[1] as Long).toInt(), (it[2] as Long).toInt())
        }
        // Classic Poletto-Sarkar scan with the gist's victim order: the interval ending last, a
        // later start breaking ties; a register whose interval ended at N is free after N.
        val held = arrayOfNulls<Triple<String, Int, Int>>(2)
        val spilled = sortedSetOf<String>()
        val assigned = sortedMapOf<String, Int>()
        for (iv in intervals.sortedBy { it.second }) {
            for (r in held.indices) if (held[r] != null && held[r]!!.third < iv.second) held[r] = null
            val free = held.indexOfFirst { it == null }
            if (free >= 0) { held[free] = iv; assigned[iv.first] = free; continue }
            val worst = held.indices.maxWith(compareBy({ held[it]!!.third }, { held[it]!!.second }))
            val w = held[worst]!!
            if (w.third > iv.third || (w.third == iv.third && w.second > iv.second)) {
                spilled += w.first; assigned.remove(w.first); held[worst] = iv; assigned[iv.first] = worst
            } else spilled += iv.first
        }
        assertEquals(assigned.map { "${it.key}=${it.value}" }.sorted(),
            d.query("reg_of(V, R)").rows.map { "${it[0]}=${it[1]}" }.sorted())
        assertEquals(spilled.toList(), d.query("spilled(V)").column("V").map { it.toString() }.sorted())
    }

    @Test
    fun `the spill decision has a proof`() {
        val d = solved()
        val r12 = d.why("spilled(r12)")!!.render()
        kotlin.test.assertTrue("allocated(r12)" in r12, r12)
        // r12 lost its register at instruction 4, where r13 was defined and r12 ended last
        val evicted = d.why("victim(4, r12)", depth = 3)!!.render()
        kotlin.test.assertTrue("evict(" in evicted && "r13" in evicted, evicted)
        kotlin.test.assertTrue("no proof reconstructed" !in evicted, evicted)
        println(evicted)
    }

    companion object {
        val EXPECTED_INTERVALS =
            listOf("r10 0..14", "r11 1..2", "r12 3..14", "r13 4..10", "r14 9..12", "r15 11..12", "r16 15..16")
        val EXPECTED_REGS = listOf("r11=1", "r13=1", "r14=0", "r15=1", "r16=0")
        val EXPECTED_SPILLED = listOf("r10", "r12")

        val PROGRAM = """
            #datalog
            % the Wimmer 2010 example: uses and defs per instruction, then control flow
            var_def(0, r10). var_def(1, r11). var_def(3, r12). var_def(4, r13).
            var_def(9, r14). var_def(11, r15). var_def(15, r16).
            var_use(2, r11). var_use(5, r13). var_use(8, r12). var_use(8, r13). var_use(10, r13).
            var_use(12, r14). var_use(12, r15). var_use(14, r10). var_use(14, r12). var_use(16, r16).
            jump(2, 3). jumpi(6, 7, 13). jump(12, 3). ret(16).
            last_insn(16).

            % instructions, the flow between them, and basic blocks
            insn(0).
            insn(N) :- insn(M), last_insn(L), M < L, N = M + 1.
            branch(I) :- jump(I, _).   branch(I) :- jumpi(I, _, _).   branch(I) :- ret(I).
            flow(I, J) :- jump(I, J).
            flow(I, J) :- jumpi(I, J, _).
            flow(I, J) :- jumpi(I, _, J).
            flow(I, J) :- insn(I), !branch(I), J = I + 1, insn(J).
            head(0).
            head(J) :- branch(I), flow(I, J).
            head(J) :- branch(I), J = I + 1, insn(J).
            block(H, H) :- head(H).
            block(H, J) :- block(H, I), !branch(I), flow(I, J), !head(J).
            block_edge(A, B) :- block(A, I), branch(I), flow(I, B).

            % liveness, backwards over the flow
            live_in(I, V) :- var_use(I, V).
            live_in(I, V) :- live_out(I, V), !var_def(I, V).
            live_out(I, V) :- flow(I, J), live_in(J, V).

            % one interval per variable, once liveness has settled
            var(V) :- var_def(_, V).
            interval(V, S, E) :- var(V), S = min I : { live_out(I, V) }, E = max I : { live_in(I, V) }.

            % two registers, each starting with a placeholder that is always the first to go
            reg(0). reg(1). next_reg(0, 1). last_reg(1).
            empty(0, none0). empty(1, none1).
            span(V, S, E) :- interval(V, S, E).
            span(N, -1, 1000) :- empty(_, N).
            evict(A, B, A) :- span(A, _, EA), span(B, _, EB), EA > EB.
            evict(A, B, B) :- span(A, _, EA), span(B, _, EB), EA < EB.
            evict(A, B, A) :- span(A, SA, E), span(B, SB, E), SA > SB.
            evict(A, B, B) :- span(A, SA, E), span(B, SB, E), SA < SB.
            evict(A, B, A) :- span(A, S, E), span(B, S, E), A <= B.
            evict(A, B, B) :- span(A, S, E), span(B, S, E), A > B.

            % the scan: before(I, R, V) holds R's contents as instruction I starts
            after(-1, R, N) :- empty(R, N).
            before(I, R, V) :- insn(I), P = I - 1, after(P, R, V), span(V, _, E), E >= I.
            before(I, R, N) :- insn(I), P = I - 1, after(P, R, V), interval(V, _, E), E < I, empty(R, N).
            defines(I, D) :- var_def(I, D), interval(D, _, _).
            has_def(I) :- defines(I, _).
            % forall registers: fold the best victim along the register order
            victim_upto(I, 0, W) :- defines(I, D), before(I, 0, V), evict(D, V, W).
            victim_upto(I, R, W) :- victim_upto(I, P, B), next_reg(P, R), before(I, R, V), evict(B, V, W).
            victim(I, W) :- victim_upto(I, R, W), last_reg(R).
            after(I, R, V) :- before(I, R, V), !has_def(I).
            after(I, R, V) :- before(I, R, V), victim(I, W), V != W.
            after(I, R, D) :- before(I, R, W), victim(I, W), defines(I, D).

            % a register belongs to a variable only if it holds it for the whole interval
            held(V, R, I) :- after(I, R, V), interval(V, _, _).
            lost(V, R) :- held(V, R, _), interval(V, S, E), insn(I), I >= S, I <= E, !held(V, R, I).
            reg_of(V, R) :- held(V, R, _), !lost(V, R).
            allocated(V) :- reg_of(V, _).
            spilled(V) :- interval(V, _, _), !allocated(V).
        """.trimIndent()
    }
}
