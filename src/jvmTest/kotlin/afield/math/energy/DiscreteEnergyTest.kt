package afield.math.energy

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

class DiscreteEnergyTest {

    // Sudoku, stated by hand: 81 variables of 9 values, 27 allDifferent groups
    private fun sudokuRules(): DiscreteEnergy {
        val s = DiscreteEnergy(81, 9)
        for (k in 0 until 9) {
            s.allDifferent((0 until 9).map { k * 9 + it })
            s.allDifferent((0 until 9).map { it * 9 + k })
            s.allDifferent((0 until 9).map { (k / 3 * 3 + it / 3) * 9 + k % 3 * 3 + it % 3 })
        }
        return s
    }

    private fun solvedGrid(r: Random): IntArray {
        val g = IntArray(81) { -1 }
        fun ok(c: Int, d: Int): Boolean {
            val row = c / 9; val col = c % 9; val br = row / 3 * 3; val bc = col / 3 * 3
            for (m in 0 until 9) if (g[row * 9 + m] == d || g[m * 9 + col] == d || g[(br + m / 3) * 9 + bc + m % 3] == d) return false
            return true
        }
        fun fill(c: Int): Boolean {
            if (c == 81) return true
            for (d in (0 until 9).shuffled(r)) if (ok(c, d)) { g[c] = d; if (fill(c + 1)) return true; g[c] = -1 }
            return false
        }
        check(fill(0)); return g
    }

    private fun puzzle(r: Random, blanks: Int): IntArray {
        val g = solvedGrid(r)
        (0 until 81).shuffled(r).take(blanks).forEach { g[it] = -1 }
        return g
    }

    /** Mean-field, greedy, then annealed Gibbs (a long schedule; short ones stall on Sudoku), over up to `chains` starts; stops once the energy reaches `target`. */
    private fun search(m: DiscreteEnergy, seed: Int, target: Double, sweeps: Int = 2000, chains: Int = 2): Pair<EnergyState, Int> {
        val r = Random(seed)
        var best: EnergyState? = null
        var steps = 0
        val scale = m.constraintScale()
        for (c in 0 until chains) {
            val s = EnergyState(m).reset(if (c == 0) null else r)
            steps += MeanFieldDescent(m).settle(s)
            GreedyMoves(m).settle(s)
            if (s.energy > target) {
                GibbsAnnealing(m, sweeps, 1.0 * scale, 0.1 * scale, seed = r.nextLong()).settle(s)
                GreedyMoves(m).settle(s)
            }
            if (best == null || s.energy < best.energy) best = s
            if (best.energy <= target) break
        }
        return best!! to steps
    }

    private fun rate(m: DiscreteEnergy, rules: DiscreteEnergy, target: Double, label: String): Double {
        val test = Random(99)
        var all = 0; var ok = 0
        for (blanks in listOf(30, 40, 50, 60)) {
            var k = 0; var steps = 0
            repeat(20) { i ->
                val p = puzzle(test, blanks)
                m.releaseAll(); p.forEachIndexed { c, d -> if (d >= 0) m.fix(c, d) }
                val (st, n) = search(m, i, target)
                steps += n
                if (rules.energy(st.assignment) == 0.0) k++
            }
            all += 20; ok += k
            println("ENERGY sudoku $label blanks=$blanks solved=$k/20 meanFieldSteps=${steps / 20}")
        }
        m.releaseAll()
        return ok.toDouble() / all
    }

