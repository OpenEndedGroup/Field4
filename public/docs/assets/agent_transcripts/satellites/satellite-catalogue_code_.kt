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
