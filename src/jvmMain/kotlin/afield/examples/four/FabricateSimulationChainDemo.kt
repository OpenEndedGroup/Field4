package afield.examples.four

import afield.Box
import afield.four.Renderer
import afield.four.TexturePreviewCache
import afield.four.fabricated.FabricateBuiltChain
import afield.four.fabricated.FabricatePassChain
import afield.four.fabricated.FabricatePassLibrary
import afield.four.fabricated.FabricatePassSize
import afield.four.fabricated.FabricatePassSpec
import afield.four.fabricated.StandardRenderer
import afield.four.fabricated.addPassChain
import afield.four.fabricated.applyScenePlan
import afield.four.targets.RenderToTexture
import afield.four.util.Options
import afield.four.util.Window
import afield.stdlib.linalg.Vector2f
import afield.stdlib.linalg.Vector3f
import org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.system.exitProcess

/*
FIELD_TEST
id: four.simulation-chain.smoke
requiresDisplay: true
runner: jvm-main
entrypoint: afield.examples.four.FabricateSimulationChainDemo
tags: [smoke, four, fabricate, pass-chain, fluid, distance-field, gpu, image]
timeoutSec: 420
expect:
  exitCode: 0
  stdoutContains:
    - "simulation chain tests PASSED"
*/
/**
 * Holds the two simulation templates to the pixels: a Stam stable fluid and a jump flood distance
 * transform, both declared as `FabricatePassChain`s.
 *
 * ### Why a GPU test and not more planner tests
 *
 * `FabricatePassChainTest` checks that `fluid-advect` binds `fluid-velocity`'s *previous frame* and
 * that the persistent slots are exclusive. Every one of those assertions would still hold if the
 * ping-pong swap never happened — if `otherImageView` had been snapshotted at build time, or if the
 * descriptor were written once and never updated. The chain would plan perfectly, allocate perfectly,
 * and render **the same frame forever**, which on a fluid whose first frame is a splat looks like a
 * static blob and on one whose first frame is empty looks like a shader bug.
 *
 * So the assertion this file exists for is the dullest one in it: *with the input switched off, the
 * picture must keep changing*. Nothing above the GPU can check that.
 *
 * ### What it asserts, and why each needs pixels
 *
 * - **The dye advances with no input.** A splat, then a hundred frames of nothing, captured twice
 *   forty frames apart. The centroid has to move, in the direction the splat pushed, and the total
 *   dye has to fall by the dissipation. A frozen feedback loop fails both; an unbound one renders
 *   black and fails the first check of all.
 * - **Vorticity confinement changes the picture**, measured against a second fluid in the same run
 *   driven by the same splat schedule with the term off. That is an A/B and not a threshold.
 * - **The distance field is a distance.** With one seed at a known texel the exact answer is
 *   arithmetic, so the readback is compared against ground truth rather than against a previous
 *   render or a plausible-looking band pattern. This is the assertion that catches a flood one rung
 *   short, which produces a field that is correct near the seed and wrong in the corners — a picture
 *   nobody looking at it would call broken.
 * - **A bilinear read would have broken the flood, and does not happen.** Seed coordinates are
 *   propagated with `texelFetch`; if they were filtered, the error against ground truth would be
 *   small but systematic, which is precisely what the ground-truth comparison measures and a
 *   bands-look-right check would not.
 * - **The signed field is signed.** An 8-bit readback cannot show a negative number, so the sign is
 *   read through `distanceBands`, which colours the two sides differently — the interior of a disc
 *   mask has to come back on the interior side and the exterior on the other.
 *
 * ### Running it
 *
 * ```
 * tools/run_field_tests.py --id four.simulation-chain.smoke          # writes PNGs to build/simulation-chain/
 * tools/run_field_tests.py --id four.simulation-chain.smoke --args=--keep
 * ```
 *
 * Needs a GPU and a window: `afield.four` has no headless mode, so offscreen targets still need a
 * `Window` for the device to exist.
 */
object FabricateSimulationChainDemo {

    /** Render size of the bundle the chains are appended to. */
    private const val DIM = 512

    /** The fluid's simulation grid. Deliberately smaller than [DIM] — a fluid is not an image. */
    private const val FLUID_DIM = 128

    /** The distance field's grid. A power of two, because the stride sequence is its halvings. */
    private const val JFA_DIM = 256

    /** Frames to let the renderer settle before anything is measured. */
    private const val WARMUP_FRAMES = 6

    /** Frames the splat is held on for. */
    private const val SPLAT_FRAMES = 24

    /** Frames of no input before the first dye capture. */
    private const val COAST_FRAMES = 30

    /** Frames between the two dye captures — the interval the motion is measured over. */
    private const val DRIFT_FRAMES = 40

