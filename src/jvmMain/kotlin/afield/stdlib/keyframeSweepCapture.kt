@file:afield.CompletionCategory("Graphics")

/**
 * The GPU half of a keyframe sweep: turning one blended look into the still and the short clip that
 * a night's archive is actually made of.
 *
 * [KeyframeSweep] is renderer-free on purpose — it blends, it records, it calls your callback. But
 * *most* callbacks do the same two things, and they are the two things with traps in them: an
 * offscreen readback that can come back empty, and a frame sequence that has to be muxed by a tool
 * which may not be installed. So they live here once rather than in every sweep.
 *
 * Both helpers follow the pattern the RectSL and StackSL gallery harnesses established: **one**
 * renderer and one [RenderToTexture] for the whole run, reused across thousands of renders, with
 * only the uniforms changing. Building a target per render is what makes an overnight sweep run out
 * of device memory at 3am.
 *
 * Neither helper will invent a file. A readback that comes back empty and a missing `ffmpeg` are
 * both recorded as an artifact with a null path and a stated reason, and the frame PNGs are left on
 * disk so the clip can be muxed later. An archive that says what it is missing can be analysed; one
 * with a plausible-looking wrong file in it cannot.
 */

package afield.stdlib

import afield.four.Renderer
import afield.four.TexturePreviewCache
import afield.four.targets.RenderToTexture
import afield.logger.lprintln
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.math.max

private val dataImageRe = Regex("base64,([^']+)")

/**
 * Suspend until this renderer has presented [frames] more frames, or fail after [timeoutMs].
 *
 * The unit a sweep callback actually waits in. `delay(16)` waits for a wall clock that has nothing
 * to do with whether the GPU drew anything; a uniform written this instant is visible only once the
 * frame that read it has been submitted, so "wait two frames" is the correct instruction and
 * "sleep 32ms" is a guess that goes wrong exactly when the machine is busy — which, on the night of
 * a long sweep, is always.
 */
suspend fun Renderer.awaitFrames(frames: Int = 1, timeoutMs: Long = 15_000L) {
    val target = frameCounter + frames
    val deadline = System.nanoTime() + timeoutMs * 1_000_000L
    while (frameCounter < target) {
        if (System.nanoTime() > deadline) {
            error("timed out waiting for $frames renderer frame(s): at $frameCounter, wanted $target")
        }
        delay(8)
        yield()
    }
}

/**
 * Read [rtt] back and write it into this render's directory as a PNG, at full target resolution.
 *
 * Waits [settleFrames] renderer frames first, so the blend applied by the sweep has actually been
 * drawn. Registers a `still` artifact either way: with the file, or with the reason there is none.
 * Returns null rather than throwing on an empty readback, because one unreadable frame should cost a
 * render rather than the night.
 */
suspend fun SweepRender.captureStill(
    renderer: Renderer,
    rtt: RenderToTexture,
    name: String = "still.png",
    settleFrames: Int = 2,
    kind: String = "still"
): Path? {
    if (settleFrames > 0) renderer.awaitFrames(settleFrames)
    val target = file(name)
    val bytes = sweepTargetPngBytes(renderer, rtt)
    if (bytes == null || bytes.isEmpty()) {
        artifact(kind, note = "readback returned no pixels for ${rtt.parameters.debugName.ifEmpty { "the sweep target" }}")
        return null
    }
    Files.write(target, bytes)
    artifact(kind, target)
    return target
}

/**
 * Render a short clip of this blend: [frames] readbacks into a `frames/` subdirectory, muxed to
 * H.264 by `ffmpeg`.
 *
 * [advance] is called once per frame with the frame index and its normalised time, and is where the
 * callback moves whatever should move — a camera, a phase, a simulation step. It runs *before* the
 * [settleFrames] wait and the readback, so frame `i` shows the state `advance(i, …)` established.
 *
 * Returns the mp4, or null when it could not be made — no `ffmpeg` on the machine, or no pixels.
 * The frame PNGs survive in either case unless [keepFrames] is false, which makes a failed mux
 * recoverable: the same archive can be muxed the next morning without re-rendering the night.
 *
 * Deliberately not recorded through Field's window recorder: that path drives a browser page, and
 * this one is reading an offscreen target in a headless-capable loop with no window involved.
 */
suspend fun SweepRender.captureClip(
    renderer: Renderer,
    rtt: RenderToTexture,
    frames: Int,
    fps: Int = 30,
    name: String = "clip.mp4",
    settleFrames: Int = 1,
    keepFrames: Boolean = true,
    kind: String = "clip",
    advance: suspend (frame: Int, t: Double) -> Unit
): Path? {
    require(frames >= 1) { "a clip needs at least one frame, not $frames" }
    require(fps >= 1) { "a clip needs a positive frame rate, not $fps" }

    val framesDir = file("frames")
    framesDir.createDirectories()
    var written = 0
    for (i in 0 until frames) {
        advance(i, if (frames > 1) i.toDouble() / (frames - 1) else 0.0)
        if (settleFrames > 0) renderer.awaitFrames(settleFrames)
        val bytes = sweepTargetPngBytes(renderer, rtt) ?: continue
        if (bytes.isEmpty()) continue
        Files.write(framesDir.resolve("frame_%05d.png".format(i)), bytes)
        written++
    }

    if (written == 0) {
        artifact(kind, note = "no frames read back; nothing to mux")
        return null
    }
    artifact("frames", framesDir, note = "$written PNG frame(s) at $fps fps")

    val ffmpeg = resolveFfmpeg()
    if (ffmpeg == null) {
        artifact(kind, note = "ffmpeg not found; $written frames left in frames/ to mux later")
        return null
    }
    val target = file(name)
    val failure = encodeMp4(ffmpeg, framesDir.toFile(), "frame_%05d.png", fps, target.toFile())
    if (failure != null) {
        artifact(kind, note = "ffmpeg failed: $failure")
        return null
    }
    artifact(kind, target)
    if (!keepFrames) {
        runCatching {
            Files.list(framesDir).use { it.forEach { f -> runCatching { Files.delete(f) } } }
            Files.delete(framesDir)
        }
    }
    return target
}

