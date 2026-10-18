@file:afield.runtime.completion.CompletionCategory("Video")

package afield.media.video

import afield.data.collections.get
import afield.data.collections.invoke
import afield.data.collections.r
import afield.data.collections.s
import afield.data.collections.times
import afield.document.graph.minusAssign
import afield.document.graph.plusAssign
import afield.document.properties.compareTo
import afield.graphics.render.rangeTo
import afield.math.autodiff.ceil
import afield.math.autodiff.compareTo
import afield.math.autodiff.div
import afield.math.autodiff.minus
import afield.math.autodiff.plus
import afield.math.autodiff.times
import afield.math.linalg.f
import afield.math.linalg.i
import afield.math.linalg.invoke
import afield.math.linalg.remAssign
import afield.math.linalg.set
import afield.math.linalg.times
import afield.math.units.div
import afield.math.units.minus
import afield.math.units.plus
import afield.math.units.times
import afield.media.image.pad

import afield.audio.synth.*
import afield.document.workspace.file
import afield.document.workspace.path
import afield.graphics.color.rgb
import afield.graphics.render.color
import afield.graphics.render.lineTo
import afield.graphics.render.moveTo
import afield.graphics.render.thickness
import afield.math.autodiff.max
import afield.math.autodiff.maxOf
import afield.math.autodiff.min
import afield.math.linalg.rgb
import afield.math.linalg.x
import afield.media.image.channel
import afield.runtime.execution.start
import afield.simulation.agents.step
import afield.simulation.automata.history
import afield.simulation.automata.step
import afield.simulation.automata.trajectory
import afield.document.graph.Box
import afield.ui.output.controlGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import javax.imageio.ImageIO

/**
 * Draws a loaded [ParticleVideo] as images and as a scrubbable box output.
 *
 * [ParticleVideo.image] rasterizes one recorded frame as per-identity trails and optional points, colored by
 * sampled RGB, identity or confidence. [particleVideoView] puts that drawing in a box's output
 * with controls for movie time, colors and trails. Both work from the loaded tracks and never run
 * inference.
 */

/**
 * Rasterizes an actual recorded frame, with sampled RGB or stable identity/confidence colors.
 * [pointRadius] uses source pixel coordinates; [scale] magnifies the complete image and dots.
 * [trailLength] draws recent consecutive samples as a polyline per identity, without bridging lifecycles.
 * [stride] selects stable IDs, so subsampling cannot flicker between unrelated trajectories.
 * Points are optional; line-only drawing is the default. [lineWidth] uses source pixels.
 */
