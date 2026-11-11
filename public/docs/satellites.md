# Satellites and Orbits

Field can say where any catalogued satellite is, at any time, from the two-line element sets that
[CelesTrak](https://celestrak.org) publishes for every tracked object, and can draw the whole catalogue
moving round the Earth on the GPU. The propagator is SGP4, the model the element sets are fitted to,
written in Kotlin after the reference code in Vallado, Crawford, Hujsak and Kelso,
[*Revisiting Spacetrack Report #3*](https://celestrak.org/publications/AIAA/2006-6753/) (AIAA 2006-6753).
Everything lives in `afield.simulation.orbits`.

## Where a satellite is

A `Tle` is one element set. `Sgp4` takes it and gives a position and velocity in kilometres and
km/s, in the TEME frame SGP4 works in, any number of minutes from the element set's epoch:

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

!!! note "Near-earth orbits only"
    SGP4 needs extra deep-space terms (SDP4) for orbits of 225 minutes or longer: GPS, Galileo,
    geostationary and Molniya orbits. `Sgp4` refuses those with an `IllegalArgumentException` rather
    than giving a wrong answer, and `Tle.isDeepSpace` says which is which. Of CelesTrak's active
    catalogue, 795 of about 15,900 objects are deep-space; `SatelliteCatalogue` leaves them out, so
    the geostationary ring is missing from the pictures below.

??? aside "How the propagator is checked"
    `Sgp4Test` runs the verification element sets published with the paper (`SGP4-VER.TLE`) and
    compares every state against the reference C++ output (`tcppver.out`): 9 near-earth satellites,
    158 states, worst position error 7 µm, worst velocity error under a µm/s. The deep-space cases in
    the same file are skipped.

## The catalogue

`SatelliteCatalogue.bundled()` reads the snapshot of CelesTrak's "active" group that ships with
Field, taken on 2026-11-11: 15,089 near-earth objects. Element sets age. SGP4 is good to a few
kilometres for a day or two either side of an element set's epoch and drifts by tens of kilometres a
week out, so for today's sky use `SatelliteCatalogue.fetch("active")`, which downloads the group,
caches it under `~/.afield/cache/celestrak/` for six hours, and falls back to the cache and then to
the snapshot when there is no network. Any CelesTrak group name works: `"stations"`, `"visual"`,
`"starlink"`, `"weather"`.

`byName` and `search` find satellites, `epoch` is the time at which the whole catalogue is fresh,
and `positionsEcef(time)` propagates every object at once.

## A week of passes over one place

An `Observer` is a place on the ground. `look` gives azimuth, elevation and range to a satellite, and
`passes` finds every interval in which the satellite is above a minimum elevation, sampling every 30
seconds and refining each rise and set to about a second. This box finds a week of passes over
Chicago for five satellites and draws one stalk per pass, at its highest moment, as tall as its
highest elevation:

```diffstatic_runner {data-lang=kotlin screenshot="assets/satellites/passes.png" capture="box" open-outputs="true" wait="3.0" timeout="300" padding="12" embed=true name="satellites-passes" alt="Five rows, one per satellite, across seven days. ISS and the Chinese station show clusters of four to six passes once a day, tallest in the middle of each cluster; Hubble shows three low stalks; Terra and Landsat 9 show two pairs of passes a day, morning and evening, at almost the same times each day" macro-file="../../dev_docs/agents/transcripts/satellites.yaml" capture-box="satellite-passes"}
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
```

The two space stations come over in a cluster of passes once a day, a few minutes later each day.
Terra and Landsat 9 are in sun-synchronous orbits, which cross any latitude at the same local time
every day: two pairs of passes, morning and evening. Hubble's orbit is inclined at 28.5°, so from
42° north it never gets more than a few degrees above the minimum.

## The whole catalogue on the GPU

Running SGP4 on fifteen thousand objects every frame is work the CPU does not need to do for a
picture. `Sgp4.meanElements` is the secular part of the orbit: a Kepler ellipse whose node, perigee
and mean anomaly drift at SGP4's rates, with no drag and no periodic terms. It stays within about 10
km of full SGP4 for the hour after the catalogue's epoch (median over every twentieth object; 15 km
at the 90th percentile), which is less than a pixel here. `MeanElements.glsl` is the same few lines
in GLSL, so a vertex shader can solve Kepler's equation for every satellite, every frame.

The drawing takes three boxes. A renderer box opens the window. Beneath it, an Earth box draws the
graticule, Natural Earth's coastlines and the ISS's ground track for the next day, bright where Chicago
sees it above 10°, as line segments. It also makes the catalogue, a `SkyClock` that runs the sky at
120 times real time, and the camera, and publishes all three with `prop` for the box beneath it.
Beneath that, a satellites box writes one record per satellite into a GPU buffer through a
[GPU schema](gpu_schema.md) and draws one point per satellite:

```diffstatic_runner {data-lang=kotlin screenshot="assets/satellites/globe.png" capture="native-window" native-wait-frames="20" trim-native=true wait="1.0" timeout="300" embed=true name="satellites-globe" max-width="1600" alt="The Earth as a wire globe seen from above North America: white coastlines of Canada, Greenland, the United States and the Gulf of Mexico on a blue graticule, crossed by orange ISS ground tracks, with a small yellow cross at Chicago. Thousands of pale blue dots, the low satellites, crowd a shell just outside the globe, densest at its rim; orange and pink dots, the higher ones, are scattered further out, and one larger white dot marks the ISS" macro-file="../../dev_docs/agents/transcripts/satellites.yaml" capture-box="satellite-catalogue"}
import afield.simulation.orbits.*

// One record per satellite: its mean elements, moved to the clock's start so the shader's time is small.
@GpuStruct
data class Orbit(
    val shape: Vector4f, // semi-major axis (km), eccentricity, inclination, 0
    val angles: Vector4f, // node, perigee, mean anomaly at the clock's start (radians), 0
    val rates: Vector4f, // their drift, radians per minute, 0
)

val orbitSchema = gpuSchema<Orbit>()
shaderTemplate["Orbit"] = orbitSchema.template
// The vertex shader solves Kepler's equation with the same few lines MeanElements.position runs on the CPU.
shaderTemplate["orbit"] = mapOf("kepler" to Resolver { MeanElements.glsl })

// `sky`, `clock` and `camera` come from the Earth box above this one.
val orbits = sky.meanElementsAt(clock.reference).map { e ->
    Orbit(
        vec(e.semiMajorAxisKm, e.eccentricity, e.inclination, 0.0),
        vec(e.raan, e.argPerigee, e.meanAnomaly, 0.0),
        vec(e.raanRate, e.argPerigeeRate, e.meanAnomalyRate, 0.0),
    )
}
val orbitData = defaultBuffer { size = (orbits.size * orbitSchema.strideBytes).bytes }.buffer
orbitData.mapAndWrite_bytes { orbitSchema.writeArray(orbits, this) }
println("${orbits.size} orbits on the GPU, ${orbitSchema.strideBytes} bytes each")

//%-

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
{{ Orbit.all }}
{{ orbit.kepler }}

layout(push_constant) uniform Push {
    OrbitArray orbits;
    mat4 p;
    mat4 m;
    vec4 eye;
    vec4 clock;
} pc;

layout(location = 0) out vec4 fcol;

void main()
{
    Orbit o = pc.orbits.elements[gl_VertexIndex];
    // TEME, in Earth radii, then into the drawing's frame (north up)
    vec3 teme = meanElementsPosition(o.shape, o.angles, o.rates.xyz, pc.clock.x) / 6378.137;
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
larger is a nearer one, so flying out to the shell shows it in more detail.

## Where to go next

- **Deep space.** SDP4's lunar-solar and resonance terms are in the same paper; with them the
  catalogue gains its 795 GPS, geostationary and Molniya objects, and the picture gains the
  geostationary ring. The SGP4-VER deep-space cases in `Sgp4Test`'s resources are ready to check
  it against.
- **Flying to a satellite.** The ISS is picked out by index (`m.clock.z`); pick by name from
  `search`, then ease the camera to the satellite's position from `Satellite.ecef` (see
  [Springs & Character Movement](movement_examples.md)), and draw the picked one's full SGP4 orbit as
  a line.
- **Level of detail.** Points scale with distance. Close up, a mesh or task shader could draw each
  nearby object as a model; on a Mac without mesh shaders, an instanced quad per object within a
  distance is the way.
- **Visible passes.** A pass is only visible to the eye when the satellite is sunlit and the observer
  is in darkness; a sun direction and the Earth's shadow cylinder would sort the passes chart into
  the ones worth going outside for.
- **The running document.** The [satellites transcript](agent_transcripts.md#satellites) builds all
  four boxes.