/**
 * One offscreen colour attachment as full-resolution PNG bytes, or null.
 *
 * Goes through [TexturePreviewCache.capture] — the readback the texture visualizers use — with
 * `maxDim` set to the target's own longest side, which is what stops it from handing back the 128px
 * thumbnail it exists to produce. That call returns an `<img>` tag rather than bytes, so the base64
 * comes back out of it; the same shim exists in the RectSL and StackSL harnesses.
 *
 * Internal rather than private so that a test comparing a sweep's stills against a fresh render can
 * use the *same* readback rather than a second implementation of it — otherwise the comparison is
 * partly a test of two decoders agreeing.
 */
internal fun sweepTargetPngBytes(renderer: Renderer, rtt: RenderToTexture): ByteArray? {
    val width = rtt.parameters.width
    val height = rtt.parameters.height
    if (width <= 0 || height <= 0) return null
    val html = TexturePreviewCache.capture(
        renderer = renderer,
        vkImage = rtt.colorImageHandles[0],
        format = rtt.colorFormats[0],
        srcWidth = width,
        srcHeight = height,
        currentLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
        maxDim = max(width, height)
    ) ?: return null
    val encoded = dataImageRe.find(html)?.groupValues?.get(1) ?: return null
    return runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
}

/** The first `ffmpeg` that exists, or null — the caller reports the gap rather than guessing. */
private fun resolveFfmpeg(): String? {
    val candidates = listOfNotNull(
        System.getenv("FFMPEG"),
        "/opt/homebrew/bin/ffmpeg",
        "/usr/local/bin/ffmpeg",
        "/usr/bin/ffmpeg"
    )
    candidates.firstOrNull { File(it).canExecute() }?.let { return it }
    // Nothing at a known path; a bare name may still resolve on PATH.
    val probe = runCatching {
        ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start().let {
            it.inputStream.close()
            it.waitFor(5, TimeUnit.SECONDS) && it.exitValue() == 0
        }
    }.getOrDefault(false)
    return if (probe) "ffmpeg" else null
}

/** Mux a numbered PNG sequence into a web-ready H.264 mp4. Returns null on success, or the failure. */
private fun encodeMp4(ffmpeg: String, framesDir: File, pattern: String, fps: Int, output: File): String? {
    output.parentFile?.mkdirs()
    output.delete()
    val process = ProcessBuilder(
        ffmpeg, "-y",
        "-framerate", fps.toString(),
        "-i", pattern,
        "-c:v", "libx264",
        "-pix_fmt", "yuv420p",
        "-crf", "20",
        "-movflags", "+faststart",
        output.absolutePath
    ).directory(framesDir).redirectErrorStream(true).start()
    val log = process.inputStream.bufferedReader().readText()
    if (!process.waitFor(120, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return "timed out after 120s"
    }
    if (process.exitValue() != 0) return "exit ${process.exitValue()}: ${log.takeLast(300).replace('\n', ' ')}"
    if (!output.toPath().exists() || output.length() == 0L) return "wrote an empty file"
    return null
}

/**
 * Mux every render in an archive that has frames but no clip — the morning repair for a night that
 * ran without `ffmpeg` installed.
 *
 * Answers how many clips it wrote. Does not touch the manifests: a render's recorded artifacts stay
 * as the night saw them, and the clip is simply there now. Rerun [KeyframeSweep.reindex] if the
 * distinction matters to your analysis.
 */
fun muxSweepArchiveClips(out: Path, fps: Int = 30, name: String = "clip.mp4"): Int {
    val ffmpeg = resolveFfmpeg() ?: run {
        lprintln("warning,keyframe", "muxSweepArchiveClips: no ffmpeg available")
        return 0
    }
    val renders = out.resolve("renders")
    if (!renders.exists()) return 0
    var made = 0
    Files.list(renders).use { stream ->
        stream.filter { Files.isDirectory(it) }.forEach { dir ->
            val framesDir = dir.resolve("frames")
            val clip = dir.resolve(name)
            if (!framesDir.exists() || clip.exists()) return@forEach
            val failure = encodeMp4(ffmpeg, framesDir.toFile(), "frame_%05d.png", fps, clip.toFile())
            if (failure == null) made++
            else lprintln("warning,keyframe", "muxSweepArchiveClips: ${dir.fileName} failed — $failure")
        }
    }
    return made
}
