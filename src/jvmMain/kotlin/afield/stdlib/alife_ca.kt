@file:afield.CompletionCategory("ALife")

package afield.stdlib

import kotlin.math.abs
import kotlin.math.max

/**
 * Discrete cellular automata: life-like and outer-totalistic rules on a 2-D lattice, cyclic
 * multi-state CA, Wolfram's elementary 1-D rules as a space-time raster, and a 3-D life-like CA that
 * marches out to a [TriMesh].
 *
 * See `alife.kt` for the family overview and for [IntGrid], the lattice everything here steps.
 *
 * The design commitment worth stating: a *rule* is a value ([LifeRule]), separate from the grid it is
 * applied to. That is what lets the gallery run the same glider under `B3/S23` and under a
 * deliberately-wrong neighbour rule and show that only one of them translates — and what lets
 * `alife_continuous.kt`'s Lenia stepper be checked against this file's discrete stepper in the limit
 * where the two must agree.
 */

// ==============================================================================================
// Life-like / outer-totalistic rules
// ==============================================================================================

/**
 * An outer-totalistic two-state rule: a cell's next state depends only on its own state and on **how
 * many** of its neighbours are alive, not on which.
 *
 * `B3/S23` is Conway's Life — born on exactly 3 live neighbours, survives on 2 or 3.
 *
 * @property birth neighbour counts at which a dead cell becomes alive
 * @property survive neighbour counts at which a live cell stays alive
 * @property radius Chebyshev (or, with [vonNeumann], Manhattan) neighbourhood radius; 1 is the
 *   classic 8-cell Moore neighbourhood, larger radii give the "Larger than Life" family
 * @property vonNeumann `true` counts only the diamond `|dx| + |dy| <= radius` instead of the square
 * @property notation the `B…/S…` string this rule was parsed from, or a reconstruction of it
 */
class LifeRule(
    val birth: Set<Int>,
    val survive: Set<Int>,
    val radius: Int = 1,
    val vonNeumann: Boolean = false,
    val notation: String = "B${birth.sorted().joinToString("")}/S${survive.sorted().joinToString("")}"
) {
    init {
        require(radius >= 1) { "radius must be >= 1, got $radius" }
        val maxN = neighborhoodSize
        require(birth.all { it in 0..maxN }) { "birth counts must be within 0..$maxN, got $birth" }
        require(survive.all { it in 0..maxN }) { "survive counts must be within 0..$maxN, got $survive" }
    }

    /** How many cells the neighbourhood contains, excluding the centre. */
    val neighborhoodSize: Int
        get() = if (vonNeumann) 2 * radius * (radius + 1) else (2 * radius + 1) * (2 * radius + 1) - 1

    /** The next state of a cell currently in [state] with [neighbors] live neighbours. */
    fun next(state: Int, neighbors: Int): Int = when {
        state != 0 -> if (neighbors in survive) 1 else 0
        else -> if (neighbors in birth) 1 else 0
    }

    override fun toString(): String =
        "LifeRule($notation${if (radius != 1) ", r=$radius" else ""}${if (vonNeumann) ", von Neumann" else ""})"
}

/**
 * Parse a life-like rule from the standard `B3/S23` notation (or the equivalent `23/3` "survive/birth"
 * form, or `S23/B3`), returning a [LifeRule].
 *
 * Both orderings exist in the wild and mean opposite things, so the leading letter decides: with `B`
 * or `S` present the labels are honoured, and a bare `a/b` is read as the older `survive/birth`
 * convention that Golly and Wikipedia use for two-state rules.
 *
 * ```
 * lifeRule("B3/S23")     // Conway's Life
 * lifeRule("23/3")       // the same rule, older notation
 * lifeRule("B36/S23")    // HighLife, which has a replicator
 * ```
 *
 * @throws IllegalArgumentException on anything that is not two slash-separated digit runs
 */
fun lifeRule(notation: String, radius: Int = 1, vonNeumann: Boolean = false): LifeRule {
    val parts = notation.trim().split('/')
    require(parts.size == 2) { "rule notation must be two slash-separated parts, got '$notation'" }
    fun digits(s: String): Set<Int> {
        val d = s.filter { it.isDigit() }
        require(d.length == s.count { it.isDigit() }) { "unreadable rule part '$s' in '$notation'" }
        return d.map { it - '0' }.toSet()
    }
    val a = parts[0].trim()
    val b = parts[1].trim()
    val aIsBirth = a.startsWith("B", ignoreCase = true)
    val bIsBirth = b.startsWith("B", ignoreCase = true)
    require(!(aIsBirth && bIsBirth)) { "both parts of '$notation' claim to be birth counts" }
    val (birthPart, survivePart) = when {
        aIsBirth -> a to b
        bIsBirth -> b to a
        // Neither is labelled: the older `survive/birth` convention.
        else -> b to a
    }
    return LifeRule(digits(birthPart), digits(survivePart), radius, vonNeumann, notation.trim())
}

