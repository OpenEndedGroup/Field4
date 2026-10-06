package afield.examples.video

import afield.stdlib.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/** Export actual cached trajectories as line-only diagnostic movies, without rerunning inference. */
object ParticlePolylineExport {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        require(args.size == 2) { "ParticlePolylineExport TRACK_FILE OUTPUT_DIRECTORY" }
        val video = loadParticleVideo(args[0])
        val out = Path.of(args[1]); Files.createDirectories(out)
        video.renderMovie(out.resolve("polylines.mp4"), color = ParticleColor.IDENTITY,
            trailLength = 25, stride = 16, lineWidth = .6)
        video.renderMovie(out.resolve("polylines-full.mp4"), color = ParticleColor.SAMPLED,
            trailLength = 12, lineWidth = .35)
        listOf(60, 180, 240).forEach { frame ->
            ImageIO.write(video.image(frame, color = ParticleColor.IDENTITY, trailLength = 25,
                stride = 16, lineWidth = .6), "png", out.resolve("polylines-$frame.png").toFile())
        }
        val hash = java.security.MessageDigest.getInstance("SHA-256")
        Files.newInputStream(Path.of(args[0])).use { stream ->
            val buffer = ByteArray(1024 * 1024)
            while (true) { val n = stream.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
        }
        Files.writeString(out.resolve("polylines.json"), org.json.JSONObject(mapOf(
            "tracks_sha256" to hash.digest().joinToString("") { "%02x".format(it) },
            "frames" to video.frameCount, "draw_points" to false, "history_frames" to 25,
            "stable_id_stride" to 16, "full_history_frames" to 12, "full_stable_id_stride" to 1,
        )).toString(2))
        println("Polyline export ok: ${video.frameCount} frames; stable ID stride 16; full density stride 1; no dots")
    }
}
