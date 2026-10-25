package afield.lang.rpn

import afield.study.search.PopulationSearch
import afield.study.search.objectiveOf
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins [ColorRpn] to the madp GA demo (`~/temp/aaaframe/madp/ga.html`).
 *
 * The golden values were produced by the demo's own interpreter: its `compileExpression`,
 * `evaluateCompiled` and `getCoordPack`, lifted verbatim out of the page and run under node. Each
 * row is a program, its normalised tokens, an FNV-1a hash of every channel byte of its 64×64
 * picture, and its colour at one point with explicit `r` and `a`. A matching hash means the two
 * interpreters drew the same picture to the byte. The programs cover the starters, the demo's
 * LLM-intro examples, and each of the rules a mutation leans on: empty and unknown-only programs,
 * the four final-stack recoveries, colour arithmetic and its wrap, safe division by zero, `if` on a
 * colour condition, the HSL setters, the clamped unary words, per-use noise seeds, comments, upper
 * case and JavaScript number forms.
 */
class ColorRpnTest {

    private class Golden(val source: String, val normalized: String, val hash: Long, val at: DoubleArray)

    private val golden = listOf(
        Golden("x 9 * sin 0.18 +\ny 0.55 +\n0.62\nhsl", "x 9 * sin 0.18 + y 0.55 + 0.62 hsl", 3660124414L, doubleArrayOf(1.0, 0.24, 0.4311862221910786)),
        Golden("a 14 * cos 0.14 +\nr 0.78 * 0.12 +\n0.48\nhsl", "a 14 * cos 0.14 + r 0.78 * 0.12 + 0.48 hsl", 4111324546L, doubleArrayOf(0.3100799999999999, 0.4037847372900864, 0.64992)),
        Golden("x 8 * perlin dup invert 0.3 blend", "x 8 * perlin dup invert 0.3 blend", 685069765L, doubleArrayOf(0.5, 0.5, 0.5)),
        Golden("x y + 7 * 2.2 fbm 0.65 0.55 hsl", "x y + 7 * 2.2 fbm 0.65 0.55 hsl", 4233326653L, doubleArrayOf(0.25749999999999995, 0.81983757812158, 0.8425000000000001)),
        Golden("x y 0.45 rgb normalize dup dot invert", "x y 0.45 rgb normalize dup dot invert", 581209801L, doubleArrayOf(0.0, 0.0, 0.0)),
        Golden("r 12 * sin a 9 * cos + 0.6 0.5 hsl", "r 12 * sin a 9 * cos + 0.6 0.5 hsl", 2710855486L, doubleArrayOf(0.7081792424679856, 0.8, 0.19999999999999996)),
        Golden("x 6 * perlin y 9 * perlin + 0.5 * 0.7 0.55 hsl", "x 6 * perlin y 9 * perlin + 0.5 * 0.7 0.55 hsl", 3873017803L, doubleArrayOf(0.2350000000000001, 0.865, 0.29467773437500017)),
        Golden("", "0.5", 3335937477L, doubleArrayOf(0.5, 0.5, 0.5)),
        Golden("foo bar", "0.5", 3335937477L, doubleArrayOf(0.5, 0.5, 0.5)),
        Golden("x", "x", 4061976005L, doubleArrayOf(0.25, 0.25, 0.25)),
        Golden("x y", "x y", 2146931714L, doubleArrayOf(0.522, 0.46799999999999997, 0.5148)),
        Golden("x y 0.3 rgb y", "x y 0.3 rgb y", 3512702727L, doubleArrayOf(0.39999999999999997, 0.7499999999999999, 0.43499999999999994)),
        Golden("x 0.2 0.9 rgb y 0.5 0.1 hsl", "x 0.2 0.9 rgb y 0.5 0.1 hsl", 2467253189L, doubleArrayOf(0.09999999999999996, 0.04999999999999999, 0.15000000000000002)),
        Golden("x 0.5 0.5 rgb 0 /", "x 0.5 0.5 rgb 0 /", 2859883973L, doubleArrayOf(0.0, 0.0, 0.0)),
        Golden("x y 0.2 rgb 3 * sin", "x y 0.2 rgb 3 * sin", 1947303413L, doubleArrayOf(0.6816387600233341, 0.24740395925452294, 0.5646424733950355)),
        Golden("x y -", "x y -", 489510341L, doubleArrayOf(0.5, 0.5, 0.5)),
        Golden("x 0.5 0.5 rgb y 0.1 0.9 rgb -", "x 0.5 0.5 rgb y 0.1 0.9 rgb -", 916966789L, doubleArrayOf(0.5, 0.4, 0.4)),
        Golden("x y a if", "x y a if", 1378388421L, doubleArrayOf(0.75, 0.75, 0.75)),
        Golden("x 0.3 0.7 rgb y 0.6 hue r sat 0.4 lum", "x 0.3 0.7 rgb y 0.6 hue r sat 0.4 lum", 1888068170L, doubleArrayOf(0.308125, 0.343125, 0.6231249999999999)),
        Golden("dup + if blend", "dup + if blend", 3335937477L, doubleArrayOf(0.5, 0.5, 0.5)),
        Golden("a r dot x y rgb dot", "a r dot x y rgb dot", 1568174631L, doubleArrayOf(0.59, 0.59, 0.59)),
        Golden("x 13 * tan exp ln asin acos atan", "x 13 * tan exp ln asin acos atan", 2984069061L, doubleArrayOf(0.9707430944352637, 0.9707430944352637, 0.9707430944352637)),
        Golden("y 40 * perlin x 30 * perlin x 20 * perlin x 10 * perlin x 5 * perlin rgb", "y 40 * perlin x 30 * perlin x 20 * perlin x 10 * perlin x 5 * perlin rgb", 1214639301L, doubleArrayOf(0.5, 0.5, 0.646484375)),
        Golden("x 0.8 fbm y 3.5 fbm r 12 fbm rgb", "x 0.8 fbm y 3.5 fbm r 12 fbm rgb", 1337972765L, doubleArrayOf(0.3367138035595417, 0.796567852050066, 0.36725973333333306)),
        Golden("# comment\nx // more\ny + 0.6 0.5 hsl", "x y + 0.6 0.5 hsl", 3528399893L, doubleArrayOf(0.8, 0.19999999999999996, 0.19999999999999996)),
        Golden("X Y + 0.6 0.5 HSL", "x y + 0.6 0.5 hsl", 3528399893L, doubleArrayOf(0.8, 0.19999999999999996, 0.19999999999999996)),
        Golden("0x10 x * sin 1e-1 + .5 0.5 hsl", "16 x * sin 0.1 + 0.5 0.5 hsl", 2573103557L, doubleArrayOf(0.25, 0.75, 0.2795925140762154)),
        Golden("x y r rgb x y 0.3 rgb blend", "x y r rgb x y 0.3 rgb blend", 3672900333L, doubleArrayOf(0.25, 0.75, 0.3)),
        Golden("x y blend", "x y blend", 1763948228L, doubleArrayOf(0.375, 0.375, 0.375)),
    )