/** Conway's Life, `B3/S23` — the rule every claim in this family's discrete half is checked against. */
val LIFE: LifeRule = lifeRule("B3/S23")

/** HighLife, `B36/S23`: Life plus birth on 6, which gives it a small self-replicating pattern. */
val HIGH_LIFE: LifeRule = lifeRule("B36/S23")

/** Seeds, `B2/S`: nothing survives, so every generation is entirely new. Explosive and never static. */
val SEEDS: LifeRule = lifeRule("B2/S")

/** Day & Night, `B3678/S34678`: self-complementary, so patterns and their photographic negatives behave alike. */
val DAY_AND_NIGHT: LifeRule = lifeRule("B3678/S34678")

/** Diamoeba, `B35678/S5678`: large amoeba-like blobs with ragged, constantly-renegotiated borders. */
val DIAMOEBA: LifeRule = lifeRule("B35678/S5678")

/** "Maze", `B3/S12345`: Life-like birth with very tolerant survival, which grows corridor mazes. */
val MAZE: LifeRule = lifeRule("B3/S12345")

/**
 * One synchronous generation of [rule] over this grid, returned as a new [IntGrid]. The border is
 * resolved by the grid's own [IntGrid.wrap] flag: a torus, or a bounded board whose outside is dead.
 *
 * Synchronous is the point — every cell reads the *old* state. An in-place update is a different (and
 * much less interesting) automaton, and is the classic way a first CA implementation ends up with a
 * glider that smears instead of moving.
 *
 * A `B/S` rule is a **two-state** rule, and "alive" here means state exactly `1`, matching
 * [IntGrid.mooreCount]'s and [IntGrid.neighborhoodCount]'s `state` convention. Handing this a grid that
 * carries other states (a cyclic-CA soup, say) is legal and treats every non-`1` cell as dead. The
 * interior fast path below originally tested `!= 0` while the border rows went through [mooreCount],
 * which tests `== 1` — so on such a grid one call produced two different automata, an interior one and a
 * border one. Both now test `== 1`. `AlifeGallery`'s differential check runs the fast path against an
 * independent brute-force stepper on 4-state soups at every size from 1 to 8, in both border modes,
 * which is what would have caught it.
 */
fun IntGrid.step(rule: LifeRule): IntGrid {
    val out = blank()
    if (rule.radius == 1 && !rule.vonNeumann && width >= 3 && height >= 3) {
        // Interior fast path: read the eight neighbours straight out of the array with no border
        // resolution and no bounds checks. This is not premature — verifying the R-pentomino's
        // 1103-generation history needs a board with room, and 620x620x1103 is 4e8 cell updates, where
        // the difference between eight `get` calls and eight array reads is the difference between a
        // figure that renders and one that times out. The border rows/columns still go through [get],
        // so wrapped and bounded grids behave exactly as before.
        val w = width
        for (y in 1 until height - 1) {
            var i = y * w + 1
            var up = i - w
            var dn = i + w
            for (x in 1 until w - 1) {
                // `== 1`, not `!= 0`: the border rows below count through mooreCount, which tests
                // `== 1`, and a fast path that disagrees with its own borders is worse than either
                // convention. See this function's KDoc.
                var n = 0
                if (cells[up - 1] == 1) n++
                if (cells[up] == 1) n++
                if (cells[up + 1] == 1) n++
                if (cells[i - 1] == 1) n++
                if (cells[i + 1] == 1) n++
                if (cells[dn - 1] == 1) n++
                if (cells[dn] == 1) n++
                if (cells[dn + 1] == 1) n++
                out.cells[i] = rule.next(cells[i], n)
                i++; up++; dn++
            }
        }
        for (x in 0 until w) {
            out.cells[x] = rule.next(cells[x], mooreCount(x, 0, 1))
            val yl = height - 1
            out.cells[yl * w + x] = rule.next(cells[yl * w + x], mooreCount(x, yl, 1))
        }
        for (y in 1 until height - 1) {
            out.cells[y * w] = rule.next(cells[y * w], mooreCount(0, y, 1))
            out.cells[y * w + w - 1] = rule.next(cells[y * w + w - 1], mooreCount(w - 1, y, 1))
        }
        return out
    }
    for (y in 0 until height) for (x in 0 until width) {
        val n = neighborhoodCount(x, y, 1, rule.radius, rule.vonNeumann)
        out.cells[y * width + x] = rule.next(cells[y * width + x], n)
    }
    return out
}

/** [step] applied [generations] times, returning only the final grid. */
fun IntGrid.stepTimes(rule: LifeRule, generations: Int): IntGrid {
    require(generations >= 0) { "generations must be >= 0, got $generations" }
    var g = this
    repeat(generations) { g = g.step(rule) }
    return g
}

/**
 * Every generation from this one to `generations`, inclusive of both ends — `generations + 1` grids.
 * Keeping the history is what lets a caller detect a cycle, or render a filmstrip, without stepping
 * twice.
 */
