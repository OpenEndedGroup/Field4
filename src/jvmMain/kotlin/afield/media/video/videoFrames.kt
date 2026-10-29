package afield.media.video

import afield.media.image.Img
import afield.tools.docs.FFMPEG
import java.io.DataInputStream
import java.io.EOFException
import java.io.File

/**
 * Reading a video file frame by frame as Field images, through ffmpeg.
 *
 * [videoFrames] is the lazy sequence of frames for a path on disk, and [videoSize] answers the
 * extent they will arrive at. ffmpeg does the decoding, the frame-rate conversion and the scaling;
 * [FFMPEG] is the binary, and the ffprobe beside it reports a clip's native size. Frames arrive as
 * [Img], with channel values in 0..1, so Field's imaging and sampling work on them without a
 * conversion step.
 */

/**
 * The frames of a video file as [Img]s, decoded by ffmpeg ([FFMPEG] says which).
 *
 * ```
 * for (frame in videoFrames("public/docs/assets/particle_video/source.mkv", width = 320, fps = 12.0, gray = true)) {
 *     // each frame is an Img with values 0..1
 * }
 * ```
 *
 * Frames are scaled to [width] pixels wide (height follows the aspect ratio, rounded to even; 0 keeps
 * the native size), resampled to [fps] frames per second if given, start at [start] seconds and stop
 * after [duration] seconds if given. With [gray] each frame has one channel (luminance), otherwise
 * three (RGB). The sequence decodes lazily, one frame at a time, and stops ffmpeg when it is closed
 * early or exhausted; iterate it once.
 */
fun videoFrames(
    path: String,
    width: Int = 0,
    fps: Double? = null,
    start: Double = 0.0,
    duration: Double? = null,
    gray: Boolean = false,
): Sequence<Img> {
    require(File(path).exists()) { "no video at $path" }
    val (w, h) = videoSize(path, width)
    val channels = if (gray) 1 else 3
    val filters = buildList {
        if (fps != null) add("fps=$fps")
        add("scale=$w:$h:flags=area")
    }.joinToString(",")
    val command = buildList {
        add(FFMPEG); add("-v"); add("error")
        if (start > 0) { add("-ss"); add(start.toString()) }
        add("-i"); add(path)
        if (duration != null) { add("-t"); add(duration.toString()) }
        add("-vf"); add(filters)
        add("-f"); add("rawvideo"); add("-pix_fmt"); add(if (gray) "gray" else "rgb24"); add("-")
    }
    return sequence {
        val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        try {
            val input = DataInputStream(process.inputStream.buffered(1 shl 20))
            val bytes = ByteArray(w * h * channels)
            while (true) {
                try { input.readFully(bytes) } catch (_: EOFException) { break }
                yield(Img(w, h, channels, FloatArray(bytes.size) { (bytes[it].toInt() and 0xff) / 255f }))
            }
        } finally {
            process.destroy()
        }
    }
}

/** The (width, height) frames come out at for [width] (0 = native), via ffprobe beside [FFMPEG]. */
fun videoSize(path: String, width: Int = 0): Pair<Int, Int> {
    val probe = File(FFMPEG).resolveSibling("ffprobe").takeIf { it.canExecute() }?.path ?: "ffprobe"
    val out = ProcessBuilder(probe, "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height", "-of", "csv=p=0", path)
        .redirectErrorStream(true).start().let { p -> p.inputStream.bufferedReader().readText().also { p.waitFor() } }
    val (nw, nh) = out.trim().lines().first().split(",").map { it.trim().toInt() }
    if (width <= 0) return nw to nh
    val h = (nh.toDouble() * width / nw).let { (it / 2).toInt() * 2 }.coerceAtLeast(2)
    return width to h
}