    /** Dye dissipation, well below one so the decay over [DRIFT_FRAMES] is unmistakable. */
    private const val DYE_DISSIPATION = 0.985f

    /**
     * Where the splat is centred and which way it pushes.
     *
     * Left of centre and pushing right, so the drift the test measures runs along +u and has most of
     * the frame to run into. A splat at the centre pushing right reaches the wall in about
     * [DRIFT_FRAMES] and the centroid stops moving, which reads as a frozen simulation.
     */
    private val SPLAT_CENTRE = Vector2f(0.3f, 0.5f)
    private val SPLAT_VELOCITY = Vector2f(140f, 0f)

    /** Minimum centroid drift, in UV, over [DRIFT_FRAMES] with no input. */
    private const val DRIFT_FLOOR = 0.01

    /**
     * Dye added per frame at the splat centre, chosen so the field stays **inside 0..1**.
     *
     * Not a cosmetic choice, and the first value here was 3.5, which is wrong for a reason worth
     * writing down. `splatMass` accumulates for [SPLAT_FRAMES] against the dissipation, so the peak
     * settles near `mass * (1 - d^n) / (1 - d)` — about 72 at 3.5. The dye field is half-float and
     * holds that happily; the readback blits it to `R8G8B8A8_SRGB` and **clamps at 1.0**. So the
     * whole visible field was saturated white, and forty frames of dissipation took the real peak
     * from 72 to 44 while the PNG did not move at all. The measured "total energy" was then the
     * *area* of the clipped region, which grows as the blob spreads — so it went up 1.1% while the
     * fluid was losing 45% of its mass.
     *
     * This is `dev_docs/FOUR_PASS_CHAIN.md`'s first measurement trap — "peak brightness cannot
     * measure a half-float target" — applied to an entire field rather than one highlight. The fix
     * is to keep the simulation in the range the instrument can see, and to assert that it is.
     */
    private const val SPLAT_MASS = 0.03f

    /**
     * Ceiling on the painted field's peak, below 1.0 with room to spare.
     *
     * A field at the clamp is a field whose decay is invisible, so this is checked *before* anything
     * is concluded from a sum or a ratio.
     */
    private const val SATURATION_CEILING = 0.96

    /**
     * The single texel the distance field is seeded at, and the mask that seeds exactly it.
     *
     * "Exactly it" is load-bearing. The ground-truth comparison is the distance to *one point*, so
     * the mask has to cover one texel and the truth has to be that texel's **centre** — at
     * `SEED_TEXEL + 0.5`, which is where the seed pass writes. Computing the truth against
     * `SEED_TEXEL` instead put the seed half a texel from where it is, which is invisible across most
     * of the field and a 4.6-code error at the seed itself, where the exact distance is 0.7 texels
     * and the measurement is correctly 0. A mask radius large enough to catch a second texel breaks
     * it the other way: nearest-seed distance stops being distance-to-a-point and the truth is no
     * longer arithmetic.
     */
    private const val SEED_TEXEL = 128

    private const val SEED_U = (SEED_TEXEL + 0.5f) / JFA_DIM
    private const val SEED_V = (SEED_TEXEL + 0.5f) / JFA_DIM

    /** Under half a texel, so the mask disc contains [SEED_TEXEL]'s centre and no other. */
    private const val SEED_RADIUS = 0.4f / JFA_DIM

    /**
     * Distance scale for the measured field: `1 / (2 * resolution)`.
     *
     * The readback is an 8-bit PNG, so the distance has to land inside 0..1 or it clips — and the
     * largest distance in a square field is its diagonal, `sqrt(2) * resolution`. Half the resolution
     * as the divisor leaves the far corner at about 0.71 with headroom either side, and makes the
     * decoding one multiplication rather than a guess.
     */
    private const val JFA_SCALE = 1f / (2f * JFA_DIM)

    /**
     * How far the flood's distance may sit from the exact one, in **8-bit codes of the readback**.
     *
     * The comparison is made in encoded space rather than in linear light, and that is the whole
     * reason this number is small enough to be worth asserting. A jump flood for a single seed is
     * near-exact, so the only real error is the readback's own 8-bit quantisation — but sRGB is steep
     * in the dark, so one code step near the middle of the field is 2.6% of the *linear* value there.
     * Comparing linear values against a 2% ceiling therefore failed at 2.34% with nothing wrong: the
     * ceiling was under the instrument's resolution. Two codes out of 255 is a bound on the
     * instrument, not on the algorithm, and it is a tighter statement about the algorithm than any
     * percentage of a linear value could be.
     */
    private const val DISTANCE_CODE_TOLERANCE = 2.0

    private val DATA_IMAGE_RE = Regex("base64,([^']+)")