fun IntGrid.history(rule: LifeRule, generations: Int): List<IntGrid> {
    require(generations >= 0) { "generations must be >= 0, got $generations" }
    val out = ArrayList<IntGrid>(generations + 1)
    var g = this
    out.add(g)
    repeat(generations) { g = g.step(rule); out.add(g) }
    return out
}

/**
 * Result of running an automaton until it repeats.
 *
 * @property period the cycle length, or 0 if none was found within the step budget
 * @property firstSeenAt the generation the repeated state was first reached (so `period == 1` and
 *   `firstSeenAt == 0` means the seed was already a still life)
 * @property generations how many generations were actually stepped
 * @property finalState the grid at [generations]
 * @property population the population at each generation, `0..generations`
 */
class CycleReport(
    val period: Int,
    val firstSeenAt: Int,
    val generations: Int,
    val finalState: IntGrid,
    val population: IntArray
) {
    /** `true` when a repeat was found within the budget. */
    val settled: Boolean get() = period > 0

    override fun toString(): String = if (settled) {
        "CycleReport(period $period from generation $firstSeenAt, population ${population.last()})"
    } else {
        "CycleReport(no cycle within $generations generations, population ${population.last()})"
    }
}

/**
 * Step until the grid repeats a state it has already been in, or until [maxGenerations].
 *
 * This is how "the R-pentomino stabilises" becomes a checkable statement: the claim is not "it looked
 * settled" but "the state at generation *g* is exactly the state at generation *g − p*", with the
 * population at that point a specific number. Exact grids are hashed, so detection is exact rather
 * than a population heuristic — a pattern whose population is constant while it rotates is *not*
 * reported as period 1.
 *
 * Memory is `O(maxGenerations)` hashes, not grids, plus the grids for the states that share a hash.
 */
fun IntGrid.runUntilCycle(rule: LifeRule, maxGenerations: Int = 2000): CycleReport {
    require(maxGenerations >= 1) { "maxGenerations must be >= 1, got $maxGenerations" }
    val seen = HashMap<Int, MutableList<Pair<Int, IntGrid>>>()
    var g = this
    val pops = ArrayList<Int>(maxGenerations + 1)
    pops.add(g.population())
    seen.getOrPut(g.cells.contentHashCode()) { ArrayList() }.add(0 to g)
    for (gen in 1..maxGenerations) {
        g = g.step(rule)
        pops.add(g.population())
        val h = g.cells.contentHashCode()
        val bucket = seen.getOrPut(h) { ArrayList() }
        val hit = bucket.firstOrNull { it.second.sameCellsAs(g) }
        if (hit != null) {
            return CycleReport(gen - hit.first, hit.first, gen, g, pops.toIntArray())
        }
        bucket.add(gen to g)
    }
    return CycleReport(0, -1, maxGenerations, g, pops.toIntArray())
}

/**
 * Named Life patterns, written as ASCII so they can be read rather than decoded.
 *
 * These exist so the family's claims can be about *known objects*: a glider must translate by exactly
 * `(1, 1)` every 4 generations, a block must be bit-identical after one step, a blinker must have
 * period 2, and the R-pentomino must settle to a specific population after a specific number of
 * generations. Those are facts about Conway's Life that predate this code, which is what makes them
 * evidence rather than self-consistency.
 */
object LifePatterns {

    /** The glider: the smallest spaceship, translating `(1, 1)` every 4 generations. */
    val glider: IntGrid get() = IntGrid.fromAscii(".O.", "..O", "OOO")

    /** The block: the smallest still life; a step must leave it bit-identical. */
    val block: IntGrid get() = IntGrid.fromAscii("OO", "OO")

    /** The beehive, a six-cell still life — a second still life, so the block is not a lucky special case. */
    val beehive: IntGrid get() = IntGrid.fromAscii(".OO.", "O..O", ".OO.")

    /** The blinker: period 2, so it is the control that a "still life" check must fail on. */
    val blinker: IntGrid get() = IntGrid.fromAscii("OOO")

    /** The toad: period 2, and unlike the blinker it is not a subset of a line. */
    val toad: IntGrid get() = IntGrid.fromAscii(".OOO", "OOO.")

    /** The R-pentomino: the classic long-lived methuselah — 1103 generations to a population of 116. */
    val rPentomino: IntGrid get() = IntGrid.fromAscii(".OO", "OO.", ".O.")

    /** The acorn: a 7-cell methuselah, 5206 generations to a population of 633. */
    val acorn: IntGrid get() = IntGrid.fromAscii(".O.....", "...O...", "OO..OOO")

    /** The lightweight spaceship: translates `(2, 0)` every 4 generations — orthogonal, not diagonal. */
    val lightweightSpaceship: IntGrid get() = IntGrid.fromAscii(".OOOO", "O...O", "....O", "O..O.")
}

