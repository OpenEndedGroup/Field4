import afield.simulation.orbits.*
import afield.graphics.vulkan.util.Camera
import java.time.Duration

// The catalogue, a clock that runs the sky at 120× real time, and one camera, all published
// with `prop` so the satellite box beneath this one shares them.
prop<SatelliteCatalogue>(sky)
prop<SkyClock>(clock)
prop<Camera>(camera)

sky = SatelliteCatalogue.bundled()
clock = SkyClock(sky.epoch, speed = 120.0)
camera = Camera()
camera.target = vec(0, 0, 0)
camera.p.fov = 0.9 // radians: narrow enough that the globe fills the window

// Earth-fixed km to the drawing's frame: north is up (+y), units are Earth radii.
fun world(e: Xyz) = (e * (1.0 / Frames.EARTH_RADIUS_KM)).let { vec(it.y, it.z, it.x) }
fun world(g: Geodetic) = world(Frames.ecefOf(g.latitudeDeg, g.longitudeDeg, g.heightKm))

// Everything here is line segments: pairs of points, each with a colour.
val points = ArrayList<Vec3>()
val colors = ArrayList<Vec4>()
fun segment(a: Vec3, b: Vec3, c: Vec4) {
    points += a
    points += b
    colors += c
    colors += c
}
fun polyline(line: List<Geodetic>, c: Vec4) {
    for (i in 0 until line.size - 1) segment(world(line[i]), world(line[i + 1]), c)
}

// A graticule every 30°, then the coastlines.
for (lon in -180 until 180 step 30) polyline(
    (-90..90 step 3).map { Geodetic(it.toDouble(), lon.toDouble(), 0.0) },
    vec(0.4, 0.55, 0.8, 0.35),
)
for (lat in -60..60 step 30) polyline(
    (-180..180 step 3).map { Geodetic(lat.toDouble(), it.toDouble(), 0.0) },
    vec(0.4, 0.55, 0.8, 0.35),
)
for (coast in Coastlines.lines) polyline(coast, vec(0.75, 0.9, 1.0, 0.9))

// The ISS's ground track for the next day: faint everywhere, bright where Chicago sees it above 10°.
val here = Observer(latitudeDeg = 41.88, longitudeDeg = -87.63)
val iss = sky.byName("ISS (ZARYA)")!!
val day = (0..1440).map { clock.reference.plus(Duration.ofMinutes(it.toLong())) }
for (i in 0 until day.size - 1) {
    val a = iss.geodetic(day[i])!!.copy(heightKm = 20.0)
    val b = iss.geodetic(day[i + 1])!!.copy(heightKm = 20.0)
    if (kotlin.math.abs(a.longitudeDeg - b.longitudeDeg) > 180) continue // wrapped round the date line
    val seen = here.look(iss, day[i])!!.elevationDeg >= 10.0
    segment(world(a), world(b), if (seen) vec(1.0, 0.6, 0.2, 1.0) else vec(1.0, 0.6, 0.2, 0.18))
}
// Chicago, as a small cross.
val c = Frames.ecefOf(41.88, -87.63, 30.0)
for (d in listOf(Xyz(120.0, 0.0, 0.0), Xyz(0.0, 120.0, 0.0), Xyz(0.0, 0.0, 120.0))) segment(
    world(Xyz(c.x - d.x, c.y - d.y, c.z - d.z)),
    world(Xyz(c.x + d.x, c.y + d.y, c.z + d.z)),
    vec(1.0, 0.85, 0.3, 1.0),
)
// Start the camera 2.6 Earth radii out, above Chicago as it is when the clock starts.
val g0 = clock.gmst()
val c0 = Xyz(
    kotlin.math.cos(g0) * c.x - kotlin.math.sin(g0) * c.y,
    kotlin.math.sin(g0) * c.x + kotlin.math.cos(g0) * c.y,
    c.z,
) * (2.6 / c.length)
camera.at = vec(c0.y, c0.z + 0.5, c0.x) // one unit is one Earth radius

println(
    "${points.size / 2} segments; ISS seen from Chicago ${here.passes(iss, clock.reference, clock.reference.plus(Duration.ofDays(1))).size} times in the day drawn",
)

var module = moduleHere { primitiveType = lines }
var m = Mesh()
module += m named "earth"
renderer += module

m.p = camera.p
m.m = camera.m
// The vertex shader dims what is on the far side of the globe, so it needs the eye.
m.eye = { vec(camera.at.x, camera.at.y, camera.at.z, 1.0) }
// The drawing is the inertial TEME frame, so the Earth turns: by the clock's sidereal angle.
m.spin = { vec(clock.gmst(), 0.0, 0.0, 0.0) }

// one line per pair of vertices, indexed
m.set(points.size, points.size / 2) {
    for ((i, p) in points.withIndex()) {
        pos.add(p.x, p.y, p.z)
        col.add(colors[i].x, colors[i].y, colors[i].z, colors[i].w)
        tri.add(i)
    }
}

var kc by keyCamera(camera = camera)
