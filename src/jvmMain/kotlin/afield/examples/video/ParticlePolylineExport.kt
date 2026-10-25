package afield.examples.video

import afield.ai.learning.*
import afield.ai.retrieval.*
import afield.animation.interpolation.*
import afield.animation.keyframes.*
import afield.animation.motion.*
import afield.app.commands.*
import afield.app.diagnostics.*
import afield.audio.analysis.*
import afield.audio.synth.*
import afield.data.collections.*
import afield.data.formats.*
import afield.data.graph.*
import afield.data.sorting.*
import afield.document.context.*
import afield.document.graph.*
import afield.document.properties.*
import afield.document.workspace.*
import afield.geometry.mesh.*
import afield.geometry.path.*
import afield.geometry.spatial.*
import afield.graphics.color.*
import afield.graphics.interaction.*
import afield.graphics.render.*
import afield.graphics.shader.*
import afield.graphics.text.*
import afield.graphics.vulkan.buffers.*
import afield.io.osc.*
import afield.io.process.*
import afield.lang.lambda.*
import afield.lang.python.*
import afield.lang.remotejs.*
import afield.lang.stacksl.*
import afield.math.autodiff.*
import afield.math.control.*
import afield.math.estimation.*
import afield.math.functions.*
import afield.math.linalg.*
import afield.math.optimization.*
import afield.math.statistics.*
import afield.math.units.*
import afield.media.image.*
import afield.media.video.*
import afield.runtime.execution.*
import afield.runtime.languages.*
import afield.runtime.lifecycle.*
import afield.simulation.agents.*
import afield.simulation.automata.*
import afield.simulation.particles.*
import afield.study.build.*
import afield.study.evidence.*
import afield.study.search.*
import afield.study.source.*
import afield.study.ui.*
import afield.ui.controls.*
import afield.ui.output.*
import afield.ui.visualization.*
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