fun ParticleVideo.image(frame: Int, scale: Int = 2, pointRadius: Double = .45,
                        color: ParticleColor = ParticleColor.SAMPLED, trailLength: Int = 24, drawPoints: Boolean = false, stride: Int = 1,
                        lineWidth: Double = .6): BufferedImage {
    require(scale in 1..8 && width.toLong() * height * scale * scale <= 64_000_000)
    require(pointRadius.isFinite() && pointRadius > 0 && trailLength >= 0 && stride > 0
        && lineWidth.isFinite() && lineWidth > 0)
    val picture = BufferedImage(width * scale, height * scale, BufferedImage.TYPE_3BYTE_BGR)
    val g = picture.createGraphics()
    try {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val samples = this.frame(frame).samples.filter { it.id % stride == 0L }
        g.stroke = BasicStroke((lineWidth * scale).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        if (trailLength > 1) {
            samples.forEach { current ->
                val rgb = particleColor(current, color)
                g.color = Color(rgb.x, rgb.y, rgb.z)
                val trajectory = trajectory(current.id, maxOf(0, frame - trailLength + 1), frame)
                val path = Path2D.Double()
                trajectory.forEachIndexed { n, sample ->
                    if (n == 0) path.moveTo(sample.x.toDouble() * scale, sample.y.toDouble() * scale)
                    else path.lineTo(sample.x.toDouble() * scale, sample.y.toDouble() * scale)
                }
                g.draw(path)
            }
        }
        val radius = pointRadius * scale
        if (drawPoints) samples.forEach { sample ->
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
fun Box.particleVideoView(video: ParticleVideo, channel: String = "particle video", initialSeconds: Double = 1.0,
                          scale: Int = 2, pointRadius: Double = .45, trailLength: Int = 24,
                          stride: Int = 1, identityColors: Boolean = true) {
    require(initialSeconds.isFinite() && trailLength in 2..100 && stride in 1..64)
    var seconds = initialSeconds.coerceIn(0.0, video.lastSampleSeconds)
    var identity = identityColors
    var points = false
    var history = trailLength
    var sampleStride = stride
    var thickness = .6
    var radius = pointRadius
    fun draw() {
        val frame = video.at(seconds, interpolate = false).index
        val image = video.image(frame, scale, radius, if (identity) ParticleColor.IDENTITY else ParticleColor.SAMPLED,
            history, points, sampleStride, thickness)
        val bytes = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        val encoded = Base64.getEncoder().encodeToString(bytes)
        val population = video.frame(frame).samples
        val count = population.count { it.id % sampleStride == 0L }
        display(channel, """<figure style="margin:0;background:#080a0e;color:#cdd5df;padding:8px;width:${image.width}px"><img alt="Recorded video trajectories" width="${image.width}" height="${image.height}" style="display:block" src="data:image/png;base64,$encoded"><figcaption style="font:12px/1.6 system-ui">frame $frame · ${"%.2f".format(seconds)} s · $count / ${population.size} trajectories · $history frames of history</figcaption></figure>""")
    }
    controlGroup("$channel controls") {
        slider("movie time", min = 0.0, max = video.lastSampleSeconds, step = 1.0 / video.fps, initialValue = seconds) {
            seconds = it; draw()
        }
        checkbox("identity colors", initialValue = identity) { identity = it; draw() }
        checkbox("points", initialValue = points) { points = it; draw() }
        slider("history (frames)", min = 2.0, max = 100.0, step = 1.0, initialValue = history.toDouble()) {
            history = it.toInt(); draw()
        }
        slider("trajectory stride", min = 1.0, max = 64.0, step = 1.0, initialValue = sampleStride.toDouble()) {
            sampleStride = it.toInt(); draw()
        }
        slider("line width", min = .15, max = 2.0, step = .05, initialValue = thickness) { thickness = it; draw() }
        slider("point radius", min = .2, max = 2.0, step = .05, initialValue = radius) { radius = it; draw() }
    }
    draw()
}

/**
 * Encodes Kotlin-rasterized tracks to MP4 with FFmpeg, without rerunning inference. The output is
 * staged beside [path] and replaced only after successful encoding. Intended for offline exports.
 */
suspend fun ParticleVideo.renderMovie(path: Path, scale: Int = 2, pointRadius: Double = .45,
                                     color: ParticleColor = ParticleColor.SAMPLED, trailLength: Int = 24,
                                     drawPoints: Boolean = false, stride: Int = 1, lineWidth: Double = .6): Path = withContext(Dispatchers.IO) {
    val output = path.toAbsolutePath()
    require(output != file.toPath().toAbsolutePath()) { "Movie output must differ from the track file" }
    Files.createDirectories(output.parent)
    val staging = Files.createTempFile(output.parent, ".particle-movie-", ".mp4")
    val log = Files.createTempFile(output.parent, ".particle-movie-", ".log")
    var process: Process? = null
    try {
        val first = image(0, scale, pointRadius, color, trailLength, drawPoints, stride, lineWidth)
        process = ProcessBuilder("ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "bgr24",
            "-s", "${first.width}x${first.height}", "-r", fps.toString(), "-i", "pipe:0", "-an",
            "-vf", "pad=ceil(iw/2)*2:ceil(ih/2)*2", "-c:v", "libx264", "-crf", "18",
            "-pix_fmt", "yuv420p", "-movflags", "+faststart", staging.toString())
            .redirectError(log.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        process.outputStream.use { stream ->
            repeat(frameCount) { frame ->
                currentCoroutineContext().ensureActive()
                val picture = if (frame == 0) first else image(frame, scale, pointRadius, color, trailLength, drawPoints, stride, lineWidth)
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
