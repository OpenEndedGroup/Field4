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
import afield.four.fabricated.setPushBinding
import afield.four.targets.FrameHistory
import afield.four.targets.RenderToTexture
import afield.four.util.Options
import afield.four.util.Window
import afield.stdlib.linalg.Matrix4f
import afield.stdlib.linalg.Vector3f
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT
import org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
import org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceProperties
import org.lwjgl.vulkan.VkPhysicalDeviceProperties
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.system.exitProcess

/*
FIELD_TEST
id: four.reprojection.smoke
requiresDisplay: true
runner: jvm-main
entrypoint: afield.examples.four.FabricateReprojectionDemo
tags: [smoke, four, fabricate, pass-chain, reprojection, temporal, gpu, image]
timeoutSec: 420
expect:
  exitCode: 0
  stdoutContains:
    - "reprojection tests PASSED"
*/
/**
 * Holds frame-history reprojection to the pixels.
 *
 * ### Why the CPU tests are not enough, and why they are still most of the value
 *
 * `FrameHistoryTest` owns the validity rule, and that is the right place for it: whether history is
 * *available* is arithmetic over a frame id, a size and a token, and a rendered picture cannot check
 * it, because an unavailable history holds a completely plausible image of the wrong thing. It also
 * owns the reprojection matrix itself, by composing the two view-projections by hand and requiring
 * agreement over the unit cube.
 *
 * What neither can check is the half that only exists on a GPU: that the matrix reaches the shader
 * at all, that it reaches it *this frame* rather than as a value frozen at build time, that the
 * Y-flip in `FRAME_HISTORY_GLSL` matches the one the fullscreen triangle applied, and that several
 * history-reading passes in one chain agree about which frame it is. Every one of those failures
 * renders an image, and some of them render a *better-looking* image than the correct one on a still
 * frame.
 *
 * ### Everything is in ONE chain, on purpose
 *
 * `addPassChain` takes `postStack.output()` as its chain input, so appending six chains would build
 * a pipeline in which each chain's `@input` is the previous chain's *output* — and the two
 * accumulation passes being compared would be accumulating different images. One chain, every pass
 * sourcing [FabricatePassChain.CHAIN_INPUT], means every pass sees the same lit scene.
 *
 * It also makes the test stronger than six chains would. Four of the seven passes declare
 * `frameHistory`, and they all share one [FrameHistory] object, so the idempotence of
 * `FrameHistory.capture` is exercised rather than asserted: if the second pass's push binding
 * advanced the history, the third and fourth would be reprojecting against each other.
 *
 * ### What it asserts, and the population behind each number
 *
 * - **The device's push-constant budget is measured, not assumed.** `maxPushConstantsSize` off
 *   `VkPhysicalDeviceProperties`, printed, floored at the 128 bytes Vulkan guarantees. This is the
 *   number the "one composed matrix rather than three" design rests on.
 * - **A still camera has history everywhere, and zero motion.** Mode 3 over all [DIM]² texels: the
 *   green fraction above [STILL_AVAILABLE_FLOOR]. Mode 1 flat grey — the independent check, because
 *   a reprojection mirrored in `y` would report *available* everywhere while reporting large motion.
 *   Mode 2 agreeing with `vUv` to within [RAMP_TEXEL_TOLERANCE] texels — the only one of the four
 *   modes that can see a constant offset, since an offset inside a difference is invisible.
 * - **A cut drops it everywhere, and only for one frame.** Two-sided, so a flag stuck *off* fails.
 * - **A rotation and an FOV change keep it.** Floored at [MOVED_AVAILABLE_FLOOR] and *capped* at
 *   [MOVED_AVAILABLE_CEILING], because a yaw must disocclude a band at the trailing edge. Full
 *   retention under rotation is not a good result, it is a hard-wired flag.
 * - **Accumulation reduces temporal noise, against its own counterfactual.** The same per-frame
 *   noise into two accumulators, one at blend [BLEND] and one at blend 0. Variance ratio floored at
 *   [VARIANCE_FLOOR]x against a theory of `(1+b)/(1-b)` = 19x.
 * - **Reprojection is what makes accumulation survive a moving camera.** Against a
 *   same-shader-different-lookup counterfactual: an identical pass that reads its history at `vUv`.
 *   Both accumulate the same scene over the same rotation at the same blend. **This is the
 *   assertion the night was for**, and no unit test can make it: both passes plan identically,
 *   allocate identically, and render a plausible image.
 * - **`frameHistory` works without `persistent`.** `cameraMotionBlur` has no history buffer at all:
 *   a near-exact copy at rest, and measurably softer in motion. A pass that received no reprojection
 *   matrix passes the first check and fails the second.
 *
 * ### Running it
 *
 * ```
 * tools/run_field_tests.py --id four.reprojection.smoke        # PNGs in build/reprojection/
 * tools/run_field_tests.py --id four.reprojection.smoke --args=--keep
 * ```
 *
 * Needs a GPU and a window: `afield.four` has no headless mode, so offscreen targets still need a
 * `Window` for the device to exist.
 */
object FabricateReprojectionDemo {

    /** Render size of the bundle the chain is appended to, and of every accumulation buffer. */
    private const val DIM = 512

    /** Frames to let the renderer settle before anything is measured. */
    private const val WARMUP_FRAMES = 6

    /** Frames of still camera to let an accumulation converge. Ten times the effective history. */
    private const val CONVERGE_FRAMES = 40

    /** History blend for the accumulating passes. 0.9 is about ten frames of effective history. */
    private const val BLEND = 0.9f

    /**
     * Yaw per frame while the camera rotates, in radians, and rotated about the camera's own
     * position rather than about the target.
     *
     * A pure rotation on purpose. Reprojection here assumes an NDC depth (see
     * `FabricatePassLibrary.FRAME_HISTORY_GLSL`), which makes it *exact* for a camera that only
     * turned — every ray is the same ray, differently indexed — and approximate for one that
     * translated. Orbiting the target would fold the approximation's error into every number below
     * and make the sharpness A/B a measurement of two things at once.
     */
    private const val YAW_PER_FRAME = 0.006