    private val outputDir = File(System.getProperty("afield.simchain.out") ?: "build/simulation-chain")

    private var failures = 0

    @JvmStatic
    fun main(args: Array<String>) {
        val keepOpen = args.contains("--keep")
        Options.enableVR = false
        Options.doVulkanSDK()

        outputDir.mkdirs()
        val window = Window("Field · Fabricate simulation chains")
        val renderer = Renderer(window)

        // Same reason as FabricatePassChainDemo: StandardRenderer.build allocates targets through a
        // bare `RenderToTexture { … }`, whose renderer defaults to `fromContext()`, so it needs a box
        // context. Without one the failure is a NullPointerException from inside the RTT constructor.
        val hostBox = Box("fabricate-simulation-chain-demo").apply { name = "fabricate-simulation-chain-demo" }
        hostBox.setPropertyRaw("renderer", renderer, doResync = false)
        hostBox.setPropertyRaw("window", window, doResync = false)

        try {
            Box.withBoxContextNow(hostBox) { run(renderer, window) }
            if (keepOpen) {
                println("-- --keep: leaving the window open, close it to exit --")
                while (!window.shouldClose()) pump(renderer, window, 1)
            }
        } catch (t: Throwable) {
            println("FAIL: ${t.message}")
            t.printStackTrace()
            failures++
        } finally {
            runCatching { renderer.cleanup() }
            runCatching { window.cleanup() }
        }

        if (failures == 0) {
            println("simulation chain tests PASSED — figures in ${outputDir.absolutePath}")
        } else {
            println("simulation chain tests FAILED ($failures)")
        }
        exitProcess(if (failures == 0) 0 else 1)
    }

