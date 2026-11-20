package afield.graphics.render

import afield.math.linalg.Vector3f

/**
 * Times [SplatTiles.Schedule.Naive] against [SplatTiles.Schedule.Balanced] on [SplatTiles.clusteredCloud],
 * printing one table per population. Not a test: the numbers belong to the machine that ran it.
 * `dev_docs/SPLAT_TILES.md` records a run and how to read it.
 *
 * Run with `run-jvm-main afield.graphics.render.SplatTilesBenchKt`.
 */
object SplatTilesBench {

    /** One framing to time: image size, camera position and splat count. */
    data class Population(val width: Int, val height: Int, val eye: Vector3f, val count: Int, val label: String)

    /** The framings measured: the whole cloud, the core filling the frame, and a few large tiles. */
    val populations = listOf(
        Population(1280, 720, Vector3f(0f, 0.3f, 2.6f), 200_000, "whole cloud"),
        Population(1280, 720, Vector3f(0f, 0.05f, 0.6f), 200_000, "close on the core"),
        Population(96, 96, Vector3f(0f, 0.3f, 2.6f), 200_000, "36 tiles")
    )

    /** Time every population at both transmittance cutoffs and print the tables. */
    fun run(repeats: Int = 5) {
        for (p in populations) {
            val view = SplatRaster.View.lookAt(eye = p.eye, target = Vector3f(0f, 0f, 0f), width = p.width, height = p.height)
            val binning = SplatTiles.bin(SplatTiles.clusteredCloud(p.count), p.count, view)
            println("${p.label}: ${p.width}x${p.height}, $binning")
            for (cutoff in listOf(1e-4f, 0f)) {
                for (schedule in listOf(SplatTiles.Schedule.Balanced(), SplatTiles.Schedule.Balanced(256))) {
                    for (row in SplatTiles.compare(binning, schedule, repeats = repeats, transmittanceCutoff = cutoff)) {
                        println("  cutoff $cutoff  $row")
                    }
                }
            }
        }
    }
}

fun main() {
    SplatTilesBench.run()
    println("splat tiles bench done")
}
