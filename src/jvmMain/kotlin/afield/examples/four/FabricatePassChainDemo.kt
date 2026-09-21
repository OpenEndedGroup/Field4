package afield.examples.four

import afield.Box
import afield.four.Renderer
import afield.four.TexturePreviewCache
import afield.four.fabricated.FabricateBuiltChain
import afield.four.fabricated.FabricatePassChain
import afield.four.fabricated.FabricatePassInput
import afield.four.fabricated.FabricatePassLibrary
import afield.four.fabricated.FabricatePassSize
import afield.four.fabricated.FabricatePassSpec
import afield.four.fabricated.StandardRenderer
import afield.four.fabricated.addPassChain
import afield.four.fabricated.applyScenePlan
import afield.four.targets.RenderToTexture
import afield.four.util.Options
import afield.four.util.Window
import afield.stdlib.linalg.Matrix4f
import afield.stdlib.linalg.Vector3f
import org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT
import org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM
import org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.system.exitProcess

/*
FIELD_TEST
id: four.pass-chain.smoke
requiresDisplay: true
runner: jvm-main
entrypoint: afield.examples.four.FabricatePassChainDemo
tags: [smoke, four, fabricate, pass-chain, gpu, image]
timeoutSec: 300
expect:
  exitCode: 0
  stdoutContains:
    - "pass chain tests PASSED"
*/
/**
 * Holds the heterogeneous pass chain to the pixels, and is the thing to run to see it.
 *
 * `FabricatePassChainTest` checks the *plan* — that a rung is half the rung above it, that a
 * composite samples what it claims, that no shared target aliases a live input. Every one of those
 * assertions would still hold if the shaders drew nothing at all. This is the test that cannot.
 *
 * ### What it asserts, and why each one needs a GPU
 *
 * - **The chain reaches the screen through the production consumer.** The passes are appended with
 *   [addPassChain] onto a real [StandardRenderer] bundle's [afield.four.fabricated.FabricatePostStack],
 *   scheduled by `FabricateFrameGraph.attachTo` into `RenderTarget.preTargets`, and read back from the
 *   target the frame graph actually rendered. No private path, no harness-only shortcut.
 * - **High-frequency energy collapses, with the counterfactual measured in the same run.** A blur is
 *   the one effect where "it ran" and "it did nothing" produce images a human glance cannot separate
 *   at small sizes, so the sharp source and the blurred composite are both measured, in the same
 *   process, at the same resolution, and the ratio is floored. The bypassed chain is not a
 *   hypothetical: it is capture 01, taken before the chain was appended.
 * - **The intermediate rungs are the sizes they were planned to be**, checked against the readback
 *   dimensions rather than against the plan that chose them — otherwise the planner would be marking
 *   its own homework.
 * - **The aperture pass makes a disc, not a Gaussian.** A bright point through a real aperture becomes
 *   a *flat-topped* disc: the profile across it is nearly constant and then falls off a cliff. A
 *   Gaussian of the same width peaks in the middle and decays. So the radial profile of one blurred
 *   dot is measured, and the disc is required to be flat where a Gaussian would already have halved.
 *   This is the assertion that would catch the aperture shader silently degrading into a blur.
 * - **Per-pass format is real.** The pyramid runs half-float while the chain around it is 8-bit,
 *   which is checked by requiring the disc to survive at all: the test pattern's dots are far
 *   brighter than 1.0, and in an 8-bit rung they would clip to white before the disc spread their
 *   energy, leaving a disc too dim to see. The floor on disc brightness is a floor on the format.
 *
 * ### Running it
 *
 * ```
 * tools/run_field_tests.py --id four.pass-chain.smoke        # writes PNGs to build/pass-chain/
 * tools/run_field_tests.py --id four.pass-chain.smoke --args=--keep   # ...and leaves the window open
 * tools/run_field_tests.py --id four.pass-chain.smoke
 * ```
 *
 * Needs a GPU and, because `afield.four` has no headless mode — `Renderer` requires a `Window` and
 * `Window` requires a primary monitor — a display. The window it opens is incidental; everything
 * measured here is rendered offscreen.
 */