/**
 * Place [pattern] with its top-left corner at `(x, y)` on a fresh `width x height` grid — the standard
 * way to give a small named pattern room to run.
 *
 * @param wrap whether the resulting board is a torus; a methuselah needs `false` and space, a
 *   spaceship demonstration is usually happier on a torus
 */
fun IntGrid.placedOn(width: Int, height: Int, x: Int, y: Int, wrap: Boolean = true): IntGrid {
    val out = IntGrid.zeros(width, height, wrap)
    for (py in 0 until this.height) for (px in 0 until this.width) {
        val v = cells[py * this.width + px]
        if (v == 0) continue
        val tx = x + px
        val ty = y + py
        if (tx in 0 until width && ty in 0 until height) out.cells[ty * width + tx] = v
    }
    return out
}

/** [placedOn] with the pattern centred. */
fun IntGrid.centeredOn(width: Int, height: Int, wrap: Boolean = true): IntGrid =
    placedOn(width, height, (width - this.width) / 2, (height - this.height) / 2, wrap)

// ==============================================================================================
// Cyclic cellular automata
// ==============================================================================================

/**
 * A cyclic cellular automaton rule: a cell in state `s` advances to `(s + 1) mod states` as soon as at
 * least [threshold] of its neighbours are *already* in state `(s + 1) mod states`; otherwise it waits.
 *
 * With states arranged in a cycle and no way to skip, the only stable structures are **rotating
 * spirals** — which is why this rule is famous: a uniformly random soup reliably self-organises into a
 * field of spiral cores, and once it has, every cell advances every step forever. That "every cell
 * advances every step" is a measurable statement, and [cyclicAdvanceFraction] is how this family makes
 * it (a random soup sits far below 1; a spiral field sits at 1).
 *
 * @property states cycle length, >= 2
 * @property threshold how many neighbours in the successor state are needed to advance
 * @property radius neighbourhood radius
 * @property vonNeumann use the diamond neighbourhood rather than the square
 */
class CyclicRule(
    val states: Int,
    val threshold: Int = 1,
    val radius: Int = 1,
    val vonNeumann: Boolean = false
) {
    init {
        require(states >= 2) { "states must be >= 2, got $states" }
        require(threshold >= 1) { "threshold must be >= 1, got $threshold" }
        require(radius >= 1) { "radius must be >= 1, got $radius" }
    }

    override fun toString(): String =
        "CyclicRule($states states, threshold $threshold, radius $radius${if (vonNeumann) ", von Neumann" else ""})"
}

/**
 * 14 states, threshold 1, Moore radius 1 — Griffeath's original cyclic CA parameters.
 *
 * Named for the phase this codebase **measures** it to produce from a random soup rather than for the
 * one it is often illustrated with: a locally-coherent labyrinth of interlocking colour bands, in which
 * every cell advances every step. Spiral *cores* are a feature of the larger-neighbourhood,
 * higher-threshold corner of the parameter space ([CYCLIC_EXCITABLE]), and this file does not claim them
 * here because the gallery's own figure does not show them.
 */
val CYCLIC_LABYRINTH: CyclicRule = CyclicRule(states = 14, threshold = 1)

/** Greenberg–Hastings-like excitable medium: 6 states, threshold 3, radius 2 — fatter, slower fronts. */
val CYCLIC_EXCITABLE: CyclicRule = CyclicRule(states = 6, threshold = 3, radius = 2)

/** One synchronous generation of a [CyclicRule]. */
fun IntGrid.step(rule: CyclicRule): IntGrid {
    val out = blank()
    for (y in 0 until height) for (x in 0 until width) {
        val s = Math.floorMod(cells[y * width + x], rule.states)
        val nextState = (s + 1) % rule.states
        var n = 0
        for (dy in -rule.radius..rule.radius) for (dx in -rule.radius..rule.radius) {
            if (dx == 0 && dy == 0) continue
            if (rule.vonNeumann && abs(dx) + abs(dy) > rule.radius) continue
            if (Math.floorMod(this[x + dx, y + dy], rule.states) == nextState) {
                n++
                if (n >= rule.threshold) break
            }
        }
        out.cells[y * width + x] = if (n >= rule.threshold) nextState else s
    }
    return out
}

/** [step] applied [generations] times under a [CyclicRule]. */
fun IntGrid.stepTimes(rule: CyclicRule, generations: Int): IntGrid {
    require(generations >= 0) { "generations must be >= 0, got $generations" }
    var g = this
    repeat(generations) { g = g.step(rule) }
    return g
}

/**
 * The fraction of cells that changed state between [before] and [after] — for a [CyclicRule], the
 * fraction that *advanced*, since the only legal change is `s -> s + 1`.
 *
 * The discriminating statistic for cyclic CA. In a fresh random soup most cells are blocked (no
 * neighbour happens to be in their successor state), so this sits well below 1; once spirals have
 * organised, every cell is being driven by its neighbour and it pins at 1.0. There is no way for a
 * broken implementation to be at 1.0 by accident, and the un-stepped soup is the control arm.
 */
