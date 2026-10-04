@file:afield.CompletionCategory("Video")

package afield.stdlib

import afield.Box
import afield.controlGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import javax.imageio.ImageIO

/**
 * Rasterizes an actual recorded frame, with sampled RGB or stable identity/confidence colors.
 * [pointRadius] uses source pixel coordinates; [scale] magnifies the complete image and dots.
 * [trailLength] adds recent paths of identities visible in this frame, without bridging lifecycles.
 */
fun ParticleVideo.image(frame: Int, scale: Int = 2, pointRadius: Double = .45,
                        color: ParticleColor = ParticleColor.SAMPLED, trailLength: Int = 0): BufferedImage {
    require(scale in 1..8 && width.toLong() * height * scale * scale <= 64_000_000)
    require(pointRadius.isFinite() && pointRadius > 0 && trailLength >= 0)
    val picture = BufferedImage(width * scale, height * scale, BufferedImage.TYPE_3BYTE_BGR)
    val g = picture.createGraphics()
    try {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val samples = this.frame(frame).samples
        if (trailLength > 1) {
            samples.forEach { current ->
                val rgb = particleColor(current, color)
                g.color = Color(rgb.x, rgb.y, rgb.z, .55f)
                val trajectory = trajectory(current.id, maxOf(0, frame - trailLength + 1), frame)
                for (n in 0 until trajectory.lastIndex) {
                    val a = trajectory[n]
                    val b = trajectory[n + 1]
                    g.draw(Line2D.Double(a.x.toDouble() * scale, a.y.toDouble() * scale, b.x.toDouble() * scale, b.y.toDouble() * scale))
                }
            }
        }
        val radius = pointRadius * scale
        samples.forEach { sample ->
            val rgb = particleColor(sample, color)
            g.color = Color(rgb.x, rgb.y, rgb.z)
            g.fill(Ellipse2D.Double(sample.x * scale - radius, sample.y * scale - radius, radius * 2, radius * 2))
        }
    } finally { g.dispose() }
    return picture
}

/**
 * Scrubbable Field output: movie time, identity colors and trails are ordinary controls.
 * Drawing runs in Kotlin from the loaded tracks; using this view never launches Python inference.
 */
fun Box.particleVideoView(video: ParticleVideo, channel: String = "particle video", initialSeconds: Double = 0.0,
                          scale: Int = 2, pointRadius: Double = .45) {
    require(initialSeconds.isFinite())
    var seconds = initialSeconds.coerceIn(0.0, video.lastSampleSeconds)
    var identity = false
    var trails = false
    var radius = pointRadius
    fun draw() {
        val frame = video.at(seconds, interpolate = false).index
        val image = video.image(frame, scale, radius, if (identity) ParticleColor.IDENTITY else ParticleColor.SAMPLED,
            if (trails) 12 else 0)
        val bytes = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        val encoded = Base64.getEncoder().encodeToString(bytes)
        val count = video.frame(frame).samples.size
        display(channel, """<figure style="margin:0;background:#080a0e;color:#cdd5df;padding:8px;width:${image.width}px"><img alt="Recorded video particles" width="${image.width}" height="${image.height}" style="display:block" src="data:image/png;base64,$encoded"><figcaption style="font:12px/1.6 system-ui">frame $frame · ${"%.2f".format(seconds)} s · $count particles</figcaption></figure>""")
    }
    controlGroup("$channel controls") {
        slider("movie time", min = 0.0, max = video.lastSampleSeconds, step = 1.0 / video.fps, initialValue = seconds) {
            seconds = it; draw()
        }
        checkbox("identity colors", initialValue = identity) { identity = it; draw() }
        checkbox("trails", initialValue = trails) { trails = it; draw() }
        slider("point radius", min = .2, max = 2.0, step = .05, initialValue = radius) { radius = it; draw() }
    }
    draw()
}

/**
 * Encodes Kotlin-rasterized tracks to MP4 with FFmpeg, without rerunning inference. The output is
 * staged beside [path] and replaced only after successful encoding. Intended for offline exports.
 */
suspend fun ParticleVideo.renderMovie(path: Path, scale: Int = 2, pointRadius: Double = .45,
                                     color: ParticleColor = ParticleColor.SAMPLED): Path = withContext(Dispatchers.IO) {
    val output = path.toAbsolutePath()
    require(output != file.toPath().toAbsolutePath()) { "Movie output must differ from the track file" }
    Files.createDirectories(output.parent)
    val staging = Files.createTempFile(output.parent, ".particle-movie-", ".mp4")
    val log = Files.createTempFile(output.parent, ".particle-movie-", ".log")
    var process: Process? = null
    try {
        val first = image(0, scale, pointRadius, color)
        process = ProcessBuilder("ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "bgr24",
            "-s", "${first.width}x${first.height}", "-r", fps.toString(), "-i", "pipe:0", "-an",
            "-vf", "pad=ceil(iw/2)*2:ceil(ih/2)*2", "-c:v", "libx264", "-crf", "18",
            "-pix_fmt", "yuv420p", "-movflags", "+faststart", staging.toString())
            .redirectError(log.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        process.outputStream.use { stream ->
            repeat(frameCount) { frame ->
                currentCoroutineContext().ensureActive()
                val picture = if (frame == 0) first else image(frame, scale, pointRadius, color)
                stream.write((picture.raster.dataBuffer as DataBufferByte).data)
            }
        }
        while (process.isAlive) { currentCoroutineContext().ensureActive(); kotlinx.coroutines.delay(25) }
        check(process.exitValue() == 0) { "Particle movie encoding failed: ${Files.readString(log)}" }
        Files.move(staging, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        output
    } finally {
        process?.takeIf { it.isAlive }?.destroyForcibly()?.waitFor()
        Files.deleteIfExists(staging); Files.deleteIfExists(log)
    }
}