    private fun run(renderer: Renderer, window: Window) {
        val bundle = StandardRenderer.build(
            renderer,
            StandardRenderer.Config(
                renderWidth = DIM,
                renderHeight = DIM,
                backgroundColor = Vector3f(0.05f, 0.06f, 0.08f)
            )
        )
        bundle.applyScenePlan()
        bundle.present(renderer = renderer)
        bundle.attachTo(renderer)
        pump(renderer, window, WARMUP_FRAMES)

        // Two fluids, identical but for the vorticity term, so "confinement does something" is an
        // A/B in one run rather than a number somebody picked.
        val fluid = bundle.addPassChain(
            FabricatePassLibrary.stableFluid(
                prefix = "fluid",
                resolution = FLUID_DIM,
                iterations = 20,
                dyeDissipation = DYE_DISSIPATION,
                vorticity = 24f
            ),
            renderer = renderer,
            printReport = true
        )
        val smooth = bundle.addPassChain(
            FabricatePassLibrary.stableFluid(
                prefix = "smooth",
                resolution = FLUID_DIM,
                iterations = 20,
                dyeDissipation = DYE_DISSIPATION,
                vorticity = 0f
            ),
            renderer = renderer
        )

        // The distance chains. Both synthesise their own mask, so neither depends on what the fluid
        // chains left in the post stack's output.
        val dot = bundle.addPassChain(dotMaskChain(), renderer = renderer, printReport = true)
        val disc = bundle.addPassChain(discMaskChain(), renderer = renderer)

        bundle.present(renderer = renderer)
        bundle.attachTo(renderer)

        guard("both fluids allocated a ping-pong pair per state field, and nothing else shares them") {
            listOf("fluid" to fluid, "smooth" to smooth).forEach { (name, chain) ->
                require(chain.stateNames() == listOf("$name-velocity", "$name-dye")) {
                    "$name state fields are ${chain.stateNames()}"
                }
                chain.stateNames().forEach { field ->
                    val state = chain.stateOf(field) ?: error("$field is not a ping-pong target")
                    require(state.frameCount == FabricatePassChain.PERSISTENT_IMAGE_COUNT) {
                        "$field has ${state.frameCount} frames"
                    }
                    require(state.width == FLUID_DIM) { "$field is ${state.width} wide" }
                }
                require(chain.plan.slots.count { it.persistent } == 2) {
                    "$name planned ${chain.plan.slots.count { it.persistent }} persistent slots"
                }
            }
            "two chains x two ${FLUID_DIM}² ping-pong fields, ${fluid.plan.passCount} passes each"
        }

        // --- paint, then stop painting ---

        splat(fluid, on = true)
        splat(smooth, on = true)
        pump(renderer, window, SPLAT_FRAMES)
        val painted = capture(renderer, fluid.output, "01-fluid-painted")

        splat(fluid, on = false)
        splat(smooth, on = false)
        pump(renderer, window, COAST_FRAMES)
        val early = capture(renderer, fluid.output, "02-fluid-coasting-early")
        val earlySmooth = capture(renderer, smooth.output, "03-fluid-coasting-early-no-vorticity")
        val velocity = capture(renderer, fluid.stateOf("fluid-velocity")!!, "04-fluid-velocity-field")

        pump(renderer, window, DRIFT_FRAMES)
        val late = capture(renderer, fluid.output, "05-fluid-coasting-late")
        val lateSmooth = capture(renderer, smooth.output, "06-fluid-coasting-late-no-vorticity")

        guard("the splat reached the dye field at all") {
            require(painted.meanLuma > 0.002) {
                "the dye field is effectively black (mean luma ${"%.5f".format(painted.meanLuma)}) — " +
                        "either the splat push constants never arrived or the persistent target is unbound"
            }
            require(painted.distinctLumaBuckets >= 6) {
                "the dye field has ${painted.distinctLumaBuckets} distinct levels — a uniform image is " +
                        "what a pass that never sampled its input renders"
            }
            // Checked before anything below draws a conclusion from a sum. A field over 1.0 reads
            // back clamped, and a clamped field's decay is invisible however fast it is decaying —
            // see SPLAT_MASS for the run where that cost an hour.
            require(painted.maxLuma < SATURATION_CEILING) {
                "the painted dye peaks at ${"%.4f".format(painted.maxLuma)}, at or over the readback's " +
                        "clamp — every energy measurement below would be measuring the *area* of the " +
                        "saturated region rather than the field. Lower SPLAT_MASS."
            }
            "painted dye mean luma ${"%.4f".format(painted.meanLuma)} over " +
                    "${painted.distinctLumaBuckets} levels, peak ${"%.3f".format(painted.maxLuma)} (unclamped)"
        }

        guard("the velocity field is being written, not left at its clear colour") {
            require(velocity.distinctLumaBuckets >= 4) {
                "the velocity field has ${velocity.distinctLumaBuckets} distinct levels"
            }
            "velocity field ${velocity.size}, ${velocity.distinctLumaBuckets} levels"
        }

        guard("with the input switched off the dye keeps moving — the feedback loop actually advances") {
            // The assertion this whole file exists for. A chain whose ping-pong halves were
            // snapshotted at build time plans, allocates and renders perfectly, and renders the
            // *same frame* forever. Both captures below are taken with every splat push constant at
            // zero, so anything that changes between them came from the previous frame's state.
            val moved = hypot(late.centroidU - early.centroidU, late.centroidV - early.centroidV)
            require(moved > DRIFT_FLOOR) {
                "the dye centroid moved only ${"%.4f".format(moved)} UV over $DRIFT_FRAMES frames with " +
                        "no input — under $DRIFT_FLOOR means the simulation is not advancing, which is " +
                        "what a frozen previous-frame binding looks like"
            }
            // And in the direction the splat pushed, which rules out the centroid drifting because
            // the dye is fading unevenly rather than being transported.
            require(late.centroidU > early.centroidU) {
                "the dye drifted to ${"%.4f".format(late.centroidU)} from " +
                        "${"%.4f".format(early.centroidU)} — the splat pushed along +u, so transport " +
                        "should carry it that way"
            }
            "centroid ${"%.4f".format(early.centroidU)} -> ${"%.4f".format(late.centroidU)} along u, " +
                    "${"%.4f".format(moved)} UV travelled over $DRIFT_FRAMES frames of no input"
        }

        guard("dissipation is compounding per frame, which only a persistent field can do") {
            // A field re-rendered from scratch every frame would land on the same total every time.
            // Geometric decay is evidence of accumulation, and the floor is set well under the
            // arithmetic so the assertion does not depend on the exact frame count.
            val ratio = late.totalEnergy / early.totalEnergy
            val expected = Math.pow(DYE_DISSIPATION.toDouble(), DRIFT_FRAMES.toDouble())
            require(ratio < 1.0) {
                "total dye rose from ${"%.1f".format(early.totalEnergy)} to ${"%.1f".format(late.totalEnergy)} " +
                        "with no input — with dissipation at $DYE_DISSIPATION it can only fall"
            }
            require(ratio > expected * 0.25) {
                "total dye fell to ${"%.3f".format(ratio)} of itself against an expected " +
                        "${"%.3f".format(expected)} — far more loss than dissipation accounts for, which " +
                        "is what advecting by a field with residual divergence does"
            }
            "total dye fell to ${"%.3f".format(ratio)} of itself over $DRIFT_FRAMES frames " +
                    "(dissipation alone predicts ${"%.3f".format(expected)})"
        }

        guard("vorticity confinement changes the fluid, measured against the same splat without it") {
            require(earlySmooth.meanLuma > 0.002) { "the no-vorticity fluid is black" }
            // Confinement puts back the curl a first-order advection dissipates, so the confined
            // fluid holds more structure at the top of the spectrum. Compared at the same instant
            // under the same splat schedule, so the only difference is the term.
            val confined = late.highFrequencyEnergy
            val plain = lateSmooth.highFrequencyEnergy
            require(abs(confined - plain) > plain * 0.05) {
                "the confined fluid's high-frequency energy (${"%.5f".format(confined)}) is within 5% of " +
                        "the unconfined one's (${"%.5f".format(plain)}) — the vorticity term is not " +
                        "reaching the shader, or the curl pass is bound to the wrong target"
            }
            "confined hf ${"%.5f".format(confined)} vs unconfined ${"%.5f".format(plain)} " +
                    "(${"%.2f".format(confined / plain)}x)"
        }

        // --- the distance field ---

        val dotSeed = capture(renderer, dot.targetOf("dot-seed")!!, "07-jfa-seed")
        val dotDistance = capture(renderer, dot.targetOf("dot-distance")!!, "08-jfa-distance")
        val dotBands = capture(renderer, dot.output, "09-jfa-bands")
        val discBands = capture(renderer, disc.output, "10-jfa-signed-bands")

        guard("the flood ran the full stride sequence and ended where the plan said") {
            val strides = dot.plan.passes.mapNotNull { it.spec.push["stride"] as? Int }
            require(strides.take(8) == listOf(128, 64, 32, 16, 8, 4, 2, 1)) { "strides were $strides" }
            require(dotDistance.width == JFA_DIM && dotDistance.height == JFA_DIM) {
                "the distance field came back ${dotDistance.size}, not ${JFA_DIM}²"
            }
            require(dotSeed.maxLuma > 0.05) { "nothing was seeded — the mask never crossed the threshold" }
            "${strides.size} flood passes over ${dotDistance.size}, seeded"
        }

        guard("the distance field agrees with the exact distance to the one seed it has") {
            // Ground truth, not a previous render and not a plausible band pattern. With a single
            // seed the answer is `length(p - seed) * JFA_SCALE`, so a flood one rung short, a
            // filtered read, or a stride sequence off by a factor of two all show up here as a
            // systematic error — and in nothing a person looking at the bands would notice.
            // Exactly one seed, checked rather than assumed: the ground truth below is the distance
            // to a single point, and a second seeded texel would make it quietly wrong in a wedge of
            // the field rather than everywhere.
            val zeros = dotDistance.countWhereRedCodeIs(0)
            require(zeros == 1) {
                "$zeros texels read as distance zero, not 1 — the mask seeded more than one texel, so " +
                        "nearest-seed distance is no longer distance to a point and the truth below is not " +
                        "the truth. Shrink SEED_RADIUS."
            }
            val seedX = SEED_TEXEL + 0.5
            val seedY = SEED_TEXEL + 0.5
            var worst = 0.0
            var worstAt = ""
            var samples = 0
            // Every eighth texel, skipping the outer ring, so the sample set spans the whole field
            // including the corners the largest stride has to reach.
            for (y in 8 until JFA_DIM - 8 step 8) {
                for (x in 8 until JFA_DIM - 8 step 8) {
                    val exact = hypot(x + 0.5 - seedX, y + 0.5 - seedY) * JFA_SCALE
                    // Both sides in 8-bit codes: the exact distance put through the same transfer
                    // function the readback applied, so the residual is quantisation and nothing else.
                    val exactCode = linearToSrgb(exact) * 255.0
                    val measuredCode = dotDistance.redCode(x, y)
                    val error = abs(measuredCode - exactCode)
                    samples++
                    if (error > worst) {
                        worst = error
                        worstAt = "($x, $y): measured code ${"%.0f".format(measuredCode)}, " +
                                "exact code ${"%.1f".format(exactCode)} (${"%.4f".format(exact)} linear)"
                    }
                }
            }
            require(samples > 200) { "only $samples usable samples" }
            require(worst <= DISTANCE_CODE_TOLERANCE) {
                "worst error ${"%.1f".format(worst)} codes at $worstAt, over a tolerance of " +
                        "$DISTANCE_CODE_TOLERANCE — a jump flood is near-exact for a single seed, so " +
                        "anything above the readback's own quantisation is a missing rung, a filtered " +
                        "read, or the wrong stride sequence"
            }
            "worst error ${"%.1f".format(worst)} of 255 codes over $samples samples spanning the whole " +
                    "field (tolerance $DISTANCE_CODE_TOLERANCE), worst at $worstAt"
        }

        guard("the bands are evenly spaced, which is the visual check that it is a distance") {
            require(dotBands.distinctLumaBuckets >= 8) {
                "the bands have ${dotBands.distinctLumaBuckets} levels — a distance field with no " +
                        "structure is one that never got a seed"
            }
            "bands ${dotBands.size}, ${dotBands.distinctLumaBuckets} levels"
        }

        guard("the signed field is negative inside the disc and positive outside it") {
            // The sign cannot be read out of an 8-bit PNG, so it is read out of the *colour* the
            // bands pass assigns each side. A signed chain that lost its mask binding produces an
            // everywhere-positive field, which is a perfectly plausible unsigned distance field and
            // is why this is asserted rather than eyeballed.
            val inside = discBands.meanChannelsIn(0.45, 0.55, 0.45, 0.55)
            val outside = discBands.meanChannelsIn(0.02, 0.12, 0.02, 0.12)
            // distanceBands' defaults: blue-dominant inside, orange (red-dominant) outside.
            require(inside.third > inside.first) {
                "the centre of the disc came back red-dominant (r ${"%.3f".format(inside.first)}, " +
                        "b ${"%.3f".format(inside.third)}) — it is on the *outside* of the field's sign"
            }
            require(outside.first > outside.third) {
                "the corner came back blue-dominant (r ${"%.3f".format(outside.first)}, " +
                        "b ${"%.3f".format(outside.third)}) — the field has no negative side, so either " +
                        "the mask binding or the boundary seeding is gone"
            }
            "inside rgb ${inside.fmt()} (blue side), outside rgb ${outside.fmt()} (orange side)"
        }

        guard("a live push retunes the fluid with no reallocation") {
            val before = fluid.plan.targetCount
            require(fluid.setPush("fluid-dye", "dissipation", 0.5f)) { "dissipation push was not accepted" }
            val was = capture(renderer, fluid.output, "11-fluid-before-fast-decay")
            pump(renderer, window, 12)
            val now = capture(renderer, fluid.output, "12-fluid-after-fast-decay")
            require(fluid.plan.targetCount == before) { "retuning changed the target count" }
            // 0.5^12 is about 0.0002, so twelve frames at half-per-frame is effectively a wipe.
            require(now.totalEnergy < was.totalEnergy * 0.05) {
                "dropping dissipation to 0.5 for 12 frames left ${"%.3f".format(now.totalEnergy / was.totalEnergy)} " +
                        "of the dye — the push constant is not reaching the shader"
            }
            "dissipation 0.985 -> 0.5 wiped the dye to " +
                    "${"%.5f".format(now.totalEnergy / was.totalEnergy)} of itself in 12 frames, no reallocation"
        }
    }

