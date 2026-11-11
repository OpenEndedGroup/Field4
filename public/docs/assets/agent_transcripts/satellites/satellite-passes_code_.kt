import afield.simulation.orbits.*
import java.time.Duration

// The bundled CelesTrak snapshot: about 15,000 near-earth objects, each with its SGP4 propagator.
val sky = SatelliteCatalogue.bundled()
println("${sky.size} satellites, ${sky.skippedDeepSpace} deep-space left out, elements fresh at ${sky.epoch}")

// One place on the ground, and the week after the snapshot.
val here = Observer(latitudeDeg = 41.88, longitudeDeg = -87.63) // Chicago
val start = sky.epoch
val end = start.plus(Duration.ofDays(7))

val names = ["ISS (ZARYA)", "CSS (TIANHE)", "HST", "TERRA", "LANDSAT 9"]
val font = afield.ai.learning.pen.HersheyFont.load("futural")
val dayW = 90.0
val rowH = 56.0
val shapes = ArrayList<FLine>()

for (d in 0..7) {
    shapes += fline {
        moveTo(d * dayW, 0)
        lineTo(d * dayW, names.size * rowH)
        thickness = 0.5
        color = vec(1.0, 1.0, 1.0, 0.2)
    }
    if (d < 7)
        shapes +=
            font.text("day ${d + 1}", size = 9.0, at = vec(d * dayW + 4, names.size * rowH + 14, 0)).apply {
                color = vec(1.0, 1.0, 1.0, 0.6)
            }
}

names.forEachIndexed { row, name ->
    val sat = sky.byName(name)!!
    val passes = here.passes(sat, start, end, minElevationDeg = 10.0)
    println("$name: ${passes.size} passes above 10° in a week, highest ${passes.maxOf { it.maxElevationDeg }.f0}°")
    val base = (row + 1) * rowH
    // one stalk per pass, at its culmination, as tall as its highest elevation (90° fills the row)
    shapes += fline {
        for (p in passes) {
            val x = Duration.between(start, p.culmination).toSeconds() / 86400.0 * dayW
            moveTo(x, base)
            lineTo(x, base - (rowH - 18) * p.maxElevationDeg / 90.0)
        }
        thickness = 2.0
        color = vec(0.45, 0.8, 1.0, 1.0)
    }
    shapes += fline {
        moveTo(0, base)
        lineTo(7 * dayW, base)
        thickness = 0.5
        color = vec(1.0, 1.0, 1.0, 0.35)
    }
    shapes +=
        font.text("$name   ${passes.size} passes", size = 9.0, at = vec(4, base - rowH + 12, 0)).apply {
            color = vec(1.0, 1.0, 1.0, 0.85)
        }
}

lines(shapes, w = 7 * dayW, h = names.size * rowH + 24, fitToSize = true)