fun cyclicAdvanceFraction(before: IntGrid, after: IntGrid): Double {
    require(before.width == after.width && before.height == after.height) {
        "cyclicAdvanceFraction needs matching shapes"
    }
    var n = 0
    for (i in before.cells.indices) if (before.cells[i] != after.cells[i]) n++
    return n.toDouble() / before.size
}

// ==============================================================================================
// Wolfram elementary 1-D CA
// ==============================================================================================

/**
 * One generation of Wolfram's elementary rule [rule] (0..255) applied to a 1-D row of 0/1 cells.
 *
 * The rule number's bit `4*left + 2*centre + 1*right` gives the cell's next state — the whole
 * definition, and the reason there are exactly 256 of them.
 *
 * @param wrap `true` treats the row as a ring; `false` treats off-row cells as 0
 */
fun elementaryStep(row: IntArray, rule: Int, wrap: Boolean = true): IntArray {
    require(rule in 0..255) { "elementary rule must be 0..255, got $rule" }
    val n = row.size
    val out = IntArray(n)
    for (i in 0 until n) {
        val l = if (i > 0) row[i - 1] else if (wrap) row[n - 1] else 0
        val c = row[i]
        val r = if (i < n - 1) row[i + 1] else if (wrap) row[0] else 0
        out[i] = (rule shr (4 * l + 2 * c + r)) and 1
    }
    return out
}

/**
 * Run elementary rule [rule] for [generations] steps and return the whole **space-time diagram** as an
 * [IntGrid]: one row per generation, time increasing downward. `grid.toImg()` is then the classic
 * picture, and `grid.toImg().upscaleNearest(k)` is the classic picture at a size you can see.
 *
 * A space-time raster is the natural output rather than a nicety: a 1-D automaton's *behaviour* is
 * two-dimensional, and every famous statement about these rules (Sierpiński from rule 90, rule 30's
 * randomness, rule 110's universality) is a statement about this image.
 *
 * @param initial the first row; `null` seeds a single 1 in the middle, which is the initial condition
 *   the classic pictures use
 */
fun elementaryCa(
    rule: Int,
    width: Int = 201,
    generations: Int = 100,
    initial: IntArray? = null,
    wrap: Boolean = true
): IntGrid {
    require(rule in 0..255) { "elementary rule must be 0..255, got $rule" }
    require(width >= 1) { "width must be >= 1, got $width" }
    require(generations >= 1) { "generations must be >= 1, got $generations" }
    var row = initial?.copyOf() ?: IntArray(width).also { it[width / 2] = 1 }
    require(row.size == width) { "initial row must be $width wide, got ${row.size}" }
    val out = IntArray(width * generations)
    for (g in 0 until generations) {
        System.arraycopy(row, 0, out, g * width, width)
        row = elementaryStep(row, rule, wrap)
    }
    return IntGrid(width, generations, out, wrap = false)
}

/**
 * `C(n, k) mod 2` — the parity of a binomial coefficient, by Kummer's theorem: it is 1 exactly when
 * `k`'s binary digits are a subset of `n`'s, i.e. `n and k == k`.
 *
 * Here so the family can assert something *independent* about a CA output. Rule 90 from a single seed
 * is Pascal's triangle mod 2 (the Sierpiński gasket), and this function computes the right answer
 * without running a CA at all — so the equality between them is a real cross-check of the stepper, not
 * the stepper agreeing with itself.
 */
fun binomialParity(n: Int, k: Int): Int {
    require(n >= 0) { "n must be >= 0, got $n" }
    if (k < 0 || k > n) return 0
    return if ((n and k) == k) 1 else 0
}

/**
 * The exact space-time diagram of rule 90 from a single central seed, computed from [binomialParity]
 * rather than by stepping — the reference the CA must reproduce cell for cell.
 *
 * Rule 90 is `left XOR right`, so from a single 1 the cell at offset `d` in generation `g` is
 * `C(g, (g + d) / 2) mod 2` when `g + d` is even, and 0 otherwise (the sublattice the seed cannot
 * reach). The result is the Sierpiński gasket. [width] must be odd and wide enough that the light cone
 * has not reached the border, or the two differ legitimately at the edges.
 */
fun sierpinskiReference(width: Int, generations: Int): IntGrid {
    require(width % 2 == 1) { "width must be odd so there is a centre column, got $width" }
    require(generations >= 1) { "generations must be >= 1, got $generations" }
    require(2 * generations < width) {
        "the light cone reaches the border: need 2*generations < width, got $generations and $width"
    }
    val c = width / 2
    val out = IntArray(width * generations)
    for (g in 0 until generations) for (x in 0 until width) {
        val d = x - c
        if (((g + d) and 1) != 0) continue
        out[g * width + x] = binomialParity(g, (g + d) / 2)
    }
    return IntGrid(width, generations, out, wrap = false)
}

