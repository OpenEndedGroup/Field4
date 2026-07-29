package afield.examples.stdlib

import afield.stdlib.BoidFlock
import afield.stdlib.BoidParams
import afield.stdlib.CYCLIC_EXCITABLE
import afield.stdlib.CYCLIC_LABYRINTH
import afield.stdlib.GRAY_SCOTT_HOLES
import afield.stdlib.GRAY_SCOTT_MAZE
import afield.stdlib.GRAY_SCOTT_MITOSIS
import afield.stdlib.GRAY_SCOTT_SPOTS
import afield.stdlib.GRAY_SCOTT_WORMS
import afield.stdlib.GrayScottParams
import afield.stdlib.Img
import afield.stdlib.IntGrid
import afield.stdlib.IntVolume
import afield.stdlib.LIFE
import afield.stdlib.LIFE3D_CLOUDS
import afield.stdlib.LSYSTEM_ALGAE
import afield.stdlib.LSYSTEM_BUSH
import afield.stdlib.LSYSTEM_DRAGON
import afield.stdlib.LSYSTEM_KOCH_SNOWFLAKE
import afield.stdlib.LSYSTEM_SIERPINSKI_ARROWHEAD
import afield.stdlib.LSYSTEM_STOCHASTIC_PLANT
import afield.stdlib.LeniaPhase
import afield.stdlib.LeniaPresets
import afield.stdlib.PhysarumParams
import afield.stdlib.PhysarumSwarm
import afield.stdlib.TurtleParams
import afield.stdlib.Vec2
import afield.stdlib.annulusAttractors
import afield.stdlib.arrivalOrderImg
import afield.stdlib.blobShapes
import afield.stdlib.crop
import afield.stdlib.centerColumn
import afield.stdlib.centeredOn
import afield.stdlib.colormap
import afield.stdlib.correlationLength
import afield.stdlib.radialAutocorrelation
import afield.stdlib.cyclicAdvanceFraction
import afield.stdlib.cyclicPalette
import afield.stdlib.depthProjection
import afield.stdlib.diffuseTimes
import afield.stdlib.dilate
import afield.stdlib.discAttractors
import afield.stdlib.discMaskImg
import afield.stdlib.dlaCluster
import afield.stdlib.drawLSystem
import afield.stdlib.edenCluster
import afield.stdlib.elementaryCa
import afield.stdlib.eulerCharacteristic
import afield.stdlib.fieldMaxAbsDiff
import afield.stdlib.filmstrip
import afield.stdlib.flineSignedArea
import afield.stdlib.flineTotalLength
import afield.stdlib.grayScottSeed
import afield.stdlib.isWatertight
import afield.stdlib.leniaFromLifeRule
import afield.stdlib.leniaRun
import afield.stdlib.leniaSeed
import afield.stdlib.leniaTimes
import afield.stdlib.lifeRule
import afield.stdlib.longestRunLength
import afield.stdlib.massRadiusDimension
import afield.stdlib.montage
import afield.stdlib.morphology
import afield.stdlib.normalized
import afield.stdlib.resize
import afield.stdlib.quantileOf
import afield.stdlib.rasterizeFLines
import afield.stdlib.runUntilCycle
import afield.stdlib.savePng
import afield.stdlib.sierpinskiReference
import afield.stdlib.slice
import afield.stdlib.spaceColonization
import afield.stdlib.sprinkleCluster
import afield.stdlib.stateEntropyBits
import afield.stdlib.step
import afield.stdlib.stepTimes
import afield.stdlib.threshold
import afield.stdlib.toImg
import afield.stdlib.toIntGrid
import afield.stdlib.toMesh
import afield.stdlib.topShare
import afield.stdlib.totalMass
import afield.stdlib.translationBetween
import afield.stdlib.upscaleNearest
import afield.stdlib.withLabel
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.system.exitProcess

/*
FIELD_TEST
id: stdlib.alife.gallery
runner: jvm-main
entrypoint: afield.examples.stdlib.AlifeGallery
tags: [smoke, stdlib, alife, capture, cpu]
timeoutSec: 900
expect:
  exitCode: 0
  stdoutContains:
    - "alife gallery rendered"
*/

/**
 * Renders — and *verifies* — the figures behind `public/docs/alife_examples.md`.
 *
 * Modelled on [WfcGallery], for the same reason: a picture of a cellular automaton is the easiest thing
 * in this codebase to fake, because almost any local rule applied to noise produces something that
 * looks organic. So every figure here is accompanied by an assertion **a control arm fails**:
 *
 *  - a glider must translate by *exactly* `(1, 1)` in 4 generations, as an integer grid equality;
 *  - rule 90's space-time diagram must equal Pascal's triangle mod 2, computed independently;
 *  - a pure-diffusion step must conserve total mass to floating-point tolerance on a torus;
 *  - Gray–Scott's spot preset must measure round and its maze preset must measure connected, by
 *    isoperimetric ratio and largest-blob share, not by eye;
 *  - Lenia's discrete limit must reproduce Conway's Life *bit for bit* — two independently written
 *    steppers, one float and one integer;
 *  - a flock's polarization must be far above the random-heading baseline, and its alignment-off twin
 *    must not be;
 *  - a Physarum network must concentrate its deposit far more than its sensing-off twin;
 *  - a DLA cluster's mass dimension must be ~1.7 while an Eden cluster's and a random sprinkle's are ~2.
 *
 * Two statistics are **calibrated rather than trusted** ([figureCalibration]): a synthetic disc is
 * measured to confirm that [blobShapes] scores a round object near 1 and that [massRadiusDimension]
 * scores a filled disc near 2. If the calibration drifts, every downstream threshold is meaningless, so
 * it runs first and fails loudly.
 *
 * As a FIELD_TEST (`stdlib.alife.gallery`) it renders to a temp dir and writes no committed assets.
 * Pass an output dir as `args[0]` (or set `ALIFE_GALLERY_DIR`) to put the PNGs somewhere that survives.
 */
object AlifeGallery {

    // ==========================================================================================
    // Verification plumbing
    // ==========================================================================================

    private val failures = ArrayList<String>()