    /** Frames the camera yaws for before a moving-camera capture. */
    private const val ROTATE_FRAMES = 24

    /**
     * Minimum fraction of texels reporting available history with a still camera.
     *
     * Should be exactly 1.0 — with an identity reprojection every texel centre maps to itself, and
     * `(x + 0.5) / 512` is strictly inside 0..1, so nothing trips `fieldHistoryValid`'s bounds test.
     * The floor leaves room for the readback's own colour quantisation and nothing else.
     */
    private const val STILL_AVAILABLE_FLOOR = 0.99

    /** Maximum fraction reporting available history on the frame after a cut. */
    private const val CUT_AVAILABLE_CEILING = 0.001

    /**
     * Minimum fraction keeping history through a rotation — the floor that matters most.
     *
     * A rotation that dropped history everywhere is what reading last frame at the same UV amounts
     * to once the camera moves, so this is the number that separates the layer from its absence.
     */
    private const val MOVED_AVAILABLE_FLOOR = 0.80

    /**
     * Maximum fraction keeping history through a rotation, and the assertion that catches a flag
     * hard-wired on.
     *
     * A yaw of [YAW_PER_FRAME] at this field of view moves the image about four texels, so roughly
     * 0.8% of a 512-wide frame was off screen last frame and must come back unavailable.
     */
    private const val MOVED_AVAILABLE_CEILING = 0.999

    /**
     * Minimum variance reduction from accumulating per-frame noise, as a ratio.
     *
     * Theory: accumulating white noise at blend `b` leaves `(1-b)/(1+b)` of its variance, so 19x at
     * `b = 0.9`. The floor is well under that, because the measurement is made through an 8-bit sRGB
     * readback whose own quantisation contributes variance no amount of accumulation removes — the
     * accumulated image's *measured* variance has a floor the theory does not.
     */
    private const val VARIANCE_FLOOR = 4.0

    /**
     * How far a converged accumulation may sit from the static scene it converged on, in mean
     * absolute 8-bit codes.
     *
     * The precondition for the comparison that follows it: at rest, reprojection is the identity, so
     * both accumulations are doing the same thing and a converged accumulation of a static image is
     * that image. If they are not both close to the scene here, the moving comparison is between two
     * different effects rather than between two lookups.
     */
    private const val REST_FIDELITY_CODES = 3.0

    /**
     * Minimum ratio by which the reprojecting accumulation must be more faithful to the live scene
     * than the same-UV one, under rotation.
     *
     * **Fidelity and not sharpness.** Written first as a high-frequency-energy ratio, this came back
     * at 0.648x — the *correct* reprojection measuring blurrier than the broken lookup — because the
     * neighbourhood clamp turns a misaligned history into a hard-edged, over-contrasted image with
     * more energy at the top of the spectrum than the aligned one. Sharpness rewards ringing.
     * Distance from the live scene does not, and it is the thing actually wanted.
     */
    private const val FIDELITY_FLOOR = 2.0

    /**
     * The motion scale mode 1 is captured at — the same default [FabricatePassLibrary.temporalDebug]
     * uses, named here because the readback has to be divided by it to get back to texels.
     */
    private const val MOTION_SCALE = 8.0

    /**
     * How much motion the motion view may report with a still camera, in texels.
     *
     * The instrument's own resolution, not a tolerance on the algorithm: one 8-bit code near the
     * mid grey that encodes zero motion is about 0.0046 in linear, which after dividing by
     * [MOTION_SCALE] and scaling to 512 texels is about 0.6 of a texel.
     */
    private const val STILL_MOTION_TEXEL_TOLERANCE = 1.0

    /** How close a still-camera motion blur has to be to its source, in mean absolute 8-bit codes. */
    private const val BLUR_IDENTITY_CODES = 2.0

    /** Minimum high-frequency energy a rotating camera's motion blur has to remove, as a ratio. */
    private const val BLUR_SOFTENING_FLOOR = 1.15

    /**
     * How far mode 2's previous-UV ramp may sit from `vUv`, in texels, with a still camera.
     *
     * Four, and the number is the instrument rather than the algorithm. The ramp is compared after
     * *decoding* the sRGB the readback applied, and sRGB is coarse in the midtones: one 8-bit code
     * near 0.5 is about 0.0046 in linear, which at 512 texels is 2.4 texels. So a tolerance under
     * about three would be below the resolution of the measurement. At four it still catches every
     * failure this mode exists for — a sign flip, a half-image offset, a transposed axis.
     */
    private const val RAMP_TEXEL_TOLERANCE = 4.0

    private val DATA_IMAGE_RE = Regex("base64,([^']+)")

    private val outputDir = File(System.getProperty("afield.reprojection.out") ?: "build/reprojection")

    private var failures = 0