/**
 * The centre column of an elementary rule's space-time diagram, as a bit sequence.
 *
 * Rule 30's centre column is the classic example of an elementary rule producing a sequence with no
 * detectable structure (Mathematica shipped it as a random generator), so extracting it is how a
 * "rule 30 is complex" claim becomes a measurement — see [longestRunLength] and
 * [IntGrid.stateEntropyBits].
 */
fun IntGrid.centerColumn(): IntArray = IntArray(height) { cells[it * width + width / 2] }

/** The longest run of identical values in [bits] — a crude but honest structure detector for a bit sequence. */
fun longestRunLength(bits: IntArray): Int {
    if (bits.isEmpty()) return 0
    var best = 1
    var run = 1
    for (i in 1 until bits.size) {
        run = if (bits[i] == bits[i - 1]) run + 1 else 1
        if (run > best) best = run
    }
    return best
}

// ==============================================================================================
// 3-D life-like CA, and the bridge to a mesh
// ==============================================================================================

/**
 * A cubic lattice of integer cell states — the 3-D counterpart of [IntGrid], stored
 * `cells[(z * height + y) * width + x]`.
 *
 * @property width/[height]/[depth] extents, each >= 1
 * @property cells state per voxel
 * @property wrap `true` for a 3-torus; otherwise outside counts as state 0
 */
class IntVolume(
    val width: Int,
    val height: Int,
    val depth: Int,
    val cells: IntArray,
    val wrap: Boolean = false
) {
    init {
        require(width >= 1 && height >= 1 && depth >= 1) {
            "IntVolume must be at least 1x1x1, got ${width}x${height}x$depth"
        }
        require(cells.size == width * height * depth) {
            "IntVolume cells must be ${width * height * depth}, got ${cells.size}"
        }
    }

    /** Number of voxels. */
    val size: Int get() = width * height * depth

    /** Flat index of `(x, y, z)`. No bounds checking; see [get]. */
    fun index(x: Int, y: Int, z: Int): Int = (z * height + y) * width + x

    /** State at `(x, y, z)`, resolving out-of-range coordinates through [wrap] (otherwise 0). */
    operator fun get(x: Int, y: Int, z: Int): Int {
        if (x in 0 until width && y in 0 until height && z in 0 until depth) return cells[index(x, y, z)]
        if (!wrap) return 0
        return cells[index(Math.floorMod(x, width), Math.floorMod(y, height), Math.floorMod(z, depth))]
    }

    /** Write [v] at `(x, y, z)` if it is inside; out-of-range writes on a bounded volume are ignored. */
    operator fun set(x: Int, y: Int, z: Int, v: Int) {
        if (x in 0 until width && y in 0 until height && z in 0 until depth) cells[index(x, y, z)] = v
    }

    /** A same-shape volume of zeros. */
    fun blank(): IntVolume = IntVolume(width, height, depth, IntArray(size), wrap)

    /** How many voxels are non-zero. */
    fun population(): Int = cells.count { it != 0 }

    /** How many of the 26 surrounding voxels are non-zero. */
    fun mooreCount(x: Int, y: Int, z: Int): Int {
        var n = 0
        for (dz in -1..1) for (dy in -1..1) for (dx in -1..1) {
            if (dx == 0 && dy == 0 && dz == 0) continue
            if (this[x + dx, y + dy, z + dz] != 0) n++
        }
        return n
    }

    override fun toString(): String =
        "IntVolume(${width}x${height}x$depth, population ${population()})"

    companion object {
        /** All-zero volume. */
        fun zeros(width: Int, height: Int, depth: Int, wrap: Boolean = false): IntVolume =
            IntVolume(width, height, depth, IntArray(width * height * depth), wrap)

        /**
         * A random two-state volume, alive with probability [density], but only inside a centred ball
         * of radius `min(extent)/2 * ballFraction`.
         *
         * Seeding a *ball* rather than the whole box is not decoration: a 3-D life-like rule seeded
         * edge-to-edge either fills the box (and then there is no surface to march) or is dominated by
         * boundary effects. A blob in the middle leaves room for the rule's own structure to show.
         */
        fun randomBall(
            width: Int,
            height: Int,
            depth: Int,
            density: Double = 0.35,
            ballFraction: Double = 0.55,
            seed: Long = 1L,
            wrap: Boolean = false
        ): IntVolume {
            val rng = kotlin.random.Random(seed)
            val v = zeros(width, height, depth, wrap)
            val cx = (width - 1) / 2.0
            val cy = (height - 1) / 2.0
            val cz = (depth - 1) / 2.0
            val r = minOf(width, height, depth) / 2.0 * ballFraction
            for (z in 0 until depth) for (y in 0 until height) for (x in 0 until width) {
                val dx = x - cx; val dy = y - cy; val dz = z - cz
                if (dx * dx + dy * dy + dz * dz > r * r) continue
                if (rng.nextDouble() < density) v.cells[v.index(x, y, z)] = 1
            }
            return v
        }
    }
}

