package drowning

import afield.four.texture.video.VideoDecodeFormat
import afield.four.texture.video.VideoDecoder
import afield.four.texture.video.VideoFrame
import afield.four.texture.video.VideoFrameIndex
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** CPU-only production-pack probe used before spending a Vulkan launch on a cue. */
object DrowningCueMain {
    private data class AtlasProbe(val maximumFlow: Float)

    private fun probeAtlas(cue: DrowningCue, frame: VideoFrame, frameIndex: Int): AtlasProbe {
        val rgba = frame.plane0.duplicate()
        fun code(x: Int, y: Int, channel: Int): Int =
            rgba.get((y * cue.atlasWidth + x) * 4 + channel).toInt() and 255

        var surfaceMin = 255
        var surfaceMax = 0
        var disparityMin = Float.POSITIVE_INFINITY
        var disparityMax = Float.NEGATIVE_INFINITY
        var depthMin = Float.POSITIVE_INFINITY
        var depthMax = Float.NEGATIVE_INFINITY
        var maximumFlow = 0f

        cue.cells.forEach { cell ->
            var cellSurfaceMin = 255
            var cellSurfaceMax = 0
            for (gy in 0 until 5) for (gx in 0 until 7) {
                fun point(rect: DrowningRect): Pair<Int, Int> =
                    (rect.x + ((gx + 0.5f) * rect.width / 7f).toInt()).coerceIn(rect.x, rect.x + rect.width - 1) to
                        (rect.y + ((gy + 0.5f) * rect.height / 5f).toInt()).coerceIn(rect.y, rect.y + rect.height - 1)

                val (sx, sy) = point(cell.sourceRect)
                for (channel in 0..2) {
                    val value = code(sx, sy, channel)
                    cellSurfaceMin = min(cellSurfaceMin, value)
                    cellSurfaceMax = max(cellSurfaceMax, value)
                }

                val (dx, dy) = point(cell.disparityRect)
                val dr = code(dx, dy, 0)
                val dg = code(dx, dy, 1)
                val db = code(dx, dy, 2)
                check(abs(dr - dg) <= 1 && abs(dr - db) <= 1) {
                    "cell ${cell.index} disparity at $dx,$dy is not duplicated RGB: $dr,$dg,$db"
                }
                val disparity = cell.decodeDisparity(dr / 255f)
                val depth = cell.decodeDepth(dr / 255f)
                check(disparity.isFinite() && depth.isFinite() && depth > 0f)
                disparityMin = min(disparityMin, disparity)
                disparityMax = max(disparityMax, disparity)
                depthMin = min(depthMin, depth)
                depthMax = max(depthMax, depth)

                val (fx, fy) = point(cell.flowRect)
                val fr = code(fx, fy, 0) / 255f
                val fg = code(fx, fy, 1) / 255f
                val fb = code(fx, fy, 2)
                check(fb <= 1) { "cell ${cell.index} flow B at $fx,$fy should be unused/zero, got $fb" }
                val source = cell.decodeFlowSource(fr, fg)
                val camera = cell.decodeFlowCamera(fr, fg)
                check(source.x.isFinite() && source.y.isFinite())
                check(camera.x == source.x && camera.y == -source.y) { "flow y flip is not exactly singular" }
                maximumFlow = max(maximumFlow, sqrt(source.x * source.x + source.y * source.y))
            }
            check(cellSurfaceMax > cellSurfaceMin) { "cell ${cell.index} source RGB samples are constant" }
            surfaceMin = min(surfaceMin, cellSurfaceMin)
            surfaceMax = max(surfaceMax, cellSurfaceMax)
        }

        println(
            "ATLAS CHANNEL CHECK frame=$frameIndex cells=${cue.cells.size} " +
                "surfaceRGB=$surfaceMin..$surfaceMax disparity=%.3f..%.3f depth=%.3f..%.3f "
                    .format(disparityMin, disparityMax, depthMin, depthMax) +
                "flowMax=%.3fpx/source-frame cameraYFlip=yes".format(maximumFlow)
        )
        return AtlasProbe(maximumFlow)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val directory = File(
            args.firstOrNull { !it.startsWith("--") }
                ?: File(System.getProperty("user.home"), "Downloads/production-pack/r040/11-1705/haunted-urban-home-movie").path
        )
        val cue = DrowningCue.open(directory)
        println(cue.describe())
        cue.cells.forEach { cell ->
            println(
                "  %02d %6.1fdeg  splats=%d  q*%.4f  1/z=%.6f*q%+.4f  f=%.2f".format(
                    cell.index,
                    cell.angleDegrees,
                    cell.splatDirectory.listFiles { f -> f.name.endsWith(".compressed.ply") }?.size ?: 0,
                    cell.disparityMultiplier,
                    cell.calibration.a,
                    cell.calibration.b,
                    cell.calibration.focalLengthPixels
                )
            )
        }

        val decoder = VideoDecoder(cue.videoFile.absolutePath, VideoDecodeFormat.RGBA8)
        check(decoder.open()) { "could not open ${cue.videoFile}" }
        decoder.use {
            val frame = checkNotNull(decoder.decodeNextFrame()) { "video has no decodable frame" }
            check(frame.format == VideoDecodeFormat.RGBA8)
            check(frame.plane0.remaining() == cue.atlasWidth * cue.atlasHeight * 4) {
                "RGBA frame has ${frame.plane0.remaining()} bytes, expected ${cue.atlasWidth * cue.atlasHeight * 4}"
            }
            var probedMaximumFlow = probeAtlas(cue, frame, 0).maximumFlow
            val samples = cue.cells.take(3).joinToString { cell ->
                val x = cell.disparityRect.x + cell.disparityRect.width / 2
                val y = cell.disparityRect.y + cell.disparityRect.height / 2
                val encoded = (frame.plane0.get((y * cue.atlasWidth + x) * 4).toInt() and 255) / 255f
                "cell${cell.index}:R=%.4f q=%.3f z=%.3f".format(
                    encoded,
                    cell.decodeDisparity(encoded),
                    cell.decodeDepth(encoded)
                )
            }
            println("RGBA frame ${frame.width}x${frame.height} @ ${"%.3f".format(frame.timestamp)}s; $samples")
            if ("--verify-video-frames" in args) {
                check(VideoFrameIndex.decoded(frame.timestamp, cue.fps) == 0L)
                for (index in 1 until cue.frameCount) {
                    val next = checkNotNull(decoder.decodeNextFrame()) { "video ended at $index/${cue.frameCount}" }
                    check(VideoFrameIndex.decoded(next.timestamp, cue.fps) == index.toLong()) {
                        "video source frame $index has PTS ${next.timestamp}"
                    }
                    if (index == cue.frameCount / 2 || index == cue.frameCount - 1) {
                        probedMaximumFlow = max(probedMaximumFlow, probeAtlas(cue, next, index).maximumFlow)
                    }
                }
                check(decoder.decodeNextFrame() == null)
                check(probedMaximumFlow > 0.25f) { "sampled optical flow is unexpectedly motionless" }
                for (target in listOf(cue.frameCount - 1, 0, cue.frameCount / 2, 0)) {
                    check(decoder.seek(target / cue.fps))
                    var found = false
                    repeat(cue.frameCount + 1) {
                        if (!found) {
                            val next = checkNotNull(decoder.decodeNextFrame()) { "seek could not reach $target" }
                            val index = VideoFrameIndex.decoded(next.timestamp, cue.fps)
                            check(index <= target) { "seek overshot $target with $index" }
                            found = index == target.toLong()
                        }
                    }
                    check(found)
                }
                println("VIDEO FRAME CHECK: all ${cue.frameCount} frames including EOF drain; end/start/middle seeks passed")
            }
        }

        if ("--mxg" in args) {
            DrowningMxgKind.entries.forEach { kind ->
                MxgCueBank.open(cue, kind).use { println(it) }
                cue.cells.forEach { cell ->
                    MxgFile.open(cell.mxg(kind).toPath()).use { file ->
                        check(file.frameCount == cue.frameCount)
                        for (index in listOf(0, cue.frameCount / 2, cue.frameCount - 1)) {
                            check(file.readFrame(index).frameIndex == index)
                        }
                    }
                }
            }
            println("MXG FRAME CHECK: both sets, all 15 cells have matching 0/middle/end source-frame identities")
        }
    }
}