    @JvmStatic
    fun main(args: Array<String>) {
        val keepOpen = args.contains("--keep")
        Options.enableVR = false
        Options.doVulkanSDK()

        outputDir.mkdirs()
        val window = Window("Field · Fabricate frame-history reprojection")
        val renderer = Renderer(window)

        // Same reason as FabricatePassChainDemo: StandardRenderer.build allocates targets through a
        // bare `RenderToTexture { … }`, whose renderer defaults to `fromContext()`, so it needs a box
        // context. Without one the failure is a NullPointerException from inside the RTT constructor.
        val hostBox = Box("fabricate-reprojection-demo").apply { name = "fabricate-reprojection-demo" }
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
            println("reprojection tests PASSED — figures in ${outputDir.absolutePath}")
        } else {
            println("reprojection tests FAILED ($failures)")
        }
        exitProcess(if (failures == 0) 0 else 1)
    }

    private fun run(renderer: Renderer, window: Window) {

        // --- the budget the design rests on, measured on this device ---

        guard("this device's push-constant budget holds one composed matrix plus a pass's constants") {
            val limit = MemoryStack.stackPush().use { stack ->
                val props = VkPhysicalDeviceProperties.malloc(stack)
                vkGetPhysicalDeviceProperties(renderer.device.physicalDevice.vkPhysicalDevice, props)
                props.limits().maxPushConstantsSize()
            }
            val historyBytes = 64 + 16      // one mat4 + one vec4: what FrameHistory actually sends
            val threeMatrices = 64 * 3      // what current + previous + inverse would have cost
            require(limit >= 128) {
                "maxPushConstantsSize is $limit bytes, under the 128 Vulkan guarantees"
            }
            require(limit >= historyBytes + 16) {
                "maxPushConstantsSize is $limit bytes; frame history alone is $historyBytes and a " +
                        "pass needs constants of its own"
            }
            "maxPushConstantsSize = $limit bytes measured here; frame history uses $historyBytes " +
                    "(one mat4 + one vec4). Three separate matrices would be $threeMatrices before " +
                    "the pass's own constants, over the 128-byte guarantee — which is why " +
                    "`reprojection` is composed on the CPU rather than sent in pieces"
        }

        val bundle = StandardRenderer.build(
            renderer,
            StandardRenderer.Config(
                renderWidth = DIM,
                renderHeight = DIM,
                backgroundColor = Vector3f(0.05f, 0.06f, 0.08f)
            )
        )
        buildScene(bundle, renderer)
        bundle.applyScenePlan()
        bundle.present(renderer = renderer)
        bundle.attachTo(renderer)
        pump(renderer, window, WARMUP_FRAMES)

        val chain = bundle.addPassChain(everything(), renderer = renderer, printReport = true)
        bundle.present(renderer = renderer)
        bundle.attachTo(renderer)

        // The noise pass has to actually vary per frame, or the variance assertion is measuring
        // nothing. *Bound*, not set: a value written here would be frame 0's noise forever, which is
        // a perfectly good-looking static noise field.
        require(chain.pass("noise")!!.setPushBinding("frame") { (renderer.frameCounter % 4096L).toFloat() }) {
            "the noise pass does not declare 'frame'"
        }

        guard("the plan records which passes take frame history, and the report says so") {
            require(chain.historyNames() == listOf("noisy", "raw", "taa", "same", "blur", "display")) {
                "history passes are ${chain.historyNames()}"
            }
            require(chain.stateNames() == listOf("noisy", "raw", "taa", "same")) {
                "state passes are ${chain.stateNames()}"
            }
            require(chain.plan.report().contains("+history")) { "the report hid the history declaration" }
            // The independence claim, on the plan side: `blur` and `display` take history and
            // allocate no ping-pong pair at all.
            require(chain.stateOf("blur") == null && chain.stateOf("display") == null) {
                "blur or display allocated a persistent target"
            }
            require(chain.plan.slots.count { it.persistent } == 4) {
                "planned ${chain.plan.slots.count { it.persistent }} persistent slots, not 4"
            }
            "${chain.historyNames().size} of ${chain.plan.passCount} passes take history and share " +
                    "one FrameHistory; 4 allocate a ping-pong pair and 2 do not, so `frameHistory` " +
                    "and `persistent` are independent declarations"
        }

        // --- still camera: availability everywhere, motion nowhere ---

        val stillAvailable = captureMode(renderer, window, bundle, chain, 3, "01-availability-still")
        val stillMotion = captureMode(renderer, window, bundle, chain, 1, "02-motion-still")
        val stillPrevUv = captureMode(renderer, window, bundle, chain, 2, "03-previous-uv-still")
        val stillBeauty = captureMode(renderer, window, bundle, chain, 0, "04-beauty-still")

        guard("a still camera has usable history at essentially every texel") {
            val green = stillAvailable.greenFraction()
            require(green > STILL_AVAILABLE_FLOOR) {
                "only ${"%.4f".format(green)} of ${stillAvailable.count} texels report available " +
                        "history with nothing moving, under $STILL_AVAILABLE_FLOOR — either the " +
                        "validity rule is rejecting a consecutive frame or the flag is not reaching " +
                        "the shader"
            }
            "${"%.4f".format(green)} of ${stillAvailable.count} texels available (floor $STILL_AVAILABLE_FLOOR)"
        }

        guard("and zero motion, which is the independent check that the matrix is not merely consistent") {
            val worst = stillMotion.worstReportedMotionInTexels(MOTION_SCALE)
            require(worst < STILL_MOTION_TEXEL_TOLERANCE) {
                "the still-camera motion view reports up to ${"%.2f".format(worst)} texels of motion " +
                        "over ${stillMotion.count} texels — the camera did not move, so the " +
                        "reprojection has to be the identity. A reprojection mirrored in y would " +
                        "report available everywhere and fail exactly here"
            }
            "worst reported motion ${"%.3f".format(worst)} texels over ${stillMotion.count} texels " +
                    "(tolerance $STILL_MOTION_TEXEL_TOLERANCE)"
        }

        guard("the previous-UV ramp agrees with vUv, which is what shows a constant offset") {
            val worst = stillPrevUv.worstRampErrorInTexels()
            require(worst < RAMP_TEXEL_TOLERANCE) {
                "the previous-UV ramp is off by up to ${"%.2f".format(worst)} texels from vUv with a " +
                        "still camera, over ${stillPrevUv.count} texels — that is a constant " +
                        "reprojection offset, and mode 1 cannot see one"
            }
            "worst ramp error ${"%.2f".format(worst)} texels over ${stillPrevUv.count} texels " +
                    "(tolerance $RAMP_TEXEL_TOLERANCE, of which about 2.4 is the sRGB readback's own " +
                    "midtone quantisation)"
        }

        guard("the scene behind all of this is actually being rendered") {
            require(stillBeauty.meanLuma > 0.02) { "the beauty pass is black (${stillBeauty.meanLuma})" }
            require(stillBeauty.distinctLumaBuckets >= 8) {
                "the beauty pass has ${stillBeauty.distinctLumaBuckets} distinct levels — a flat " +
                        "image would make every high-frequency assertion below vacuous"
            }
            "beauty ${stillBeauty.size}, mean luma ${"%.4f".format(stillBeauty.meanLuma)}, " +
                    "${stillBeauty.distinctLumaBuckets} levels, hf ${"%.5f".format(stillBeauty.highFrequencyEnergy)}"
        }

        // --- a cut drops history, for exactly one frame ---

        guard("an explicit cut drops history everywhere, and only for one frame") {
            setMode(chain, 3)
            pump(renderer, window, 2)
            bundle.invalidateFrameHistory()
            pump(renderer, window, 1)
            val cut = capture(renderer, chain.targetOf("display")!!, "05-availability-after-cut")
            val cutGreen = cut.greenFraction()
            require(cutGreen < CUT_AVAILABLE_CEILING) {
                "${"%.4f".format(cutGreen)} of ${cut.count} texels still reported available history " +
                        "on the frame after invalidateFrameHistory() — the epoch token is not reaching " +
                        "the rule, or the rule is not comparing it by identity"
            }
            pump(renderer, window, 1)
            val recovered = capture(renderer, chain.targetOf("display")!!, "06-availability-recovered")
            val recoveredGreen = recovered.greenFraction()
            require(recoveredGreen > STILL_AVAILABLE_FLOOR) {
                "history only recovered to ${"%.4f".format(recoveredGreen)} the frame after a cut — " +
                        "one invalidate must not disable history for longer than one frame"
            }
            "cut frame ${"%.4f".format(cutGreen)} available (ceiling $CUT_AVAILABLE_CEILING), " +
                    "next frame ${"%.4f".format(recoveredGreen)} (floor $STILL_AVAILABLE_FLOOR), " +
                    "over ${cut.count} texels"
        }

        // --- a rotation and an FOV change KEEP history ---

        guard("a rotating camera keeps most of its history and loses a band — both sides floored") {
            setMode(chain, 3)
            rotate(renderer, window, bundle, ROTATE_FRAMES)
            val moved = capture(renderer, chain.targetOf("display")!!, "07-availability-rotating")
            val green = moved.greenFraction()
            require(green > MOVED_AVAILABLE_FLOOR) {
                "a yaw of $YAW_PER_FRAME rad kept only ${"%.4f".format(green)} of ${moved.count} " +
                        "texels' history, under $MOVED_AVAILABLE_FLOOR — reading last frame at the " +
                        "same UV is what produces that"
            }
            require(green < MOVED_AVAILABLE_CEILING) {
                "a yawing camera kept ${"%.4f".format(green)} of its history, over " +
                        "$MOVED_AVAILABLE_CEILING — a rotation MUST disocclude a band at the trailing " +
                        "edge, so full retention means the availability flag is hard-wired on"
            }
            "${"%.4f".format(green)} of ${moved.count} texels retained through a yaw " +
                    "(floor $MOVED_AVAILABLE_FLOOR, ceiling $MOVED_AVAILABLE_CEILING)"
        }

        setMode(chain, 1)
        rotate(renderer, window, bundle, 3)
        capture(renderer, chain.targetOf("display")!!, "08-motion-rotating")
        setMode(chain, 2)
        rotate(renderer, window, bundle, 3)
        capture(renderer, chain.targetOf("display")!!, "09-previous-uv-rotating")

        guard("a field-of-view change keeps history too — it is a reindexing, not a new scene") {
            setMode(chain, 3)
            pump(renderer, window, 4)
            val before = capture(renderer, chain.targetOf("display")!!, "10-availability-before-fov")
            require(before.greenFraction() > STILL_AVAILABLE_FLOOR) {
                "the camera had not settled before the FOV change " +
                        "(${"%.4f".format(before.greenFraction())} available)"
            }
            bundle.scene.projection.fov = bundle.scene.projection.fov * 1.02
            pump(renderer, window, 1)
            val after = capture(renderer, chain.targetOf("display")!!, "11-availability-after-fov")
            val green = after.greenFraction()
            require(green > MOVED_AVAILABLE_FLOOR) {
                "a 2% FOV change kept only ${"%.4f".format(green)} of ${after.count} texels' history " +
                        "— zooming re-indexes the same rays and must not invalidate them"
            }
            bundle.scene.projection.fov = bundle.scene.projection.fov / 1.02
            "${"%.4f".format(green)} of ${after.count} texels retained through a 2% FOV change " +
                    "(floor $MOVED_AVAILABLE_FLOOR), from ${"%.4f".format(before.greenFraction())} before it"
        }

        // --- accumulation reduces noise, against its own counterfactual ---

        guard("accumulating per-frame noise reduces its variance, measured against blend zero") {
            pump(renderer, window, CONVERGE_FRAMES)
            val accumulated = capture(renderer, chain.targetOf("noisy")!!, "12-noise-accumulated")
            val single = capture(renderer, chain.targetOf("raw")!!, "13-noise-single-frame")
            // Checked before the ratio: a flat accumulated image would win this by being empty and a
            // saturated one by being clipped.
            require(single.variance > 1e-4) {
                "the counterfactual has no noise to remove (variance ${single.variance}) — the noise " +
                        "pass is not varying per frame, so the ratio below would mean nothing"
            }
            require(accumulated.maxLuma < 0.96 && accumulated.meanLuma > 0.05) {
                "the accumulated image is clipped or empty (mean ${"%.4f".format(accumulated.meanLuma)}, " +
                        "peak ${"%.4f".format(accumulated.maxLuma)}); a ratio over a clipped field " +
                        "measures the area of the clip"
            }
            val ratio = single.variance / accumulated.variance
            val theory = (1.0 + BLEND) / (1.0 - BLEND)
            require(ratio > VARIANCE_FLOOR) {
                "accumulation reduced variance only ${"%.2f".format(ratio)}x over " +
                        "${accumulated.count} texels, under $VARIANCE_FLOOR — at blend $BLEND the " +
                        "theory is ${"%.1f".format(theory)}x"
            }
            "variance ${"%.6f".format(single.variance)} -> ${"%.6f".format(accumulated.variance)}, " +
                    "${"%.2f".format(ratio)}x over ${accumulated.count} texels " +
                    "(floor $VARIANCE_FLOOR, theory ${"%.1f".format(theory)}x at blend $BLEND)"
        }

        // --- THE assertion: reprojection is what makes accumulation survive a moving camera ---

        guard("reprojection keeps a moving camera's accumulation FAITHFUL, against the same-UV lookup") {
            // Both from the same cold start, so neither has an accumulation the other does not. Then
            // the same rotation, the same blend, the same scene — the only difference in the whole
            // run is which texel of the history buffer the shader reads.
            bundle.invalidateFrameHistory()
            pump(renderer, window, 2 + CONVERGE_FRAMES)
            val restScene = capture(renderer, chain.input, "14-scene-converged-still")
            val restTaa = capture(renderer, chain.targetOf("taa")!!, "15-taa-converged-still")
            val restSame = capture(renderer, chain.targetOf("same")!!, "16-sameuv-converged-still")
            // At rest the two must agree, and both must agree with the scene: reprojection is the
            // identity, so the lookup is the same lookup and a converged accumulation of a static
            // image is that image. That is what makes the moving comparison below a statement about
            // motion and not about two different effects.
            val restTaaErr = restTaa.meanAbsoluteCodeDifference(restScene)
            val restSameErr = restSame.meanAbsoluteCodeDifference(restScene)
            require(restTaaErr < REST_FIDELITY_CODES && restSameErr < REST_FIDELITY_CODES) {
                "at rest the accumulations sit ${"%.2f".format(restTaaErr)} and " +
                        "${"%.2f".format(restSameErr)} mean codes from the scene, over " +
                        "$REST_FIDELITY_CODES — a converged accumulation of a static image is that " +
                        "image, so they are not the same effect and the moving comparison below " +
                        "would be comparing two different things"
            }

            rotate(renderer, window, bundle, ROTATE_FRAMES)
            val scene = capture(renderer, chain.input, "17-scene-rotating")
            val movedTaa = capture(renderer, chain.targetOf("taa")!!, "18-taa-rotating")
            val movedSame = capture(renderer, chain.targetOf("same")!!, "19-sameuv-rotating")
            // Fidelity to the live scene, NOT sharpness.
            //
            // This assertion was first written as a high-frequency-energy ratio and it came back
            // 0.648x — the reprojecting accumulation measured *blurrier* than the broken one — with
            // the reprojection working correctly. The reason is the neighbourhood clamp: a history
            // read at the wrong texel is far outside the 3x3 range of the pixel in front of it, so
            // it clamps hard to the local min or max, and the result is a hard-edged, over-contrasted
            // image with MORE energy at the top of the spectrum than the correct one. High-frequency
            // energy rewards ringing, so it cannot tell "sharp because aligned" from "sharp because
            // clamped". Distance from the live scene can, and it is the thing actually wanted.
            //
            // Under a pure rotation this is a strong oracle: `reprojection` is P·R·P⁻¹, and a
            // rotation about the camera position maps a ray to a ray whatever its depth, so the
            // assumed-depth approximation is *exact* here and a correct reprojection should land
            // within a texel of bilinear softening.
            val taaErr = movedTaa.meanAbsoluteCodeDifference(scene)
            val sameErr = movedSame.meanAbsoluteCodeDifference(scene)
            val ratio = sameErr / taaErr
            require(ratio > FIDELITY_FLOOR) {
                "over a ${"%.3f".format(YAW_PER_FRAME * ROTATE_FRAMES)} rad yaw the reprojecting " +
                        "accumulation sits ${"%.2f".format(taaErr)} mean codes from the live scene " +
                        "and the same-UV one ${"%.2f".format(sameErr)}, a ratio of " +
                        "${"%.2f".format(ratio)} under $FIDELITY_FLOOR — if reading history at the " +
                        "reprojected UV is no more faithful than reading it at vUv, the matrix is " +
                        "not arriving or is the identity"
            }
            "at rest both sit under ${"%.2f".format(maxOf(restTaaErr, restSameErr))} mean codes from " +
                    "the scene; over a ${"%.3f".format(YAW_PER_FRAME * ROTATE_FRAMES)} rad yaw the " +
                    "reprojecting accumulation sits ${"%.2f".format(taaErr)} mean codes from the live " +
                    "scene against the same-UV one's ${"%.2f".format(sameErr)} — " +
                    "${"%.2f".format(ratio)}x more faithful over ${movedTaa.count} texels " +
                    "(floor $FIDELITY_FLOOR). Their high-frequency energies are " +
                    "${"%.5f".format(movedTaa.highFrequencyEnergy)} and " +
                    "${"%.5f".format(movedSame.highFrequencyEnergy)}, which is why sharpness is NOT " +
                    "the metric here — the clamp makes the broken lookup ring"
        }

        // --- frameHistory without persistent ---

        guard("a history-reading pass with no history buffer is a no-op at rest and blurs in motion") {
            pump(renderer, window, 6)
            val source = capture(renderer, chain.input, "20-blur-source-still")
            val atRest = capture(renderer, chain.targetOf("blur")!!, "21-blur-still")
            val drift = atRest.meanAbsoluteCodeDifference(source)
            require(drift < BLUR_IDENTITY_CODES) {
                "a still camera's motion blur differs from its source by ${"%.2f".format(drift)} mean " +
                        "8-bit codes over ${atRest.count} texels — with no motion every tap lands on " +
                        "the same texel, so it has to be a copy"
            }
            rotate(renderer, window, bundle, ROTATE_FRAMES)
            val moving = capture(renderer, chain.targetOf("blur")!!, "22-blur-rotating")
            val movingSource = capture(renderer, chain.input, "23-blur-source-rotating")
            val softening = movingSource.highFrequencyEnergy / moving.highFrequencyEnergy
            require(softening > BLUR_SOFTENING_FLOOR) {
                "a rotating camera's motion blur removed only ${"%.3f".format(softening)}x the " +
                        "high-frequency energy of its source over ${moving.count} texels, under " +
                        "$BLUR_SOFTENING_FLOOR — a pass that received no reprojection matrix passes " +
                        "the at-rest check above and fails exactly here"
            }
            "at rest ${"%.2f".format(drift)} mean codes from its source (ceiling $BLUR_IDENTITY_CODES); " +
                    "rotating, it removes ${"%.3f".format(softening)}x the high-frequency energy " +
                    "(floor $BLUR_SOFTENING_FLOOR) over ${moving.count} texels — all with no " +
                    "persistent target"
        }

        guard("the CPU-side history agrees with what the pixels just said") {
            // The last thing the shader was told, read back off the object that told it. Cheap, and
            // it is what turns every assertion above from "the picture looks right" into "the
            // picture looks right AND the rule that produced it says so".
            require(chain.history.available) { "the chain reports no history after a settled run" }
            require(chain.history.width == DIM && chain.history.height == DIM) {
                "the history is ${chain.history.width}x${chain.history.height}, not ${DIM}²"
            }
            // Exactly one behind, and the one is the point. The push binding runs *during* a render,
            // while `frameCounter` still holds that frame's id; the counter advances after the
            // render completes. So a history in step with the renderer reads `frameCounter - 1`
            // from out here, and a history that read `frameCounter` would mean the capture had run
            // after the frame rather than inside it. Measured, not assumed: this assertion was
            // written as equality first and failed at 192 against 193.
            val lag = renderer.frameCounter - chain.history.frameId
            require(lag == 1L) {
                "the history stands at frame ${chain.history.frameId} and the renderer at " +
                        "${renderer.frameCounter}, a lag of $lag — at 1 the capture ran inside the " +
                        "last render; anything else and it is not being driven once per frame"
            }
            require(chain.history.packed.size == FrameHistory.PACKED_FLOATS)
            require(chain.history.packed[FrameHistory.PACKED_AVAILABLE] == 1f)
            "${chain.history}, packed ${FrameHistory.PACKED_FLOATS} floats, exactly $lag frame " +
                    "behind renderer.frameCounter = ${renderer.frameCounter}"
        }
    }

    // ── the one chain every assertion is written against ─────────────────────

    /**
     * Seven passes, all sourcing the lit scene, four of them taking frame history.
     *
     * One chain rather than six, because `addPassChain` takes `postStack.output()` as its chain
     * input: six chains would be a pipeline, and the two accumulations being compared would be
     * accumulating different images. Here `noisy`/`raw` accumulate the same noise, and `taa`/`same`
     * accumulate the same scene.
     */
    private fun everything(): List<FabricatePassSpec> = buildList {
        // Per-frame noise, which is its own source — it ignores `sourceTex` — so the variance
        // assertion does not depend on anything the rendered scene is doing.
        add(
            FabricatePassSpec(
                name = "noise",
                fragment = NOISE_FRAGMENT,
                size = FabricatePassSize.Absolute(DIM, DIM),
                format = VK_FORMAT_R16G16B16A16_SFLOAT,
                source = FabricatePassChain.CHAIN_INPUT,
                push = mapOf("frame" to 0f)
            )
        )
        // The variance A/B: the same noise, two blends.
        addAll(FabricatePassLibrary.temporalAccumulate("noisy", "noise", BLEND, resolution = DIM to DIM))
        addAll(FabricatePassLibrary.temporalAccumulate("raw", "noise", 0f, resolution = DIM to DIM))
        // The reprojection A/B: the same scene, two lookups.
        addAll(
            FabricatePassLibrary.temporalAccumulate(
                "taa", FabricatePassChain.CHAIN_INPUT, BLEND, resolution = DIM to DIM
            )
        )
        add(sameUvAccumulate("same", BLEND))
        // History with no history buffer.
        addAll(
            FabricatePassLibrary.cameraMotionBlur(
                name = "blur",
                source = FabricatePassChain.CHAIN_INPUT,
                strength = 1f,
                taps = 17
            )
        )
        // Last, so the debug view is what the window shows.
        addAll(FabricatePassLibrary.temporalDebug("display", FabricatePassChain.CHAIN_INPUT))
    }

    /**
     * The reprojection counterfactual: [FabricatePassLibrary.temporalAccumulate]'s pass with its
     * history read at `vUv` instead of at the reprojected UV.
     *
     * The *same* push block and the same `frameHistory = true`, so the matrix still arrives and the
     * validity flag is still honoured. The one thing it does differently is the lookup, which is
     * what makes the comparison a statement about reprojection rather than about accumulation, a
     * blend weight, a target format, a plan, or a rejection rule.
     */
    private fun sameUvAccumulate(name: String, blend: Float): FabricatePassSpec = FabricatePassSpec(
        name = name,
        fragment = SAME_UV_ACCUMULATE_FRAGMENT,
        size = FabricatePassSize.Absolute(DIM, DIM),
        format = VK_FORMAT_R16G16B16A16_SFLOAT,
        source = FabricatePassChain.CHAIN_INPUT,
        extraInputs = listOf(FabricatePassInput("historyTex", name, previousFrame = true)),
        push = mapOf("blend" to blend, "rejection" to 0f, "ndcDepth" to 0.5f),
        persistent = true,
        frameHistory = true
    )

    /**
     * Flat mid grey plus per-frame white noise, from a hash of the texel and the frame number.
     *
     * Ignores `sourceTex`: this pass is a source. The amplitude is ±0.25 about 0.5, so neither the
     * noisy image nor a 19x-averaged version of it comes near either rail — which matters, because a
     * clipped field's variance is the variance of its clip and not of the noise.
     */
    private const val NOISE_FRAGMENT = """
        #version 450
        layout(location = 0) in vec2 vUv;
        layout(set = 0, binding = 0) uniform sampler2D sourceTex;
        layout(push_constant) uniform NoisePush {
            float frame;
        } push;
        layout(location = 0) out vec4 outColor;

        float hash(vec3 p) {
            p = fract(p * vec3(443.897, 441.423, 437.195));
            p += dot(p, p.yzx + 19.19);
            return fract((p.x + p.y) * p.z);
        }

        void main() {
            vec2 texel = floor(vUv * float($DIM));
            float n = hash(vec3(texel, push.frame));
            outColor = vec4(vec3(0.5 + (n - 0.5) * 0.5), 1.0);
        }
    """

    /**
     * [FabricatePassLibrary.TEMPORAL_ACCUMULATE_FRAGMENT] with `hUv` replaced by `vUv` in the one
     * place it matters.
     *
     * Kept as its own literal rather than generated by a string substitution on the library's
     * shader, because a counterfactual whose construction depends on the thing it is a
     * counterfactual to is not one: a change to the library's source that happened to move the
     * substring would silently turn this into a copy of the real pass, and the comparison into 1.0.
     */
    private val SAME_UV_ACCUMULATE_FRAGMENT = """
        #version 450
        layout(location = 0) in vec2 vUv;
        layout(set = 0, binding = 0) uniform sampler2D sourceTex;
        layout(set = 0, binding = 1) uniform sampler2D historyTex;
        layout(push_constant) uniform TemporalPush {
            mat4 reprojection;
            vec4 historyParams;
            float blend;
            float rejection;
            float ndcDepth;
        } push;
        layout(location = 0) out vec4 outColor;

${FabricatePassLibrary.FRAME_HISTORY_GLSL}

        void main() {
            vec4 here = texture(sourceTex, vUv);
            // The whole counterfactual: the reprojected UV is computed, and then not used.
            vec2 hUv = fieldHistoryUv(push.reprojection, vUv, push.ndcDepth);
            if (!fieldHistoryValid(push.historyParams, hUv)) {
                outColor = here;
                return;
            }

            vec2 t = 1.0 / vec2(textureSize(sourceTex, 0));
            vec3 lo = here.rgb;
            vec3 hi = here.rgb;
            for (int dy = -1; dy <= 1; ++dy) {
                for (int dx = -1; dx <= 1; ++dx) {
                    vec3 c = texture(sourceTex, vUv + t * vec2(dx, dy)).rgb;
                    lo = min(lo, c);
                    hi = max(hi, c);
                }
            }
            vec3 pad = (hi - lo) * 0.5 * max(push.rejection, 0.0);
            lo -= pad;
            hi += pad;

            vec3 history = clamp(texture(historyTex, vUv).rgb, lo, hi);
            outColor = vec4(mix(here.rgb, history, clamp(push.blend, 0.0, 0.999)), here.a);
        }
    """

    private fun buildScene(bundle: StandardRenderer.Bundle, renderer: Renderer) {
        bundle.scene.camera.at.set(0f, 1.4f, -4.2f)
        bundle.scene.camera.target.set(0f, 0f, 0f)
        bundle.scene.lightDirection.set(Vector3f(-0.5f, -1f, -0.3f).normalize())
        bundle.scene.lightIntensity = 3.4f
        bundle.scene.shadowStrength = 1f
        bundle.scene.aoStrength = 0.8f
        // Motion blur off in the *standard* stack. This file is about the chain layer's own
        // reprojection, and leaving the g-buffer's blur on would put a second, different temporal
        // effect upstream of every measurement below.
        bundle.scene.motionBlurStrength = 0f

        // A grid of spheres, so the high-frequency assertions have silhouette edges to measure. A
        // smooth scene would make every sharpness ratio here vacuous.
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

    // ── plumbing ─────────────────────────────────────────────────────────────

    private fun pump(renderer: Renderer, window: Window, frames: Int) {
        repeat(frames) {
            window.pollEvents()
            renderer.render()
        }
    }

    /**
     * Pump [frames] frames, yawing the camera **before** each one.
     *
     * Before, not after: the last thing that happens has to be a render whose previous frame saw a
     * different camera, or the captured frame's motion is zero and every moving-camera assertion
     * silently becomes a still-camera one that happens to pass.
     *
     * `aroundTarget = false` — a rotation about the camera's own position. See [YAW_PER_FRAME].
     */
    private fun rotate(renderer: Renderer, window: Window, bundle: StandardRenderer.Bundle, frames: Int) {
        repeat(frames) {
            bundle.scene.camera.yaw(YAW_PER_FRAME, aroundTarget = false)
            window.pollEvents()
            renderer.render()
        }
    }

    private fun setMode(chain: FabricateBuiltChain, mode: Int) {
        require(chain.setPush("display", "mode", mode)) { "the display pass does not declare 'mode'" }
    }

    /** Push a debug mode, advance [frames] still frames, and capture the display pass's target. */
    private fun captureMode(
        renderer: Renderer,
        window: Window,
        bundle: StandardRenderer.Bundle,
        chain: FabricateBuiltChain,
        mode: Int,
        name: String,
        frames: Int = 3
    ): Shot {
        setMode(chain, mode)
        pump(renderer, window, frames)
        return capture(renderer, chain.targetOf("display")!!, name)
    }

    /**
     * Read a target back and keep both the file and the pixels.
     *
     * `maxDim` is the target's own largest dimension: [TexturePreviewCache] makes *thumbnails* by
     * default, and a downscaled availability mask would blend its red band into its green field and
     * turn every fraction below into a different number.
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
                    "hf ${"%.5f".format(shot.highFrequencyEnergy)}, var ${"%.6f".format(shot.variance)}"
        )
        return shot
    }

    /** One captured PNG, with the statistics the assertions are written in terms of. */
    private class Shot(val name: String, file: File) {
        private val image = ImageIO.read(file) ?: error("could not re-read $name from ${file.absolutePath}")
        val width = image.width
        val height = image.height
        val size get() = "${width}x$height"

        /** The population every fraction, mean and worst-case below is over. */
        val count = width * height

        private val rgb = IntArray(count) { image.getRGB(it % width, it / width) }

        private fun r(i: Int) = rgb[i] shr 16 and 0xff
        private fun g(i: Int) = rgb[i] shr 8 and 0xff
        private fun b(i: Int) = rgb[i] and 0xff

        private val luma = DoubleArray(count) {
            0.2126 * r(it) / 255.0 + 0.7152 * g(it) / 255.0 + 0.0722 * b(it) / 255.0
        }

        val meanLuma = luma.average()
        val maxLuma = luma.max()
        val distinctLumaBuckets = luma.map { (it * 64).toInt() }.distinct().size

        /**
         * Spatial variance of luminance, left in **encoded** space on purpose.
         *
         * The only thing it is used for here is a *ratio* of the same statistic between two captures
         * of the same shape at the same exposure, which does not need decoding — and decoding would
         * import the sRGB curve's steepness in the dark into a comparison that does not want it.
         */
        val variance: Double by lazy {
            val mean = luma.average()
            luma.sumOf { (it - mean) * (it - mean) } / count
        }

        /**
         * Mean absolute discrete Laplacian of luminance — energy at the top of the spectrum.
         *
         * Deliberately not a gradient magnitude: a gradient survives a blur reasonably well, which
         * would make every sharpness ratio in this file far weaker than it looks.
         */
        val highFrequencyEnergy: Double by lazy {
            var total = 0.0
            var n = 0
            for (y in 1 until height - 1) {
                for (x in 1 until width - 1) {
                    val here = luma[y * width + x]
                    val lap = luma[y * width + x - 1] + luma[y * width + x + 1] +
                            luma[(y - 1) * width + x] + luma[(y + 1) * width + x] - 4 * here
                    total += abs(lap)
                    n++
                }
            }
            total / n
        }

        /**
         * Fraction of texels the availability view painted green rather than red.
         *
         * Read as "green dominates red" rather than against the exact colours, because the readback
         * is sRGB-encoded and the two only have to be separable, not exact.
         */
        fun greenFraction(): Double = (0 until count).count { g(it) > r(it) }.toDouble() / count

        /**
         * Worst motion the motion view reports, **in texels**, decoded out of the readback.
         *
         * Mode 1 encodes motion as `motion * motionScale * 0.5 + 0.5`, so zero motion is 0.5 in
         * *linear* light — and the readback is sRGB-encoded, which puts it at code 188 and not at
         * 128. Comparing against 128 is how this assertion first failed by exactly 60 codes with
         * nothing at all wrong with the shader: `linearToSrgb(0.5) * 255 = 188`. Decoding first and
         * reporting a distance on the image is both correct and a number that means something.
         */
        fun worstReportedMotionInTexels(motionScale: Double): Double {
            var worst = 0.0
            for (i in 0 until count) {
                val du = (srgbToLinear(r(i) / 255.0) - 0.5) * 2.0 / motionScale * width
                val dv = (srgbToLinear(g(i) / 255.0) - 0.5) * 2.0 / motionScale * height
                worst = maxOf(worst, abs(du), abs(dv))
            }
            return worst
        }

        /**
         * Worst disagreement between the previous-UV view and `vUv` itself, **in texels**.
         *
         * Only meaningful with a still camera, where the reprojected coordinate *is* `vUv`. Decoded
         * out of the sRGB the readback applied before comparing, and expressed in texels rather than
         * in codes, so the tolerance can be reasoned about as a distance on the image rather than as
         * a position on a transfer curve.
         */
        fun worstRampErrorInTexels(): Double {
            var worst = 0.0
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val i = y * width + x
                    val u = srgbToLinear(r(i) / 255.0) * width
                    val v = srgbToLinear(g(i) / 255.0) * height
                    worst = maxOf(worst, abs(u - (x + 0.5)), abs(v - (y + 0.5)))
                }
            }
            return worst
        }

        /** Mean absolute per-channel difference against [other], in 8-bit codes. */
        fun meanAbsoluteCodeDifference(other: Shot): Double {
            require(other.width == width && other.height == height) {
                "$name is $size and ${other.name} is ${other.size}"
            }
            var total = 0.0
            for (i in 0 until count) {
                total += abs(r(i) - other.r(i)) + abs(g(i) - other.g(i)) + abs(b(i) - other.b(i))
            }
            return total / (count * 3.0)
        }
    }

    /** The sRGB electro-optical transfer function, which the readback applied and nothing undid. */
    private fun srgbToLinear(encoded: Double): Double =
        if (encoded <= 0.04045) encoded / 12.92 else Math.pow((encoded + 0.055) / 1.055, 2.4)

    private fun guard(what: String, body: () -> String) {
        try {
            println("OK  $what — ${body()}")
        } catch (t: Throwable) {
            println("FAIL: $what — ${t.message}")
            failures++
        }
    }
}