    @Test
    fun sudokuStatedByHandAndLearnedFromSolutions() {
        val rules = sudokuRules()
        val hand = rate(sudokuRules(), rules, 1e-6, "hand")

        val learned = DiscreteEnergy(81, 9)
        val r = Random(1)
        val grids = List(3000) { solvedGrid(r) }
        val learner = PseudolikelihoodLearner(learned, rate = 0.2f, l1 = 1e-4f, augment = { g, rr ->
            val perm = (0 until 9).shuffled(rr)
            IntArray(81) { perm[g[it]] }
        })
        val t0 = System.nanoTime()
        repeat(10) { learner.epoch(grids) }
        println("ENERGY sudoku learned train=${(System.nanoTime() - t0) / 1_000_000}ms loss=${learner.loss(grids)}")
        val target = grids.maxOf { learned.energy(it) } + 1e-3
        val lr = rate(learned, rules, target, "learned")

        // the learned same-value image against the stated one, as a correlation over cell pairs
        val xs = rules.sameValueImage().flatMap { it.toList() }
        val ys = learned.sameValueImage().flatMap { it.toList() }
        val mx = xs.average(); val my = ys.average()
        val corr = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } /
            Math.sqrt(xs.sumOf { (it - mx) * (it - mx) } * ys.sumOf { (it - my) * (it - my) })
        println("ENERGY sudoku hand=$hand learned=$lr imageCorrelation=$corr")
        assertTrue(hand > 0.5 && lr > 0.3 && corr > 0.8, "hand $hand learned $lr corr $corr")
    }

    @Test
    fun nQueens() {
        for (n in listOf(8, 12)) {
            val q = DiscreteEnergy(n, n)
            q.allDifferent((0 until n).toList())
            for (a in 0 until n) for (b in a + 1 until n) q.pairCost(a, b) { x, y -> if (abs(x - y) == b - a) 1.0 else 0.0 }
            var solved = 0
            repeat(10) { seed ->
                val s = EnergyState(q).reset(Random(seed))
                MeanFieldDescent(q).settle(s)
                GreedyMoves(q).settle(s)
                GibbsAnnealing(q, 300, 1.0, 0.05, seed.toLong()).settle(s)
                if (s.energy == 0.0) solved++
            }
            println("ENERGY queens n=$n solved=$solved/10")
            assertTrue(solved > 0)
        }
    }

    @Test
    fun graphColouring() {
        val r = Random(5)
        val pts = List(40) { r.nextDouble() to r.nextDouble() }
        val edges = HashSet<Pair<Int, Int>>()
        for (i in pts.indices) pts.indices.filter { it != i }
            .sortedBy { hypot(pts[it].first - pts[i].first, pts[it].second - pts[i].second) }.take(3)
            .forEach { edges += minOf(i, it) to maxOf(i, it) }
        for (k in listOf(3, 4)) {
            val g = DiscreteEnergy(pts.size, k)
            for ((a, b) in edges) g.notEqual(a, b)
            val s = EnergyState(g).reset(Random(1))
            val steps = MeanFieldDescent(g).settle(s)
            val afterMeanField = s.energy
            GreedyMoves(g).settle(s)
            GibbsAnnealing(g, 200, 1.0, 0.05, 2).settle(s)
            println("ENERGY colouring k=$k edges=${edges.size} meanFieldSteps=$steps conflictsAfterMeanField=$afterMeanField conflicts=${s.energy}")
        }
    }

    @Test
    fun travellingSalesman() {
        val r = Random(3)
        val n = 10
        val c = List(n) { r.nextDouble() to r.nextDouble() }
        fun d(i: Int, j: Int) = hypot(c[i].first - c[j].first, c[i].second - c[j].second)
        val t = DiscreteEnergy(n, n)
        val stops = (0 until n).toList()
        t.allDifferent(stops, weight = 2.0)
        for (k in 0 until n) t.pairCost(k, (k + 1) % n) { x, y -> d(x, y) }
        t.fix(0, 0)
        fun length(tour: IntArray) = (0 until n).sumOf { d(tour[it], tour[(it + 1) % n]) }
        fun crossings(tour: IntArray): Int {
            fun ccw(a: Pair<Double, Double>, b: Pair<Double, Double>, q: Pair<Double, Double>) =
                (b.first - a.first) * (q.second - a.second) - (b.second - a.second) * (q.first - a.first)
            var k = 0
            for (i in 0 until n) for (j in i + 2 until n) {
                if (i == 0 && j == n - 1) continue
                val p1 = c[tour[i]]; val p2 = c[tour[(i + 1) % n]]; val p3 = c[tour[j]]; val p4 = c[tour[(j + 1) % n]]
                if (ccw(p1, p2, p3) * ccw(p1, p2, p4) < 0 && ccw(p3, p4, p1) * ccw(p3, p4, p2) < 0) k++
            }
            return k
        }
        var opt = Double.MAX_VALUE
        fun perm(prefix: IntArray, left: List<Int>) {
            if (left.isEmpty()) { opt = minOf(opt, length(prefix)); return }
            for (x in left) perm(prefix + x, left - x)
        }
        perm(intArrayOf(0), (1 until n).toList())

        // single-variable moves: Gibbs, then greedy value changes
        val single = (0 until 10).map { seed ->
            val s = EnergyState(t).reset(Random(seed))
            GibbsAnnealing(t, 400, 1.0, 0.01, seed.toLong()).settle(s)
            GreedyMoves(t).settle(s)
            s.assignment.copyOf()
        }.filter { it.toSet().size == n }
        // permutation moves: start from any permutation, anneal with swaps and 2-opt reversals, then greedy
        val perms = listOf(SwapValues(stops), ReverseSegment(stops))
        val permuted = (0 until 10).map { seed ->
            val s = EnergyState(t)
            for (k in 0 until n) s.assignment[k] = k
            MetropolisAnnealing(t, perms, sweeps = 300, startTemperature = 0.5, endTemperature = 0.005, seed = seed.toLong()).settle(s)
            GreedyMoves(t, perms).settle(s)
            s.assignment.copyOf()
        }
        assertTrue(permuted.all { it.toSet().size == n }, "permutation moves keep a permutation")
        val sb = single.minBy { length(it) }; val pb = permuted.minBy { length(it) }
        println("ENERGY tsp n=$n optimum=$opt")
        println("ENERGY tsp single-value moves: valid=${single.size}/10 best=${length(sb)} (+${((length(sb) / opt - 1) * 100).toInt()}%) crossings=${crossings(sb)}")
        println("ENERGY tsp swap+2-opt moves: best=${length(pb)} (+${((length(pb) / opt - 1) * 100).toInt()}%) crossings=${crossings(pb)} " +
            "atOptimum=${permuted.count { length(it) < opt + 1e-9 }}/10 mean=${permuted.map { length(it) }.average()}")
        assertTrue(length(pb) <= length(sb) + 1e-9 && crossings(pb) == 0)
    }

    @Test
    fun gpuStateRoundTripsTheBeliefs() {
        val m = DiscreteEnergy(intArrayOf(2, 3, 4))
        m.notEqual(0, 1).fix(2, 3)
        val s = EnergyState(m).reset(Random(1))
        MeanFieldDescent(m).step(s)
        val buf = m.gpuState(s)
        assertTrue(buf.size == m.gpuStateFloats)
        val back = EnergyState(m)
        m.readGpuBeliefs(buf, 0, back)
        assertTrue(back.p.contentEquals(s.p) && back.assignment.contentEquals(s.assignment))
        // one-hot beliefs from a discrete assignment
        back.assignment[0] = 1
        back.beliefsFromAssignment()
        assertTrue(back.p[m.unit(0, 1)] == 1f && back.p[m.unit(0, 0)] == 0f)
    }
}
