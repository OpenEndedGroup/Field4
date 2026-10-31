import afield.geometry.topology.vectorfield.*
import afield.tools.docs.FFMPEG
import java.io.File

// Launch this one: every frame drawn in the raw look, encoded at the film's 18 frames per second.
val flow = loadParticleFlow("public/docs/assets/vector_field_topology/eagle.flow.gz")
val out = File("build/eagle_flow/eagle_flow_raw.mp4")

suspend fun render() {
    val pngs = File(out.parentFile, "frames").apply {
        deleteRecursively()
        mkdirs()
    }
    for ((frame, picture) in
        videoFrames("public/docs/assets/vector_field_topology/eagle.mp4", width = 480).withIndex()) {
        if (frame >= flow.frameCount) break
        val topology = VectorFieldTopology.compute(
            flow.at(frame, window = 1, camera = CameraMotion.KEEP),
            minStrength = 0.02,
            maxLength = 300.0,
        )
        val lines =
            topology.streamlines(spacing = 1.5, length = 14.0) + topology.toFLines() + topology.criticalPointsFLine(6.0)
        val dark = Img(picture.width, picture.height, 3, FloatArray(picture.width * picture.height * 3))
        picture
            .overlay(dark, 0.55)
            .drawFLines(lines.map { it * flow.cell.toDouble() }, scale = 2.0)
            .savePng(File(pngs, "%03d.png".format(frame)).path)
        if (frame % 30 == 0) println("frame $frame of ${flow.frameCount}")
    }
    val encode = ProcessBuilder(
        FFMPEG,
        "-v",
        "error",
        "-y",
        "-framerate",
        "18",
        "-i",
        File(pngs, "%03d.png").path,
        "-c:v",
        "libx264",
        "-preset",
        "slow",
        "-crf",
        "28",
        "-pix_fmt",
        "yuv420p",
        out.path,
    ).inheritIO()
        .start()
    check(encode.waitFor() == 0) { "ffmpeg failed" }
    println("wrote ${out.absolutePath}")
}
start(::render)