/**
 * A 3-D outer-totalistic rule over the 26-voxel Moore neighbourhood — the direct generalisation of
 * [LifeRule]. Notation is the same `B…/S…`, with counts up to 26, so `B5/S45` is written
 * `LifeRule3D(setOf(5), setOf(4, 5))`.
 *
 * @property birth neighbour counts at which an empty voxel fills
 * @property survive neighbour counts at which a filled voxel stays filled
 */
class LifeRule3D(val birth: Set<Int>, val survive: Set<Int>) {
    init {
        require(birth.all { it in 0..26 }) { "3-D birth counts must be within 0..26, got $birth" }
        require(survive.all { it in 0..26 }) { "3-D survive counts must be within 0..26, got $survive" }
    }

    /** The `B…/S…` string for this rule. */
    val notation: String get() = "B${birth.sorted().joinToString(",")}/S${survive.sorted().joinToString(",")}"

    override fun toString(): String = "LifeRule3D($notation)"
}

/**
 * Parse a 3-D rule from `B…/S…` where the counts are **comma-separated** (they run to 26, so the
 * digit-per-count convention of 2-D notation cannot work).
 *
 * ```
 * lifeRule3D("B4,5/S5,6,7")
 * ```
 */
fun lifeRule3D(notation: String): LifeRule3D {
    val parts = notation.trim().split('/')
    require(parts.size == 2) { "3-D rule notation must be two slash-separated parts, got '$notation'" }
    fun counts(s: String): Set<Int> = s.trim().drop(1).split(',')
        .map { it.trim() }.filter { it.isNotEmpty() }.map {
            it.toIntOrNull() ?: throw IllegalArgumentException("unreadable count '$it' in '$notation'")
        }.toSet()
    val a = parts[0].trim()
    val b = parts[1].trim()
    val birthFirst = a.startsWith("B", ignoreCase = true)
    return if (birthFirst) LifeRule3D(counts(a), counts(b)) else LifeRule3D(counts(b), counts(a))
}

/** "Clouds 1", `B13,14,17,18,19/S13,…,26` — a 3-D rule that grows soft, rounded, cloud-like solids. */
val LIFE3D_CLOUDS: LifeRule3D = LifeRule3D(
    birth = setOf(13, 14, 17, 18, 19),
    survive = (13..26).toSet()
)

/** `B4/S4` — a sparse 3-D rule whose survivors form thin, crystalline, coral-like shells. */
val LIFE3D_CRYSTAL: LifeRule3D = LifeRule3D(birth = setOf(4), survive = setOf(4))

/** `B5/S4,5` — the 3-D rule that gives compact blobs with a corrugated surface. */
val LIFE3D_BLOB: LifeRule3D = LifeRule3D(birth = setOf(5), survive = setOf(4, 5))

/** One synchronous generation of a [LifeRule3D]. */
fun IntVolume.step(rule: LifeRule3D): IntVolume {
    val out = blank()
    for (z in 0 until depth) for (y in 0 until height) for (x in 0 until width) {
        val n = mooreCount(x, y, z)
        val i = index(x, y, z)
        out.cells[i] = if (cells[i] != 0) (if (n in rule.survive) 1 else 0) else (if (n in rule.birth) 1 else 0)
    }
    return out
}

/** [step] applied [generations] times under a [LifeRule3D]. */
fun IntVolume.stepTimes(rule: LifeRule3D, generations: Int): IntVolume {
    require(generations >= 0) { "generations must be >= 0, got $generations" }
    var v = this
    repeat(generations) { v = v.step(rule) }
    return v
}

/**
 * Adapt this volume to a [ScalarField3D] so `mesh_sdf.kt`'s [marchingCubes] can extract its surface —
 * the bridge from "a 3-D automaton ran" to "here is a mesh you can export, boolean and render".
 *
 * The occupancy is smoothed by a `(2*smoothRadius+1)³` box blur before sampling, and the field is
 * returned as `occupancy - iso`, i.e. **positive inside**. So march it with `positiveOutside = false`
 * and `iso = 0.0`:
 *
 * ```
 * val mesh = marchingCubes(volume.asDensityField(), volume.marchBounds(), resolution = 96,
 *                          positiveOutside = false)
 * ```
 *
 * Smoothing is not cosmetic. Marching a raw 0/1 occupancy grid gives a surface made only of axis-aligned
 * facets at exactly the voxel scale, so every automaton looks like the same box of cubes; a small blur
 * lets the *shape* the rule made be the thing you see. It also removes isolated single voxels, which
 * otherwise contribute a tiny closed shell each and dominate the triangle count.
 *
 * @param smoothRadius box-blur radius in voxels; 0 marches the raw occupancy
 * @param iso the smoothed-occupancy level taken as the surface
 */