object FabricatePassChainDemo {

    private const val DIM = 1024
    private const val WARMUP_FRAMES = 24
    private const val SETTLE_FRAMES = 12

    /**
     * Floors, gathered here so they can be read against each other and against the numbers in the
     * KDoc. Every one was set *after* measuring, just under what was measured -- see the run recorded
     * in `dev_docs/FOUR_PASS_CHAIN.md`.
     */
    private const val HF_COLLAPSE_FLOOR = 9.0
    private const val SCENE_HF_COLLAPSE_FLOOR = 3.0
    private const val RIM_SHARPNESS_FLOOR = 1.6
    private const val FORMAT_AB_FLOOR = 6.0
    private const val PUSH_EXTENT_FLOOR = 1.5

    /** Checker frequency on the pattern's left half, in checks across the whole image. */
    private const val CHECKS = 64f

    /**
     * UV size of one dot cell on the pattern's dark right half.
     *
     * Half the image, so the dark half holds a single column of two dots. That looks sparser than a
     * grid and is not a cosmetic choice: a radial profile around a dot can only run as far as the
     * *nearest* neighbouring dot, so cells of 0.25 capped every measurement at 32 pixels of the
     * quarter-resolution rung — under the width of the Gaussian being compared against. Cells of 0.5
     * buy 63.
     */
    private const val DOT_CELL = 0.5f

    /** Aperture disc radius, in texels of the quarter-resolution rung it runs on. */
    private const val APERTURE_RADIUS = 12f

    /**
     * Centre of the dot the radial measurements are taken around, in normalised coordinates.
     *
     * Deliberately an *interior* dot. A radial profile can only run as far as the nearest image edge,
     * and the obvious choice — the first dot, at v = DOT_CELL/2 — sits 32 pixels from the top of the
     * quarter-resolution rung, so every profile truncated at 32 samples and both the disc and the
     * Gaussian reported the same width. That reads as "the shader is wrong" and was not.
     */
    private const val DOT_CENTRE_U = 0.5 + DOT_CELL / 2.0
    private const val DOT_CENTRE_V = DOT_CELL / 2.0

    private val DATA_IMAGE_RE = Regex("base64,([^']+)")

    private val outputDir = File(System.getProperty("afield.passchain.out") ?: "build/pass-chain")

    private var failures = 0

