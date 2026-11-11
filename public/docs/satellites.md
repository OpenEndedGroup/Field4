# Satellites and Orbits

Give Field a two-line element set from [CelesTrak](https://celestrak.org) and it can tell you where
that satellite will be. You can find passes over a place on Earth, or draw thousands of satellites
moving around the planet on the GPU. The propagator is SGP4, the model the element sets are fitted to,
written in Kotlin after the reference code in Vallado, Crawford, Hujsak and Kelso,
[*Revisiting Spacetrack Report #3*](https://celestrak.org/publications/AIAA/2006-6753/) (AIAA 2006-6753).
The code lives in `afield.simulation.orbits` and covers near-Earth and deep-space orbits. Element sets age, so for the current sky you'll want a fresh one.

## Where a satellite is

A `Tle` is one element set. Give it to `Sgp4` to find a position and velocity at a time measured in
minutes from the set's epoch. The result is in kilometres and km/s, in SGP4's TEME frame:

```diffstatic {data-lang=kotlin}
import afield.simulation.orbits.*

val vanguard = Tle.parse(
    "1 00005U 58002B   00179.78495062  .00000023  00000-0  28098-4 0  4753",
    "2 00005  34.2682 348.7242 1859667 331.7664  19.3264 10.82419157413667",
)
val state = Sgp4(vanguard).propagate(minutesSinceEpoch = 360.0)
println(state!!.position) // Xyz(x=-7154.03..., y=-3783.17..., z=-3536.19...)
```

`propagate` returns `null` when the model breaks down, which happens to real objects close to
re-entry. Positions are `Xyz`, a double-precision vector: a single-precision `Vec3` is only good to
about half a metre at orbital radius.

`Frames.temeToEcef` turns a TEME position into Earth-fixed coordinates by Greenwich sidereal time, and
`Frames.geodetic` gives latitude, longitude and height on the WGS-84 ellipsoid. A `Satellite` (an
element set with its propagator already set up) does both: `iss.geodetic(time)`.

Orbits of 225 minutes or longer (GPS, Galileo, geostationary, Molniya) take SGP4's deep-space branch,
which adds the pull of the moon and the sun and the resonances of 12- and 24-hour orbits.
`Tle.isDeepSpace` says which branch an element set takes; nothing else changes, and `propagate` works
the same way for both.

??? aside "How the propagator is checked"
    `Sgp4Test` runs the verification element sets published with the paper (`SGP4-VER.TLE`) and
    compares every state against the reference C++ output (`tcppver.out`). The 9 near-earth cases
    agree to 7 µm (158 states) and the 24 deep-space cases to 0.12 mm and 0.9 µm/s (508 states),
    including the Molniya and geostationary resonances, the switch to Lyddane's form below 11° of
    inclination, and a run 3.5 years past the epoch. Where the reference propagator stops with an
    error, the test checks that `propagate` returns `null` at the same step.

## The catalogue

`SatelliteCatalogue.bundled()` reads the snapshot of CelesTrak's "active" group that ships with
Field, taken on 2026-11-11: 15,884 objects, 795 of them deep-space (`bundled(includeDeepSpace = false)`
keeps the low ones alone). Element sets age. SGP4 is good to a few
kilometres for a day or two either side of an element set's epoch and drifts by tens of kilometres a
week out, so for today's sky use `SatelliteCatalogue.fetch("active")`, which downloads the group,
caches it under `~/.afield/cache/celestrak/` for six hours, and falls back to the cache and then to
the snapshot when there is no network. Any CelesTrak group name works: `"stations"`, `"visual"`,
`"starlink"`, `"weather"`.

`byName` and `search` find satellites, `epoch` is the time for which the catalogue is fresh (the
latest near-earth epoch: CelesTrak dates a few high orbits days ahead), and `positionsEcef(time)`
propagates every object at once.

## A week of passes over one place

An `Observer` is a place on the ground. `look` gives azimuth, elevation and range to a satellite, and
`passes` finds every interval in which the satellite is above a minimum elevation, sampling every 30
seconds and refining each rise, set and highest moment to about a second.

Most of those passes cannot be seen. A satellite shines by reflected sunlight, so it is visible to the
eye only when it is lit and the sky is dark: in the hours after sunset and before sunrise, when the
ground is in darkness and the satellite, high above it, is not yet in the Earth's shadow.
`visiblePasses` keeps those parts of each pass, using `Sun.position` for the sun, `Frames.isSunlit`
for the shadow and a sun at least 6° below the horizon for the dark (`maxSunElevationDeg`).

This box finds a week of passes over Chicago for five satellites and draws one stalk per pass, at its
highest moment, as tall as its highest elevation; the visible ones are drawn again in yellow, as high as
they are while visible:

```diffstatic_runner {data-lang=kotlin screenshot="assets/satellites/passes.png" capture="box" open-outputs="true" wait="3.0" timeout="300" padding="12" embed=true name="satellites-passes" alt="Five rows, one per satellite, across seven days. ISS and the Chinese station show clusters of four to six passes once a day, tallest in the middle of each cluster, and one or two of the early passes in each ISS cluster are drawn again in yellow as visible; Hubble shows three low stalks; Terra and Landsat 9 show two pairs of passes a day, morning and evening, at almost the same times each day, none of them visible" macro-file="../../dev_docs/agents/transcripts/satellites.yaml" capture-box="satellite-passes"}
import afield.simulation.orbits.*
import java.time.Duration

// The bundled CelesTrak snapshot: about 15,900 objects, each with its SGP4 propagator.
val sky = SatelliteCatalogue.bundled()
println("${sky.size} satellites, elements fresh at ${sky.epoch}")

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
    // the parts of those passes with the satellite in sunlight and the sky dark: the ones you can see
    val seen = here.visiblePasses(sat, start, end, minElevationDeg = 10.0)
    println(
        "$name: ${passes.size} passes above 10° in a week, ${seen.size} visible, highest ${passes.maxOf { it.maxElevationDeg }.f0}°",
    )
    val base = (row + 1) * rowH
    // one stalk per pass, at its culmination, as tall as its highest elevation (90° fills the row)
    fun stalks(list: List<Pass>, width: Double, c: Vec4) {
        shapes += fline {
            for (p in list) {
                val x = Duration.between(start, p.culmination).toSeconds() / 86400.0 * dayW
                moveTo(x, base)
                lineTo(x, base - (rowH - 18) * p.maxElevationDeg / 90.0)
            }
            thickness = width
            color = c
        }
    }
    stalks(passes, 2.0, vec(0.45, 0.8, 1.0, 0.45))
    stalks(seen, 2.5, vec(1.0, 0.85, 0.35, 1.0))
    shapes += fline {
        moveTo(0, base)
        lineTo(7 * dayW, base)
        thickness = 0.5
        color = vec(1.0, 1.0, 1.0, 0.35)
    }
    shapes +=
        font
            .text("$name   ${passes.size} passes, ${seen.size} visible", size = 9.0, at = vec(4, base - rowH + 12, 0))
            .apply {
                color = vec(1.0, 1.0, 1.0, 0.85)
            }
}

lines(shapes, w = 7 * dayW, h = names.size * rowH + 24, fitToSize = true)
```

The two space stations come over in a cluster of passes once a day, a few minutes later each day.
Terra and Landsat 9 are in sun-synchronous orbits, which cross any latitude at the same local time
every day: two pairs of passes, morning and evening. Hubble's orbit is inclined at 28.5°, so from
42° north it never gets more than a few degrees above the minimum.

Only the ISS can be seen this week, 11 times, always in the early passes of its evening cluster: by the
later passes the station, 400 km up, has followed the ground into the Earth's shadow. The Chinese
station comes over in the middle of the day this week, and Terra and Landsat 9 cross at about 10:30 in
the morning and at night, local time, in daylight or in the shadow, so none of the three is visible.

## The geosynchronous belt

A satellite with a period of one sidereal day, 1,436 minutes, keeps pace with the turning Earth. Over
the equator, with a circular orbit, it stays above one point: a geostationary satellite. With some
inclination it traces a figure-eight about that point once a day, north and south by the inclination.
This box draws the point beneath each of the snapshot's geosynchronous satellites, every 20 minutes for a
day, over the coastlines near the equator, with latitude stretched about three times. Satellites held on
station are blue; the ones drifting more than 2° of longitude a day, retired or never controlled, are
pink:

```diffstatic_runner {data-lang=kotlin screenshot="assets/satellites/geo.png" capture="box" open-outputs="true" wait="3.0" timeout="300" padding="12" embed=true name="satellites-geo" alt="A strip map of the equatorial band from 180° west to 180° east, latitude 15° south to 15° north, over faint coastlines of the Americas, Africa and Asia. Hundreds of blue dots and short blue vertical figure-eights crowd the equator, densest over Asia and the Americas; pink tracks of drifting satellites cross the band as long slanted lines and tall loops, most of them between 60° and 150° east, and one wide pink ellipse spans from 130° west to 60° east" macro-file="../../dev_docs/agents/transcripts/satellites.yaml" capture-box="satellite-geo"}
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
```

Of the 564 geosynchronous objects in the snapshot, 68 drift. The rest sit in two crowds, over the
Americas and over Asia, where most of the people they serve are. Many of the station-kept ones draw
short vertical figure-eights: holding latitude costs most of a satellite's fuel, so operators often let
the inclination grow and keep only the longitude. The other deep-space objects are the navigation
constellations, 124 of them in 12- to 14-hour orbits (GPS, GLONASS, Galileo, BeiDou), and the
eccentric orbits of Molniya satellites and astronomy missions.

## The whole catalogue on the GPU

To draw sixteen thousand objects every frame, we can use a cheaper approximation on the GPU.
`Sgp4.meanElements` is the secular part of the orbit: a Kepler ellipse whose node, perigee
and mean anomaly drift at SGP4's rates (with the moon's and sun's for a deep-space orbit), with no drag
and no periodic terms. It stays within about 10 km of full SGP4 for the hour after the catalogue's
epoch (median over every twentieth low orbit; 15 km at the 90th percentile), and about 23 km for the
deep-space ones, at ten to forty thousand kilometres, which is less than a pixel here.
`MeanElements.glsl` is the same few lines
in GLSL, so a vertex shader can solve Kepler's equation for every satellite, every frame.

The drawing takes three boxes. A renderer box opens the window. Beneath it, an Earth box draws the
graticule, Natural Earth's coastlines and the ISS's ground track for the next day, bright where Chicago
sees it above 10°, as line segments. It also makes the catalogue, a `SkyClock` that runs the sky at
120 times real time, and the camera. It publishes all three with `prop` for the box beneath it.
Beneath that, a satellites box writes one record per satellite into a GPU buffer, laid out by a
[GPU schema](gpu_schema.md), and draws one point per satellite:

```diffstatic_runner {data-lang=kotlin screenshot="assets/satellites/globe.png" capture="native-window" native-wait-frames="20" trim-native=true wait="1.0" timeout="300" embed=true name="satellites-globe" max-width="1600" alt="The Earth as a wire globe seen from above North America: white coastlines of Canada, Greenland, the United States and the Gulf of Mexico on a blue graticule, crossed by orange ISS ground tracks, with a small yellow cross at Chicago. Thousands of pale blue dots, the low satellites, crowd a shell just outside the globe, densest at its rim; orange and pink dots, the higher ones, are scattered further out, and one larger white dot marks the ISS" macro-file="../../dev_docs/agents/transcripts/satellites.yaml" capture-box="satellite-catalogue"}
import afield.simulation.orbits.*

// One record per satellite: its mean elements, moved to the clock's start so the shader's time is small.
@GpuStruct
data class Orbit(
    val shape: Vector4f, // semi-major axis (km), eccentricity, inclination, 0
    val angles: Vector4f, // node, perigee, mean anomaly at the clock's start (radians), 0
    val rates: Vector4f, // their drift, radians per minute, 0
)

// The schema lays the records out as std430: three vec4s each, which is how the vertex shader reads them.
val orbitSchema = gpuSchema<Orbit>()

// `sky`, `clock` and `camera` come from the Earth box above this one.
val orbits =
    sky.meanElementsAt(clock.reference).map { e ->
        Orbit(
            vec(e.semiMajorAxisKm, e.eccentricity, e.inclination, 0.0),
            vec(e.raan, e.argPerigee, e.meanAnomaly, 0.0),
            vec(e.raanRate, e.argPerigeeRate, e.meanAnomalyRate, 0.0),
        )
    }
val orbitData = defaultBuffer { size = (orbits.size * orbitSchema.strideBytes).bytes }.buffer
orbitData.mapAndWrite_bytes { orbitSchema.writeArray(orbits, this) }
println("${orbits.size} orbits on the GPU, ${orbitSchema.strideBytes} bytes each")

var module = moduleHere { primitiveType = points }
var m = Mesh()
module += m named "satellites"
renderer += module

m.orbits = { orbitData }
m.p = camera.p
m.m = camera.m
m.eye = { vec(camera.at.x, camera.at.y, camera.at.z, 1.0) }
// minutes since the clock started, the point size scale, and which satellite to pick out (the ISS)
val picked = sky.satellites.indexOf(sky.byName("ISS (ZARYA)"))
m.clock = { vec(clock.minutes(), 10.0, picked.toDouble(), 0.0) }

// one vertex per satellite; the shader fetches its orbit from the buffer
m.resize(orbits.size)
```

The satellites box's vertex shader fetches the satellite's record by `gl_VertexIndex`, solves for its
position at the clock's time, dims it when the globe is between it and the camera, colours it by
height (pale blue below 600 km, orange to 1,000 km, pink above) and sizes it by its distance from the
camera:

```diffstatic {data-lang=glsl}
#version 460
#extension GL_EXT_buffer_reference : require

// Three vec4s per satellite, as the satellites box's Orbit records are laid out.
layout(std430, buffer_reference) buffer OrbitData {
    vec4 v[];
};

// Kepler's equation with secular drift: the GLSL twin of MeanElements.position (MeanElements.glsl).
vec3 meanElementsPosition(vec4 shape, vec4 angles, vec3 rates, float t) {
    float m = angles.z + rates.z * t;
    float w = angles.y + rates.y * t;
    float o = angles.x + rates.x * t;
    float e = shape.y;
    float ea = m;
    for (int k = 0; k < 6; k++) ea -= (ea - e * sin(ea) - m) / (1.0 - e * cos(ea));
    float px = shape.x * (cos(ea) - e);
    float py = shape.x * sqrt(1.0 - e * e) * sin(ea);
    float x1 = cos(w) * px - sin(w) * py;
    float y1 = sin(w) * px + cos(w) * py;
    return vec3(cos(o) * x1 - sin(o) * cos(shape.z) * y1, sin(o) * x1 + cos(o) * cos(shape.z) * y1, sin(shape.z) * y1);
}

layout(push_constant) uniform Push {
    OrbitData orbits;
    mat4 p;
    mat4 m;
    vec4 eye;
    vec4 clock;
} pc;

layout(location = 0) out vec4 fcol;

void main()
{
    int i = 3 * gl_VertexIndex;
    // TEME, in Earth radii, then into the drawing's frame (north up)
    vec3 teme = meanElementsPosition(pc.orbits.v[i], pc.orbits.v[i + 1], pc.orbits.v[i + 2].xyz, pc.clock.x) / 6378.137;
    vec3 w = vec3(teme.y, teme.z, teme.x);

    // hidden behind the globe: the ray from the eye meets the unit sphere before it reaches the satellite
    vec3 d = w - pc.eye.xyz;
    float dist = length(d);
    d /= dist;
    float b = dot(pc.eye.xyz, d);
    float disc = b * b - (dot(pc.eye.xyz, pc.eye.xyz) - 1.0);
    float hit = -b - sqrt(max(disc, 0.0));
    bool hidden = disc > 0.0 && hit > 0.0 && hit < dist;

    // colour by height: below 600 km, 600 to 1,000 km, above
    float alt = (length(teme) - 1.0) * 6378.137;
    vec3 c = alt < 600.0 ? vec3(0.45, 0.8, 1.0) : (alt < 1000.0 ? vec3(1.0, 0.78, 0.35) : vec3(1.0, 0.45, 0.6));
    fcol = vec4(c, hidden ? 0.06 : 0.9);
    bool picked = gl_VertexIndex == int(pc.clock.z);
    if (picked) fcol = vec4(1.0, 0.95, 0.8, 1.0);

    gl_Position = pc.p * pc.m * vec4(w, 1);
    // level of detail by distance: a satellite near the camera is drawn larger
    gl_PointSize = picked ? 16.0 : clamp(pc.clock.y / dist, 1.5, 12.0);
}
```

The Earth box draws in the same inertial frame, turning the Earth-fixed coastlines by Greenwich
sidereal time each frame (`m.spin` in its `vertex` shader), so the Earth turns under the orbits. With
the renderer window focused, the [camera keys](afield_four_graphics.md#keyboard-camera) fly the camera: W and S move
in and out, Shift with Left and Right orbits the globe, R puts the camera back. A satellite drawn
larger is a nearer one, so flying out to the shell shows it in more detail. Flying further out, past
four Earth radii, brings in the navigation satellites, and at six and a half the geostationary ring.

## Where to go next

- **Lit and dark on the globe.** The globe's points are coloured by height. Pass the sun's direction
  (`Sun.position`) to the satellites box's vertex shader and dim the points inside the Earth's shadow
  cylinder, the test `Frames.isSunlit` makes, and the globe shows which satellites are catching the
  light at dusk.
- **How bright.** `visiblePasses` says when a satellite can be seen, not how easily. A brightness
  estimate needs a standard magnitude per object (published for the brighter ones) and the
  phase angle between sun, satellite and observer, which `Sun.position` and `look` already give.
- **Flying to a satellite.** The ISS is picked out by index (`m.clock.z`); pick by name from
  `search`, then ease the camera to the satellite's position from `Satellite.ecef` (see
  [Springs & Character Movement](movement_examples.md)), and draw the picked one's full SGP4 orbit as
  a line.
- **Six-digit catalogue numbers.** The two-line format has five columns for the catalogue number; the
  newest object in the snapshot is 69998, and the format runs out at 99999. CelesTrak also publishes
  every group as OMM (`FORMAT=csv` or `json`), the same elements as named fields; an OMM parser
  beside `Tle.parseAll` would keep `fetch` working past that point.
- **Passes for the whole catalogue.** `passes` propagates every 30 seconds, which is fine for a few
  satellites and slow for all 15,900 over a week. A first pass that drops every orbit whose perigee
  and inclination can never bring it above the observer's horizon would leave far fewer to search.
- **Level of detail.** Points scale with distance. Close up, a mesh or task shader could draw each
  nearby object as a model; on a Mac without mesh shaders, an instanced quad per object within a
  distance is the way.
- **The running document.** The [satellites transcript](agent_transcripts.md#satellites) builds all
  five boxes.