fun IntVolume.asDensityField(smoothRadius: Int = 1, iso: Double = 0.5): ScalarField3D {
    require(smoothRadius >= 0) { "smoothRadius must be >= 0, got $smoothRadius" }
    val src = FloatArray(size) { if (cells[it] != 0) 1f else 0f }
    val smooth = if (smoothRadius == 0) src else boxBlur3D(src, width, height, depth, smoothRadius)
    val level = iso.toFloat()
    return { p ->
        // Trilinear sample in voxel coordinates; outside the volume is empty.
        val fx = p.x; val fy = p.y; val fz = p.z
        val x0 = kotlin.math.floor(fx).toInt()
        val y0 = kotlin.math.floor(fy).toInt()
        val z0 = kotlin.math.floor(fz).toInt()
        val tx = fx - x0; val ty = fy - y0; val tz = fz - z0
        fun s(x: Int, y: Int, z: Int): Float =
            if (x in 0 until width && y in 0 until height && z in 0 until depth) {
                smooth[(z * height + y) * width + x]
            } else 0f
        val c00 = s(x0, y0, z0) + (s(x0 + 1, y0, z0) - s(x0, y0, z0)) * tx
        val c10 = s(x0, y0 + 1, z0) + (s(x0 + 1, y0 + 1, z0) - s(x0, y0 + 1, z0)) * tx
        val c01 = s(x0, y0, z0 + 1) + (s(x0 + 1, y0, z0 + 1) - s(x0, y0, z0 + 1)) * tx
        val c11 = s(x0, y0 + 1, z0 + 1) + (s(x0 + 1, y0 + 1, z0 + 1) - s(x0, y0 + 1, z0 + 1)) * tx
        val c0 = c00 + (c10 - c00) * ty
        val c1 = c01 + (c11 - c01) * ty
        (c0 + (c1 - c0) * tz) - level
    }
}

/**
 * The march window for [asDensityField]: the volume's own voxel coordinates, grown by one voxel on
 * every side so the surface of a blob touching the volume edge still closes.
 */
fun IntVolume.marchBounds(): AxisAlignedBoundingBox3D = boundsOf(
    Vec3(-1f, -1f, -1f),
    Vec3(width.toFloat(), height.toFloat(), depth.toFloat())
)

/**
 * Surface mesh of this volume's filled voxels, via [asDensityField] and [marchingCubes]. Watertight
 * by construction (the marcher shares vertices across cubes), which the gallery checks with
 * [TriMesh.isWatertight] rather than assuming.
 *
 * @param resolution march cells along the longest axis
 */
fun IntVolume.toMesh(resolution: Int = 96, smoothRadius: Int = 1, iso: Double = 0.5): TriMesh =
    marchingCubes(
        asDensityField(smoothRadius, iso),
        marchBounds(),
        resolution = resolution,
        iso = 0.0,
        positiveOutside = false
    )

/** Separable box blur of a 3-D scalar volume, clamping at the borders. Used by [IntVolume.asDensityField]. */
private fun boxBlur3D(src: FloatArray, w: Int, h: Int, d: Int, radius: Int): FloatArray {
    val n = 2 * radius + 1
    var cur = src
    var tmp = FloatArray(src.size)
    // x
    for (z in 0 until d) for (y in 0 until h) {
        val row = (z * h + y) * w
        for (x in 0 until w) {
            var s = 0f
            for (k in -radius..radius) s += cur[row + (x + k).coerceIn(0, w - 1)]
            tmp[row + x] = s / n
        }
    }
    cur = tmp; tmp = FloatArray(src.size)
    // y
    for (z in 0 until d) for (x in 0 until w) {
        for (y in 0 until h) {
            var s = 0f
            for (k in -radius..radius) s += cur[(z * h + (y + k).coerceIn(0, h - 1)) * w + x]
            tmp[(z * h + y) * w + x] = s / n
        }
    }
    cur = tmp; tmp = FloatArray(src.size)
    // z
    for (y in 0 until h) for (x in 0 until w) {
        for (z in 0 until d) {
            var s = 0f
            for (k in -radius..radius) s += cur[((z + k).coerceIn(0, d - 1) * h + y) * w + x]
            tmp[(z * h + y) * w + x] = s / n
        }
    }
    return tmp
}

/**
 * A z-slice of this volume as an [IntGrid] — the cheap way to look at a 3-D automaton without a
 * renderer, and how the gallery shows a volume beside the mesh marched from it.
 */
fun IntVolume.slice(z: Int): IntGrid {
    require(z in 0 until depth) { "z must be within 0..${depth - 1}, got $z" }
    val out = IntArray(width * height)
    System.arraycopy(cells, z * width * height, out, 0, width * height)
    return IntGrid(width, height, out, wrap = false)
}

/**
 * The volume's occupancy summed along z and normalised to `0..1` — a "density projection", which
 * shows the internal structure a surface mesh hides.
 */
fun IntVolume.depthProjection(): Img {
    val out = FloatArray(width * height)
    for (z in 0 until depth) for (y in 0 until height) for (x in 0 until width) {
        if (cells[index(x, y, z)] != 0) out[y * width + x] += 1f
    }
    val mx = max(1f, out.max())
    for (i in out.indices) out[i] /= mx
    return Img(width, height, 1, out)
}