    private fun fnv(px: IntArray): Long {
        var h = 2166136261L.toInt()
        for (p in px) for (shift in intArrayOf(16, 8, 0)) h = (h xor ((p ushr shift) and 0xff)) * 16777619
        return h.toLong() and 0xffffffffL
    }

    @Test
    fun everyGoldenProgramDrawsTheDemosPictureToTheByte() {
        for (g in golden) {
            val program = ColorRpn.compile(g.source)
            assertEquals(g.normalized, program.normalized, "tokens of '${g.source}'")
            assertEquals(g.hash, fnv(program.render(64)), "picture of '${g.source}'")
            val c = program.color(0.25, 0.75, 0.3, 0.6)
            for (k in 0 until 3) assertTrue(abs(c[k] - g.at[k]) < 1e-12, "channel $k of '${g.source}' at (0.25, 0.75): ${c[k]} vs ${g.at[k]}")
        }
    }

    @Test
    fun emptyProgramsAndEmptyStacksReadOneHalf() {
        assertEquals("0.5", ColorRpn.compile("").normalized)
        assertEquals(listOf("foo", "bar"), ColorRpn.compile("foo bar").unknown)
        assertEquals(0.5, ColorRpn.compile("foo bar").color(0.1, 0.9)[0])
        // + on an empty stack adds 0.5 and 0.5: 1.0, which a lone number wraps to 0
        assertEquals(0.0, ColorRpn.compile("+").color(0.1, 0.9)[1])
        // hsl with nothing beneath it is hsl(0.5, 0.5, 0.5)
        val c = ColorRpn.compile("hsl").color(0.1, 0.9)
        assertEquals(0.25, c[0], 1e-12); assertEquals(0.75, c[1], 1e-12); assertEquals(0.75, c[2], 1e-12)
    }

    @Test
    fun wrapAndSafeDivisionAreTheDemos() {
        assertEquals(0.75, ColorRpn.wrap01(-0.25), 1e-12)
        assertEquals(0.5, ColorRpn.wrap01(Double.NaN))
        assertEquals(0.5, ColorRpn.wrap01(Double.POSITIVE_INFINITY))
        assertEquals(1e6, ColorRpn.safeDiv(1.0, 0.0))
        assertEquals(-1e6, ColorRpn.safeDiv(1.0, -1e-9))
        assertEquals(0.5, ColorRpn.safe(Double.NaN))
    }

