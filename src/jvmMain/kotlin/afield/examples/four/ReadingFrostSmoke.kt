package afield.examples.four

import afield.geometry.path.FLine
import afield.graphics.scene.reading.*
import afield.graphics.text.Typefaces
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.targets.MultiviewStereoTarget
import afield.graphics.vulkan.util.Options
import afield.graphics.vulkan.util.Window
import afield.math.linalg.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.system.exitProcess

/*
FIELD_TEST
id: four.reading.frost
runner: jvm-main
entrypoint: afield.examples.four.ReadingFrostSmoke
requiresDisplay: true
tags: [smoke, vulkan, stereo, reading]
timeoutSec: 180
expect:
  exitCode: 0
  stdoutContains:
    - "Reading frost PASSED"
  stdoutNotContains:
    - "VUID-"
    - "Validation Error"
  stderrNotContains:
    - "VUID-"
    - "Validation Error"
*/
/** Production shaders/RTTs on the real GPU: replacement identity, foreground isolation and pool churn. */
object ReadingFrostSmoke {
    @JvmStatic
    fun main(args: Array<String>) {
        Options.enableVR = false
        Options.enableHDR = false
        Options.validate = true
        val window = Window("Reading frost regression")
        val renderer = Renderer(window)
        val stereo = MultiviewStereoTarget(renderer, 640, 480, withDepth = false)
        stereo.stereoCamera.base.at = Vector3f(0f, 0f, 2.35f)
        stereo.stereoCamera.base.target = Vector3f()
        lateinit var panel: DiagramTextFrame
        val graph = readingDiagram {
            val text = text("text", "HI\nH", point("p", 0, 0, pinned = true)) {
                blurCapacity = .1f
                blur = .03f
            }
            panel = textFrame("frame", text, .08f, DiagramTextFrameMode.LINE_UNION) {
                color = Vector4f(1f, 1f, 1f, .1f)
            }
        }
        val player = ReadingDiagramPlayer.prepare(
            graph,
            Typefaces.named("Serif", 100),
            File(args.firstOrNull() ?: "native/field-slug/build/field-slug"),
            diagramScore(graph, 10.0) {},
        )
        player.playback.playing = false
        player.textScale = .004f
        var foreground = true
        val scene = object : ReadingScene by player {
            override fun update(elapsedSeconds: Double) {
                player.update(0.0)
                for (i in -35..35) {
                    val x = i * .024f
                    geometry.stroke(
                        FLine().moveTo(x, -.8f, -.3f).lineTo(x, .8f, -.3f),
                        DiagramInk(width = .0018f, color = Vector4f(1f, 1f, 1f, .25f)),
                    )
                }
                if (foreground)
                    geometry.stroke(
                        FLine().moveTo(-.9f, -.2f, .3f).lineTo(.9f, .2f, .3f),
                        DiagramInk(width = .005f, color = Vector4f(1f)),
                    )
            }
        }
        val drawing = StereoDiagramRenderer(
            renderer,
            stereo,
            scene,
            textBlurConfig = ReadingTextBlurConfig(width = 256, height = 128),
        )
        drawing.followReader = false
        fun frame() {
            val previous = renderer.frameCounter
            val deadline = System.nanoTime() + 5_000_000_000L
            do {
                window.pollEvents()
                renderer.render()
                check(renderer.lastRenderFailure == null) { renderer.lastRenderFailure.toString() }
            } while (renderer.frameCounter == previous && System.nanoTime() < deadline)
            check(renderer.frameCounter > previous) { "No frame submitted" }
        }
        fun read(): FloatArray {
            val request = drawing.compositor.sharp.readPixelsAsync()
            val end = System.nanoTime() + 15_000_000_000L
            while (!request.isDone && System.nanoTime() < end) frame()
            check(request.isDone) { "Frost readback timed out" }
            val data = ByteBuffer.wrap(request.get()).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(data.remaining() / 2) { java.lang.Float.float16ToFloat(data.getShort(it * 2)) }
        }
        fun difference(a: FloatArray, b: FloatArray) = a.indices.maxOf { abs(a[it] - b[it]) }
        try {
            repeat(4) { frame() }
            val plain = read()
            fun save(name: String) {
                val path = File(System.getProperty("java.io.tmpdir"), "reading-frost-$name.jpg")
                val request = drawing.compositor.sharp.saveJpgAsync(path.absolutePath)
                val end = System.nanoTime() + 15_000_000_000L
                while (!request.isDone && System.nanoTime() < end) frame()
                check(request.isDone) { "Frost image save timed out" }
                request.get()
            }
            save("plain")
            check(plain.max() > .5f) { "Fixture is empty" }
            panel.frost.enabled = true
            panel.frost.blurPixels = 0f
            panel.frost.brightness = 1f
            val identity = read()
            check(drawing.frost.activeSurfaces == 1) { "Panel did not render" }
            check(difference(plain, identity) == 0f) { "Zero-blur replacement is not an exact identity" }
            panel.frost.blurPixels = 7f
            val frosted = read()
            save("blurred")
            check(frosted.indices.count { abs(frosted[it] - plain[it]) > .01f } > 200) {
                "Frost did not change the image"
            }
            // Alpha is geometry coverage, untouched by the signed correction.
            check((3 until plain.size step 4).all { plain[it] == frosted[it] })
            foreground = false
            val frostNoForeground = read()
            panel.frost.enabled = false
            val plainNoForeground = read()
            val foregroundError =
                plain.indices
                    .filter { it % 4 != 3 }
                    .maxOf {
                        abs((plain[it] - plainNoForeground[it]) - (frosted[it] - frostNoForeground[it]))
                    }
            check(foregroundError < .003f) { "Frost changed sharp foreground: $foregroundError" }
            panel.frost.enabled = true
            panel.frost.blackLevel = .3f
            val localFloor = read()
            check(localFloor.indices.count { abs(localFloor[it] - frostNoForeground[it]) > .03f } > 200) {
                "Black-level floor did not lift the panel"
            }
            check(localFloor.indices.filter { it % 4 == 1 || it % 4 == 3 }.all { localFloor[it] == frostNoForeground[it] }) {
                "Floor changed green control data or alpha coverage"
            }
            panel.frost.blackLevel = 0f
            drawing.compositor.sharp.modules.filterKeys { it.startsWith("reading-frost-") }.values.forEach {
                it.current.liveUniformStructures.getValue("FrostTone").bind("globalBlackLevel") { .3f }
            }
            val globalFloor = read()
            check(difference(localFloor, globalFloor) == 0f) { "Document and panel floors disagree" }
            drawing.compositor.sharp.modules.filterKeys { it.startsWith("reading-frost-") }.values.forEach {
                it.current.liveUniformStructures.getValue("FrostTone").bind("globalBlackLevel") { 0f }
            }
            drawing.frost.blurGamma = .5f
            val darkGamma = read()
            drawing.frost.blurGamma = 2f
            val brightGamma = read()
            check(brightGamma.indices.count { it % 4 == 0 && brightGamma[it] - darkGamma[it] > .01f } > 100) {
                "Blur-space gamma did not change the filtered image"
            }
            check(brightGamma.indices.filter { it % 4 == 1 || it % 4 == 3 }.all { brightGamma[it] == darkGamma[it] }) {
                "Blur gamma changed green control data or alpha"
            }
            check(brightGamma.all { it.isFinite() } && darkGamma.all { it.isFinite() })
            drawing.frost.blurGamma = 1f
            val restoredLinear = read()
            check(difference(restoredLinear, frostNoForeground) == 0f) { "Gamma 1 did not restore linear blur" }
            drawing.textBlurConfig.appearance.set(.8f, 1.3f, 0f, 0f)
            val clock = player.playback.sampleTime
            val origin = Vector3f(drawing.origin)
            for (minimum in listOf(64, 128, 64)) {
                val quality = ReadingTextBlurConfig(512, 256, minimum, 3, if (minimum == 128) 16 else 96, 4.5f)
                drawing.requestTextBlurRebuild(quality)
                val rebuilt = read()
                check(rebuilt.all { it.isFinite() })
                check(drawing.textBlurConfig === quality && drawing.textBlurPreparationCount > 0)
                check(drawing.textBlurConfig.appearance.x == .8f && drawing.textBlurConfig.appearance.y == 1.3f)
                check(drawing.frost.activeSurfaces == 1 && drawing.frost.omittedSurfaces == 0)
                check(player.playback.sampleTime == clock && drawing.origin.distance(origin) == 0f)
            }
            val activeQuality = drawing.textBlurConfig
            drawing.maximumTextBlurBytes = 1L
            check(runCatching { drawing.requestTextBlurRebuild(ReadingTextBlurConfig()) }.isFailure)
            check(drawing.textBlurConfig === activeQuality)
            drawing.maximumTextBlurBytes = 2L * 1024 * 1024 * 1024
            // The union is the same surface but moves into and out of view: no stale-mask ghosts.
            graph.point("p").rest.x = 100f
            player.resetPositions()
            frame()
            check(drawing.frost.activeSurfaces == 0)
            graph.point("p").rest.x = 0f
            player.resetPositions()
            frame()
            check(drawing.frost.recordedFrame == drawing.compositor.stageFrames[0])
            check(drawing.frost.recordedCommand == drawing.compositor.stageCommands[0])
            // Retire, reallocate and toggle repeatedly, while validation watches in-flight lifetimes.
            drawing.frost.warmFrames = 0
            repeat(3) {
                panel.frost.enabled = true
                frame()
                check(drawing.frost.allocatedSurfaces == 1) {
                    "Pool: active=${drawing.frost.activeSurfaces}, omitted=${drawing.frost.omittedSurfaces}, bytes=${drawing.frost.allocatedBytes}, frame=${renderer.frameCounter}"
                }
                panel.frost.enabled = false
                frame()
                check(drawing.frost.allocatedSurfaces == 0)
            }
            repeat(40) { frame() }
            println(
                "Reading frost PASSED: exact identity, clipped foreground error=$foregroundError, same-frame captures, black-level floor, blur-space gamma, live text-cache quality rebuilds, deferred retirement",
            )
        } finally {
            drawing.close()
            repeat(40) { frame() }
            renderer.renderList.preTargets.remove("stereo-multiview", stereo)
            renderer.cleanup()
        }
        exitProcess(0)
    }
}