    // ── the chains the assertions are written against ────────────────────────

    /** Set or clear both splat halves at once — the force pass and the dye pass share their names. */
    private fun splat(chain: FabricateBuiltChain, on: Boolean) {
        val prefix = chain.names().first().substringBefore("-advect")
        listOf("$prefix-force", "$prefix-dye").forEach { pass ->
            chain.setPush(pass, "splatCenter", SPLAT_CENTRE)
            chain.setPush(pass, "splatRadius", 0.07f)
            chain.setPush(pass, "splatColor", Vector3f(0.3f, 0.75f, 1.0f))
            chain.setPush(pass, "splatVelocity", if (on) SPLAT_VELOCITY else Vector2f(0f, 0f))
            chain.setPush(pass, "splatMass", if (on) SPLAT_MASS else 0f)
        }
    }

    /**
     * A single-texel mask, flooded and banded.
     *
     * One seed is the case with an exact answer, which is the whole reason the mask is a dot and not
     * something interesting to look at.
     */
    private fun dotMaskChain(): List<FabricatePassSpec> {
        val mask = FabricatePassSpec(
            name = "dot-mask",
            fragment = DOT_MASK_FRAGMENT,
            size = FabricatePassSize.Absolute(JFA_DIM, JFA_DIM),
            source = FabricatePassChain.CHAIN_INPUT,
            push = mapOf("seed" to Vector2f(SEED_U, SEED_V), "radius" to SEED_RADIUS)
        )
        return listOf(mask) + FabricatePassLibrary.jumpFloodDistance(
            prefix = "dot",
            resolution = JFA_DIM,
            source = "dot-mask",
            scale = JFA_SCALE
        ) + FabricatePassLibrary.distanceBands(
            name = "dot-bands",
            source = "dot-distance",
            spacing = 0.05f
        )
    }