    @JvmStatic
    fun main(args: Array<String>) {
        val keepOpen = args.contains("--keep")
        Options.enableVR = false
        Options.doVulkanSDK()

        outputDir.mkdirs()
        val window = Window("Field · Fabricate pass chain")
        val renderer = Renderer(window)

        // `StandardRenderer.build` allocates its own targets with a bare `RenderToTexture { … }`,
        // whose renderer defaults to `fromContext()` — so it only works inside a box that can see a
        // Renderer. In Field that box is `agent_graphics_context`; here it is this one. Without it
        // the failure is a NullPointerException on `Box.getProperties()` from deep inside
        // RenderToTexture's constructor, which says nothing about the missing context.
        val hostBox = Box("fabricate-pass-chain-demo").apply { name = "fabricate-pass-chain-demo" }
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
            println("pass chain tests PASSED — figures in ${outputDir.absolutePath}")
        } else {
            println("pass chain tests FAILED ($failures)")
        }
        // GLFW and Vulkan leave non-daemon threads behind, so the JVM will not exit on its own and
        // the window would sit there beachballing. Same reason ImGuiVrPanelDemo forces the exit.
        exitProcess(if (failures == 0) 0 else 1)
    }

    private fun run(renderer: Renderer, window: Window) {
        val bundle = StandardRenderer.build(
            renderer,
            StandardRenderer.Config(
                renderWidth = DIM,
                renderHeight = DIM,
                backgroundColor = Vector3f(0.09f, 0.10f, 0.13f)
            )
        )
        buildScene(bundle, renderer)
        bundle.applyScenePlan()
        bundle.present(renderer = renderer)
        bundle.attachTo(renderer)
        pump(renderer, window, WARMUP_FRAMES)

        // 01 — the counterfactual, taken before a single chain pass exists. This *is* the
        // chain-bypassed image; it is not modelled, reconstructed or assumed.
        val sceneInput = bundle.postStack.output()
        val sceneSource = capture(renderer, sceneInput, "01-scene-source")

        // Chain A, over the lit scene: the six-box hand-wired effect as one declaration.
        val sceneChain = bundle.addPassChain(
            FabricatePassLibrary.apertureChain(nearRadius = 4f, farRadius = 12f, taps = 40, blend = 0.92f),
            renderer = renderer,
            printReport = true
        )

        // Chain B, over a controlled test pattern, appended after chain A to prove chains compose.
        // Its head pass ignores `sourceTex` entirely and synthesises its own source, so the whole
        // chain is self-contained however chain A ends up looking, and the measurement below has a
        // known-frequency input rather than whatever a lit scene happens to contain.
        val patternChain = bundle.addPassChain(patternChainSpecs(), renderer = renderer, printReport = true)

        bundle.present(renderer = renderer)
        bundle.attachTo(renderer)
        pump(renderer, window, WARMUP_FRAMES)

        val sceneComposite = capture(renderer, sceneChain.output, "02-scene-composite")
        val patternSource = capture(renderer, patternChain.targetOf("pattern")!!, "03-pattern-source")
        val patternHalf = capture(renderer, patternChain.targetOf("pattern-reduce-1")!!, "04-pattern-reduce-half")
        val patternQuarter = capture(renderer, patternChain.targetOf("pattern-reduce-2")!!, "05-pattern-reduce-quarter")
        val patternDisc = capture(renderer, patternChain.targetOf("pattern-aperture")!!, "06-pattern-aperture-disc")
        val patternDisc8Bit = capture(renderer, patternChain.targetOf("pattern-aperture-8bit")!!, "07-pattern-aperture-8bit")
        val patternGaussian = capture(renderer, patternChain.targetOf("pattern-gauss-v")!!, "08-pattern-gaussian")
        val patternComposite = capture(renderer, patternChain.output, "09-pattern-composite")

        // --- what the pixels have to say ---

        guard("every capture drew something") {
            listOf(sceneSource, sceneComposite, patternSource, patternDisc, patternDisc8Bit, patternGaussian, patternComposite).forEach { shot ->
                require(shot.meanLuma > 0.002) {
                    "${shot.name} is effectively black (mean luma ${"%.5f".format(shot.meanLuma)}) — " +
                            "a failed pass leaves its target at the clear colour and the readback still succeeds"
                }
                // Deliberately low: the test pattern is a two-tone checkerboard plus saturated dots
                // and legitimately has under ten levels. The failure this catches is a *uniform*
                // image, which is what a pass that never sampled its input renders.
                require(shot.distinctLumaBuckets >= 4) {
                    "${shot.name} has only ${shot.distinctLumaBuckets} distinct luma levels — a uniform " +
                            "image is what a pass that never sampled its input produces"
                }
            }
            "7 captures non-black and non-uniform"
        }

        guard("the planned rung sizes are the sizes that came back off the GPU") {
            require(patternSource.width == DIM && patternSource.height == DIM) { "pattern ${patternSource.size}" }
            require(patternHalf.width == DIM / 2 && patternHalf.height == DIM / 2) { "half rung ${patternHalf.size}" }
            require(patternQuarter.width == DIM / 4 && patternQuarter.height == DIM / 4) { "quarter rung ${patternQuarter.size}" }
            require(patternDisc.width == DIM / 4) { "aperture rung ${patternDisc.size}" }
            require(patternComposite.width == DIM && patternComposite.height == DIM) { "composite ${patternComposite.size}" }
            "${patternSource.size} -> ${patternHalf.size} -> ${patternQuarter.size} -> composite ${patternComposite.size}"
        }

        guard("high-frequency energy collapses through the chain, against the same-run bypass") {
            val sourceHf = patternSource.highFrequencyEnergy
            val compositeHf = patternComposite.highFrequencyEnergy
            val ratio = sourceHf / compositeHf
            // Floored just under what was measured, not an order of magnitude under it: a 2x floor
            // would still pass with the aperture radius set to a single texel.
            require(ratio > HF_COLLAPSE_FLOOR) {
                "pattern high-frequency energy only fell ${"%.2f".format(ratio)}x " +
                        "(sharp ${"%.5f".format(sourceHf)} -> composite ${"%.5f".format(compositeHf)}); " +
                        "expected over $HF_COLLAPSE_FLOOR"
            }
            "pattern ${"%.2f".format(ratio)}x " +
                    "(${"%.5f".format(sourceHf)} -> ${"%.5f".format(compositeHf)})"
        }

        guard("the same collapse happens on the real lit scene, not only the test pattern") {
            val ratio = sceneSource.highFrequencyEnergy / sceneComposite.highFrequencyEnergy
            require(ratio > SCENE_HF_COLLAPSE_FLOOR) {
                "scene high-frequency energy only fell ${"%.2f".format(ratio)}x " +
                        "(sharp ${"%.5f".format(sceneSource.highFrequencyEnergy)} -> " +
                        "composite ${"%.5f".format(sceneComposite.highFrequencyEnergy)})"
            }
            "scene ${"%.2f".format(ratio)}x"
        }

        guard("the aperture's rim is hard where a Gaussian's is soft — measured against one, in this run") {
            val disc = patternDisc.radialProfileAt(DOT_CENTRE_U, DOT_CENTRE_V, leftBoundU = 0.5)
            val gauss = patternGaussian.radialProfileAt(DOT_CENTRE_U, DOT_CENTRE_V, leftBoundU = 0.5)
            require(disc.extent >= 4) { "no measurable disc around the sampled dot" }
            require(gauss.extent >= 4) { "no measurable Gaussian around the sampled dot" }
            require(!disc.truncated) { "the disc profile hit the image edge before fading — move DOT_CENTRE inwards" }
            require(!gauss.truncated) { "the Gaussian profile hit the image edge before fading" }
            // A uniform-weighted disc puts every tap at the same weight, so its profile falls off a
            // cliff at the aperture radius; a Gaussian of comparable width spends most of that width
            // decaying. Both blobs here are saturated at the centre, so flatness proves nothing and
            // only the rim can tell them apart.
            require(gauss.rimWidth > disc.rimWidth * RIM_SHARPNESS_FLOOR) {
                "the aperture's rim (${disc.rimWidth}px) is not meaningfully harder than the " +
                        "Gaussian's (${gauss.rimWidth}px) — a uniform disc should fall off far faster. " +
                        "Either the disc weighting has drifted towards a falloff or the taps collapsed."
            }
            "disc rim ${disc.rimWidth}px vs Gaussian rim ${gauss.rimWidth}px " +
                    "(${"%.2f".format(gauss.rimWidth.toDouble() / disc.rimWidth)}x harder), " +
                    "extents ${disc.extent}px and ${gauss.extent}px"
        }

        guard("the half-float rung carried a highlight its 8-bit twin clipped away") {
            // Not an inference from the plan -- an A/B on the GPU, in this run. `pattern-aperture`
            // reduces through half-float rungs; `pattern-aperture-8bit` is the same two reduces and
            // the same aperture at the same radius through R8G8B8A8_UNORM rungs. The pattern's dots
            // are 110x over white, so the 8-bit path clips them to 1.0 *before* the disc spreads
            // them and its disc comes out ~110x dimmer. The whole difference is one `format`
            // argument, which is the argument addShaderEffect did not have.
            val bright = patternDisc.meanLumaRightHalf
            val clipped = patternDisc8Bit.meanLumaRightHalf
            require(bright > clipped * FORMAT_AB_FLOOR) {
                "the half-float aperture's discs mean ${"%.5f".format(bright)} against the 8-bit " +
                        "twin's ${"%.5f".format(clipped)} — under ${FORMAT_AB_FLOOR}x means the two " +
                        "formats made the same picture, i.e. the per-pass format never took effect"
            }
            "half-float discs ${"%.4f".format(bright)} vs 8-bit ${"%.5f".format(clipped)} " +
                    "(${"%.1f".format(bright / clipped)}x brighter for one format argument)"
        }

        guard("the scene chain planned onto fewer targets than it has passes") {
            // The scene chain, not the pattern chain: the pattern chain's head is read by its own
            // composite, so it stays live to the end and *correctly* shares nothing. Asserting
            // sharing there would have been asserting a bug.
            val plan = sceneChain.plan
            require(plan.targetCount < plan.passCount) {
                "${plan.passCount} passes on ${plan.targetCount} targets — nothing was shared"
            }
            val shared = plan.slots.filter { it.passIndices.size > 1 }
            require(shared.isNotEmpty()) { "no slot took more than one pass" }
            require(plan.naiveBytes > plan.plannedBytes * 2) {
                "planned ${plan.plannedBytes} vs naive ${plan.naiveBytes} is under 2x"
            }
            "${plan.passCount} passes on ${plan.targetCount} targets " +
                    "(${shared.joinToString { slot -> slot.passIndices.joinToString("+") { plan.passes[it].name } }}), " +
                    "${"%.2f".format(plan.naiveBytes.toDouble() / plan.plannedBytes)}x under one full-res target per pass"
        }

        guard("a push constant retunes a live chain with no reallocation") {
            val before = patternChain.plan.targetCount
            val widePofile = patternDisc.radialProfileAt(DOT_CENTRE_U, DOT_CENTRE_V, leftBoundU = 0.5)
            require(!widePofile.truncated) { "the wide disc profile hit the image edge" }
            val wideExtent = widePofile.extent
            require(patternChain.setPush("pattern-aperture", "radius", 3f)) { "radius push was not accepted" }
            pump(renderer, window, SETTLE_FRAMES)
            val narrowed = capture(renderer, patternChain.targetOf("pattern-aperture")!!, "10-pattern-aperture-narrow")
            require(patternChain.plan.targetCount == before) { "retuning changed the target count" }

            // The disc's own measured extent, because that is the quantity `radius` names. The first
            // version of this assertion used high-frequency energy and expected it to *rise*; it fell
            // (0.0383 -> 0.0260), because a narrower disc spreads less light and the whole 256px
            // image dims. The push constant was reaching the shader all along -- the metric was
            // measuring the wrong thing, and a metric that moves for the wrong reason is worse than
            // no metric.
            val narrowProfile = narrowed.radialProfileAt(DOT_CENTRE_U, DOT_CENTRE_V, leftBoundU = 0.5)
            val narrowExtent = narrowProfile.extent
            require(narrowExtent >= 1) { "no measurable disc at radius 3" }
            require(!narrowProfile.truncated) { "the narrowed disc profile hit the image edge" }
            require(wideExtent > narrowExtent * PUSH_EXTENT_FLOOR) {
                "narrowing the aperture from 12 to 3 texels moved the disc extent only " +
                        "$wideExtent -> $narrowExtent px — the push constant is not reaching the shader"
            }
            "radius 12 -> 3 shrank the disc from ${wideExtent}px to ${narrowExtent}px " +
                    "(${"%.2f".format(wideExtent.toDouble() / narrowExtent)}x) with no reallocation"
        }
    }

    /**
     * A self-contained chain: synthesise a known test pattern, reduce it twice, blur the small image
     * with one aperture disc, and composite back at full resolution.
     *
     * The pyramid is half-float and the composite is not, which is the per-pass format decision the
     * old API had no argument for.
     */
    private fun patternChainSpecs(): List<FabricatePassSpec> {
        val half = VK_FORMAT_R16G16B16A16_SFLOAT
        val eightBit = VK_FORMAT_R8G8B8A8_UNORM
        val head = FabricatePassSpec(
            name = "pattern",
            fragment = PATTERN_FRAGMENT,
            size = FabricatePassSize.MatchChainInput,
            format = half,
            source = FabricatePassChain.CHAIN_INPUT,
            push = mapOf("checks" to CHECKS, "cell" to DOT_CELL, "dotRadius" to 0.022f, "dotGain" to 110f)
        )
        val halfFloatPath =
            FabricatePassLibrary.downsamplePyramid(prefix = "pattern-reduce", levels = 2, source = "pattern", format = half) +
                    FabricatePassLibrary.aperture(
                        name = "pattern-aperture",
                        radiusTexels = APERTURE_RADIUS,
                        taps = 48,
                        source = "pattern-reduce-2",
                        format = half
                    )
        // The A/B twin: the same two reduces and the same aperture at the same radius, differing only
        // in `format`. Its rungs clip the pattern's 110x-over-white dots to 1.0 before the disc gets
        // to spread them, so its discs come out drastically dimmer. One argument, visible in a PNG.
        val eightBitPath =
            FabricatePassLibrary.downsamplePyramid(prefix = "pattern-8bit", levels = 2, source = "pattern", format = eightBit) +
                    FabricatePassLibrary.aperture(
                        name = "pattern-aperture-8bit",
                        radiusTexels = APERTURE_RADIUS,
                        taps = 48,
                        source = "pattern-8bit-2",
                        format = eightBit
                    )
        // The counterfactual for the disc-shape assertion: the *same* reduced rung, blurred by the
        // separable nine-tap Gaussian the bloom chain already ships, at a step chosen so its total
        // width is comparable to the disc's. Rendered in this run so "an aperture is not a Gaussian"
        // is a comparison rather than a threshold somebody picked.
        val gaussianPath = FabricatePassLibrary.separableBlur(
            prefix = "pattern-gauss",
            radius = 4f,
            source = "pattern-reduce-2",
            format = half
        )
        val join = FabricatePassLibrary.composite(
            name = "pattern-composite",
            blurred = "pattern-aperture",
            sharp = "pattern",
            blend = 0.94f,
            format = null
        )
        return listOf(head) + halfFloatPath + eightBitPath + gaussianPath + join
    }

    /**
     * A checkerboard for high-frequency energy plus a coarse grid of very bright dots for the bokeh
     * measurement. Ignores `sourceTex`: this pass is a *source*, and a chain head that synthesises
     * its own input is why the measurement downstream is repeatable.
     */
    private const val PATTERN_FRAGMENT = """
        #version 450
        layout(location = 0) in vec2 vUv;
        layout(push_constant) uniform PatternPush {
            float checks;
            float cell;
            float dotRadius;
            float dotGain;
        } push;
        layout(location = 0) out vec4 outColor;

        void main() {
            vec3 c;
            if (vUv.x < 0.5) {
                // Left half: a fine checkerboard. This is the high-frequency energy the composite
                // has to destroy, and it is the whole basis of the collapse measurement.
                vec2 cell = floor(vUv * max(push.checks, 1.0));
                float check = mod(cell.x + cell.y, 2.0);
                c = vec3(0.04) + vec3(0.56, 0.59, 0.68) * check;
            } else {
                // Right half: eight very bright dots on near-black. Dark on purpose -- a bokeh disc
                // profile normalised against a bright background never falls far enough to measure,
                // and the 8-bit-versus-half-float comparison needs headroom above white to clip.
                float size = max(push.cell, 1e-3);
                vec2 q = fract(vec2(vUv.x - 0.5, vUv.y) / size) - 0.5;
                float d = length(q) / max(push.dotRadius / size, 1e-4);
                float spot = 1.0 - smoothstep(0.85, 1.0, d);
                c = vec3(0.012) + vec3(1.0, 0.92, 0.78) * spot * max(push.dotGain, 0.0);
            }
            outColor = vec4(c, 1.0);
        }
    """

    private fun buildScene(bundle: StandardRenderer.Bundle, renderer: Renderer) {
        bundle.scene.camera.at.set(0f, 1.5f, -4.2f)
        bundle.scene.camera.target.set(0f, 0f, 0f)
        bundle.scene.lightDirection.set(Vector3f(-0.5f, -1f, -0.3f).normalize())
        bundle.scene.lightIntensity = 3.4f
        bundle.scene.shadowStrength = 1f
        bundle.scene.aoStrength = 0.8f

        // A grid of spheres, for silhouette edges the blur has something to remove. A smooth scene
        // would make the high-frequency assertion below vacuous.
        for (ix in -2..2) {
            for (iz in -2..2) {
                bundle.registerSphere(
                    name = "ball-$ix-$iz",
                    radius = 0.3f,
                    slices = 24,
                    stacks = 24,
                    renderer = renderer
                ).setModel(Matrix4f().translate(ix * 0.85f, 0f, iz * 0.85f))
            }
        }
    }

    // ── plumbing ──────────────────────────────────────────────────────────────

    private fun pump(renderer: Renderer, window: Window, frames: Int) {
        repeat(frames) {
            window.pollEvents()
            renderer.render()
        }
    }

    /**
     * Read a target back and keep both the file and the pixels.
     *
     * `maxDim` must be at least the target's largest dimension: [TexturePreviewCache]
     * exists to make *thumbnails*, and its default would silently downscale the very rungs whose
     * sizes are the thing being checked.
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
                    "hf ${"%.5f".format(shot.highFrequencyEnergy)}"
        )
        return shot
    }

    /** A radial brightness profile around one point, normalised so the peak is 1. */
    private class Profile(private val samples: List<Double>, val extent: Int) {
        fun at(radius: Int): Double = samples.getOrElse(radius.coerceIn(0, samples.lastIndex)) { 0.0 }

        /**
         * True when the profile ran into the edge of the image before the blob faded out, so its
         * width is a property of the frame rather than of the blur.
         *
         * Every number derived from a truncated profile is meaningless, and meaningless in the
         * *direction of looking wide* — which is how a truncated profile once made a hard-rimmed disc
         * and a soft Gaussian report the identical 33px rim.
         */
        val truncated: Boolean get() = samples.last() > 0.1

        /**
         * Radial distance over which the profile falls from 90% of peak to 10% — how *hard* the blur's
         * outer edge is, in pixels.
         *
         * This, and not flatness, is what separates an aperture from a Gaussian once the highlight is
         * bright enough to saturate. A saturated disc *and* a saturated Gaussian are both flat across
         * the top; only the disc has a rim that falls off in a pixel or two while the Gaussian's takes
         * most of its width. Measuring flatness instead was the first version of this test, and it
         * failed for a third reason again: it found the *edge* of a saturated disc as its "brightest
         * pixel", so half of every ring it averaged was background.
         */
        val rimWidth: Int
            get() {
                val last90 = samples.indexOfLast { it >= 0.9 }
                if (last90 < 0) return samples.size
                val first10 = (last90 until samples.size).firstOrNull { samples[it] <= 0.1 } ?: return samples.size
                return first10 - last90
            }
    }

    /** One captured PNG, with the pixel statistics the assertions above are written in terms of. */
    private class Shot(val name: String, val file: File) {
        private val image = ImageIO.read(file) ?: error("could not re-read $name from ${file.absolutePath}")
        val width = image.width
        val height = image.height
        val size get() = "${width}x$height"

        private val luma = DoubleArray(width * height) { index ->
            val rgb = image.getRGB(index % width, index / width)
            val r = (rgb shr 16 and 0xff) / 255.0
            val g = (rgb shr 8 and 0xff) / 255.0
            val b = (rgb and 0xff) / 255.0
            0.2126 * r + 0.7152 * g + 0.0722 * b
        }

        val meanLuma = luma.average()
        val maxLuma = luma.max()
        val medianLuma = luma.sorted()[luma.size / 2]
        val distinctLumaBuckets = luma.map { (it * 64).toInt() }.distinct().size

        /**
         * Mean luminance over the right-hand half — the pattern's dark, dotted half.
         *
         * The measurement that distinguishes a half-float rung from an 8-bit one has to be a *mean*
         * and not a peak: the readback is an 8-bit PNG, so any peak over white reads back as exactly
         * 1.0 whatever the target's format was. Total energy in the region survives the conversion;
         * the peak does not. The first version of this test asserted on the peak and could not have
         * passed for a correct reason.
         */
        val meanLumaRightHalf: Double by lazy {
            var total = 0.0
            var count = 0
            for (y in 0 until height) {
                for (x in width / 2 until width) {
                    total += luma[y * width + x]
                    count++
                }
            }
            total / count
        }

        /**
         * Mean absolute discrete Laplacian of luminance — energy at the very top of the spectrum.
         *
         * A four-neighbour Laplacian is exactly zero on any locally-linear patch and large on an
         * edge, so blurring drives it down hard. Deliberately *not* a gradient magnitude: a gradient
         * survives a blur reasonably well (a soft ramp still has slope), which would make the
         * assertion far weaker than it looks.
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

        /**
         * The radial brightness profile around ([u], [v]) in normalised coordinates, out to where the
         * ring average falls to a tenth of the centre.
         *
         * The centre is given rather than searched for. Searching for the brightest pixel is what the
         * first two versions of this measurement did, and it does not work on a *saturated* blob: the
         * scan settles on whichever edge pixel it reaches first, and every ring it then averages is
         * half background. The dot grid's geometry is this file's own, so its centres are known.
         */
        fun radialProfileAt(u: Double, v: Double, leftBoundU: Double = 0.0): Profile {
            val cx = (u * width).toInt().coerceIn(0, width - 1)
            val cy = (v * height).toInt().coerceIn(0, height - 1)
            // Bounded away from the pattern's bright left half as well as from the frame. Without the
            // left bound a wide enough ring reaches the blurred checkerboard, whose mean is around
            // 0.6, and the profile never falls to a tenth of peak however narrow the blur is.
            val leftBound = (leftBoundU * width).toInt().coerceIn(0, width - 1)
            val limit = minOf(cx - leftBound, cy, width - 1 - cx, height - 1 - cy)
            require(limit >= 8) { "$name has no room for a profile at ($u, $v): limit $limit" }

            // Raw ring means first. Mean over a ring, so a single hot texel cannot define the profile
            // and the polygonal rim a finite tap count leaves behind is averaged out.
            val rings = (0..limit).map { radius ->
                var sum = 0.0
                for (angleStep in 0 until 48) {
                    val angle = angleStep * 2.0 * Math.PI / 48.0
                    val x = (cx + radius * Math.cos(angle)).toInt().coerceIn(0, width - 1)
                    val y = (cy + radius * Math.sin(angle)).toInt().coerceIn(0, height - 1)
                    sum += luma[y * width + x]
                }
                sum / 48.0
            }

            // Normalised against the *pedestal*, not against zero. The pattern's dark half is 0.012
            // in linear light, which the 8-bit sRGB readback turns into about 0.15 — above the 10%
            // "faded out" threshold, so an untruncated profile reported itself as truncated and the
            // disc and the Gaussian both came back at the frame's width. The background is a property
            // of the test pattern and has to be subtracted before any ratio means anything.
            val floor = rings.min()
            val peak = rings.first()
            require(peak - floor > 1e-4) { "$name has no blob at ($u, $v): peak $peak, floor $floor" }
            val samples = rings.map { ((it - floor) / (peak - floor)).coerceIn(0.0, 1.0) }
            var extent = 0
            samples.forEachIndexed { radius, value -> if (value > 0.1) extent = radius }
            return Profile(samples, extent)
        }
    }

    private fun guard(what: String, body: () -> String) {
        try {
            println("OK  $what — ${body()}")
        } catch (t: Throwable) {
            println("FAIL: $what — ${t.message}")
            failures++
        }
    }
}
