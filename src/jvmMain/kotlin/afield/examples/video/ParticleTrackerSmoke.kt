package afield.examples.video

import afield.stdlib.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * Explicit optional CUDA check, never run by ordinary Field startup or portable test suites.
 * Pass a local video and an output directory. It exercises Kotlin -> Python -> RAFT -> C++ ->
 * strict Kotlin reader -> Kotlin raster/movie, cache reuse and coroutine cancellation.
 */
object ParticleTrackerSmoke {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        require(args.size == 2) { "ParticleTrackerSmoke VIDEO OUTPUT_DIRECTORY" }
        val input = Path.of(args[0]); val out = Path.of(args[1])
        Files.createDirectories(out)
        val tracker = particleTracker()
        val options = ParticleTrackingOptions(particles = 24000, colorLimit = 1.0)
        val first = tracker.track(input, options, force = true)
        check(!first.cacheHit)
        val timestamp = Files.getLastModifiedTime(first.tracksFile)
        val second = tracker.track(input, options, offline = true)
        check(second.cacheHit && first.key == second.key && timestamp == Files.getLastModifiedTime(second.tracksFile))
        val video = first.load()
        val receipt = JSONObject(Files.readString(first.manifest))
        val summary = receipt.getJSONObject("summary")
        check(video.frameCount == summary.getInt("frames") && video.recordCount == summary.getLong("records"))
        check(video.tracks.size == summary.getInt("tracks") && video.tracks.all { it.samples >= options.minTrack })
        check(video.frame(0).samples.size > 16000) { "Dense example underfilled" }
        val metadata = JSONObject(Files.readString(first.tracksFile.resolveSibling("tracks.pvt.run.json")))
        check(metadata.getString("backend") == "torchvision-raft-large" && metadata.getString("device") == "cuda")
        video.renderMovie(out.resolve("particles.mp4"), pointRadius = .7, drawPoints = true, trailLength = 0)
        ImageIO.write(video.image(minOf(180, video.frameCount - 1), pointRadius = .7, drawPoints = true, trailLength = 0), "png", out.resolve("particles.png").toFile())
        ImageIO.write(video.image(minOf(180, video.frameCount - 1), pointRadius = .3,
            color = ParticleColor.IDENTITY, trailLength = 12), "png", out.resolve("identities.png").toFile())
        Files.writeString(out.resolve("run.json"), JSONObject(mapOf(
            "key" to first.key, "tracksFile" to first.tracksFile.toString(), "cacheHitOnRepeat" to second.cacheHit,
            "width" to video.width, "height" to video.height, "frames" to video.frameCount,
            "particlesAtFrame0" to video.frame(0).samples.size, "records" to video.recordCount,
            "backend" to metadata.getString("backend"), "device" to metadata.getString("device"),
            "trackingWallSeconds" to receipt.getDouble("wall_seconds"),
            "renderPointRadius" to .7,
        )).toString(2))
        val cancelled = launch { tracker.track(input, options, force = true) }
        delay(4000)
        cancelled.cancel(); cancelled.join()
        check(timestamp == Files.getLastModifiedTime(first.tracksFile)) { "Cancelled forced run replaced a successful result" }
        check(tracker.track(input, options, offline = true).cacheHit) { "Cancelled forced run damaged cache receipt" }
        check(Files.list(tracker.cache).use { stream -> stream.noneMatch { it.fileName.toString().startsWith(".launch-") } })
        println("ParticleTrackerSmoke ok: ${video.frameCount} frames; ${video.recordCount} records; cache hit; Kotlin render; cancellation")
    }
}
