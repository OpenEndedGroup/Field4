package afield.examples.four

import afield.graphics.render.*
import afield.graphics.scene.video.VideoAutoExposure
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.Target
import afield.graphics.vulkan.targets.MultiviewStereoTarget
import afield.graphics.vulkan.targets.RenderToTexture
import afield.graphics.vulkan.util.Options
import afield.graphics.vulkan.util.Window
import afield.math.linalg.Vector4f
import afield.math.statistics.AlphaBetaFilter
import org.lwjgl.vulkan.VK10.*
import kotlin.math.abs
import kotlin.system.exitProcess

/*
FIELD_TEST
id: four.stereo-video.auto-exposure
runner: jvm-main
entrypoint: afield.examples.four.VideoAutoExposureSmoke
requiresDisplay: true
tags: [smoke, vulkan, stereo, video]
timeoutSec: 120
expect:
  exitCode: 0
  stdoutContains:
    - "Video auto exposure PASSED"
  stdoutNotContains:
    - "VUID-"
    - "Validation Error"
  stderrNotContains:
    - "VUID-"
    - "Validation Error"
*/
/** Real float RTT reductions, weighted pooling, directional alpha-beta tracking and asynchronous telemetry. */
object VideoAutoExposureSmoke {
    @JvmStatic fun main(args: Array<String>) {
        Options.enableVR = false
        Options.enableHDR = false
        Options.validate = true
        val window = Window("Video exposure regression")
        val renderer = Renderer(window)
        val stereo = MultiviewStereoTarget(renderer, 64, 32, withDepth = false)
        var time = 1_000_000_000L
        val meter = VideoAutoExposure(renderer, 2, now = { time })
        val targets = List(2) { RenderToTexture(renderer) {
            width = 17; height = 5; useDepth = false; colorSampled = true; format = VK_FORMAT_R16G16B16A16_SFLOAT
        } }
        val modules = targets.map { target -> Module(parameters(renderer) {
            +target; depthTest = false; depthWrite = false; blend = false
            vertex = """
                #version 450
                void main() {
                    vec2 q=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);
                    gl_Position=vec4(q*2-1,0,1);
                }
            """.trimIndent()
            fragment = """
                #version 450
                layout(push_constant) uniform Fixture { vec4 data; } pc; // base, span, width, reserved
                layout(location=0) out vec4 color;
                void main() {
                    float value=pc.data.x+pc.data.y*(gl_FragCoord.x-.5)/(pc.data.z-1);
                    if(pc.data.w>.5) value=gl_FragCoord.x<1 ? 0 : (gl_FragCoord.x>pc.data.z-1 ? 1 : pc.data.x);
                    color=vec4(vec3(value),1);
                }
            """.trimIndent()
        }).pointer() }
        val meshes = modules.mapIndexed { i, module -> Mesh(renderer, module).resize(3, 0).also {
            it.push["data"] = Vector4f(if (i == 0) .2f else .6f, .2f, 17f, 0f)
            module += "fixture" to it
            targets[i] += "fixture" to module
        } }
        val versions = longArrayOf(1, 1)
        val valid = booleanArrayOf(true, true)
        meter.settings = VideoAutoExposure.Settings(enabled = true, mode = VideoAutoExposure.Mode.PER_SCREEN,
            meterHz = 1.0, blackMaximum = 1f, whiteMinimum = 0f, whiteMaximum = 2f)
        stereo.beforeScene["meter-fixture"] = Target { command, index ->
            targets.forEach { it.render(command, index) }
            meter.beginFrame(command)
            meter.record(command, targets.mapIndexed { i, target ->
                if (valid[i]) VideoAutoExposure.Source(target, 17, 5, versions[i]) else null
            })
        }
        fun frame() { window.pollEvents(); renderer.render(); check(meter.error == null) { meter.error!! } }
        fun awaitNew(previous: Long = -1): VideoAutoExposure.Snapshot {
            val end = System.nanoTime() + 10_000_000_000L
            while ((meter.latest?.serial ?: -1) <= previous && System.nanoTime() < end) frame()
            return meter.latest?.takeIf { it.serial > previous } ?: error("GPU meter never completed: ${meter.error}")
        }
        fun near(actual: Float, expected: Float, label: String, tolerance: Float = .001f) {
            check(abs(actual - expected) <= tolerance) { "$label: $actual versus $expected" }
        }
        try {
            val first = awaitNew()
            check(first.screens.all { it.valid } && first.linked.valid)
            near(first.screens[0].rawMinimum[3], .2f, "first min")
            near(first.screens[0].rawMean[3], .3f, "first mean")
            near(first.screens[0].rawMaximum[3], .4f, "first max")
            near(first.screens[1].rawMean[3], .7f, "second mean")
            near(first.linked.rawMean[3], .5f, "pooled mean")
            near(first.linked.rawMinimum[3], .2f, "pooled min")
            near(first.linked.rawMaximum[3], .8f, "pooled max")
            near(first.linked.finiteCount[3], 170f, "partial-tile pixel count", 0f)
            near(first.screens[0].black, .19f, "black padding")
            near(first.screens[1].white, .82f, "white padding")
            val reference = AlphaBetaFilter(.25f, .05f, dt = 1f)
            reference(first.screens[0].rawMean[3])

            meshes[0].push["data"] = Vector4f(.4f, .2f, 17f, 0f); versions[0]++
            time = 2_000_000_000L
            val rising = awaitNew(first.serial)
            near(rising.screens[0].mean[3], reference(rising.screens[0].rawMean[3]), "Field alpha-beta rising", .00001f)
            val step = rising.screens[0].rawMinimum[3] - first.screens[0].rawMinimum[3]
            near(rising.screens[0].minimumVelocity[3], .05f * step, "rising velocity", .00001f)

            meshes[0].push["data"] = Vector4f(.1f, .2f, 17f, 0f); versions[0]++
            time = 3_000_000_000L
            reference.alpha = .1f; reference.beta = .01f
            val falling = awaitNew(rising.serial)
            near(falling.screens[0].mean[3], reference(falling.screens[0].rawMean[3]), "Field alpha-beta falling", .00001f)
            val corrected = falling.screens[0]
            time = 3_500_000_000L
            val predicted = awaitNew(falling.serial)
            near(predicted.screens[0].minimum[3], corrected.minimum[3], "no duplicate measurement correction", .00001f)
            near(predicted.screens[0].black, corrected.minimum[3] + corrected.minimumVelocity[3] * .5f - .01f,
                "inter-sample prediction", .00001f)
            check(meter.measurements == 3L) { "A headset render was treated as a new measurement" }
            check(meter.tileDispatches == 4L) { "Unchanged accumulation buffers were scanned again" }

            meter.settings = meter.settings.copy(blackMaximum = .2f, whiteMinimum = .9f, whiteMaximum = 1f,
                minimumSpan = .1f, maximumVelocity = .005f, mode = VideoAutoExposure.Mode.LINKED)
            time = 4_000_000_000L
            val bounded = awaitNew(predicted.serial)
            (bounded.screens + bounded.linked).forEach {
                check(it.black in 0f.. .20001f && it.white in .89999f..1.00001f)
                check(it.white - it.black >= .09999f)
                check(it.minimumVelocity.all { v -> abs(v) <= .00501f })
            }
            near(meter.appearance(0, 1f).y, 2f, "linked statistics selector", 0f)
            valid[0] = false; time = 5_000_000_000L
            val excluded = awaitNew(bounded.serial)
            check(!excluded.screens[0].valid)
            near(excluded.linked.rawMean[3], .7f, "invalid buffer excluded")
            near(excluded.linked.finiteCount[3], 85f, "valid pooled pixel count", 0f)
            check(meter.tileDispatches == 4L)
            // Gamma edits must rescan a paused buffer immediately, before the next scheduled sample.
            meter.settings = meter.settings.copy(preExposureGamma = 2f)
            time = 5_300_000_000L
            val graded = awaitNew(excluded.serial)
            near(graded.linked.rawMinimum[3], kotlin.math.sqrt(.6f), "pre-gamma minimum")
            near(graded.linked.rawMaximum[3], kotlin.math.sqrt(.8f), "pre-gamma maximum")
            val expectedMean = (0..16).map { kotlin.math.sqrt(.6f + .2f * it / 16) }.average().toFloat()
            near(graded.linked.rawMean[3], expectedMean, "gamma before mean reduction")
            near(graded.linked.mean[3], graded.linked.rawMean[3], "gamma resets tracker domain", .00001f)
            check(meter.tileDispatches == 5L) { "Gamma edit did not rescan the held valid image" }
            near(meter.appearance(0, 1f).z, 2f, "presentation pre-gamma linkage", 0f)
            // Narrow central mass with black/white outliers: the sketch should ignore the extreme columns.
            meshes[1].push["data"] = Vector4f(.4f, 0f, 17f, 1f); versions[1]++
            meter.settings = meter.settings.copy(preExposureGamma = 1f, endpointSource = VideoAutoExposure.EndpointSource.QUANTILES,
                lowQuantile = .1f, highQuantile = .9f, blackPadding = 0f, whitePadding = 0f,
                blackMinimum = 0f, blackMaximum = 1f, whiteMinimum = 0f, whiteMaximum = 2f)
            time = 6_000_000_000L
            val quantile = awaitNew(graded.serial)
            near(quantile.linked.rawMinimum[3], 0f, "outlier black")
            near(quantile.linked.rawMaximum[3], 1f, "outlier white")
            for (i in 0..2) near(quantile.linked.rawQuantiles[i], .4f, "robust sampled quantile $i")
            near(quantile.linked.black, .4f, "quantile-derived black")
            near(quantile.linked.sketchCount, 4096f, "pooled bounded samples", 0f)
            near(quantile.linked.droppedSketchPartials, 0f, "no dropped sketch partials", 0f)
            check(meter.sketchBatches == 1L && meter.sketchSamples == 8192L)
            time = 7_000_000_000L
            val cached = awaitNew(quantile.serial)
            near(cached.linked.rawQuantiles[1], .4f, "cached sketch median")
            check(meter.sketchBatches == 1L) { "Paused buffer was resampled" }
            meter.settings = meter.settings.copy(endpointSource = VideoAutoExposure.EndpointSource.EXTREMA)
            time = 8_000_000_000L
            val extrema = awaitNew(cached.serial)
            near(extrema.linked.black, 0f, "extrema comparison restored")
            check(meter.sketchBatches == 1L) { "Extrema mode dispatched a sketch" }
            val updates = meter.measurements
            meter.settings = meter.settings.copy(enabled = false)
            time = 9_000_000_000L
            repeat(5) { frame() }
            check(meter.measurements == updates) { "Disabled meter kept updating" }
            println("Video auto exposure PASSED: GPU reductions, pooling, Field alpha-beta parity, prediction, bounds, cached scans, nonblocking telemetry")
        } finally {
            stereo.beforeScene.remove("meter-fixture")
            meter.close()
            modules.forEach { it.current.cleanup() }
            renderer.cleanup()
            window.cleanup()
        }
        exitProcess(0)
    }
}