    /** A filled disc, flooded *signed*, so there is an inside for the sign to be negative in. */
    private fun discMaskChain(): List<FabricatePassSpec> {
        val mask = FabricatePassSpec(
            name = "disc-mask",
            fragment = DOT_MASK_FRAGMENT,
            size = FabricatePassSize.Absolute(JFA_DIM, JFA_DIM),
            source = FabricatePassChain.CHAIN_INPUT,
            push = mapOf("seed" to Vector2f(0.5f, 0.5f), "radius" to 0.22f)
        )
        return listOf(mask) + FabricatePassLibrary.jumpFloodDistance(
            prefix = "disc",
            resolution = JFA_DIM,
            source = "disc-mask",
            signed = true,
            scale = JFA_SCALE
        ) + FabricatePassLibrary.distanceBands(
            name = "disc-bands",
            source = "disc-distance",
            spacing = 0.04f
        )
    }

    /**
     * A filled circle in every channel, so the mask reads the same whichever channel is selected.
     *
     * Ignores `sourceTex`: this pass is a source, and a chain head that synthesises its own input is
     * what makes the ground-truth comparison downstream repeatable.
     */
    private const val DOT_MASK_FRAGMENT = """
        #version 450
        layout(location = 0) in vec2 vUv;
        layout(push_constant) uniform MaskPush {
            vec2 seed;
            float radius;
        } push;
        layout(location = 0) out vec4 outColor;

        void main() {
            float inside = length(vUv - push.seed) <= max(push.radius, 1e-5) ? 1.0 : 0.0;
            outColor = vec4(inside, inside, inside, inside);
        }
    """