    private fun fail(message: String) {
        failures.add(message)
        println("[alife-gallery] FAIL $message")
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) fail(message) else println("[alife-gallery] ok  $message")
    }

    /** Format a double for the log without dragging in a formatter per call site. */
    private fun f(v: Double, digits: Int = 3): String = "%.${digits}f".format(v)

    // ==========================================================================================
    // 0 — Calibration of the two measurements everything else leans on
    // ==========================================================================================

    /**
     * Measures a synthetic disc so the two shape statistics are shown to read what theory says.
     *
     * A disc is the one object whose isoperimetric ratio (1) and mass dimension (2) are known exactly,
     * so this is the closest thing the family has to a ruler check. It runs first because every
     * threshold in [figureGrayScott] and [figureDla] is stated relative to these two numbers.
     */
    private fun figureCalibration(outDir: File) {
        val disc = discMaskImg(160, 160, 40.0)
        val shapes = disc.blobShapes()
        check(shapes.size == 1, "calibration: a disc is one blob (got ${shapes.size})")
        val ratio = shapes.firstOrNull()?.isoperimetricRatio ?: 0.0
        check(
            ratio in 0.90..1.15,
            "calibration: a 40px disc has isoperimetric ratio ${f(ratio)} — theory says 1.0 " +
                "(the crack-perimeter π/4 correction is doing its job)"
        )

        val pts = ArrayList<Vec2>()
        for (y in 0 until disc.height) for (x in 0 until disc.width) {
            if (disc.data[y * disc.width + x] > 0.5f) pts.add(Vec2(x.toFloat(), y.toFloat()))
        }
        val fit = massRadiusDimension(pts, Vec2(79.5f, 79.5f), rMin = 4.0, rMax = 30.0)
        check(
            abs(fit.dimension - 2.0) < 0.06 && fit.rSquared > 0.999,
            "calibration: a filled disc has mass dimension ${f(fit.dimension)} (r²=${f(fit.rSquared, 5)}) " +
                "— theory says exactly 2"
        )

        // A one-cell-wide ring: the other end of the isoperimetric scale, so the statistic is shown to
        // have range and not merely to sit near 1 for everything.
        val ring = Img.generate(160, 160) { x, y ->
            val d = kotlin.math.hypot(x - 79.5, y - 79.5)
            if (d in 38.0..40.0) 1f else 0f
        }
        val ringRatio = ring.blobShapes().firstOrNull()?.isoperimetricRatio ?: 0.0
        check(
            ringRatio > 8.0,
            "calibration: a thin annulus scores ${f(ringRatio, 1)} — a disc scores ~1, so the statistic " +
                "separates round from thin by nearly an order of magnitude"
        )

        montage(
            listOf(
                "disc: ratio ${f(ratio, 2)}, D ${f(fit.dimension, 2)}" to disc.upscaleNearest(2),
                "annulus: ratio ${f(ringRatio, 1)}" to ring.upscaleNearest(2)
            ),
            columns = 2, cellSide = 320
        ).savePng(File(outDir, "alife-calibration.png").absolutePath)
    }

    // ==========================================================================================
    // 1 — Conway's Life against known objects
    // ==========================================================================================

    /**
     * The discrete half's evidence: named Life patterns must behave the way the literature says, stated
     * as integer-grid equalities rather than as observations about a picture.
     */
    private fun figureLife(outDir: File) {
        // --- a glider translates by exactly (1, 1) every 4 generations ---
        val glider = afield.stdlib.LifePatterns.glider.centeredOn(40, 40, wrap = true)
        val after4 = glider.stepTimes(LIFE, 4)
        val t = translationBetween(glider, after4)
        check(
            t == (1 to 1),
            "life: the glider is a rigid translate by exactly (1, 1) after 4 generations under " +
                "${LIFE.notation} (measured $t)"
        )
        val after8 = glider.stepTimes(LIFE, 8)
        check(
            translationBetween(glider, after8) == (2 to 2),
            "life: and by (2, 2) after 8 — the speed is c/4 diagonally, not merely 'it moved'"
        )

        // Control arm: the same pattern under a rule one birth-count away is not a translate of itself.
        val wrong = lifeRule("B34/S23")
        val wrongAfter4 = glider.stepTimes(wrong, 4)
        check(
            translationBetween(glider, wrongAfter4) == null,
            "life CONTROL: under B34/S23 the same seed is NOT a rigid translate after 4 generations — " +
                "so the (1,1) result is about the rule, not about the checker"
        )

        // --- still lifes are bit-identical after a step; oscillators are not ---
        val block = afield.stdlib.LifePatterns.block.centeredOn(20, 20)
        check(
            block.step(LIFE).sameCellsAs(block),
            "life: the block is bit-identical after one generation (a still life)"
        )
        val beehive = afield.stdlib.LifePatterns.beehive.centeredOn(20, 20)
        check(
            beehive.step(LIFE).sameCellsAs(beehive),
            "life: so is the beehive — the block is not a lucky special case"
        )
        val blinker = afield.stdlib.LifePatterns.blinker.centeredOn(20, 20)
        check(
            !blinker.step(LIFE).sameCellsAs(blinker) && blinker.stepTimes(LIFE, 2).sameCellsAs(blinker),
            "life CONTROL: the blinker is NOT identical after 1 generation but IS after 2 (period 2)"
        )
        val toad = afield.stdlib.LifePatterns.toad.centeredOn(20, 20)
        check(
            toad.runUntilCycle(LIFE, 40).period == 2,
            "life: the toad's detected period is 2 (found by exact state hashing, not by population)"
        )

        // --- the R-pentomino: a specific number of generations to a specific population ---
        // The textbook figure — 1103 generations to a population of 116 — is about an **unbounded**
        // board: six of those 116 cells are gliders still in flight, escaping to infinity. So the check
        // needs room, not walls. 620x620 gives every glider more than the 1103/4 ≈ 276 cells it can
        // travel, and [liveBounds] is asserted to confirm nothing ever reached an edge — which is what
        // makes a finite board a legitimate stand-in for an infinite one rather than an assumption.
        val big = afield.stdlib.LifePatterns.rPentomino.centeredOn(620, 620, wrap = false)
        var g = big
        var peak = 0
        var peakAt = 0
        for (gen in 1..1103) {
            g = g.step(LIFE)
            val pop = g.population()
            if (pop > peak) { peak = pop; peakAt = gen }
        }
        val bounds = g.liveBounds()!!
        check(
            bounds[0] >= 3 && bounds[1] >= 3 && bounds[2] <= 616 && bounds[3] <= 616,
            "life: at generation 1103 the R-pentomino's live cells are still ${bounds[0]} cells clear of " +
                "the board edge, so the walls never took part — a finite board standing in for an " +
                "infinite one, checked rather than assumed"
        )
        val pop1103 = g.population()
        check(
            pop1103 == 116,
            "life: the R-pentomino's population at generation 1103 is EXACTLY 116 — the textbook " +
                "figure, of which 6 cells are still-flying gliders (measured $pop1103)"
        )
        // Re-checking 40 generations later, not 200: by generation 1303 the earliest glider has travelled
        // ~326 cells and a 620-wide board can only certify 310, so the population drops to 115 as a
        // glider is eaten by the wall. That is the board's limit, not the automaton's, and the honest fix
        // is to check only as far as the clearance assertion can vouch for.
        val later = g.stepTimes(LIFE, 40)
        val laterBounds = later.liveBounds()!!
        check(
            laterBounds[0] >= 3 && laterBounds[1] >= 3 && laterBounds[2] <= 616 && laterBounds[3] <= 616 &&
                later.population() == 116,
            "life: and it is still ${later.population()} forty generations later, with the live cells " +
                "still clear of the board edge — stabilised, not merely passing through 116"
        )
        println("[alife-gallery]     R-pentomino peak population $peak at generation $peakAt")
        check(
            peak == 319 && peakAt == 821,
            "life: its peak population is $peak at generation $peakAt — the literature says 319 at 821"
        )

        // The same seed on a board with WALLS is a different system, and reporting the difference is the
        // point: the gliders are destroyed at the edge, so it reaches an exactly periodic state (which
        // the unbounded version never does, because the gliders fly forever) at a lower population.
        val walled = afield.stdlib.LifePatterns.rPentomino.centeredOn(250, 250, wrap = false)
        val report = walled.runUntilCycle(LIFE, 1400)
        println("[alife-gallery]     bounded 250x250 board: $report")
        check(
            report.settled && report.period == 2 && report.population.last() < pop1103,
            "life: on a 250x250 walled board the same seed instead becomes exactly period-" +
                "${report.period} at generation ${report.firstSeenAt} with " +
                "${report.population.last()} cells — fewer than 116, because the escaping gliders hit " +
                "the wall. Same rule, different boundary, different (and correctly different) answer"
        )

        val strip = afield.stdlib.LifePatterns.glider.centeredOn(16, 16).let { g ->
            (0..4).map { "gen $it" to g.stepTimes(LIFE, it).toImg(2).upscaleNearest(14) }
        }
        montage(strip, columns = 5, cellSide = 240).savePng(File(outDir, "alife-life-glider.png").absolutePath)

        // A picture of the R-pentomino's history: population over time, plus the settled ash.
        val popImg = plotSeries(report.population.map { it.toDouble() }, 460, 300)
        // Two views, because the object has two scales: still ash within ~60 cells of the origin, and four
        // gliders 270 cells out. One picture cannot show both.
        val ashCentre = g.toImg(2).crop(310 - 60, 310 - 60, 121, 121).upscaleNearest(4)
        val ashWide = g.toImg(2)
            .crop(bounds[0] - 2, bounds[1] - 2, bounds[2] - bounds[0] + 5, bounds[3] - bounds[1] + 5)
            .dilate(1)
            .resize(460, 460)
        montage(
            listOf(
                "population, walled board" to popImg,
                "ash near origin (121 cells)" to ashCentre,
                "full extent: ash + gliders" to ashWide
            ),
            columns = 3, cellSide = 480
        ).savePng(File(outDir, "alife-life-rpentomino.png").absolutePath)
    }

    // ==========================================================================================
    // 2 — Elementary 1-D CA against an independent oracle
    // ==========================================================================================

    /**
     * The sharpest evidence in the family: rule 90's space-time diagram is Pascal's triangle mod 2, and
     * `binomialParity` computes that without running a CA at all — so the equality is a cross-check
     * between two unrelated computations rather than a stepper agreeing with itself.
     */
    private fun figureElementary(outDir: File) {
        val w = 257
        val gens = 128
        val rule90 = elementaryCa(90, w, gens)
        val reference = sierpinskiReference(w, gens)
        var mismatches = 0
        for (i in rule90.cells.indices) if (rule90.cells[i] != reference.cells[i]) mismatches++
        check(
            mismatches == 0,
            "elementary: rule 90's ${w}x$gens space-time diagram is EXACTLY Pascal's triangle mod 2 " +
                "(computed from Kummer's theorem, $mismatches differing cells)"
        )

        // Control: the same oracle must NOT match a different rule, or the comparison proves nothing.
        val rule150 = elementaryCa(150, w, gens)
        var mismatches150 = 0
        for (i in rule150.cells.indices) if (rule150.cells[i] != reference.cells[i]) mismatches150++
        check(
            mismatches150 > 1000,
            "elementary CONTROL: rule 150 differs from the same oracle in $mismatches150 cells — the " +
                "oracle is discriminating, not vacuous"
        )

        // Rule 184 is the traffic rule: it conserves the number of 1s exactly, every step, forever.
        val seedRow = IntArray(257) { if ((it * 7919) % 3 == 0) 1 else 0 }
        val expected = seedRow.count { it == 1 }
        val traffic = elementaryCa(184, 257, 200, seedRow)
        val perRow = (0 until 200).map { g -> (0 until 257).count { traffic.cells[g * 257 + it] == 1 } }
        check(
            perRow.all { it == expected },
            "elementary: rule 184 conserves density exactly — all 200 generations hold $expected ones " +
                "(min ${perRow.min()}, max ${perRow.max()})"
        )
        // The control for "184 conserves density" cannot just be "another rule has many distinct row
        // populations" — rule 90's populations are powers of two and so there are few distinct values.
        // The sharp statement is that rule 90's row populations follow Gould's sequence exactly:
        // generation g holds 2^(number of 1 bits in g) live cells. A second independent oracle.
        val rule90rows = (0 until 128).map { r -> (0 until w).count { rule90.cells[r * w + it] == 1 } }
        val gould = (0 until 128).map { 1 shl Integer.bitCount(it) }
        check(
            rule90rows == gould,
            "elementary: rule 90's row populations are exactly 2^popcount(generation) — Gould's " +
                "sequence, a second oracle independent of the Pascal comparison"
        )
        check(
            rule90rows.count { it != expected } > 100,
            "elementary CONTROL: rule 90 does NOT conserve density (${rule90rows.count { it != expected }} " +
                "of 128 generations differ from the seed's population) — conservation is a property of " +
                "rule 184, not of the harness"
        )

        // Rule 30's centre column: the classic 'complex' elementary rule.
        val col = elementaryCa(30, 601, 600).centerColumn()
        val ones = col.count { it == 1 }
        val run = longestRunLength(col)
        val col90 = rule90.centerColumn()
        check(
            ones in 250..350 && run <= 12,
            "elementary: rule 30's centre column over 600 generations is $ones/600 ones with a longest " +
                "run of $run — balanced and unstructured, which is why it shipped as a random generator"
        )
        check(
            longestRunLength(col90) >= 60,
            "elementary CONTROL: rule 90's centre column has a run of ${longestRunLength(col90)} — " +
                "so 'longest run' really does separate structured from unstructured here"
        )

        montage(
            listOf(
                "rule 90 (= Pascal mod 2)" to rule90.toImg(2),
                "rule 30" to elementaryCa(30, 257, 128).toImg(2),
                "rule 110 (universal)" to elementaryCa(110, 257, 128, IntArray(257) { if (it % 13 == 0) 1 else 0 }).toImg(2),
                "rule 184 (traffic, density-conserving)" to traffic.toImg(2)
            ),
            columns = 2, cellSide = 300
        ).savePng(File(outDir, "alife-elementary.png").absolutePath)
    }

    // ==========================================================================================
    // 3 — Cyclic CA: the advance fraction pins at 1 once spirals organise
    // ==========================================================================================

    /**
     * A cyclic CA's self-organisation, measured: in a fresh random soup most cells are blocked, so few
     * advance per step; once spiral cores have formed every cell is driven by a neighbour and the
     * advance fraction pins at 1.0.
     */
    private fun figureCyclic(outDir: File) {
        val rule = CYCLIC_LABYRINTH
        val soup = IntGrid.random(220, 220, states = rule.states, seed = 7L)
        val early = cyclicAdvanceFraction(soup, soup.step(rule))
        val settled = soup.stepTimes(rule, 400)
        val late = cyclicAdvanceFraction(settled, settled.step(rule))

        // In an uncorrelated soup a cell advances exactly when at least one of its 8 Moore neighbours
        // happens to hold its successor state, which is 1 - (1 - 1/states)^8. That is a closed form, so
        // the initial measurement has a prediction to match rather than a threshold to clear.
        val predicted = 1.0 - Math.pow(1.0 - 1.0 / rule.states, 8.0)
        println("[alife-gallery]     cyclic advance: soup ${f(early)} (predicted ${f(predicted)}), settled ${f(late)}")
        check(
            abs(early - predicted) < 0.02,
            "cyclic: the soup's advance fraction is ${f(early)}, matching the uncorrelated-field " +
                "prediction 1 - (13/14)^8 = ${f(predicted)} to ${f(abs(early - predicted), 4)}"
        )
        check(
            late > 0.99,
            "cyclic: after 400 generations ${f(late)} of cells advance every step — the field has " +
                "organised into locally-coherent bands where every cell is driven by a neighbour, which " +
                "is precisely what no uncorrelated field does"
        )
        check(
            late > early * 2.0,
            "cyclic: the advance fraction rose ${f(late / early, 2)}x from soup to organised phase"
        )

        val palette = cyclicPalette(rule.states)
        val exRule = CYCLIC_EXCITABLE
        val exSoup = IntGrid.random(220, 220, states = exRule.states, seed = 7L)
        val exSettled = exSoup.stepTimes(exRule, 200)
        val exLate = cyclicAdvanceFraction(exSettled, exSettled.step(exRule))
        println("[alife-gallery]     excitable (${exRule.states} states, threshold ${exRule.threshold}, r=${exRule.radius}) advance ${f(exLate)}")
        // The two parameter sets are visibly different phases — the excitable one curls into spiral
        // cores — and the difference is measurable as a length scale rather than left to the eye.
        val labLength = settled.toImg(rule.states).correlationLength(maxLag = 30)
        val exLength = exSettled.toImg(exRule.states).correlationLength(maxLag = 30)
        println("[alife-gallery]     correlation length: labyrinth ${f(labLength, 2)}, excitable ${f(exLength, 2)}")
        check(
            labLength > exLength * 1.3,
            "cyclic: the two parameter sets settle at measurably different length scales — the " +
                "threshold-1 labyrinth at ${f(labLength, 2)} cells against the excitable rule's " +
                "${f(exLength, 2)}, a factor of ${f(labLength / exLength, 2)}. (The larger " +
                "neighbourhood gives the *smaller* correlation length, which is the opposite of what " +
                "this figure first asserted; the excitable rule's spiral arms are narrow even though its " +
                "neighbourhood is wide.)"
        )
        montage(
            listOf(
                "soup: advance ${f(early, 2)}" to soup.toImg(palette),
                "gen 40" to soup.stepTimes(rule, 40).toImg(palette),
                "gen 120" to soup.stepTimes(rule, 120).toImg(palette),
                "gen 400: adv ${f(late, 2)}" to settled.toImg(palette),
                "excitable gen 200" to exSettled.toImg(cyclicPalette(exRule.states)),
                "excitable gen 201" to exSettled.step(exRule).toImg(cyclicPalette(exRule.states))
            ),
            columns = 3, cellSide = 280
        ).savePng(File(outDir, "alife-cyclic.png").absolutePath)
    }

    // ==========================================================================================
    // 4 — Diffusion conserves mass; Gray–Scott regimes are told apart by measurement
    // ==========================================================================================

    /**
     * The numerical invariant behind every continuous system here: with a zero-sum Laplacian on a torus,
     * diffusion moves material and creates none. Checked to relative `1e-5` over 500 steps, and shown to
     * *fail* under a clamped border, which is the mistake this check exists to catch.
     */
    private fun figureDiffusion(outDir: File) {
        // A field that REACHES the border, so a lossy boundary actually has something to lose. The first
        // run of this figure used a small central disc, and its "CLAMP loses mass" control passed
        // vacuously because nothing ever got near an edge.
        val seedField = discMaskImg(96, 96, 40.0)
        val m0 = seedField.totalMass()
        val wrapped = seedField.diffuseTimes(500, 0.25, afield.stdlib.Border.WRAP)
        val relWrap = abs(wrapped.totalMass() - m0) / m0
        check(
            relWrap < 1e-5,
            "diffusion: 500 steps on a torus change total mass by ${"%.3e".format(relWrap)} relative — " +
                "the 9-point Laplacian sums to exactly zero, so mass is conserved"
        )
        // CLAMP is a *reflecting* (zero-flux Neumann) boundary, so it conserves mass too. Stated as a
        // positive claim rather than left as a surprise: the first version of this figure asserted the
        // opposite and was wrong.
        val clamped = seedField.diffuseTimes(500, 0.25, afield.stdlib.Border.CLAMP)
        val relClamp = abs(clamped.totalMass() - m0) / m0
        check(
            relClamp < 1e-5,
            "diffusion: and a CLAMP border also conserves it (${"%.3e".format(relClamp)}) — clamping is " +
                "a reflecting, zero-flux boundary, which is worth knowing rather than discovering"
        )
        val zeroed = seedField.diffuseTimes(500, 0.25, afield.stdlib.Border.ZERO)
        val relZero = abs(zeroed.totalMass() - m0) / m0
        check(
            relZero > 0.01 && relZero > relWrap * 1000,
            "diffusion CONTROL: a ZERO border loses ${f(relZero * 100, 1)}% of the mass over the same " +
                "500 steps — ${"%.0f".format(relZero / relWrap)}x the toroidal residual. An absorbing " +
                "boundary is the one case where 'the diffusion is leaking' is the right diagnosis"
        )
        montage(
            listOf(
                "seed" to seedField.upscaleNearest(3),
                "WRAP: mass kept" to wrapped.normalized().upscaleNearest(3),
                "ZERO: ${f(relZero * 100, 1)}% lost" to zeroed.normalized().upscaleNearest(3)
            ),
            columns = 3, cellSide = 260
        ).savePng(File(outDir, "alife-diffusion.png").absolutePath)
    }

    /**
     * Gray–Scott's regimes, discriminated by *measurement*: the spot preset must produce many small
     * round features and the maze preset one large convoluted one. Both have similar coverage and
     * similar means; only the shape statistics tell them apart, which is the point.
     */
    private fun figureGrayScott(outDir: File) {
        val side = 128
        val steps = 6000
        val presets = listOf(
            "spots 0.030/0.062" to GRAY_SCOTT_SPOTS,
            "worms 0.037/0.060" to GRAY_SCOTT_WORMS,
            "maze 0.029/0.057" to GRAY_SCOTT_MAZE,
            "sparse 0.0367/0.0649" to GRAY_SCOTT_MITOSIS,
            "holes 0.039/0.058" to GRAY_SCOTT_HOLES
        )
        val panels = ArrayList<Pair<String, Img>>()
        val reports = LinkedHashMap<String, afield.stdlib.MorphologyReport>()
        for ((label, prm) in presets) {
            val end = grayScottSeed(side, side, patches = 6, patchSize = 14, seed = 11L).stepTimes(prm, steps)
            val m = end.v.morphology()
            reports[label] = m
            println("[alife-gallery]     $label -> $m")
            panels.add(label to end.v.colormap("magma", 0f, 0.5f).upscaleNearest(2))
        }

        val spots = reports.entries.first { it.key.startsWith("spots") }.value
        val maze = reports.entries.first { it.key.startsWith("maze") }.value
        val worms = reports.entries.first { it.key.startsWith("worms") }.value
        val holes = reports.entries.first { it.key.startsWith("holes") }.value

        check(
            spots.blobCount >= 15 && spots.largestShare < 0.25,
            "gray-scott: the spot preset produced ${spots.blobCount} separate features, the largest " +
                "holding ${f(spots.largestShare)} of the covered area — many small things, not one big one"
        )
        // Note the direction of the small-blob bias: the crack-perimeter π/4 correction is asymptotic, so
        // a 10-cell blob scores slightly BELOW 1 (a 40-cell disc scores 1.0 — see figureCalibration).
        // That biases this statistic toward "round", which makes a *stripe* verdict conservative.
        check(
            spots.medianIsoperimetric < 1.6,
            "gray-scott: and they are ROUND — median P^2/4piA ${f(spots.medianIsoperimetric, 2)}, against " +
                "1.0 for a calibrated 40-cell disc (small blobs read slightly under 1; see calibration)"
        )
        check(
            worms.largestShare > 0.40 && maze.largestShare > 0.40,
            "gray-scott: both labyrinth presets are dominated by ONE connected structure — worms " +
                "${f(worms.largestShare)}, maze ${f(maze.largestShare)} of the covered area, against " +
                "${f(spots.largestShare)} for the spots"
        )
        check(
            maze.medianIsoperimetric > spots.medianIsoperimetric * 2.5 &&
                worms.medianIsoperimetric > spots.medianIsoperimetric * 2.5,
            "gray-scott: and they are ${f(maze.medianIsoperimetric / spots.medianIsoperimetric, 1)}x / " +
                "${f(worms.medianIsoperimetric / spots.medianIsoperimetric, 1)}x less round than the " +
                "spots (${f(maze.medianIsoperimetric, 1)}, ${f(worms.medianIsoperimetric, 1)} vs " +
                "${f(spots.medianIsoperimetric, 2)}) — the SAME code, two numbers changed"
        )
        check(
            holes.blobCount == 1 && holes.medianIsoperimetric > 20.0 && holes.coverage > 0.6,
            "gray-scott: the inverse morphology reads as one blob covering ${f(holes.coverage)} of the " +
                "field with an isoperimetric ratio of ${f(holes.medianIsoperimetric, 0)} — a filled " +
                "sheet perforated by holes, which is what an enormous ratio at high coverage means"
        )

        // Control arm 1: v = 0 is a fixed point, so an unseeded field never patterns at all.
        val unseeded = grayScottSeed(side, side, patches = 0).stepTimes(GRAY_SCOTT_WORMS, 500)
        check(
            unseeded.v.max() < 1e-6f,
            "gray-scott CONTROL: with no seed the autocatalyst stays at exactly 0 after 500 steps " +
                "(max ${unseeded.v.max()}) — the pattern comes from the dynamics, not from the renderer"
        )
        // Control arm 2: equal diffusion coefficients destroy the Turing instability's short-scale
        // advantage, so the same seed and the same reaction give nothing at all.
        val equalD = grayScottSeed(side, side, patches = 6, patchSize = 14, seed = 11L)
            .stepTimes(GrayScottParams(feed = 0.037, kill = 0.060, du = 0.08, dv = 0.08), steps)
        val equalReport = equalD.v.morphology()
        println("[alife-gallery]     CONTROL du==dv -> $equalReport")
        check(
            equalReport.coverage < 0.02 || equalReport.blobCount == 0,
            "gray-scott CONTROL: with Du == Dv the pattern does not survive (coverage " +
                "${f(equalReport.coverage, 4)}, ${equalReport.blobCount} blobs) — the morphology needs " +
                "the diffusion *ratio*, exactly as Turing's argument says"
        )

        panels.add("CONTROL no seed" to unseeded.v.colormap("magma", 0f, 0.5f).upscaleNearest(2))
        panels.add("CONTROL Du == Dv" to equalD.v.colormap("magma", 0f, 0.5f).upscaleNearest(2))
        montage(panels, columns = 4, cellSide = 256).savePng(File(outDir, "alife-gray-scott.png").absolutePath)

        // Division, measured: three seeded patches become twelve spots. Sampled finely enough to catch
        // the event rather than its aftermath — at 6000 steps the count has long since settled, which is
        // how the first version of this figure managed to show five identical frames.
        val strip = grayScottSeed(side, side, patches = 3, patchSize = 10, seed = 3L)
            .filmstrip(GRAY_SCOTT_MITOSIS, 160, 20)
        val counts = strip.map { it.v.threshold(0.15f).blobShapes(minArea = 4).size }
        println("[alife-gallery]     mitosis blob counts over 0..160 every 20: $counts")
        check(
            counts.first() <= 4 && counts.last() >= counts.first() * 3,
            "gray-scott: the 'mitosis' preset divides — ${counts.first()} seeded patches become " +
                "${counts.last()} spots over 160 steps, a measured ${f(counts.last().toDouble() / counts.first(), 1)}x, " +
                "with coverage still only ${f(strip.last().v.morphology().coverage, 3)} (so this is " +
                "division, not a growing stain)"
        )
        val risingIntervals = counts.zipWithNext().count { (a, b) -> b > a }
        check(
            counts.zipWithNext().all { (a, b) -> b >= a } && risingIntervals == 1,
            "gray-scott: and the count never falls, rising in exactly $risingIntervals of the eight " +
                "sampled intervals ($counts). So the honest description is a SINGLE synchronous " +
                "division — every square seed pinches into four spots inside the same 20-step window and " +
                "the field is then stable at that count. Not sustained mitosis, which is what the " +
                "preset's name would suggest and what a coarser sampling of this figure appeared to show"
        )
        montage(
            strip.mapIndexed { i, st ->
                "step ${i * 20}: ${counts[i]}" to st.v.colormap("magma", 0f, 0.5f).upscaleNearest(2)
            },
            columns = 5, cellSide = 256
        ).savePng(File(outDir, "alife-gray-scott-mitosis.png").absolutePath)
    }

    /**
     * Turing's prediction, tested: the wavelength an activator–inhibitor system selects grows with the
     * square root of the diffusion scale. Three runs from the same seed at three scales must produce a
     * **monotonically increasing** correlation length, and a shuffled field must produce ~0.
     */
    private fun figureTuring(outDir: File) {
        val scales = doubleArrayOf(0.3, 0.6, 1.2)
        val fields = scales.map {
            afield.stdlib.turingPattern(128, 128, scale = it, steps = 6000, rho = 0.010, seed = 5L)
        }
        val lengths = fields.map { it.correlationLength(maxLag = 40) }
        scales.forEachIndexed { i, sc ->
            println("[alife-gallery]     turing scale $sc -> correlation length ${f(lengths[i], 2)}")
        }
        check(
            lengths[0] < lengths[1] && lengths[1] < lengths[2],
            "turing: correlation length rises monotonically with diffusion scale — " +
                lengths.joinToString(" < ") { f(it, 2) } +
                " for scales ${scales.joinToString(", ")}"
        )
        val predicted = sqrt(scales[2] / scales[0])
        val observed = lengths[2] / lengths[0]
        check(
            observed > 1.3,
            "turing: over a ${f(scales[2] / scales[0], 1)}x range of diffusion the length scale grew " +
                "${f(observed, 2)}x (linear theory predicts ~${f(predicted, 2)}x for sqrt scaling)"
        )

        // Control: the same pixels, order destroyed. Correlation length must collapse — and it only does
        // because [radialAutocorrelation] samples on the lattice; with bilinear sampling the same
        // shuffled field reads ~2, which is the artefact this control caught.
        val shuffled = shuffleImg(fields[2], 42L)
        val shuffledLength = shuffled.correlationLength(maxLag = 40)
        check(
            shuffledLength < 1.0,
            "turing CONTROL: shuffling the same pixels drops the correlation length to " +
                "${f(shuffledLength, 3)} — the statistic reads spatial structure, not the histogram"
        )
        // The interpolation artefact, pinned at the level it actually happens: the lag-1 autocorrelation
        // of WHITE NOISE, which must be zero and is not when the sampler interpolates. Kept as a check so
        // the default cannot quietly go back to bilinear.
        val acNearest = shuffled.radialAutocorrelation(maxLag = 4)
        val acBilinear = shuffled.radialAutocorrelation(maxLag = 4, bilinear = true)
        println("[alife-gallery]     white-noise ac(1): nearest ${f(acNearest[1], 4)}, bilinear ${f(acBilinear[1], 4)}")
        check(
            abs(acNearest[1]) < 0.02 && acBilinear[1] > 5.0 * abs(acNearest[1]),
            "turing: on that same white noise the lag-1 autocorrelation is ${f(acNearest[1], 4)} with " +
                "lattice sampling (correct: zero) against ${f(acBilinear[1], 4)} with bilinear sampling, " +
                "${f(acBilinear[1] / abs(acNearest[1]), 0)}x larger — bilinear interpolation averages in " +
                "the centre pixel and so manufactures correlation from nothing. This is why the estimator " +
                "does not interpolate, and this check is why it cannot quietly start again"
        )

        montage(
            scales.mapIndexed { i, sc ->
                "scale $sc: len ${f(lengths[i], 2)}" to fields[i].normalized().colormap("viridis").upscaleNearest(2)
            } + listOf(
                "shuffled: len ${f(shuffledLength, 2)}" to shuffled.normalized().colormap("viridis").upscaleNearest(2)
            ),
            columns = 2, cellSide = 256
        ).savePng(File(outDir, "alife-turing.png").absolutePath)
    }

    // ==========================================================================================
    // 5 — Lenia: exact in the discrete limit, measured in the continuous one
    // ==========================================================================================

    /**
     * The cross-check that ties the family's two halves together: Lenia's discrete limit must reproduce
     * Conway's Life *bit for bit*. Two implementations that share no code — a sparse float convolution
     * with a growth mapping, and integer Moore-neighbour counting — must agree exactly for 20
     * generations, including on a glider's rigid translation.
     */
    private fun figureLenia(outDir: File) {
        val discreteRule = leniaFromLifeRule(LIFE)
        check(
            discreteRule.taps.size == 8 && abs(discreteRule.taps.weight.sum() - 1f) < 1e-6f,
            "lenia: the discrete-limit kernel is the 8-tap Moore ring, weights summing to 1 " +
                "(${discreteRule.taps.size} taps)"
        )

        var grid = afield.stdlib.LifePatterns.glider.centeredOn(32, 32, wrap = true)
        var field = grid.toImg(2)
        var worstDiff = 0.0
        for (g in 1..20) {
            grid = grid.step(LIFE)
            field = field.leniaTimes(discreteRule, 1)
            val d = fieldMaxAbsDiff(field, grid.toImg(2))
            if (d > worstDiff) worstDiff = d
        }
        check(
            worstDiff == 0.0,
            "lenia: 20 generations of continuous Lenia in its discrete limit are BIT-IDENTICAL to " +
                "20 generations of the integer Life stepper (worst per-cell difference $worstDiff)"
        )
        val leniaGrid = field.toIntGrid(0.5f, wrap = true)
        check(
            translationBetween(afield.stdlib.LifePatterns.glider.centeredOn(32, 32, wrap = true), leniaGrid)
                == (5 to 5),
            "lenia: and the glider the float stepper carried is a rigid (5, 5) translate after 20 " +
                "generations — 20/4 = 5 steps of (1, 1)"
        )

        // Control: shift the growth window by one neighbour count. Nothing else changes.
        val wrongLimit = leniaFromLifeRule(lifeRule("B4/S234"))
        val wrongField = afield.stdlib.LifePatterns.glider.centeredOn(32, 32).toImg(2)
            .leniaTimes(wrongLimit, 20)
        check(
            fieldMaxAbsDiff(wrongField, grid.toImg(2)) > 0.5,
            "lenia CONTROL: the same machinery with the growth window moved to B4/S234 diverges " +
                "completely — the agreement is about the rule, not about the plumbing"
        )

        // --- the continuous regime: a measured phase trichotomy along mu ---
        val side = 96
        val mus = doubleArrayOf(0.04, 0.06, 0.08, 0.10, 0.14)
        val runs = mus.map { mu ->
            val rule = LeniaPresets.ring(mu, sigma = 0.017, radius = 8, dt = 0.12)
            mu to leniaRun(leniaSeed(side, side, rule, blobRadii = 1.6, seed = 9L), rule, steps = 240)
        }
        runs.forEach { (mu, r) -> println("[alife-gallery]     lenia mu=$mu sigma=0.017 -> $r") }
        // A wide growth bell makes growth positive over almost the whole domain of `K * A`, so the field
        // pins at 1 — the third phase, and the arm that makes the trichotomy a trichotomy rather than a
        // pair. Same seed, same kernel; only sigma moved.
        val wideRule = LeniaPresets.ring(0.60, sigma = 0.60, radius = 8, dt = 0.12)
        val saturated = leniaRun(leniaSeed(side, side, wideRule, blobRadii = 1.6, seed = 9L), wideRule, steps = 240)
        println("[alife-gallery]     lenia mu=0.60 sigma=0.60 -> $saturated")
        val phases = runs.map { it.second.phase } + saturated.phase
        check(
            phases.contains(LeniaPhase.DEATH) &&
                phases.contains(LeniaPhase.BOUNDED) &&
                phases.contains(LeniaPhase.SATURATION),
            "lenia: the growth mapping's two parameters produce all three phases from ONE seed — " +
                phases.joinToString(", ") { it.name.lowercase() } +
                " (mu = ${mus.joinToString(", ")} at sigma 0.017, then mu 0.60 / sigma 0.60, whose " +
                "growth bell is positive over the whole unit interval)"
        )
        val bounded = runs.filter { it.second.phase == LeniaPhase.BOUNDED }
        check(
            bounded.isNotEmpty() && bounded.all { it.second.blobCount >= 1 && it.second.finalMass < 0.5 },
            "lenia: the bounded cells hold ${bounded.joinToString(", ") { "${it.second.blobCount}" }} " +
                "localised feature(s) at mean field ${bounded.joinToString(", ") { f(it.second.finalMass, 2) }} " +
                "— neither empty nor a filled field. NOTE these are fine-grained self-maintaining " +
                "textures, not the named solitons of the Lenia literature; this family does not ship a " +
                "published initial pattern and so does not claim one"
        )
        val dead = runs.filter { it.second.phase == LeniaPhase.DEATH }
        check(
            dead.isNotEmpty() && dead.all { it.second.finalMass < 0.005 },
            "lenia CONTROL: the death cells (mu = ${dead.joinToString(", ") { "${it.first}" }}) end at " +
                "mass < 0.005 from the identical seed — the bounded cells are a property of mu"
        )

        val panels = ArrayList<Pair<String, Img>>()
        // A FIXED 0..1 colour range, not the per-image auto range: a saturated field is constant at 1, so
        // an auto-ranged colormap maps it to the dark end and a SATURATION panel renders as black —
        // indistinguishable from DEATH, which is the opposite of what the figure is for.
        panels.add("discrete limit == Life" to field.upscaleNearest(8))
        panels.add("ring kernel R=8" to LeniaPresets.ring(0.15, radius = 8).kernelImg().normalized().upscaleNearest(10))
        runs.forEach { (mu, r) ->
            panels.add("mu=$mu ${r.phase.name.lowercase()}" to r.field.colormap("inferno", 0f, 1f).upscaleNearest(3))
        }
        panels.add("wide sigma: saturation" to saturated.field.colormap("inferno", 0f, 1f).upscaleNearest(3))
        montage(panels, columns = 4, cellSide = 280).savePng(File(outDir, "alife-lenia.png").absolutePath)
    }

    // ==========================================================================================
    // 6 — Boids: polarization far above the random baseline, and a control that is not
    // ==========================================================================================

    /**
     * A flock is not "dots that move together"; it is a population whose alignment order parameter is
     * far above the level independent headings would give, *and* whose nearest-neighbour distribution has
     * a floor. Both are checked against the same initial state run with one weight zeroed.
     */
    private fun figureBoids(outDir: File) {
        val n = 400
        val domain = 260.0
        val start = BoidFlock.random(n, domain, domain, speed = 1.4, seed = 4L)
        val baseline = afield.stdlib.randomPolarizationBaseline(n)
        val p0 = start.order()
        check(
            p0 < baseline * 4.0,
            "boids: the initial random population has polarization ${f(p0, 3)}, near the " +
                "${f(baseline, 3)} expected of ${n} independent headings"
        )

        val flock = start.copy()
        val params = BoidParams()
        flock.stepTimes(params, 900)
        val pFlock = flock.order()

        val noAlign = start.copy()
        noAlign.stepTimes(params.copy(alignmentWeight = 0.0), 900)
        val pNoAlign = noAlign.order()

        val noSep = start.copy()
        noSep.stepTimes(params.copy(separationWeight = 0.0), 900)

        println("[alife-gallery]     boids polarization: flock ${f(pFlock, 3)}, no-align ${f(pNoAlign, 3)}, baseline ${f(baseline, 3)}")
        // Bounded above by the number of independent flocks, not by 1: the population settles into
        // several separate flocks with different headings (which is correct boids behaviour — see the
        // figure), and the *global* order parameter of k independent flocks tops out near 1/sqrt(k). So
        // the claim is stated against the two things it must beat: the random baseline, and the
        // alignment-off twin.
        check(
            pFlock > 0.60 && pFlock > baseline * 10,
            "boids: after 900 steps the population's global polarization is ${f(pFlock, 3)} — " +
                "${f(pFlock / baseline, 1)}x the independent-heading baseline, in a state of several " +
                "separate flocks (so 1.0 is not the ceiling here)"
        )
        check(
            pNoAlign < pFlock * 0.4,
            "boids CONTROL: the identical initial state with alignmentWeight = 0 reaches only " +
                "${f(pNoAlign, 3)}, ${f(pFlock / pNoAlign, 1)}x lower — the order comes from the " +
                "alignment term and from nothing else that changed"
        )

        val dFlock = quantileOf(flock.neighborDistances(), 0.05)
        val dNoSep = quantileOf(noSep.neighborDistances(), 0.05)
        println("[alife-gallery]     boids 5th-pct nearest neighbour: flock ${f(dFlock, 2)}, no-separation ${f(dNoSep, 2)}")
        check(
            dFlock > 1.0,
            "boids: the 5th-percentile nearest-neighbour distance is ${f(dFlock, 2)} cells — " +
                "separation really does keep them apart"
        )
        check(
            dNoSep < dFlock * 0.6,
            "boids CONTROL: with separationWeight = 0 it collapses to ${f(dNoSep, 2)} — the floor is the " +
                "separation term, not an artefact of the domain size"
        )
        check(
            flock.meanSpeed() > params.maxSpeed * params.minSpeedFraction * 0.99,
            "boids: mean speed ${f(flock.meanSpeed(), 2)} of a ${params.maxSpeed} cap — the flock is " +
                "flying, not a frozen lattice with lucky headings (see BoidParams.minSpeedFraction: the " +
                "first run of this figure measured 0.46 here and was a stalled flock scoring well on " +
                "alignment)"
        )

        montage(
            listOf(
                "gen 0: order ${f(p0, 2)}" to start.toImg(1.6, tail = 5.0),
                "900 steps: order ${f(pFlock, 2)}" to flock.toImg(1.6, tail = 5.0),
                "no alignment: ${f(pNoAlign, 2)}" to noAlign.toImg(1.6, tail = 5.0),
                "no separation" to noSep.toImg(1.6, tail = 5.0)
            ),
            columns = 2, cellSide = 300
        ).savePng(File(outDir, "alife-boids.png").absolutePath)
    }

    // ==========================================================================================
    // 7 — Physarum: a network concentrates deposit; the sensing-off twin does not
    // ==========================================================================================

    /**
     * The stigmergy claim, measured: a sensing population funnels its deposit into filaments, so the
     * brightest 5 % of the trail holds most of the material. The identical population with sensing
     * switched off — same count, same seed, same deposit, same decay — spreads it out.
     */
    private fun figurePhysarum(outDir: File) {
        val side = 256
        val agents = 6000
        val steps = 400
        val p = PhysarumParams()
        val sensing = PhysarumSwarm.disc(side, side, agents, 0.55, seed = 6L).stepTimes(p, steps, seed = 6L)
        val blind = PhysarumSwarm.disc(side, side, agents, 0.55, seed = 6L)
            .stepTimes(p.copy(sensing = false), steps, seed = 6L)

        val sSense = sensing.trail.topShare(0.05)
        val sBlind = blind.trail.topShare(0.05)
        println("[alife-gallery]     physarum top-5% deposit share: sensing ${f(sSense, 3)}, blind ${f(sBlind, 3)}")
        check(
            sSense in 0.20..0.95,
            "physarum: the brightest 5% of the trail holds ${f(sSense, 3)} of all deposit, against the " +
                "0.05 a flat field gives — concentrated into filaments, but NOT the 1.000 that a " +
                "collapsed population reports (which is what the first run of this figure produced; see " +
                "PhysarumParams.exclusive)"
        )
        check(
            sBlind < sSense * 0.6,
            "physarum CONTROL: with sensing off the same agents concentrate only ${f(sBlind, 3)} — " +
                "${f(sSense / sBlind, 1)}x less. The network is built by the sensing, not by the deposit"
        )
        val filaments = sensing.trail.normalized().morphology()
        val blindShape = blind.trail.normalized().morphology()
        println("[alife-gallery]     physarum morphology: sensing $filaments")
        println("[alife-gallery]     physarum morphology: blind   $blindShape")
        check(
            filaments.coverage in 0.02..0.60,
            "physarum: the network covers ${f(filaments.coverage, 3)} of the field — a structure spread " +
                "over the domain, not a dot (a collapsed run reads under 0.005)"
        )
        check(
            filaments.medianIsoperimetric > 2.0 &&
                filaments.medianIsoperimetric > blindShape.medianIsoperimetric,
            "physarum: and its features are filamentary rather than blobby — median P^2/4piA " +
                "${f(filaments.medianIsoperimetric, 1)} against ${f(blindShape.medianIsoperimetric, 1)} " +
                "for the sensing-off control"
        )

        montage(
            listOf(
                "sensing: top-5% ${f(sSense, 2)}" to sensing.trail.normalized().colormap("magma"),
                "no sensing: ${f(sBlind, 2)}" to blind.trail.normalized().colormap("magma")
            ),
            columns = 2, cellSide = 380
        ).savePng(File(outDir, "alife-physarum.png").absolutePath)
    }

    // ==========================================================================================
    // 8 — DLA: a mass dimension of ~1.7, and two controls at 2
    // ==========================================================================================

    /**
     * The one system in the family with a quantitative literature value to hit. DLA in the plane has a
     * mass dimension of about 1.71; an Eden cluster (uniform growth site) and a random sprinkle both
     * have exactly 2. Same estimator, same code path, three objects.
     */
    private fun figureDla(outDir: File) {
        val side = 340
        val dla = dlaCluster(side, side, particles = 9000, seed = 2L)
        println("[alife-gallery]     $dla")
        val fit = dla.fractalDimension(rMin = 5.0, rMaxFraction = 0.75)
        println("[alife-gallery]     DLA dimension ${f(fit.dimension, 3)} (r²=${f(fit.rSquared, 4)})")
        check(
            fit.rSquared > 0.99,
            "dla: log N(r) against log r is a straight line over the fitted range (r² = " +
                "${f(fit.rSquared, 4)}) — so a single slope is a meaningful description"
        )
        check(
            fit.dimension in 1.50..1.80,
            "dla: the mass dimension is ${f(fit.dimension, 3)}. The asymptotic literature value for " +
                "planar DLA is ~1.71 and lattice clusters of this size are known to read below it, so " +
                "this is stated as 'clearly between 1.5 and 1.8, and clearly not 2' rather than as a " +
                "match — nothing in the code mentions branching either way"
        )

        val eden = edenCluster(side, side, particles = dla.points.size, seed = 2L)
        val edenFit = eden.fractalDimension(rMin = 5.0, rMaxFraction = 0.75)
        println("[alife-gallery]     Eden dimension ${f(edenFit.dimension, 3)} (r²=${f(edenFit.rSquared, 4)})")
        check(
            edenFit.dimension > 1.85,
            "dla CONTROL: an Eden cluster of the same size grown from uniformly-chosen sites measures " +
                "${f(edenFit.dimension, 3)} — compact, dimension ~2. Only the diffusing walker makes it 1.7"
        )
        val sprinkle = sprinkleCluster(side, side, dla.points.size, dla.maxRadius, seed = 2L)
        // minCount raises the inner fit radius until the count is worth fitting. Without it this control
        // measured 2.32 for a uniform disc, which is not a possible answer — the fit was starting where
        // N(r) was 3 points. See massRadiusDimension's `minCount`.
        val sprinkleFit = massRadiusDimension(
            sprinkle.points, sprinkle.center,
            rMin = 5.0, rMax = sprinkle.maxRadius * 0.75, minCount = 60
        )
        println("[alife-gallery]     sprinkle dimension ${f(sprinkleFit.dimension, 3)} (r²=${f(sprinkleFit.rSquared, 4)})")
        check(
            abs(sprinkleFit.dimension - 2.0) < 0.12,
            "dla CONTROL: a uniform sprinkle over the same disc measures " +
                "${f(sprinkleFit.dimension, 3)} against the exact 2.0 it must have — so the estimator is " +
                "not simply reading the support"
        )
        check(
            edenFit.dimension - fit.dimension > 0.15,
            "dla: DLA and Eden differ by ${f(edenFit.dimension - fit.dimension, 2)} in dimension from " +
                "the same estimator, the same particle count and the same seed"
        )

        montage(
            listOf(
                "DLA: D = ${f(fit.dimension, 2)}" to dla.arrivalOrderImg().colormap("turbo"),
                "Eden: D = ${f(edenFit.dimension, 2)}" to eden.arrivalOrderImg().colormap("turbo"),
                "sprinkle: D = ${f(sprinkleFit.dimension, 2)}" to sprinkle.mask,
                "log N(r) vs log r" to plotLogLog(listOf(fit, edenFit, sprinkleFit), 400, 300)
            ),
            columns = 2, cellSide = 320
        ).savePng(File(outDir, "alife-dla.png").absolutePath)
    }

    // ==========================================================================================
    // 9 — L-systems: exact combinatorial and geometric identities
    // ==========================================================================================

    /**
     * The family's exact half: Fibonacci derivation lengths, `3 * 4^n` forward symbols, a turtle path
     * that closes to floating-point zero, a length that grows by exactly `4/3` per generation, and an
     * enclosed area matching the closed form for the Koch snowflake.
     */
    private fun figureLSystem(outDir: File) {
        // Lindenmayer's algae: generation lengths are the Fibonacci numbers.
        val lengths = LSYSTEM_ALGAE.derivations(9).map { it.length }
        val fib = listOf(1, 2, 3, 5, 8, 13, 21, 34, 55, 89)
        check(
            lengths == fib,
            "l-system: the algae system's derivation lengths are exactly the Fibonacci numbers — " +
                "$lengths"
        )

        // Koch snowflake: 3 * 4^n forward symbols, exactly.
        val kochCounts = (0..5).map { g -> LSYSTEM_KOCH_SNOWFLAKE.derive(g).count { it == 'F' } }
        val expected = (0..5).map { 3 * Math.pow(4.0, it.toDouble()).toInt() }
        check(
            kochCounts == expected,
            "l-system: the Koch snowflake has exactly 3*4^n forward symbols — $kochCounts"
        )

        // The turtle: the path closes, its length grows by 4/3, and its area matches the closed form.
        val step = 1.0
        val tp = TurtleParams(angle = Math.PI / 3.0, step = step)
        val results = (0..5).map { drawLSystem(LSYSTEM_KOCH_SNOWFLAKE, it, tp) }
        val closures = results.map { afield.stdlib.turtleClosureError(it, step) }
        // FLine stores coordinates as single-precision floats, and generation 5 spans 3^5 = 243 steps, so
        // "exact" here means to float precision. Stated rather than papered over with a loose tolerance.
        check(
            closures.all { it < 1e-5 },
            "l-system: every Koch snowflake generation's turtle path CLOSES — worst residual " +
                "${"%.3e".format(closures.max())} of a step (FLine coordinates are 32-bit floats)"
        )
        val lens = results.map { flineTotalLength(it.lines) }
        val ratios = (1..5).map { lens[it] / lens[it - 1] }
        // The textbook 4/3 is the ratio when the figure is held at constant *diameter*. This turtle holds
        // the *step* constant, so the figure triples in size each generation and the drawn length
        // quadruples exactly. Same identity, stated for what is actually drawn.
        check(
            ratios.all { abs(it - 4.0) < 1e-5 },
            "l-system: total drawn length grows by exactly 4x per generation at constant step size " +
                "(= the textbook 4/3 once the 3x linear growth is divided out) — " +
                ratios.joinToString(", ") { f(it, 6) }
        )
        // Area: A_n = 9^n * (sqrt(3)/4 * s^2) * (1 + 3/5 * (1 - (4/9)^n)), the closed form, with the 9^n
        // because the figure grows rather than the segments shrinking.
        val areaErrors = (0..5).map { n ->
            val a0 = sqrt(3.0) / 4.0 * step * step
            val predicted = Math.pow(9.0, n.toDouble()) * a0 *
                (1.0 + 0.6 * (1.0 - Math.pow(4.0 / 9.0, n.toDouble())))
            val measured = abs(flineSignedArea(results[n].lines))
            abs(measured - predicted) / predicted
        }
        println("[alife-gallery]     Koch area relative errors: " + areaErrors.joinToString(", ") { "%.2e".format(it) })
        check(
            areaErrors.all { it < 1e-4 },
            "l-system: and the enclosed area matches the closed form 9^n * A0 * (1 + 3/5(1 - (4/9)^n)) " +
                "at every generation to ${"%.1e".format(areaErrors.max())} relative — a second, " +
                "independent geometric identity from the same turtle"
        )
        // Control: one wrong turn and none of that holds.
        val wrongAngle = drawLSystem(LSYSTEM_KOCH_SNOWFLAKE, 4, tp.copy(angle = Math.PI / 3.0 + 0.01))
        check(
            afield.stdlib.turtleClosureError(wrongAngle, step) > 1.0,
            "l-system CONTROL: perturbing the turn angle by 0.01 rad leaves the path open by " +
                "${f(afield.stdlib.turtleClosureError(wrongAngle, step), 1)} steps — the closure test has teeth"
        )

        // Stochastic productions really do change topology, not merely coordinates.
        val plants = (1L..4L).map { drawLSystem(LSYSTEM_STOCHASTIC_PLANT, 5, TurtleParams(angle = Math.toRadians(24.0), step = 4.0), seed = it) }
        val branchCounts = plants.map { it.lines.size }
        check(
            branchCounts.distinct().size >= 3,
            "l-system: four seeds of the stochastic plant give ${branchCounts.distinct().size} distinct " +
                "branch counts ($branchCounts) — the variation is topological, not a jittered copy"
        )

        // `LSYSTEM_SIERPINSKI_ARROWHEAD` draws on A and B, not on F — with the default alphabet it
        // renders a blank panel, which is exactly what the first run of this figure produced.
        val arrowhead = drawLSystem(
            LSYSTEM_SIERPINSKI_ARROWHEAD, 7,
            TurtleParams(angle = Math.PI / 3.0, drawSymbols = "AB")
        )
        check(
            arrowhead.drawnSegments == 3 * Math.pow(3.0, 7.0).toInt() / 3,
            "l-system: the arrowhead curve at generation 7 draws ${arrowhead.drawnSegments} segments " +
                "(3^7 = 2187) — with the default F/G alphabet it would draw 0, which is why " +
                "TurtleParams.drawSymbols exists"
        )
        val tree = drawLSystem(LSYSTEM_BUSH, 5, TurtleParams(angle = Math.toRadians(22.5), step = 3.0), seed = 1L)
        montage(
            listOf(
                "Koch snowflake gen 4" to rasterizeFLines(results[4].lines, 300, 300),
                "Sierpinski arrowhead gen 7" to rasterizeFLines(arrowhead.lines, 300, 300),
                "dragon curve gen 12" to rasterizeFLines(drawLSystem(LSYSTEM_DRAGON, 12, TurtleParams(angle = Math.PI / 2.0)).lines, 300, 300),
                "bush gen 5: ${tree.lines.size} branches" to rasterizeFLines(tree.lines, 300, 300)
            ) + plants.mapIndexed { i, pl -> "plant seed ${i + 1}: ${pl.lines.size}" to rasterizeFLines(pl.lines, 240, 300) },
            columns = 4, cellSide = 280
        ).savePng(File(outDir, "alife-lsystem.png").absolutePath)
    }

    /**
     * Space colonization: the form is the space. Grown into an annulus — a shape no upward-with-noise
     * heuristic reaches — the structure must consume nearly all of it, and must be an actual tree.
     */
    private fun figureColonization(outDir: File) {
        // The root must start within influenceRadius of the nearest attractor or nothing grows at all —
        // the first run of this figure put the trunk 45 cells from a 30-cell influence radius and
        // produced a bare stick, which the "0.000 consumed" measurement caught.
        val disc = discAttractors(1400, 150.0, 140.0, 105.0, seed = 3L)
        val discTree = spaceColonization(disc, 150.0, 265.0, influenceRadius = 30.0, killRadius = 7.0, stepSize = 5.0)
        val ring = annulusAttractors(1400, 150.0, 140.0, 60.0, 120.0, seed = 3L)
        val ringTree = spaceColonization(ring, 150.0, 285.0, influenceRadius = 30.0, killRadius = 7.0, stepSize = 5.0)
        println("[alife-gallery]     $discTree / $ringTree")
        check(
            discTree.isTree() && ringTree.isTree(),
            "colonization: both structures are trees — one root, n-1 edges, no cycles (a growth " +
                "algorithm producing a cycle looks identical in a picture)"
        )
        check(
            discTree.consumedAttractors > 1400 * 0.9,
            "colonization: the disc cloud is ${f(discTree.consumedAttractors / 1400.0)} consumed — the " +
                "structure really did reach its space"
        )
        check(
            ringTree.consumedAttractors > 1400 * 0.85,
            "colonization: and so is the annulus (${f(ringTree.consumedAttractors / 1400.0)}), which " +
                "cannot be filled by growing straight"
        )
        val emptyTree = spaceColonization(emptyList(), 150.0, 285.0)
        check(
            emptyTree.nodeCount == 2,
            "colonization CONTROL: with no attractors nothing grows beyond the initial trunk " +
                "(${emptyTree.nodeCount} nodes) — the branching is driven by the cloud"
        )
        montage(
            listOf(
                "disc: ${discTree.nodeCount} nodes" to rasterizeFLines(discTree.toFLines(), 320, 320),
                "annulus: ${ringTree.nodeCount} nodes" to rasterizeFLines(ringTree.toFLines(), 320, 320)
            ),
            columns = 2, cellSide = 320
        ).savePng(File(outDir, "alife-colonization.png").absolutePath)
    }

    // ==========================================================================================
    // 10 — 3-D CA out to a mesh
    // ==========================================================================================

    /**
     * The bridge to `mesh_sdf.kt`: a 3-D life-like automaton's occupancy marched into a [TriMesh]. The
     * claim checked is the one that matters for a mesh — it is **watertight**, with an even Euler
     * characteristic — because a leaking mesher produces a picture that looks fine and a model that
     * cannot be printed, booleaned or subdivided.
     */
    private fun figureVolume(outDir: File) {
        val rule = LIFE3D_CLOUDS
        val volume = IntVolume.randomBall(56, 56, 56, density = 0.55, ballFraction = 0.70, seed = 8L)
            .stepTimes(rule, 6)
        println("[alife-gallery]     3-D CA volume: $volume under ${rule.notation}")
        check(
            volume.population() > 4000 && volume.population() < volume.size / 2,
            "3d-ca: after 6 generations of ${rule.notation} the volume holds " +
                "${volume.population()} of ${volume.size} voxels — it neither died nor filled the box"
        )
        val mesh = volume.toMesh(resolution = 110, smoothRadius = 1)
        println("[alife-gallery]     marched mesh: ${mesh.triangleCount} triangles, chi=${mesh.eulerCharacteristic()}")
        check(
            mesh.triangleCount > 2000,
            "3d-ca: the marched surface has ${mesh.triangleCount} triangles"
        )
        check(
            mesh.triangleCount > 0 && mesh.isWatertight(),
            "3d-ca: and it is watertight — every edge is shared by exactly two triangles, so the " +
                "automaton's surface is a closed solid. (The triangleCount guard is not redundant: an " +
                "EMPTY mesh is vacuously watertight, and the first run of this figure passed that way.)"
        )
        check(
            mesh.eulerCharacteristic() % 2 == 0,
            "3d-ca: Euler characteristic ${mesh.eulerCharacteristic()} is even, as a closed orientable " +
                "surface's must be"
        )

        montage(
            listOf(
                "z = 28 slice" to volume.slice(28).toImg(2).upscaleNearest(4),
                "depth projection (all z)" to volume.depthProjection().colormap("viridis").upscaleNearest(4),
                "marched: ${mesh.triangleCount} tris, chi=${mesh.eulerCharacteristic()}"
                    to meshDepthImg(mesh, 260)
            ),
            columns = 3, cellSide = 260
        ).savePng(File(outDir, "alife-3d-ca.png").absolutePath)
    }

    // ==========================================================================================
    // Small plotting / rendering helpers (AWT-free, like the rest of the imaging family)
    // ==========================================================================================

    /** A minimal line plot of [values] against index, as a 1-channel [Img]. */
    private fun plotSeries(values: List<Double>, w: Int, h: Int): Img {
        val out = Img.zeros(w, h, 1)
        if (values.isEmpty()) return out
        val mx = values.max().coerceAtLeast(1e-9)
        var prevX = 0
        var prevY = h - 1
        for (i in values.indices) {
            val x = (i.toDouble() / (values.size - 1).coerceAtLeast(1) * (w - 1)).toInt()
            val y = ((1.0 - values[i] / mx) * (h - 1)).toInt().coerceIn(0, h - 1)
            drawLine(out, prevX, prevY, x, y)
            prevX = x; prevY = y
        }
        return out.withLabel("max ${"%.0f".format(mx)}", 4, 4, 1)
    }

    /** A log–log scatter of several [afield.stdlib.MassRadiusFit]s, one intensity band each. */
    private fun plotLogLog(fits: List<afield.stdlib.MassRadiusFit>, w: Int, h: Int): Img {
        val out = Img.zeros(w, h, 1)
        val all = fits.flatMap { it.points }
        if (all.isEmpty()) return out
        val x0 = all.minOf { it.first }; val x1 = all.maxOf { it.first }
        val y0 = all.minOf { it.second }; val y1 = all.maxOf { it.second }
        fits.forEachIndexed { k, fit ->
            val v = 1f - k * 0.3f
            var px = -1; var py = -1
            for ((lx, ly) in fit.points) {
                val x = ((lx - x0) / (x1 - x0 + 1e-9) * (w - 9)).toInt() + 4
                val y = h - 5 - ((ly - y0) / (y1 - y0 + 1e-9) * (h - 9)).toInt()
                if (px >= 0) drawLine(out, px, py, x, y, v)
                px = x; py = y
            }
        }
        return out
    }

    /** Bresenham stroke into a 1-channel [Img]. */
    private fun drawLine(img: Img, ax: Int, ay: Int, bx: Int, by: Int, v: Float = 1f) {
        var x0 = ax; var y0 = ay
        val dx = abs(bx - x0); val sx = if (x0 < bx) 1 else -1
        val dy = -abs(by - y0); val sy = if (y0 < by) 1 else -1
        var err = dx + dy
        while (true) {
            if (x0 in 0 until img.width && y0 in 0 until img.height) img.data[y0 * img.width + x0] = v
            if (x0 == bx && y0 == by) break
            val e2 = 2 * err
            if (e2 >= dy) { err += dy; x0 += sx }
            if (e2 <= dx) { err += dx; y0 += sy }
        }
    }

    /**
     * A depth image of a mesh by orthographic z-buffering — enough to see that a marched automaton has
     * the shape claimed, without a renderer or a GPU.
     */
    private fun meshDepthImg(mesh: afield.stdlib.TriMesh, side: Int): Img {
        val out = Img.zeros(side, side, 1)
        if (mesh.vertexCount == 0) return out
        val depth = FloatArray(side * side) { Float.MAX_VALUE }
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        for (i in 0 until mesh.vertexCount) {
            val px = mesh.x(i); val py = mesh.y(i); val pz = mesh.z(i)
            if (px < minX) minX = px
            if (px > maxX) maxX = px
            if (py < minY) minY = py
            if (py > maxY) maxY = py
            if (pz < minZ) minZ = pz
            if (pz > maxZ) maxZ = pz
        }
        val s = (side - 8) / kotlin.math.max(maxX - minX, maxY - minY).coerceAtLeast(1e-6f)
        for (i in 0 until mesh.vertexCount) {
            val px = ((mesh.x(i) - minX) * s + 4).toInt()
            val py = ((mesh.y(i) - minY) * s + 4).toInt()
            if (px !in 0 until side || py !in 0 until side) continue
            val j = py * side + px
            if (mesh.z(i) < depth[j]) depth[j] = mesh.z(i)
        }
        for (i in depth.indices) {
            out.data[i] = if (depth[i] == Float.MAX_VALUE) 0f
            else 0.25f + 0.75f * (1f - (depth[i] - minZ) / (maxZ - minZ).coerceAtLeast(1e-6f))
        }
        return out
    }

    /** The same pixels in a random order — the control for any spatial-structure statistic. */
    private fun shuffleImg(img: Img, seed: Long): Img {
        val rng = kotlin.random.Random(seed)
        val d = img.data.copyOf()
        for (i in d.indices.reversed()) {
            val j = rng.nextInt(i + 1)
            val t = d[i]; d[i] = d[j]; d[j] = t
        }
        return Img(img.width, img.height, img.channels, d)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val outDir = File(
            args.getOrNull(0) ?: System.getenv("ALIFE_GALLERY_DIR")
                ?: java.nio.file.Files.createTempDirectory("alife-gallery").toFile().absolutePath
        )
        outDir.mkdirs()

        val stages: List<Pair<String, (File) -> Unit>> = listOf(
            "calibration" to ::figureCalibration,
            "life" to ::figureLife,
            "elementary" to ::figureElementary,
            "cyclic" to ::figureCyclic,
            "diffusion" to ::figureDiffusion,
            "gray-scott" to ::figureGrayScott,
            "turing" to ::figureTuring,
            "lenia" to ::figureLenia,
            "boids" to ::figureBoids,
            "physarum" to ::figurePhysarum,
            "dla" to ::figureDla,
            "l-system" to ::figureLSystem,
            "colonization" to ::figureColonization,
            "3d-ca" to ::figureVolume
        )
        // Iterating on one figure otherwise means waiting for the other thirteen. Development only: an
        // unfiltered run is the default and is what the FIELD_TEST does.
        val only = System.getenv("ALIFE_GALLERY_ONLY")
            ?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.toSet()
        only?.let { println("[alife-gallery] FILTERED to ${it.joinToString()} — not a full run") }
        for ((name, stage) in stages) {
            if (only != null && name !in only) continue
            val t0 = System.currentTimeMillis()
            println("[alife-gallery] --- $name ---")
            stage(outDir)
            println("[alife-gallery] --- $name done in ${System.currentTimeMillis() - t0} ms ---")
        }

        if (failures.isNotEmpty()) {
            System.err.println("AlifeGallery FAILED with ${failures.size} problem(s):")
            failures.forEach { System.err.println("  - $it") }
            exitProcess(1)
        }
        println("alife gallery rendered to ${outDir.absolutePath}")
    }
}
