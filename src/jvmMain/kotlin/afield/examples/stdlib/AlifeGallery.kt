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
import afield.stdlib.isoContourFLines
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
import kotlin.math.E
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.system.exitProcess

/*
FIELD_TEST
id: stdlib.alife.gallery
runner: jvm-main
entrypoint: afield.examples.stdlib.AlifeGallery
args:
  - "--tier"
  - "fast"
tags: [smoke, stdlib, alife, capture, cpu]
timeoutSec: 180
expect:
  exitCode: 0
  stdoutContains:
    - "alife gallery rendered"
*/

/*
FIELD_TEST
id: stdlib.alife.gallery.slow
runner: jvm-main
entrypoint: afield.examples.stdlib.AlifeGallery
args:
  - "--tier"
  - "slow"
tags: [stdlib, alife, capture, cpu, slow]
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
 *  - Lenia's discrete limit must reproduce Conway's Life *bit for bit* — two different update
 *    mechanisms, one float-convolution-plus-additive-growth and one integer neighbour count, driven from
 *    the same `B3/S23` table (they are not independent *rules*, and this file used to say they shared no
 *    code, which was wrong — see [figureLenia]);
 *  - a flock's polarization must be far above the random-heading baseline, and its alignment-off twin
 *    must not be;
 *  - a Physarum network must concentrate its deposit far more than its sensing-off twin;
 *  - a DLA cluster's mass dimension must be ~1.7 while an Eden cluster's and a random sprinkle's are ~2.
 *
 * The statistics those claims are made through are themselves checked, in two layers that run first and
 * fail loudly, because a wrong estimator makes every downstream number meaningless (and two of them
 * *were* wrong in the first version of this family):
 *
 *  - [figureCalibration] measures a synthetic disc and a thin annulus, to show that [blobShapes] scores
 *    a round object near 1 and a filamentary one near 40, and that [massRadiusDimension] scores a filled
 *    disc near 2;
 *  - [figureMeasurement] pins the same two estimators against **closed-form** answers — an uncorrelated
 *    field's `1/sqrt(N)` noise band, the `(1 - 1/e)` interpolation floor, a checkerboard's exactly
 *    `-1/3` lag-1 autocorrelation, proportionality of correlation length to wavelength, and recovery of
 *    a *prescribed* mass–radius exponent at `D = 1.00, 1.26, 1.71, 2.00`.
 *
 * **Two tiers.** `--tier fast` (the `stdlib.alife.gallery` FIELD_TEST, 11 figures, ~6 s) is everything whose
 * evidence is an exact identity or a closed-form comparison. `--tier slow`
 * (`stdlib.alife.gallery.slow`, ~25 s) is the seven figures that integrate something to a statistical
 * verdict. `--tier all` is the default when run by hand. See [Tier].
 *
 * As a FIELD_TEST it renders to a temp dir and writes no committed assets. Pass `--out DIR` (or a bare
 * first argument, or set `ALIFE_GALLERY_DIR`) to put the PNGs somewhere that survives, and `--only
 * name,name` to iterate on one figure.
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
    // 0b — The two estimators pinned against closed-form answers
    // ==========================================================================================

    /**
     * Pins the family's two *derived* statistics — [correlationLength]/[radialAutocorrelation] and
     * [massRadiusDimension] — against inputs whose answers are known in closed form.
     *
     * [figureCalibration] shows that the estimators read the right thing for a disc. That is one point
     * of one curve, and a disc is the easiest case there is. This figure exists because a measurement
     * function that "does not fail now" is not the same as a correct one, and two of the numbers this
     * family reports (a correlation length, a fractal dimension) were *wrong* in the first version and
     * were only caught because a control arm happened to disagree. The fix for that is not a better
     * control arm; it is an oracle.
     *
     * Four analytic cases, none of them involving an automaton:
     *
     *  1. **Uncorrelated noise.** The true autocorrelation is zero at every non-zero lag, so the
     *     estimator's lag-1 reading must sit inside its own sampling noise band — and that band has a
     *     closed form, `1/sqrt(N)` for `N` sampled sites. This replaces the magic `< 0.02` the Turing
     *     figure used to carry (which was a 1.3-sigma bound at its sample count, i.e. a bound that a
     *     *correct* estimator fails about 20 % of the time).
     *  2. **The interpolation floor.** With zero correlation, `correlationLength` reduces to
     *     `(1 - 1/e) / (1 - ac(1))` — the linear interpolation between lag 0 and lag 1. So the floor of
     *     this statistic is **0.632, not 0**, which is the number every "a structureless field reads
     *     low" claim in this family has to be compared against. Asserted to `1e-9`.
     *  3. **A checkerboard.** Its autocorrelation at the 8 Moore offsets is `+1` on the diagonals and
     *     `-1` on the axes. Of the 24 sampled directions, rounding sends 8 to a diagonal and 16 to an
     *     axis, so the lag-1 reading is **exactly `-1/3`** and the correlation length is exactly
     *     `(1 - 1/e) / (4/3) = 0.4741`. Any change to the direction count or the offset rounding moves
     *     this, which is the point.
     *  4. **Sinusoidal stripes.** For a pattern of wavelength `λ` the correlation length must be
     *     *proportional to* `λ` — that proportionality is the entire basis of the Turing figure's
     *     "the length scale grows with the diffusion scale" claim, and it had never been checked.
     *
     * And for the dimension estimator, point sets with an **exactly prescribed** mass–radius exponent:
     * `n` points at radii `R·(i/n)^(1/D)` and uniformly random angles give `N(r) = n·(r/R)^D` by
     * construction, for any real `D`. Recovering `D = 1.00, 1.26, 1.71, 2.00` from those pins down the
     * question the DLA figure cannot otherwise answer: **is 1.617 the cluster, or the estimator reading
     * low?** If the estimator returns 1.71 when handed a set that has 1.71, the answer is "the cluster".
     */
    private fun figureMeasurement(outDir: File) {
        val side = 192

        // ---- 1 & 2: uncorrelated noise, and the interpolation floor ----------------------------
        val rng = kotlin.random.Random(20260729L)
        val noise = Img.zeros(side, side, 1)
        for (i in noise.data.indices) noise.data[i] = rng.nextFloat()
        // stride 1 so N is the whole field: the noise band is 1/sqrt(N) and a tighter band makes a
        // sharper statement about the bilinear bias below.
        val acNoise = noise.radialAutocorrelation(maxLag = 8, stride = 1)
        val sigma = 1.0 / side.toDouble()   // 1/sqrt(N) with N = side*side sampled sites
        println("[alife-gallery]     oracle: white-noise ac = " + (1..6).joinToString(", ") { f(acNoise[it], 4) } +
            " (sampling sigma ${f(sigma, 4)})")
        check(
            (1..6).all { abs(acNoise[it]) < 3.0 * sigma },
            "oracle: on uncorrelated noise the autocorrelation estimator reads " +
                "|ac| <= ${f((1..6).maxOf { abs(acNoise[it]) }, 4)} at every lag 1..6, inside the " +
                "3-sigma sampling band ${f(3.0 * sigma, 4)} that ${side * side} samples give (theory: " +
                "exactly zero). The bound is 1/sqrt(N), not a number chosen to pass"
        )
        val noiseLength = noise.correlationLength(maxLag = 8, stride = 1)
        val floorClosedForm = (1.0 - 1.0 / E) / (1.0 - acNoise[1])
        println("[alife-gallery]     oracle: white-noise correlation length ${f(noiseLength, 6)}, " +
            "closed form ${f(floorClosedForm, 6)}")
        check(
            abs(noiseLength - floorClosedForm) < 1e-9,
            "oracle: and its correlation length is EXACTLY the interpolation floor " +
                "(1 - 1/e)/(1 - ac(1)) = ${f(floorClosedForm, 6)} (measured ${f(noiseLength, 6)}). So " +
                "the floor of this statistic is 0.632, NOT 0 — which is the number every " +
                "'a structureless field reads low' claim in this family must be compared against"
        )
        // The interpolation artefact, as a *bias* rather than a magnitude: bilinear sampling at a
        // non-integer offset mixes in the centre pixel, so its lag-1 reading is systematically POSITIVE
        // and far outside the sampling band that the lattice sampler sits inside.
        val acBilinear = noise.radialAutocorrelation(maxLag = 8, stride = 1, bilinear = true)
        println("[alife-gallery]     oracle: bilinear white-noise ac(1) ${f(acBilinear[1], 4)}")
        check(
            acBilinear[1] > 3.0 * sigma && abs(acNoise[1]) < 3.0 * sigma,
            "oracle CONTROL: the same field sampled bilinearly reads ${f(acBilinear[1], 4)} at lag 1 — " +
                "${f(acBilinear[1] / (3.0 * sigma), 1)}x outside the 3-sigma band, while the lattice " +
                "sampler is inside it. Bilinear interpolation averages in the centre pixel and so " +
                "manufactures correlation from nothing; this is a systematic bias, not bad luck"
        )

        // ---- 3: a checkerboard, whose lag-1 autocorrelation is exactly -1/3 --------------------
        val checker = Img.generate(side, side) { x, y -> if ((x + y) % 2 == 0) 1f else 0f }
        val acChecker = checker.radialAutocorrelation(maxLag = 4, stride = 1)
        println("[alife-gallery]     oracle: checkerboard ac(1) ${f(acChecker[1], 6)} (theory -1/3)")
        check(
            abs(acChecker[1] + 1.0 / 3.0) < 1e-6,
            "oracle: a checkerboard's lag-1 autocorrelation is ${f(acChecker[1], 6)}, exactly the " +
                "-1/3 theory gives: +1 on the four diagonal Moore offsets and -1 on the four axial " +
                "ones, and rounding sends 8 of the 24 sampled directions to a diagonal and 16 to an axis"
        )
        val checkerLength = checker.correlationLength(maxLag = 4, stride = 1)
        val checkerClosedForm = (1.0 - 1.0 / E) / (4.0 / 3.0)
        check(
            abs(checkerLength - checkerClosedForm) < 1e-6,
            "oracle: so its correlation length is exactly (1 - 1/e)/(4/3) = " +
                "${f(checkerClosedForm, 6)} (measured ${f(checkerLength, 6)}) — the smallest reading " +
                "this statistic can produce, from the shortest-wavelength pattern a lattice holds"
        )

        // ---- 4: correlation length is PROPORTIONAL to wavelength -------------------------------
        // The Turing figure's claim is that this statistic reads out a length scale. That is only true
        // if it is linear in the wavelength, which had never been checked. 192 is a multiple of all
        // three wavelengths, so the toroidal wrap is consistent and no seam contributes.
        val waves = intArrayOf(12, 24, 48)
        val waveLengths = waves.map { lambda ->
            val stripes = Img.generate(side, side) { x, _ ->
                (0.5 + 0.5 * cos(2.0 * PI * x / lambda)).toFloat()
            }
            stripes.correlationLength(maxLag = 40, stride = 1)
        }
        val perLambda = waves.indices.map { waveLengths[it] / waves[it] }
        println("[alife-gallery]     oracle: stripe wavelengths ${waves.joinToString()} -> lengths " +
            waveLengths.joinToString(", ") { f(it, 3) } + ", length/lambda " +
            perLambda.joinToString(", ") { f(it, 4) })
        val meanPerLambda = perLambda.average()
        check(
            perLambda.all { abs(it - meanPerLambda) / meanPerLambda < 0.06 },
            "oracle: the correlation length is PROPORTIONAL to wavelength — length/lambda is " +
                perLambda.joinToString(", ") { f(it, 4) } + " across a 4x range of lambda, a spread of " +
                "${f(perLambda.indices.maxOf { abs(perLambda[it] - meanPerLambda) / meanPerLambda } * 100, 1)}% " +
                "about ${f(meanPerLambda, 4)}. That proportionality is what makes 'the length scale grew " +
                "3.1x' in the Turing figure a statement about the pattern rather than about the estimator"
        )
        check(
            waveLengths[0] > noiseLength * 2.0,
            "oracle: and even the shortest stripe pattern (lambda 12, length ${f(waveLengths[0], 2)}) " +
                "reads ${f(waveLengths[0] / noiseLength, 1)}x above the ${f(noiseLength, 3)} floor, so " +
                "the statistic has range above its own floor"
        )

        // ---- 5: the dimension estimator against exactly-prescribed exponents -------------------
        // N(r) = n * (r / R)^D holds by construction for radii R*(i/n)^(1/D): the i-th point is at
        // exactly the radius enclosing i points. So this is an oracle for any real D, not just for the
        // two integers a disc and a line give.
        val targets = doubleArrayOf(1.00, 1.2619, 1.71, 2.00)
        val recovered = ArrayList<afield.stdlib.MassRadiusFit>()
        for ((k, d) in targets.withIndex()) {
            val pts = powerLawPoints(60_000, d, 400.0, Vec2(512f, 512f), seed = 100L + k)
            val fit = massRadiusDimension(pts, Vec2(512f, 512f), rMin = 12.0, rMax = 300.0, samples = 24)
            recovered.add(fit)
            println("[alife-gallery]     oracle: prescribed D=$d -> measured ${f(fit.dimension, 4)} " +
                "(r2=${f(fit.rSquared, 6)})")
        }
        check(
            targets.indices.all { abs(recovered[it].dimension - targets[it]) < 0.02 },
            "oracle: massRadiusDimension recovers a PRESCRIBED exponent to " +
                "${f(targets.indices.maxOf { abs(recovered[it].dimension - targets[it]) }, 4)} at " +
                "D = ${targets.joinToString(", ")} (measured " +
                recovered.joinToString(", ") { f(it.dimension, 3) } + "). In particular it returns " +
                "${f(recovered[2].dimension, 3)} when handed a set whose exponent IS 1.71 — so the DLA " +
                "figure's 1.617 is a fact about the cluster, not the estimator reading low"
        )
        // A literal straight line on the integer lattice: a different geometry with the same answer,
        // and — more usefully — a reading of how much the *lattice* costs. `N(r) = 2*floor(r) + 1` is a
        // staircase rather than a power law, and fitting a line through a staircase in log–log space
        // reads slightly low. It comes out at 0.993, i.e. 0.7 % under 1 — the same 0.7 % the calibrated
        // filled disc reads under 2 (1.986). Two different lattice objects, the same small bias, and the
        // continuum point sets above show none of it. So the honest reading of the DLA figure's 1.617 is
        // "about 1.63 in the continuum, and clearly not 1.71" rather than "1.617".
        val line = (-300..300).map { Vec2((512 + it).toFloat(), 512f) }
        val lineFit = massRadiusDimension(line, Vec2(512f, 512f), rMin = 5.0, rMax = 250.0)
        val discBias = 1.0 - 1.986 / 2.0
        println("[alife-gallery]     oracle: a straight line of lattice points -> D ${f(lineFit.dimension, 4)} " +
            "(lattice bias ${f((1.0 - lineFit.dimension) * 100, 2)}%, cf. the disc's ${f(discBias * 100, 2)}%)")
        check(
            abs(lineFit.dimension - 1.0) < 0.02 && lineFit.rSquared > 0.999,
            "oracle: a literal straight line of lattice points measures " +
                "${f(lineFit.dimension, 4)} (r2 ${f(lineFit.rSquared, 6)}) against the exact 1 a 1-D " +
                "set has — ${f((1.0 - lineFit.dimension) * 100, 2)}% low, because N(r) = 2*floor(r)+1 " +
                "is a staircase and not a power law. The continuum sets above are recovered to 0.3%, so " +
                "this is the lattice's error and not the estimator's"
        )

        // Every panel is built to land on exactly the 300 px cell. Not because [montage] would otherwise
        // resample — it only ever scales *down* (`min(1.0, ...)`), so an undersized panel is centred, not
        // stretched — but because a checkerboard is the one image where any resampling anywhere in the
        // chain shows up as a moiré, and a reader who sees one cannot tell whether the figure or their
        // viewer produced it. (This author saw a moiré in a downscaled preview of this very panel and
        // spent a round-trip "fixing" it; the PNG had exactly two grey values, 0 and 255, in 10 px
        // blocks. Decode and sample the pixels — do not trust a preview.)
        montage(
            listOf(
                "white noise: ac(1) ${f(acNoise[1], 4)}" to noise.crop(0, 0, 100, 100).upscaleNearest(3),
                "checkerboard: ac(1) = -1/3" to checker.crop(0, 0, 30, 30).upscaleNearest(10),
                "stripes lambda 48: len/lambda ${f(perLambda[2], 3)}" to
                    Img.generate(300, 300) { x, _ -> (0.5 + 0.5 * cos(2.0 * PI * x / 48)).toFloat() }
                        .colormap("viridis"),
                "prescribed D: 1.00 / 1.26 / 1.71 / 2.00" to plotLogLog(recovered, 300, 300)
            ),
            columns = 2, cellSide = 300
        ).savePng(File(outDir, "alife-measurement-oracles.png").absolutePath)
    }

    /**
     * `n` points whose mass–radius exponent about [center] is **exactly** [d]: the `i`-th point sits at
     * the radius that encloses exactly `i` points, `R*(i/n)^(1/d)`, at a uniformly random angle. So
     * `N(r) = n*(r/R)^d` identically, and the only error [massRadiusDimension] can make is its own.
     */
    private fun powerLawPoints(n: Int, d: Double, rOuter: Double, center: Vec2, seed: Long): List<Vec2> {
        val rng = kotlin.random.Random(seed)
        return (1..n).map { i ->
            val r = rOuter * Math.pow(i.toDouble() / n, 1.0 / d)
            val a = rng.nextDouble() * 2.0 * PI
            Vec2((center.x + r * cos(a)).toFloat(), (center.y + r * sin(a)).toFloat())
        }
    }

    // ==========================================================================================
    // 0c — The inline ToHTML views, checked at the pixel
    // ==========================================================================================

    private val base64Png = Regex("data:image/png;base64,([A-Za-z0-9+/=]+)")

    /** Every inline image in [html], decoded back to pixels. */
    private fun decodePanels(html: String): List<java.awt.image.BufferedImage> =
        base64Png.findAll(html).map { m ->
            val bytes = java.util.Base64.getDecoder().decode(m.groupValues[1])
            javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bytes))
                ?: error("emitted data URI is not a decodable image (${bytes.size} bytes)")
        }.toList()

    private fun rgbAt(img: java.awt.image.BufferedImage, x: Int, y: Int): Int = img.getRGB(x, y) and 0xffffff

    /** Channel-wise comparison with a tolerance, so a rounding step is not a failure. */
    private fun nearColor(actual: Int, expected: Int, tol: Int = 2): Boolean {
        for (shift in intArrayOf(16, 8, 0)) {
            if (abs(((actual shr shift) and 0xff) - ((expected shr shift) and 0xff)) > tol) return false
        }
        return true
    }

    /** Colormap [name] at [t], packed the way [afield.stdlib.colormap] quantises it. */
    private fun ramp(name: String, t: Double): Int {
        val c = afield.Colormaps.sample(name, t)
        fun q(v: Double) = (v.coerceIn(0.0, 1.0) * 255.0 + 0.5).toInt().coerceIn(0, 255)
        return (q(c.r) shl 16) or (q(c.g) shl 8) or q(c.b)
    }

    /**
     * Checks the three inline `ToHTML` views the family ships — `IntGrid`, `GrayScottState`, `LeniaRun` —
     * by **decoding the base64 PNG back out of the emitted HTML and reading individual pixels**, in the
     * pattern `WfcSmokeTest` uses for the solver's view.
     *
     * Looking at an inline view is not verifying it, and an inline view is the *easiest* thing in this
     * family to get quietly wrong, for a reason peculiar to it: it is rendered on every evaluation, with
     * no test in the loop, from whatever value happens to be in the box. The specific failure this figure
     * exists to catch is an **auto-ranged colour scale**, which is not a cosmetic problem — a saturated
     * Lenia field is constant at 1, so an auto-ranged map sends it to the *dark* end and it renders
     * black, identical to a dead one. That already cost this family a figure once (see `dev_docs/ALIFE.md`),
     * and a figure is regenerated deliberately while a view is regenerated by accident.
     *
     * So each claim below is a claim about a *named pixel*:
     *
     *  - a Life board's view is one pixel per cell, and a live cell reads white where the caption says
     *    white and a dead cell reads black — checked at coordinates taken from the grid, not from the
     *    image, so the two coordinate systems are asserted to be the same one;
     *  - `DEATH` and `SATURATION` Lenia runs must render **differently**, which is exactly what the
     *    auto-ranging bug made impossible: death reads the bottom of `inferno` and saturation the top;
     *  - an unseeded `GrayScottState` — the control arm, `v = 0` everywhere, a fixed point — must render
     *    as the bottom of `magma` and not as a texture.
     */
    private fun figureInlineView(outDir: File) {
        // --- IntGrid: one pixel per cell, and the palette the caption promises ---
        val glider = afield.stdlib.LifePatterns.glider.centeredOn(12, 12, wrap = true)
        val gridPanels = decodePanels(glider.toHTML())
        check(
            gridPanels.size == 1 && gridPanels[0].width == 12 && gridPanels[0].height == 12,
            "inline-view: an IntGrid's view is ONE panel at one pixel per cell — " +
                "${gridPanels.size} panel(s) at ${gridPanels.firstOrNull()?.width}x" +
                "${gridPanels.firstOrNull()?.height} for a 12x12 grid. Cell coordinates and pixel " +
                "coordinates are therefore the same thing, which is what makes the checks below meaningful"
        )
        var liveWrong = 0
        var deadWrong = 0
        var liveSeen = 0
        for (y in 0 until 12) for (x in 0 until 12) {
            val alive = glider.cells[y * 12 + x] == 1
            val rgb = rgbAt(gridPanels[0], x, y)
            if (alive) {
                liveSeen++
                if (!nearColor(rgb, 0xffffff)) liveWrong++
            } else if (!nearColor(rgb, 0x000000)) deadWrong++
        }
        check(
            liveSeen == 5 && liveWrong == 0 && deadWrong == 0,
            "inline-view: in the picture a box actually draws, all $liveSeen of the glider's live cells " +
                "read white and all ${144 - liveSeen} dead cells read black ($liveWrong / $deadWrong " +
                "wrong) — the caption's 'black = state 0, white = state 1' is checked against the pixels, " +
                "not asserted"
        )
        // A multi-state grid must NOT be drawn grayscale, or a cyclic soup's view would be unreadable.
        val soup = IntGrid.random(24, 24, states = 8, seed = 3L)
        val soupPanel = decodePanels(soup.toHTML())[0]
        val distinctHues = (0 until 24).flatMap { y -> (0 until 24).map { x -> rgbAt(soupPanel, x, y) } }
            .distinct()
        check(
            distinctHues.size >= 6 && soup.toHTML().contains("cyclicPalette"),
            "inline-view: an 8-state grid is drawn with the cyclic palette, not grayscale — " +
                "${distinctHues.size} distinct colours in a 24x24 soup, and the caption names " +
                "cyclicPalette so the repeat is disclosed"
        )

        // --- LeniaRun: DEATH and SATURATION must not render the same ---
        // This is the check that would have caught the black-saturation bug, stated as the thing that
        // actually matters: the two phases are DISTINGUISHABLE in the emitted pixels.
        val deathRule = LeniaPresets.ring(0.14, sigma = 0.017, radius = 8, dt = 0.12)
        val deathRun = leniaRun(leniaSeed(48, 48, deathRule, blobRadii = 1.6, seed = 9L), deathRule, steps = 120)
        val satRule = LeniaPresets.ring(0.60, sigma = 0.60, radius = 8, dt = 0.12)
        val satRun = leniaRun(leniaSeed(48, 48, satRule, blobRadii = 1.6, seed = 9L), satRule, steps = 120)
        println("[alife-gallery]     inline-view: $deathRun / $satRun")
        check(
            deathRun.phase == LeniaPhase.DEATH && satRun.phase == LeniaPhase.SATURATION,
            "inline-view: the two runs really are ${deathRun.phase} and ${satRun.phase} (so the pixel " +
                "comparison below is between the two phases it claims to be between)"
        )
        val deathPanel = decodePanels(deathRun.toHTML())[0]
        val satPanel = decodePanels(satRun.toHTML())[0]
        val deathCentre = rgbAt(deathPanel, 24, 24)
        val satCentre = rgbAt(satPanel, 24, 24)
        println("[alife-gallery]     inline-view: lenia centre pixel death=#%06x saturation=#%06x".format(deathCentre, satCentre))
        check(
            nearColor(deathCentre, ramp("inferno", 0.0), tol = 6),
            "inline-view: a DEATH run's field reads the BOTTOM of inferno (#%06x, expected #%06x) — an empty field looks empty"
                .format(deathCentre, ramp("inferno", 0.0))
        )
        check(
            nearColor(satCentre, ramp("inferno", 1.0), tol = 6),
            "inline-view: and a SATURATION run's field reads the TOP of inferno (#%06x, expected #%06x). "
                .format(satCentre, ramp("inferno", 1.0)) +
                "This is the assertion the family needed: with an auto-ranged scale a saturated field is " +
                "constant at 1, maps to the dark end, and renders BLACK — identical to death. The fixed " +
                "0..1 range in alifeLeniaRunHtml is what makes these two pixels differ"
        )
        check(
            !nearColor(deathCentre, satCentre, tol = 24),
            "inline-view: so the two phases are DISTINGUISHABLE in the emitted pixels, which is the whole " +
                "claim — not merely that each renders something"
        )

        // --- GrayScottState: the v = 0 control arm must render as empty, not as a texture ---
        val unseeded = grayScottSeed(48, 48, patches = 0, seed = 11L).stepTimes(GRAY_SCOTT_WORMS, 200)
        val gsPanels = decodePanels(unseeded.toHTML())
        check(
            gsPanels.size == 2,
            "inline-view: a GrayScottState's view is two panels, v and u (got ${gsPanels.size})"
        )
        val vColors = (0 until 48 step 4).flatMap { y -> (0 until 48 step 4).map { x -> rgbAt(gsPanels[0], x, y) } }
            .distinct()
        check(
            vColors.size == 1 && nearColor(vColors[0], ramp("magma", 0.0), tol = 6),
            "inline-view: an UNSEEDED Gray-Scott field — v = 0 everywhere, a fixed point, the control " +
                "arm — renders as exactly one colour, the bottom of magma (${vColors.size} colours, " +
                "#%06x). Img's own thumbnail auto-normalises and would turn this field's numerical dust "
                    .format(vColors.firstOrNull() ?: 0) +
                "into a rich texture; on a fixed 0..1 scale it is black, which is what it is"
        )
        check(
            unseeded.toHTML().contains("the field is flat"),
            "inline-view: and the caption SAYS the field is flat, because a uniform panel is otherwise " +
                "indistinguishable from a broken renderer"
        )

        // A seeded field, for contrast, and to confirm the same view is not simply always black.
        val seeded = grayScottSeed(48, 48, patches = 4, patchSize = 10, seed = 11L)
            .stepTimes(GRAY_SCOTT_SPOTS, 2000)
        val seededPanel = decodePanels(seeded.toHTML())[0]
        val seededColors = (0 until 48).flatMap { y -> (0 until 48).map { x -> rgbAt(seededPanel, x, y) } }
            .distinct()
        check(
            seededColors.size > 20,
            "inline-view CONTROL: the same view of a SEEDED field carries ${seededColors.size} distinct " +
                "colours, so the fixed scale has range and the unseeded panel above is black because the " +
                "field is empty, not because the view is broken"
        )

        // The figure itself: the four views as they are actually emitted, side by side.
        montage(
            listOf(
                "IntGrid: glider 12x12" to glider.toImg(2).upscaleNearest(20),
                "IntGrid: 8-state soup" to soup.toImg(cyclicPalette(8)).upscaleNearest(10),
                "LeniaRun DEATH" to deathRun.field.colormap("inferno", 0f, 1f).upscaleNearest(5),
                "LeniaRun SATURATION" to satRun.field.colormap("inferno", 0f, 1f).upscaleNearest(5),
                "GrayScott unseeded (v = 0)" to unseeded.v.colormap("magma", 0f, 1f).upscaleNearest(5),
                "GrayScott seeded" to seeded.v.colormap("magma", 0f, 1f).upscaleNearest(5)
            ),
            columns = 3, cellSide = 240
        ).savePng(File(outDir, "alife-inline-view.png").absolutePath)
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

        // --- rule PARSING rejects garbage instead of silently inventing a rule ---
        // `lifeRule`'s validation was a tautology: `s.filter { it.isDigit() }.length ==
        // s.count { it.isDigit() }` is the same expression on both sides, so the documented @throws
        // never fired. `lifeRule("hello/world")` returned B/S with both sets EMPTY — a rule under which
        // everything dies, silently — and `lifeRule("B3junk/S2x3")` returned B3/S23, quietly discarding
        // the part it could not read. A wrong rule that runs is far worse here than one that throws,
        // because every figure below would still produce a plausible picture.
        val garbage = listOf("hello/world", "B3junk/S2x3", "Bx/S23", "B3/S2!3", "//", "B3/S23/S4")
        val rejected = garbage.count { spec ->
            try { lifeRule(spec); false } catch (e: IllegalArgumentException) { true }
        }
        check(
            rejected == garbage.size,
            "life: rule notation that is not digits is REJECTED — $rejected of ${garbage.size} " +
                "(${garbage.joinToString()}) throw. Before this pass none of them did: the guard " +
                "compared an expression with itself, so garbage became an empty rule under which " +
                "everything dies, or a truncated rule, with no signal either way"
        )
        // The forms that must keep working, including the unlabelled one — and `B2/S`, whose survive set
        // is legitimately EMPTY. That entry is here because tightening the guard above initially
        // required a non-empty body and so rejected `SEEDS`, a rule this family ships: a validation fix
        // that outlaws valid input is a regression, and this line is what caught it on the next run.
        // NB `23/3`, NOT `3/23`: an unlabelled pair is the older **survive/birth** order. This list was
        // first written with `3/23 to LIFE` and failed, which is the assertion earning its keep — a
        // reader reaching for the short form has a 50/50 chance of picking the wrong one and getting a
        // silently different automaton, and nothing else in the family would have said so.
        val accepted = listOf(
            "B3/S23" to LIFE,
            "23/3" to LIFE,
            "b3/s23" to LIFE,
            "S23/B3" to LIFE,
            "B2/S" to afield.stdlib.SEEDS,
            "B3678/S34678" to afield.stdlib.DAY_AND_NIGHT
        )
        check(
            accepted.all { (spec, want) ->
                val r = lifeRule(spec)
                r.birth == want.birth && r.survive == want.survive
            },
            "life: and every legitimate spelling still parses to the rule it names — " +
                accepted.joinToString { it.first } + " (note B2/S: an EMPTY survive set is valid, and " +
                "the first version of the stricter guard rejected it)"
        )
        // The 3-D parser had the mirror-image bug: `drop(1)` ran unconditionally, so the unlabelled form
        // it also accepts lost the first count of each list. Note the unlabelled order is
        // **survive/birth**, as in 2-D — this check was first written expecting birth/survive and caught
        // its own author, which is the same trap the 2-D list above documents.
        val r3 = afield.stdlib.lifeRule3D("13,14/13,14,15")
        check(
            r3.survive == setOf(13, 14) && r3.birth == setOf(13, 14, 15),
            "3d-ca: the UNLABELLED 3-D rule form parses every count and reads survive/birth — survive " +
                "${r3.survive}, birth ${r3.birth} for \"13,14/13,14,15\". `drop(1)` used to run " +
                "unconditionally, so this silently became survive {4,15} / birth {4} with the first " +
                "count of each list eaten"
        )
        val r3labelled = afield.stdlib.lifeRule3D("S13,14/B13,14,15")
        check(
            r3labelled.birth == r3.birth && r3labelled.survive == r3.survive,
            "3d-ca: and labelling it explicitly as S13,14/B13,14,15 gives the identical rule"
        )
        val r3swapped = afield.stdlib.lifeRule3D("B13,14/S13,14,15")
        check(
            r3swapped.birth == setOf(13, 14) && r3swapped.survive == setOf(13, 14, 15),
            "3d-ca: while B13,14/S13,14,15 is the OTHER rule (birth ${r3swapped.birth}, survive " +
                "${r3swapped.survive}) — the labels are load-bearing, and the count is never eaten either way"
        )

        // --- the interior fast path against an independent brute-force stepper ---
        // `IntGrid.step` has a no-bounds-check interior loop and sends the border rows/columns through
        // `mooreCount`. That split is the highest-risk line of new code in this family — every Life claim
        // above runs through it, including the exact 319/821 and 116/1103 figures — and the first pass
        // flagged it as "the kind of thing that could be subtly wrong for a 1- or 2-cell grid" without
        // checking. So: every size from 1x1 to 8x8, both border modes, three rules, and soups carrying up
        // to 4 states, against a reference stepper written here from scratch.
        //
        // It found a real divergence on the FIRST run: the interior counted neighbours `!= 0` while the
        // borders counted `== 1`, so a grid holding any state above 1 was stepped by two different
        // automata in one call, an interior one and a border one. Nothing in the family produces such a
        // grid — every Life board here is binary — but `IntGrid.random(w, h, states = 14).step(LIFE)` is a
        // legal call, and "the answer depends on whether the cell is near an edge" is not a defensible
        // one. Both now test `== 1`.
        var diffChecked = 0
        var worstMismatch: String? = null
        for (rule in listOf(LIFE, lifeRule("B36/S23"), lifeRule("B3/S12345"))) {
            for (wrap in listOf(true, false)) {
                for (side in 1..8) {
                    for (states in listOf(2, 4)) {
                        val g = IntGrid.random(side, side, states = states, seed = 5000L + side * 31L + states)
                            .let { IntGrid(it.width, it.height, it.cells.copyOf(), wrap = wrap) }
                        val fast = g.step(rule)
                        val naive = naiveLifeStep(g, rule)
                        diffChecked++
                        for (k in fast.cells.indices) {
                            if (fast.cells[k] != naive.cells[k]) {
                                if (worstMismatch == null) {
                                    worstMismatch = "${rule.notation} ${side}x$side wrap=$wrap states=$states " +
                                        "cell (${k % side}, ${k / side}): step=${fast.cells[k]} " +
                                        "reference=${naive.cells[k]}"
                                }
                            }
                        }
                    }
                }
            }
        }
        check(
            worstMismatch == null,
            "life: the interior fast path agrees cell-for-cell with an independent brute-force stepper " +
                "over all $diffChecked (rule x border x size 1..8 x states) combinations — so the " +
                "no-bounds-check interior loop and the mooreCount border rows compute the SAME automaton, " +
                "including at the 3x3 size where the fast path turns on" +
                (worstMismatch?.let { " (first mismatch: $it)" } ?: "")
        )

        val strip = afield.stdlib.LifePatterns.glider.centeredOn(16, 16).let { g ->
            (0..4).map { "gen $it" to g.stepTimes(LIFE, it).toImg(2).upscaleNearest(14) }
        }
        montage(strip, columns = 5, cellSide = 240).savePng(File(outDir, "alife-life-glider.png").absolutePath)
    }

    /**
     * A deliberately slow, obvious `B/S` stepper, written here rather than reused from `alife_ca.kt`, so
     * that [figureLife]'s fast-path check is a comparison between two pieces of code rather than a
     * function agreeing with itself. Indexes explicitly and resolves the border by hand.
     */
    private fun naiveLifeStep(g: IntGrid, rule: afield.stdlib.LifeRule): IntGrid {
        val out = IntArray(g.width * g.height)
        for (y in 0 until g.height) for (x in 0 until g.width) {
            var n = 0
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                var nx = x + dx
                var ny = y + dy
                if (g.wrap) {
                    nx = Math.floorMod(nx, g.width)
                    ny = Math.floorMod(ny, g.height)
                } else if (nx < 0 || ny < 0 || nx >= g.width || ny >= g.height) {
                    continue
                }
                if (g.cells[ny * g.width + nx] == 1) n++
            }
            out[y * g.width + x] = rule.next(g.cells[y * g.width + x], n)
        }
        return IntGrid(g.width, g.height, out, wrap = g.wrap)
    }

    /**
     * The R-pentomino, split out from [figureLife] because it is the family's one genuinely expensive
     * *discrete* claim: 1103 generations on a 620x620 board plus 1400 on a walled 250x250, ~2.7 s of the
     * gallery's runtime, against ~10 ms for every other Life assertion. It lives in the slow tier.
     *
     * Nothing here is a threshold: every number is an exact integer equality against a figure from the
     * literature, so this is the cheapest possible thing to keep and the most obviously safe to defer.
     */
    private fun figureRPentomino(outDir: File) {
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
        // Stated against the closed form rather than as a bare ratio: `early` is pinned to
        // 1 - (13/14)^8 = 0.447 by the check above and `late` is pinned above 0.99, so this ratio cannot
        // be less than 0.99/(0.447 + 0.02) = 2.12 without one of those two failing first. Reported, not
        // thresholded independently.
        println("[alife-gallery]     cyclic: advance fraction rose ${f(late / early, 2)}x from soup to " +
            "organised phase (floor implied by the two checks above: " +
            "${f(0.99 / (predicted + 0.02), 2)}x)")

        val palette = cyclicPalette(rule.states)
        val exRule = CYCLIC_EXCITABLE
        val exSoup = IntGrid.random(220, 220, states = exRule.states, seed = 7L)
        val exSettled = exSoup.stepTimes(exRule, 200)
        val exLate = cyclicAdvanceFraction(exSettled, exSettled.step(exRule))
        println("[alife-gallery]     excitable (${exRule.states} states, threshold ${exRule.threshold}, r=${exRule.radius}) advance ${f(exLate)}")
        // The two parameter sets are visibly different phases — the excitable one curls into spiral
        // cores — and the difference is measurable as a length scale rather than left to the eye.
        val labImg = settled.toImg(rule.states)
        val exImg = exSettled.toImg(exRule.states)
        val labLength = labImg.correlationLength(maxLag = 30)
        val exLength = exImg.correlationLength(maxLag = 30)
        // The load-bearing claim is that each settled field HAS a length scale, and the control arm for
        // that is the same field with its pixels shuffled — which reads the interpolation floor
        // (~0.632; see figureMeasurement, where that floor is derived in closed form). This replaces a
        // bare `labyrinth > excitable * 1.3` that measured 1.39 and so had 7 % headroom: a threshold
        // that thin is a scheduled red build, and it was also the weaker of the two available claims.
        val labShuffled = shuffleImg(labImg, 91L).correlationLength(maxLag = 30)
        val exShuffled = shuffleImg(exImg, 91L).correlationLength(maxLag = 30)
        println("[alife-gallery]     correlation length: labyrinth ${f(labLength, 2)} (shuffled " +
            "${f(labShuffled, 3)}), excitable ${f(exLength, 2)} (shuffled ${f(exShuffled, 3)})")
        check(
            labLength > labShuffled * 2.5 && exLength > exShuffled * 2.5,
            "cyclic: both settled fields have a real length scale — labyrinth ${f(labLength, 2)} cells " +
                "against ${f(labShuffled, 3)} for its own shuffled pixels (${f(labLength / labShuffled, 1)}x), " +
                "excitable ${f(exLength, 2)} against ${f(exShuffled, 3)} (${f(exLength / exShuffled, 1)}x). " +
                "The control is the identical histogram with the order destroyed"
        )
        check(
            labLength > exLength * 1.15,
            "cyclic: and the two parameter sets settle at *different* length scales — the threshold-1 " +
                "labyrinth at ${f(labLength, 2)} cells against the excitable rule's ${f(exLength, 2)}, a " +
                "factor of ${f(labLength / exLength, 2)}. The bound is 1.15 rather than the measured " +
                "1.39 on purpose: the direction is the interesting part and the margin is not large, so " +
                "there is no case for a threshold that only just passes. (The larger neighbourhood gives " +
                "the *smaller* correlation length, which is the opposite of what this figure first " +
                "asserted; the excitable rule's spiral arms are narrow even though its neighbourhood is " +
                "wide.)"
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
     * diffusion moves material and creates none. Checked to relative `1e-5` over 500 steps — and checked
     * for a **clamped** border too, which also conserves it, because clamping is a reflecting zero-flux
     * boundary. Only an *absorbing* (`ZERO`) border loses mass, and that is the arm that fails. (This
     * KDoc used to say the check was "shown to fail under a clamped border", contradicting the body
     * three lines below it.)
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
        // Stated against the measured calibration rather than against a constant: the same run measures a
        // 40-cell disc at [discRatio], so "round" here means "within a factor of 2 of an actual disc,
        // measured by this build, in this process". The old form was `< 1.6`, a number with no
        // provenance. Note the direction of the small-blob bias: the crack-perimeter π/4 correction is
        // asymptotic, so a 10-cell blob scores slightly BELOW 1, which biases the statistic toward
        // "round" and makes a *stripe* verdict conservative rather than flattering.
        val discRatio = discMaskImg(160, 160, 40.0).blobShapes().first().isoperimetricRatio
        check(
            spots.medianIsoperimetric < discRatio * 2.0,
            "gray-scott: and they are ROUND — median P^2/4piA ${f(spots.medianIsoperimetric, 2)} against " +
                "${f(discRatio, 3)} measured for a calibrated 40-cell disc in this same run, i.e. " +
                "${f(spots.medianIsoperimetric / discRatio, 2)}x a disc (bound: 2x)"
        )
        // A control-arm comparison, not a magic floor: the spot preset's largest feature holds
        // [spots.largestShare] of the covered area, so "dominated by ONE structure" is 10x that, not
        // "> 0.40". The old absolute bound had 12 % headroom on the maze preset (measured 0.449).
        check(
            worms.largestShare > spots.largestShare * 10 && maze.largestShare > spots.largestShare * 10,
            "gray-scott: both labyrinth presets are dominated by ONE connected structure — worms " +
                "${f(worms.largestShare)} and maze ${f(maze.largestShare)} of the covered area against " +
                "${f(spots.largestShare)} for the spots at the same coverage, i.e. " +
                "${f(worms.largestShare / spots.largestShare, 0)}x and " +
                "${f(maze.largestShare / spots.largestShare, 0)}x. The comparison is with the spot arm, " +
                "not with a constant"
        )
        check(
            maze.medianIsoperimetric > spots.medianIsoperimetric * 2.0 &&
                worms.medianIsoperimetric > spots.medianIsoperimetric * 2.0,
            "gray-scott: and they are ${f(maze.medianIsoperimetric / spots.medianIsoperimetric, 1)}x / " +
                "${f(worms.medianIsoperimetric / spots.medianIsoperimetric, 1)}x less round than the " +
                "spots (${f(maze.medianIsoperimetric, 1)}, ${f(worms.medianIsoperimetric, 1)} vs " +
                "${f(spots.medianIsoperimetric, 2)}) — the SAME code, two numbers changed. Bound 2.0 " +
                "rather than the measured 3.0: the claim is 'a different morphology', and doubling is " +
                "already far beyond anything the small-blob bias can produce"
        )
        check(
            holes.blobCount == 1 && holes.medianIsoperimetric > 20.0 && holes.coverage > 0.6,
            "gray-scott: the inverse morphology reads as one blob covering ${f(holes.coverage)} of the " +
                "field with an isoperimetric ratio of ${f(holes.medianIsoperimetric, 0)} — a filled " +
                "sheet perforated by holes, which is what an enormous ratio at high coverage means"
        )

        // Control arm 1: v = 0 is a fixed point, so an unseeded field never patterns at all.
        val unseeded = grayScottSeed(side, side, patches = 0, seed = 11L).stepTimes(GRAY_SCOTT_WORMS, 500)
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
        // `>= first * 2`, not `>= first * 3`. The measured sequence is 4 -> 12, i.e. exactly 3x, so the
        // old bound passed with ZERO headroom: one blob landing on the other side of the 0.15 threshold
        // would have turned this red. The claim is "each patch divides", and doubling is the weakest
        // statement that means it.
        check(
            counts.first() <= 4 && counts.last() >= counts.first() * 2,
            "gray-scott: the 'mitosis' preset divides — ${counts.first()} seeded patches become " +
                "${counts.last()} spots over 160 steps, a measured ${f(counts.last().toDouble() / counts.first(), 1)}x, " +
                "with coverage still only ${f(strip.last().v.morphology().coverage, 3)} (so this is " +
                "division, not a growing stain)"
        )
        val risingIntervals = counts.zipWithNext().count { (a, b) -> b > a }
        check(
            counts.zipWithNext().all { (a, b) -> b >= a } && risingIntervals <= 2,
            "gray-scott: and the count never falls, rising in $risingIntervals of the eight sampled " +
                "intervals ($counts). So the honest description is a SINGLE synchronous division — every " +
                "square seed pinches into four spots inside the same 20-step window and the field is then " +
                "stable at that count. Not sustained mitosis, which is what the preset's name would " +
                "suggest and what a coarser sampling of this figure appeared to show. The bound is <= 2 " +
                "rather than == 1 so that a division event straddling a sample boundary is not a failure; " +
                "sustained mitosis would show 8"
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
        // Compared with the statistic's *floor*, which figureMeasurement derives in closed form as
        // (1 - 1/e) = 0.632 for a field with no correlation at all — not with a magic `< 1.0`. Both
        // directions are stated: the shuffled arm must sit at the floor, and the real field must sit
        // far above it.
        val acShuffled = shuffled.radialAutocorrelation(maxLag = 4)
        val floorClosedForm = (1.0 - 1.0 / E) / (1.0 - acShuffled[1])
        check(
            abs(shuffledLength - floorClosedForm) < 1e-6 && lengths[2] > shuffledLength * 3.0,
            "turing CONTROL: shuffling the same pixels drops the correlation length to " +
                "${f(shuffledLength, 4)}, which is *exactly* the (1 - 1/e)/(1 - ac(1)) floor a field " +
                "with no correlation must give (${f(floorClosedForm, 4)}) — while the unshuffled field " +
                "reads ${f(lengths[2], 2)}, ${f(lengths[2] / shuffledLength, 1)}x that floor. The " +
                "statistic reads spatial structure, not the histogram"
        )
        // The interpolation artefact, pinned as a *bias* against the estimator's own sampling noise
        // rather than against a constant: sigma is 1/sqrt(N) for N sampled sites, so the lattice sampler
        // must land inside 3 sigma and the bilinear one must land outside it. The bound this replaces was
        // `< 0.02`, which at this sample count is 1.3 sigma — i.e. a bound a *correct* estimator fails
        // about a fifth of the time. figureMeasurement derives the same pair on a purpose-built field.
        val acBilinear = shuffled.radialAutocorrelation(maxLag = 4, bilinear = true)
        val sigma = 2.0 / 128.0    // stride 2 over a 128x128 field: N = 64*64, sigma = 1/sqrt(N)
        println("[alife-gallery]     white-noise ac(1): nearest ${f(acShuffled[1], 4)}, bilinear " +
            "${f(acBilinear[1], 4)}, sampling sigma ${f(sigma, 4)}")
        check(
            abs(acShuffled[1]) < 3.0 * sigma && acBilinear[1] > 3.0 * sigma,
            "turing: on that same white noise the lag-1 autocorrelation is ${f(acShuffled[1], 4)} with " +
                "lattice sampling — inside the ${f(3.0 * sigma, 4)} three-sigma sampling band, as a " +
                "correct estimator of a true zero must be — against ${f(acBilinear[1], 4)} with bilinear " +
                "sampling, which is outside it. Bilinear interpolation averages in the centre pixel and " +
                "so manufactures correlation from nothing. This is why the estimator does not " +
                "interpolate, and this check is why it cannot quietly start again"
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
     * Conway's Life *bit for bit*: a sparse float convolution with an additive growth mapping and a
     * clamp, and integer Moore-neighbour counting with a table lookup, must agree exactly for 20
     * generations, including on a glider's rigid translation.
     *
     * **A correction to what this file used to claim.** The first version of this KDoc said the two
     * "share no code" and were "two independently written steppers". That is not true, and it overstates
     * the evidence. [leniaFromLifeRule] closes over the *same* [afield.stdlib.LifeRule.birth] and
     * [afield.stdlib.LifeRule.survive] sets that [afield.stdlib.step] uses, so a wrong `B3/S23` table
     * would produce two wrong steppers that agree perfectly. What this check actually cross-validates is
     * the two **update mechanisms** — float convolution, additive `A + dt*G(K*A)`, `Math.round(u*8)`
     * quantisation, clamping to `0..1`, toroidal borders and the `Img`/[IntGrid] bridges on one side
     * against integer counting on the other. That is worth having, and it is a smaller claim. The rule
     * table itself is pinned separately and independently, by the named-object checks in [figureLife]
     * (a glider that moves `(1,1)` in 4 generations, a block that does not move, a blinker of period 2)
     * — which are facts about `B3/S23` from the literature, not from this codebase.
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
                "20 generations of the integer Life stepper (worst per-cell difference $worstDiff). Two " +
                "update MECHANISMS agreeing exactly — float convolution plus additive growth plus a " +
                "clamp, against integer counting — from the one shared B3/S23 table, which is pinned " +
                "separately by the named-object checks"
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
        // No absolute floor. The old form carried `pFlock > 0.60` against a measured 0.692 — 15 %
        // headroom on a magic number, and a number that says nothing anyway: what makes this evidence is
        // that the *same initial state* run with alignment off does not get there. So the claim is made
        // entirely against the two arms it must beat, both of which are computed in this same run.
        check(
            pFlock > baseline * 10 && pFlock > pNoAlign * 4,
            "boids: after 900 steps the population's global polarization is ${f(pFlock, 3)} — " +
                "${f(pFlock / baseline, 1)}x the independent-heading baseline ${f(baseline, 3)} and " +
                "${f(pFlock / pNoAlign, 1)}x its own alignment-off twin ${f(pNoAlign, 3)}, in a state of " +
                "several separate flocks (so 1.0 is not the ceiling here, and no absolute threshold " +
                "would be meaningful)"
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
        // Again relational rather than absolute: `dFlock > 1.0` measured 1.37, and 1.0 is not a number
        // anything in the model derives. The separation-off twin collapses to 0.01, so a factor of 10 is
        // both a far stronger statement and one with two orders of magnitude of room.
        check(
            dFlock > dNoSep * 10.0,
            "boids: the 5th-percentile nearest-neighbour distance is ${f(dFlock, 2)} cells against " +
                "${f(dNoSep, 3)} for the identical initial state with separationWeight = 0 — " +
                "${f(dFlock / dNoSep, 0)}x. Separation really does keep them apart, and the floor is the " +
                "separation term rather than an artefact of the domain size"
        )
        // A by-construction invariant rather than a threshold: the integrator clamps every agent's speed
        // up to maxSpeed*minSpeedFraction, so the MINIMUM speed in the flock cannot be below it. Checking
        // the minimum (not the mean) is what makes this exact — and the control arm is the same run with
        // the floor removed, which is the configuration whose mean speed decayed to 0.46 in the first
        // version of this figure.
        val minSpeed = params.maxSpeed * params.minSpeedFraction
        val slowest = (0 until n).minOf { kotlin.math.hypot(flock.vx[it].toDouble(), flock.vy[it].toDouble()) }
        val noFloor = start.copy()
        noFloor.stepTimes(params.copy(minSpeedFraction = 0.0), 900)
        println("[alife-gallery]     boids speed: mean ${f(flock.meanSpeed(), 3)}, slowest " +
            "${f(slowest, 4)}, floor ${f(minSpeed, 3)}; no-floor twin mean ${f(noFloor.meanSpeed(), 3)}")
        check(
            slowest >= minSpeed * (1.0 - 1e-6),
            "boids: the SLOWEST agent flies at ${f(slowest, 4)}, at or above the " +
                "${f(minSpeed, 3)} floor the integrator clamps to — an exact invariant, not a threshold, " +
                "and stronger than the mean (${f(flock.meanSpeed(), 3)}) because a mean can hide a " +
                "stalled subpopulation"
        )
        check(
            noFloor.meanSpeed() < minSpeed * 0.8,
            "boids CONTROL: the identical run with minSpeedFraction = 0 decays to mean speed " +
                "${f(noFloor.meanSpeed(), 3)}, ${f(minSpeed / noFloor.meanSpeed(), 1)}x below the floor — " +
                "so the floor is doing real work. This is the configuration the first version of this " +
                "figure shipped, and it measured 0.46 while still scoring 0.88 on alignment: a frozen " +
                "lattice with lucky headings"
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
        // The lower bound is relational (against the sensing-off twin below and against the 0.05 a flat
        // field gives by definition); the UPPER bound is the one that has to be absolute, because the
        // failure this figure exists to catch is a *collapsed* population reading 1.000 — a number that
        // looks like perfect concentration and means total failure.
        check(
            sSense > 0.05 * 4 && sSense < 0.95,
            "physarum: the brightest 5% of the trail holds ${f(sSense, 3)} of all deposit, " +
                "${f(sSense / 0.05, 1)}x the 0.05 a flat field gives *by definition* — concentrated into " +
                "filaments, but NOT the 1.000 that a collapsed population reports (which is what the " +
                "first run of this figure produced; see PhysarumParams.exclusive). The upper bound is the " +
                "load-bearing half"
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
        val requested = 9000
        val dla = dlaCluster(side, side, particles = requested, seed = 2L)
        println("[alife-gallery]     $dla (requested $requested particles)")
        // The cluster stops when it reaches the domain edge, so it holds 3277 of the 9000 particles
        // asked for. That is documented behaviour and it is fine — but it was NOT checked, and "asked for
        // 9000, silently got 36 %" has two failure modes hiding behind it that a dimension of 1.6 would
        // not reveal: the walk budget could have been exhausted instead (a much smaller, much noisier
        // cluster), and the fit range could be sitting on the wall. Both are now assertions, in the same
        // "checked rather than assumed" spirit as the R-pentomino's clearance test.
        val domainRadius = side / 2.0 - 2.0
        check(
            dla.points.size < requested && dla.maxRadius > domainRadius - 12.0,
            "dla: the cluster stopped at ${dla.points.size} of $requested requested particles because it " +
                "reached the domain edge — its outer radius ${f(dla.maxRadius, 1)} is within " +
                "${f(domainRadius - dla.maxRadius, 1)} cells of the ${f(domainRadius, 1)} the field " +
                "allows. A cluster that ran out of WALK BUDGET instead would be small and noisy and would " +
                "stop far short of this, and dlaCluster reports neither case as an error"
        )
        val fit = dla.fractalDimension(rMin = 5.0, rMaxFraction = 0.75)
        check(
            dla.maxRadius * 0.75 < domainRadius * 0.85,
            "dla: and the fitted range stops at r = ${f(dla.maxRadius * 0.75, 1)}, well inside the " +
                "${f(domainRadius, 1)} where walkers are killed — so the slope is measured on the part of " +
                "the cluster the finite domain did not shape"
        )
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
        // The Eden arm is stated against the sprinkle arm, not against a `> 1.85` constant (which had
        // 0.058 of headroom). Two compact objects built by entirely different code — one grown one
        // particle at a time from uniformly-chosen perimeter sites, one sprinkled independently — must
        // measure the SAME dimension, and DLA must not. That is a three-way statement no single threshold
        // makes.
        check(
            abs(edenFit.dimension - sprinkleFit.dimension) < 0.10,
            "dla CONTROL: an Eden cluster measures ${f(edenFit.dimension, 3)} and a uniform sprinkle " +
                "${f(sprinkleFit.dimension, 3)} — two compact objects built by different code agreeing to " +
                "${f(abs(edenFit.dimension - sprinkleFit.dimension), 3)}, both near the 2 they must have"
        )
        check(
            edenFit.dimension - fit.dimension > 0.15 && sprinkleFit.dimension - fit.dimension > 0.15,
            "dla: and DLA sits ${f(edenFit.dimension - fit.dimension, 2)} / " +
                "${f(sprinkleFit.dimension - fit.dimension, 2)} below both of them, from the same " +
                "estimator, the same particle count and the same seed. Only the diffusing walker makes it " +
                "1.6, and figureMeasurement shows the estimator returns 1.71 for a set that HAS 1.71, so " +
                "this is the cluster and not the tool (the lattice costs about 0.7%, so read " +
                "${f(fit.dimension / 0.993, 3)} in the continuum — still clearly under 1.71)"
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
    // 9b — The bridge out to FLine, against the geometry of a circle
    // ==========================================================================================

    /**
     * Verifies the `Img` -> [afield.stdlib.isoContourFLines] -> `FLine` bridge, which the public page and
     * the shipped transcript both *use* and which nothing asserted until now: they printed
     * "195 iso-contour FLine(s)" and a picture, and 195 is not a number a reader can check.
     *
     * The bridge is what makes this family Field's rather than a generic ALife library — a
     * reaction-diffusion field becomes drawable, animatable, exportable geometry through the same
     * marching-squares extractor in `contours.kt` that everything else uses. So it is checked against
     * shapes whose contours are known in closed form:
     *
     *  - **a disc** contours to exactly one closed loop of length `2*pi*r` enclosing area `pi*r^2`;
     *  - **three discs** give exactly three loops, matching what [blobShapes] independently counts;
     *  - **an annulus** gives two loops whose *signed* areas have opposite sign, so their **sum** is the
     *    annulus area `pi*(R^2 - r^2)`. That is the invariant which says the extractor winds a hole
     *    opposite to an outer boundary — what any consumer that fills, booleans or exports these curves
     *    depends on, and something no vertex count would catch;
     *  - **a constant field** gives zero contours, because nothing crosses the iso level.
     *
     * Stating the claims as *circle geometry* rather than as vertex or loop-point counts is deliberate.
     * `contours.kt` is shared code under active change; a claim about the area of a circle stays
     * meaningful across a reimplementation of marching squares, and still fails if the reimplementation
     * is wrong. This figure only ever *consumes* `contours.kt`.
     */
    private fun figureBridgeFLines(outDir: File) {
        val side = 200
        val r = 60.0
        val disc = discMaskImg(side, side, r)
        // cells = side marches one cell per pixel, the finest the raster supports.
        val discLoops = disc.isoContourFLines(0.5, cells = side)
        val discLen = discLoops.sumOf { flineTotalLength(listOf(it)) }
        val discArea = discLoops.sumOf { flineSignedArea(listOf(it)) }
        val lenErr = abs(discLen - 2.0 * PI * r) / (2.0 * PI * r)
        val areaErr = abs(abs(discArea) - PI * r * r) / (PI * r * r)
        println("[alife-gallery]     bridge: disc r=$r -> ${discLoops.size} loop(s), perimeter " +
            "${f(discLen, 2)} vs 2*pi*r ${f(2.0 * PI * r, 2)} (${f(lenErr * 100, 2)}%), area " +
            "${f(abs(discArea), 1)} vs pi*r^2 ${f(PI * r * r, 1)} (${f(areaErr * 100, 2)}%)")
        check(
            discLoops.size == 1,
            "bridge: a disc contours to exactly ONE closed loop (got ${discLoops.size})"
        )
        check(
            areaErr < 0.01,
            "bridge: and it encloses ${f(abs(discArea), 1)} against the exact ${f(PI * r * r, 1)} a " +
                "circle of radius $r has — ${f(areaErr * 100, 2)}% out. Area is the sharper of the two " +
                "tests because it averages the marching cell's interpolation error rather than " +
                "accumulating it"
        )
        check(
            lenErr < 0.05,
            "bridge: and its perimeter is ${f(discLen, 2)} against 2*pi*r = ${f(2.0 * PI * r, 2)}, " +
                "${f(lenErr * 100, 2)}% out. Bounded at 5% rather than at the measured value because a " +
                "polyline on a pixel lattice cannot be exactly a circle and the residual is a property " +
                "of the grid, not of the extractor"
        )

        // Three discs: the loop count must agree with what an independent estimator counts.
        val centres = listOf(Triple(50.0, 60.0, 26.0), Triple(140.0, 55.0, 22.0), Triple(95.0, 145.0, 30.0))
        val three = Img.generate(side, side) { x, y ->
            if (centres.any { (cx, cy, rad) -> kotlin.math.hypot(x - cx, y - cy) <= rad }) 1f else 0f
        }
        val threeLoops = three.isoContourFLines(0.5, cells = side)
        val threeBlobs = three.blobShapes().size
        check(
            threeLoops.size == 3 && threeBlobs == 3,
            "bridge: three separated discs give ${threeLoops.size} contour loop(s) and $threeBlobs " +
                "connected component(s) — two unrelated pieces of machinery agreeing on the same count"
        )

        // An annulus: two loops, oppositely wound, so the SIGNED areas SUM to the annulus area.
        val rOut = 70.0
        val rIn = 35.0
        val annulus = Img.generate(side, side) { x, y ->
            val d = kotlin.math.hypot(x - (side - 1) / 2.0, y - (side - 1) / 2.0)
            if (d in rIn..rOut) 1f else 0f
        }
        val ringLoops = annulus.isoContourFLines(0.5, cells = side)
        val signed = ringLoops.map { flineSignedArea(listOf(it)) }
        val ringExpected = PI * (rOut * rOut - rIn * rIn)
        val signedSum = abs(signed.sum())
        val ringErr = abs(signedSum - ringExpected) / ringExpected
        println("[alife-gallery]     bridge: annulus -> ${ringLoops.size} loop(s), signed areas " +
            signed.joinToString(", ") { f(it, 1) } + ", |sum| ${f(signedSum, 1)} vs pi*(R^2-r^2) " +
            "${f(ringExpected, 1)} (${f(ringErr * 100, 2)}%)")
        check(
            ringLoops.size == 2,
            "bridge: an annulus contours to exactly TWO loops, an outer and an inner (got ${ringLoops.size})"
        )
        // Each loop against its OWN circle first, which is the part that is unambiguously right.
        val outerMeasured = signed.maxOf { abs(it) }
        val innerMeasured = signed.minOf { abs(it) }
        val outerErr = abs(outerMeasured - PI * rOut * rOut) / (PI * rOut * rOut)
        val innerErr = abs(innerMeasured - PI * rIn * rIn) / (PI * rIn * rIn)
        check(
            outerErr < 0.01 && innerErr < 0.01,
            "bridge: each loop matches its own circle — outer ${f(outerMeasured, 1)} against " +
                "${f(PI * rOut * rOut, 1)} (${f(outerErr * 100, 2)}%), inner ${f(innerMeasured, 1)} " +
                "against ${f(PI * rIn * rIn, 1)} (${f(innerErr * 100, 2)}%)"
        )
        // ---------------------------------------------------------------------------------------
        // A finding, pinned as the behaviour it is rather than as the behaviour it "should" be.
        //
        // This figure was written expecting the two loops to be wound in OPPOSITE directions, because
        // that is the convention that lets a consumer sum signed areas and get the area of a shape with
        // a hole. `isoContourFLines` does not do that: both loops come back with the SAME sign, so the
        // naive sum reads 19270 for an annulus whose area is 11545 — 67% high. Getting the right answer
        // means subtracting the smaller magnitude, i.e. knowing which loop is the hole by some means
        // other than its winding.
        //
        // This is NOT a violated contract: `contours.kt` documents no orientation guarantee (the one
        // "counter-clockwise" comment in it refers to a marching cell's corner ordering, not to output
        // winding). So it is undocumented behaviour that a caller who fills, booleans or exports these
        // curves has to know, which is exactly why it is asserted here and written up in
        // `dev_docs/ALIFE.md` rather than left to be rediscovered.
        //
        // It is also a deliberate tripwire on a live coupling: `contours.kt` is shared code and its
        // marching-squares behaviour is under change on another branch. If winding becomes
        // orientation-aware, THIS assertion fails and says so in one line, instead of the annulus area
        // silently starting to come out right for a reason nobody recorded.
        // ---------------------------------------------------------------------------------------
        check(
            signed.all { it > 0.0 } || signed.all { it < 0.0 },
            "bridge: both annulus loops are wound the SAME way (signed areas " +
                signed.joinToString(", ") { f(it, 1) } + "), so isoContourFLines does NOT encode a hole " +
                "by orientation. Summing signed areas therefore OVERSTATES a holed shape's area — " +
                "${f(signedSum, 1)} against the true ${f(ringExpected, 1)}, ${f(ringErr * 100, 0)}% high. " +
                "contours.kt promises no winding convention, so this is undocumented behaviour a caller " +
                "must know, not a broken contract. If a change to contours.kt makes winding " +
                "orientation-aware, this line is the one that will say so"
        )
        val ringByDifference = outerMeasured - innerMeasured
        val diffErr = abs(ringByDifference - ringExpected) / ringExpected
        check(
            diffErr < 0.005,
            "bridge: taking the DIFFERENCE of the two magnitudes instead gives ${f(ringByDifference, 1)} " +
                "against the exact ${f(ringExpected, 1)} — ${f(diffErr * 100, 3)}% out. So the geometry " +
                "the extractor produces is right to four figures; it is only the orientation that " +
                "carries no information"
        )

        // Control: no iso crossing, no contour.
        val flat = Img.generate(side, side) { _, _ -> 0.9f }
        check(
            flat.isoContourFLines(0.5, cells = side).isEmpty(),
            "bridge CONTROL: a constant field at 0.9 yields no contours at iso 0.5 — nothing crosses the " +
                "level, so there is nothing to trace"
        )

        // And the real thing the docs page shows, with its count tied to a second measurement.
        val rd = grayScottSeed(128, 128, patches = 6, patchSize = 14, seed = 11L)
            .stepTimes(GRAY_SCOTT_SPOTS, 4000)
        val rdLoops = rd.v.isoContourFLines(0.25, cells = 128)
        val rdBlobs = rd.v.threshold(0.25f).blobShapes(minArea = 4).size
        println("[alife-gallery]     bridge: Gray-Scott -> ${rdLoops.size} contour loop(s), $rdBlobs blob(s) at the same level")
        check(
            rdLoops.size >= rdBlobs && rdBlobs > 20,
            "bridge: the Gray-Scott field gives ${rdLoops.size} contour loops for $rdBlobs thresholded " +
                "blobs at the same level — at least one loop per blob, and more is correct because a blob " +
                "with a hole contributes two. This ties the docs page's contour count to a second " +
                "measurement instead of printing it"
        )
        check(
            rdLoops.all { flineTotalLength(listOf(it)) > 0.0 },
            "bridge: and every one of the ${rdLoops.size} loops has non-zero length — no degenerate " +
                "single-point FLines in the output"
        )

        montage(
            listOf(
                "disc: area ${f(areaErr * 100, 2)}% off pi r^2" to rasterizeFLines(discLoops, 300, 300),
                "annulus: 2 loops, signed sum ${f(ringErr * 100, 2)}% off" to rasterizeFLines(ringLoops, 300, 300),
                "three discs: ${threeLoops.size} loops = $threeBlobs blobs" to rasterizeFLines(threeLoops, 300, 300),
                "Gray-Scott: ${rdLoops.size} loops" to rasterizeFLines(rdLoops, 300, 300)
            ),
            columns = 2, cellSide = 300
        ).savePng(File(outDir, "alife-bridge-flines.png").absolutePath)
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
            // Floor the intensity at 0.4: with 4 fits a 0.3 step put the last curve at 0.1, which is
            // invisible on a dark background — and an invisible curve in a figure whose whole point is
            // "look at the four slopes" is a rendering lie of the same kind as the black Lenia panel.
            val v = 1f - k * 0.2f
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

    /**
     * Which tier a figure belongs to.
     *
     * [FAST] is everything whose evidence is an **exact identity or a closed-form comparison** and whose
     * runtime is milliseconds: the two estimator oracles, Life's named objects, the elementary rules'
     * two independent number-theoretic oracles, mass conservation, the L-system identities, the tree
     * invariants, the dimension controls and the watertight mesh. It is ~6 s and it is the tier that
     * catches a broken stepper, a broken estimator or a broken bridge.
     *
     * [SLOW] is the seven figures that integrate a PDE, a 6000-step reaction, a 240-step Lenia sweep or
     * an `O(N²)` agent loop to a *statistical* verdict — ~25 s of the ~27. Their claims are the more
     * interesting ones, and they are also the ones a change to `Random`, to float rounding or to a
     * default parameter can move.
     *
     * The split is not a claim that the slow tier matters less. It is a claim that a 27-second
     * unconditional cost on every routine smoke run buys less than the same seconds spent elsewhere,
     * and that ~6 s of *exact* checks is the right thing to make unconditional.
     */
    private enum class Tier { FAST, SLOW }

    private data class Stage(val name: String, val tier: Tier, val run: (File) -> Unit)

    private val STAGES: List<Stage> = listOf(
        Stage("oracles", Tier.FAST, ::figureMeasurement),
        Stage("calibration", Tier.FAST, ::figureCalibration),
        Stage("inline-view", Tier.FAST, ::figureInlineView),
        Stage("life", Tier.FAST, ::figureLife),
        Stage("elementary", Tier.FAST, ::figureElementary),
        Stage("diffusion", Tier.FAST, ::figureDiffusion),
        Stage("dla", Tier.FAST, ::figureDla),
        Stage("l-system", Tier.FAST, ::figureLSystem),
        Stage("colonization", Tier.FAST, ::figureColonization),
        Stage("bridge-flines", Tier.FAST, ::figureBridgeFLines),
        Stage("3d-ca", Tier.FAST, ::figureVolume),
        Stage("rpentomino", Tier.SLOW, ::figureRPentomino),
        Stage("cyclic", Tier.SLOW, ::figureCyclic),
        Stage("gray-scott", Tier.SLOW, ::figureGrayScott),
        Stage("turing", Tier.SLOW, ::figureTuring),
        Stage("lenia", Tier.SLOW, ::figureLenia),
        Stage("boids", Tier.SLOW, ::figureBoids),
        Stage("physarum", Tier.SLOW, ::figurePhysarum)
    )

    @JvmStatic
    fun main(args: Array<String>) {
        // `--tier fast|slow|all`, `--out DIR`, `--only a,b`. A bare first argument is still an output
        // dir, because that is what every other gallery in this tree accepts and what the docs say.
        var tierArg: String? = System.getenv("ALIFE_GALLERY_TIER")
        var outArg: String? = null
        var onlyArg: String? = System.getenv("ALIFE_GALLERY_ONLY")
        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                a == "--tier" -> { tierArg = args.getOrNull(++i); }
                a.startsWith("--tier=") -> tierArg = a.substringAfter('=')
                a == "--out" -> { outArg = args.getOrNull(++i); }
                a.startsWith("--out=") -> outArg = a.substringAfter('=')
                a == "--only" -> { onlyArg = args.getOrNull(++i); }
                a.startsWith("--only=") -> onlyArg = a.substringAfter('=')
                a.startsWith("-") -> error("AlifeGallery: unknown option $a (want --tier/--out/--only)")
                outArg == null -> outArg = a
                else -> error("AlifeGallery: unexpected extra argument $a")
            }
            i++
        }
        val tiers: Set<Tier> = when (tierArg?.lowercase()) {
            null, "", "all" -> setOf(Tier.FAST, Tier.SLOW)
            "fast" -> setOf(Tier.FAST)
            "slow" -> setOf(Tier.SLOW)
            else -> error("AlifeGallery: --tier must be fast, slow or all, got $tierArg")
        }
        val outDir = File(
            outArg ?: System.getenv("ALIFE_GALLERY_DIR")
                ?: java.nio.file.Files.createTempDirectory("alife-gallery").toFile().absolutePath
        )
        outDir.mkdirs()

        // Iterating on one figure otherwise means waiting for the other fifteen. Development only: the
        // FIELD_TESTs pass a tier and no filter.
        val only = onlyArg?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.toSet()
        only?.let { println("[alife-gallery] FILTERED to ${it.joinToString()} — not a full run") }
        println("[alife-gallery] tier(s): " + tiers.joinToString { it.name.lowercase() })
        var ran = 0
        for ((name, tier, stage) in STAGES) {
            if (tier !in tiers) continue
            if (only != null && name !in only) continue
            val t0 = System.currentTimeMillis()
            println("[alife-gallery] --- $name ---")
            stage(outDir)
            ran++
            println("[alife-gallery] --- $name done in ${System.currentTimeMillis() - t0} ms ---")
        }
        if (ran == 0) fail("no figure ran — check --tier/--only against the stage list")

        if (failures.isNotEmpty()) {
            System.err.println("AlifeGallery FAILED with ${failures.size} problem(s):")
            failures.forEach { System.err.println("  - $it") }
            exitProcess(1)
        }
        println("alife gallery rendered to ${outDir.absolutePath}")
    }
}