    @Test
    fun theFinalStackRecoversAColour() {
        val one = ColorRpn.compile("0.3").color(0.0, 0.0)
        assertTrue(one.all { abs(it - 0.3) < 1e-12 }, "one number is a grey")
        val twoColours = ColorRpn.compile("0.8 0 0 rgb 0 0 0.5 rgb 0.9").color(0.0, 0.0)
        assertEquals(listOf(0.4, 0.0, 0.25), twoColours.map { Math.round(it * 1e9) / 1e9 }, "two colours under a number are averaged")
        val colourAndNumber = ColorRpn.compile("0.5 0 0 rgb 1.25").color(0.0, 0.0)
        assertEquals(listOf(0.425, 0.075, 0.075), colourAndNumber.map { Math.round(it * 1e9) / 1e9 }, "colour + number is 70/30, the number wrapped")
    }

    @Test
    fun numbersAreWrittenAsTheDemoWritesThem() {
        val cases = listOf(0.0 to "0", -0.0004 to "0", 0.0625 to "0.063", -0.0625 to "-0.063", 1.0005 to "1", 12.345 to "12.35",
            123.456 to "123.5", 1e7 to "1000000", 0.1 + 0.2 to "0.3", -3.14159 to "-3.142", 99.995 to "100", Double.NaN to "0.5")
        for ((v, s) in cases) assertEquals(s, ColorRpn.format(v), "format($v)")
        assertEquals(
            "x 9 * sin 0.18 + y 0.55\n+ 0.62 hsl x 9 * sin 0.18\n+ y 0.55 + 0.62 hsl\na b c d e f g h\ni",
            ColorRpn.pretty(ColorRpn.tokens("x 9 * sin 0.18 + y 0.55 + 0.62 hsl x 9 * sin 0.18 + y 0.55 + 0.62 hsl a b c d e f g h i")),
        )
        assertEquals(16.0, ColorRpn.number("0x10")); assertEquals(0.5, ColorRpn.number(".5")); assertEquals(null, ColorRpn.number("1d"))
    }

    @Test
    fun childrenAreBredWithinTheDemosBounds() {
        val genome = ColorRpnGenome()
        val a = ColorRpn.tokens("x 9 * sin 0.18 + y 0.55 + 0.62 hsl")
        val b = ColorRpn.tokens("a 14 * cos 0.14 + r 0.78 * 0.12 + 0.48 hsl")
        val rng = Random(7)
        val kids = genome.children(a, b, 400, rng)
        // the target is 18 ± 2, and a growing nudge may overshoot by four before it stops
        assertTrue(kids.all { it.tokens.size in 1..24 }, "lengths ${kids.map { it.tokens.size }.toSet()}")
        assertTrue(kids.count { "seed" in it.notes } in 130..210, "about 42% take a seed splice: ${kids.count { "seed" in it.notes }}")
        val notes = kids.flatMap { it.notes }.filter { it != "seed" }.groupingBy { it }.eachCount()
        assertTrue((notes["num"] ?: 0) > (notes["splice"] ?: 0), "number tweaks outnumber splices: $notes")
        // same seed, same children
        assertEquals(genome.children(a, b, 5, Random(3)).map { it.tokens }, genome.children(a, b, 5, Random(3)).map { it.tokens })
        // breeding settings live in the box: a zero mutation rate and no splice leaves only the length nudge
        val still = ColorRpnGenome(ColorRpnBreeding(mutationRate = 0.0, seedSplice = 0.0, targetLength = a.size))
        assertTrue(still.children(a, b, 50, Random(1)).count { it.notes.isEmpty() } == 50)
    }

    @Test
    fun theGenomeAlsoRunsUnderPopulationSearch() {
        // A computed stand-in for the eye: prefer pictures whose centre is red. This checks the
        // plumbing (GenomeOps, crossover, signature, determinism), not that search beats chance:
        // on an objective this easy the random control's 300 draws find a near-perfect red first.
        val genome = ColorRpnGenome()
        val objective = objectiveOf<List<String>> { g ->
            val c = ColorRpn.compile(g.joinToString(" ")).color(0.5, 0.5)
            c[0] - (c[1] + c[2]) / 2
        }
        val run = PopulationSearch.evolve(genome, objective, budget = 300, seed = 5, crossoverRate = 0.5)
        assertTrue(run.recombined > 0, run.summary())
        assertTrue(run.bestFitness >= run.initialFitness, run.summary())
        val again = PopulationSearch.evolve(genome, objective, budget = 300, seed = 5, crossoverRate = 0.5)
        assertEquals(run.best!!.genome, again.best!!.genome)
    }
}
