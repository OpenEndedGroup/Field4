import afield.simulation.orbits.*
import java.time.Duration
import kotlin.math.abs

// Every object in the snapshot, near-earth and deep-space.
val sky = SatelliteCatalogue.bundled()
val deep = sky.satellites.filter { it.tle.isDeepSpace }
println("${sky.size} satellites, ${deep.size} of them deep-space")

// Geosynchronous orbits go round once a sidereal day, 1,436 minutes; the navigation constellations take 12 to 14 hours.
val geo = deep.filter { it.tle.periodMinutes in 1426.0..1446.0 }
val navigation = deep.filter { it.tle.periodMinutes in 700.0..850.0 }
println("${geo.size} geosynchronous, ${navigation.size} in 12 to 14 hour orbits (GPS, GLONASS, Galileo, BeiDou)")

// Longitude across, latitude up: ±15° fills the height, so the figure-eights of inclined orbits show.
val mapW = 720.0
val mapH = 200.0
fun lonX(lon: Double) = (lon + 180.0) / 360.0 * mapW
fun latY(lat: Double) = mapH / 2 - lat / 15.0 * mapH / 2
val font = afield.ai.learning.pen.HersheyFont.load("futural")
val shapes = ArrayList<FLine>()

// The coastlines inside the band, to show where on Earth each satellite hangs.
for (coast in Coastlines.lines) shapes += fline {
    var pen = false
    for (g in coast) {
        if (abs(g.latitudeDeg) > 15.0) {
            pen = false
            continue
        }
        if (pen) lineTo(lonX(g.longitudeDeg), latY(g.latitudeDeg))
        else moveTo(lonX(g.longitudeDeg), latY(g.latitudeDeg))
        pen = true
    }
    thickness = 1.0
    color = vec(0.75, 0.9, 1.0, 0.8)
}
for (lon in -180..180 step 60) {
    shapes += fline {
        moveTo(lonX(lon.toDouble()), 0)
        lineTo(lonX(lon.toDouble()), mapH)
        thickness = 0.5
        color = vec(1.0, 1.0, 1.0, 0.2)
    }
    shapes +=
        font.text("$lon°", size = 9.0, at = vec(lonX(lon.toDouble()) + 3, mapH + 12, 0)).apply {
            color = vec(1.0, 1.0, 1.0, 0.6)
        }
}
shapes += fline {
    moveTo(0, mapH / 2)
    lineTo(mapW, mapH / 2)
    thickness = 0.5
    color = vec(1.0, 1.0, 1.0, 0.35)
}

// Each satellite's point under it, every 20 minutes for a day from the snapshot, drawn where it is within 15° of the
// equator.
val start = sky.epoch
var drifting = 0
for (sat in geo) {
    val day = (0..72).mapNotNull { sat.geodetic(start.plus(Duration.ofMinutes(20L * it))) }
    if (day.isEmpty()) continue
    var travel = 0.0
    for (i in 1 until day.size) travel +=
        abs((day[i].longitudeDeg - day[i - 1].longitudeDeg + 540.0).rem(360.0) - 180.0)
    val drifts = travel > 2.0 // more than 2° of longitude in a day: not held on station
    if (drifts) drifting++
    val c = if (drifts) vec(1.0, 0.45, 0.6, 0.8) else vec(0.45, 0.8, 1.0, 0.9)
    shapes += fline {
        for (i in day.indices) {
            val g = day[i]
            if (abs(g.latitudeDeg) > 15.0) continue
            val prev = day.getOrNull(i - 1)
            val jump = prev == null || abs(prev.latitudeDeg) > 15.0 || abs(g.longitudeDeg - prev.longitudeDeg) > 180.0
            if (jump) moveTo(lonX(g.longitudeDeg), latY(g.latitudeDeg))
            else lineTo(lonX(g.longitudeDeg), latY(g.latitudeDeg))
        }
        thickness = 1.0
        color = c
    }
    // a satellite held on station barely moves, so mark where it starts
    val g = day[0]
    if (abs(g.latitudeDeg) <= 15.0)
        shapes += fline {
            moveTo(lonX(g.longitudeDeg) - 1.2, latY(g.latitudeDeg))
            lineTo(lonX(g.longitudeDeg) + 1.2, latY(g.latitudeDeg))
            thickness = 2.5
            color = c
        }
}
println("$drifting of them drift more than 2° of longitude in a day")
shapes += font.text("15°N", size = 9.0, at = vec(4, 12, 0)).apply { color = vec(1.0, 1.0, 1.0, 0.6) }
shapes += font.text("15°S", size = 9.0, at = vec(4, mapH - 4, 0)).apply { color = vec(1.0, 1.0, 1.0, 0.6) }

lines(shapes, w = mapW, h = mapH + 18, fitToSize = true)