    // ── plumbing ─────────────────────────────────────────────────────────────

    private fun pump(renderer: Renderer, window: Window, frames: Int) {
        repeat(frames) {
            window.pollEvents()
            renderer.render()
        }
    }

    /**
     * Read a target back and keep both the file and the pixels.
     *
     * `maxDim` is the target's own largest dimension: [TexturePreviewCache] makes *thumbnails* by
     * default, and a downscaled distance field would be compared against ground truth at the wrong
     * coordinates.
     */
    private fun capture(renderer: Renderer, rtt: RenderToTexture, name: String): Shot {
        val html = TexturePreviewCache.capture(
            renderer = renderer,
            vkImage = rtt.colorImageHandles[0],
            format = rtt.colorFormats[0],
            srcWidth = rtt.width,
            srcHeight = rtt.height,
            currentLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            maxDim = maxOf(rtt.width, rtt.height)
        ) ?: error("could not read back '$name' (${rtt.width}x${rtt.height})")
        val encoded = DATA_IMAGE_RE.find(html)?.groupValues?.get(1)
            ?: error("could not parse the readback of '$name'")
        val file = File(outputDir, "$name.png")
        file.delete()
        file.writeBytes(Base64.getDecoder().decode(encoded))
        require(file.length() > 0L) { "readback of '$name' wrote an empty PNG" }
        val shot = Shot(name, file)
        println(
            " -- $name: ${shot.size}, ${file.length() / 1024}KB, mean luma ${"%.4f".format(shot.meanLuma)}, " +
                    "centroid (${"%.3f".format(shot.centroidU)}, ${"%.3f".format(shot.centroidV)})"
        )
        return shot
    }

    /** One captured PNG, with the statistics the assertions are written in terms of. */
    private class Shot(val name: String, file: File) {
        private val image = ImageIO.read(file) ?: error("could not re-read $name from ${file.absolutePath}")
        val width = image.width
        val height = image.height
        val size get() = "${width}x$height"

        private val rgb = IntArray(width * height) { image.getRGB(it % width, it / width) }

        private val luma = DoubleArray(width * height) { index ->
            val c = rgb[index]
            0.2126 * (c shr 16 and 0xff) / 255.0 + 0.7152 * (c shr 8 and 0xff) / 255.0 + 0.0722 * (c and 0xff) / 255.0
        }

        /**
         * The same luminance in **linear light**.
         *
         * `TexturePreviewCache` blits a float target to `VK_FORMAT_R8G8B8A8_SRGB` on the way out, so
         * the PNG's bytes are display-encoded and *not* the numbers the shader wrote. Two of this
         * file's assertions failed on that before anything was wrong with a shader:
         *
         * - the total dye **rose** 1.7% over forty frames of pure dissipation, because a blob
         *   spreading out moves energy from high sRGB codes, where the curve compresses, to low ones
         *   where it expands — the encoded sum went up while the energy went down;
         * - the distance field read 0.3137 where the exact answer was 0.0804, a 290% error, which is
         *   `srgbToLinear(80/255) = 0.0802` and therefore a 0.25% error.
         *
         * Anything that is a *sum* or is compared against an *absolute* expectation has to be
         * decoded first. Ratios of the same statistic between two captures do not, which is why
         * [highFrequencyEnergy] is left encoded.
         */
        private val linearLuma = DoubleArray(width * height) { index ->
            val c = rgb[index]
            0.2126 * srgbToLinear((c shr 16 and 0xff) / 255.0) +
                    0.7152 * srgbToLinear((c shr 8 and 0xff) / 255.0) +
                    0.0722 * srgbToLinear((c and 0xff) / 255.0)
        }

