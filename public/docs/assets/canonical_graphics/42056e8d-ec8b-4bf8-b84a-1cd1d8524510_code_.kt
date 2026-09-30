import afield.four.buffers.*
import afield.four.util.*
import afield.stdlib.linalg.*

@GpuStruct
data class Particle(
    val position: Vector3f,
    val color: Vector4f
)

val particleSchema = gpuSchema<Particle>()
val particles = listOf(
    Particle(vec(-0.8f, -0.6f, 0f), vec(1f, 0.15f, 0.1f, 1f)),
    Particle(vec( 0.8f, -0.6f, 0f), vec(0.1f, 0.9f, 0.3f, 1f)),
    Particle(vec( 0.0f,  0.7f, 0f), vec(0.2f, 0.4f, 1.0f, 1f))
)

// The Kotlin declaration is the one ABI description. The shader in this box expands
// {{ Particle.all }} into the matching struct + buffer-reference declarations.
shaderTemplate["Particle"] = particleSchema.template

val particleData = defaultBuffer {
    size = (particles.size * particleSchema.strideBytes).bytes
}.buffer

particleData.mapAndWrite_bytes {
    particleSchema.writeArray(particles, this)
}

//%-
var module = moduleHere {
    primitiveType = triangles
}

var m = Mesh()
module += m named "gpu-schema triangle"
renderer += module

// `particles` is reflected as a physical pointer, so assigning a Field buffer here writes
// its device address into the push constant. The struct array itself remains in the buffer.
m.particles = { particleData }
m.count = { particles.size.toUInt() }

m.resize(particles.size)