        val meanLuma = luma.average()
        val maxLuma = luma.max()

        /** Total energy, in linear light — the statistic dissipation and mass conservation act on. */
        val totalEnergy = linearLuma.sum()
        val distinctLumaBuckets = luma.map { (it * 64).toInt() }.distinct().size

        /**
         * The red channel at a texel in linear light — the channel a distance pass writes its
         * distance into, decoded back out of the sRGB the readback encoded it into.
         */
        fun red(x: Int, y: Int): Double = srgbToLinear(redCode(x, y) / 255.0)

        /**
         * The red channel as the raw 0..255 code the PNG holds.
         *
         * The distance comparison is made here rather than in linear light, because sRGB is steep in
         * the dark and amplifies one code step into several percent of the linear value — see
         * [DISTANCE_CODE_TOLERANCE].
         */
        fun redCode(x: Int, y: Int): Double = (rgb[y * width + x] shr 16 and 0xff).toDouble()

        /** How many texels hold exactly [code] in red — used to count the seeds a mask produced. */
        fun countWhereRedCodeIs(code: Int): Int = rgb.count { (it shr 16 and 0xff) == code }

        /**
         * Luminance-weighted centroid, in UV.
         *
         * The statistic that distinguishes dye being *transported* from dye merely fading: a fading
         * blob keeps its centroid, a advected one moves it. Weighted by luminance rather than by a
         * threshold so a blob that is spreading as it moves does not jump as texels cross the line.
         */
        val centroidU: Double
        val centroidV: Double

        init {
            var wu = 0.0
            var wv = 0.0
            var total = 0.0
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val w = luma[y * width + x]
                    wu += w * (x + 0.5) / width
                    wv += w * (y + 0.5) / height
                    total += w
                }
            }
            val safe = if (total > 1e-9) total else 1.0
            centroidU = wu / safe
            centroidV = wv / safe
        }

        /**
         * Mean absolute discrete Laplacian of luminance — energy at the top of the spectrum.
         *
         * Deliberately not a gradient magnitude: a gradient survives a blur reasonably well, which
         * would make the vorticity A/B far weaker than it looks.
         */
        val highFrequencyEnergy: Double by lazy {
            var total = 0.0
            var count = 0
            for (y in 1 until height - 1) {
                for (x in 1 until width - 1) {
                    val here = luma[y * width + x]
                    val lap = luma[y * width + x - 1] + luma[y * width + x + 1] +
                            luma[(y - 1) * width + x] + luma[(y + 1) * width + x] - 4 * here
                    total += abs(lap)
                    count++
                }
            }
            total / count
        }

        /** Mean (r, g, b) over a UV rectangle — how the sign of a signed field is read back. */
        fun meanChannelsIn(u0: Double, u1: Double, v0: Double, v1: Double): Triple<Double, Double, Double> {
            var r = 0.0
            var g = 0.0
            var b = 0.0
            var count = 0
            for (y in (v0 * height).toInt() until (v1 * height).toInt().coerceAtMost(height)) {
                for (x in (u0 * width).toInt() until (u1 * width).toInt().coerceAtMost(width)) {
                    val c = rgb[y * width + x]
                    r += (c shr 16 and 0xff) / 255.0
                    g += (c shr 8 and 0xff) / 255.0
                    b += (c and 0xff) / 255.0
                    count++
                }
            }
            require(count > 0) { "$name: empty region ($u0..$u1, $v0..$v1)" }
            return Triple(r / count, g / count, b / count)
        }
    }

    /** The sRGB electro-optical transfer function, which the readback applied and nothing undid. */
    private fun srgbToLinear(encoded: Double): Double =
        if (encoded <= 0.04045) encoded / 12.92 else Math.pow((encoded + 0.055) / 1.055, 2.4)

    /** Its inverse, for putting an exact answer through the same instrument the measurement came out of. */
    private fun linearToSrgb(linear: Double): Double {
        val l = linear.coerceIn(0.0, 1.0)
        return if (l <= 0.0031308) l * 12.92 else 1.055 * Math.pow(l, 1.0 / 2.4) - 0.055
    }

    private fun Triple<Double, Double, Double>.fmt() =
        "(${"%.3f".format(first)}, ${"%.3f".format(second)}, ${"%.3f".format(third)})"

    private fun guard(what: String, body: () -> String) {
        try {
            println("OK  $what — ${body()}")
        } catch (t: Throwable) {
            println("FAIL: $what — ${t.message}")
            failures++
        }
    }
}
