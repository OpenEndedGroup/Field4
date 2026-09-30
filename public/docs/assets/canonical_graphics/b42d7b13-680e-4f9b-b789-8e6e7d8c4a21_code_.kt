import afield.four.Renderer
import afield.four.buffers.*
import afield.four.modules.*
import afield.stdlib.linalg.*

// This is deliberately a sibling to the PushConstants<T> example, not a replacement for it.
// The sidecar can bootstrap itself when added to the canonical document by Field's file watcher.
name = "5d. GPU schema"
frame = frame.copy(x = -343.5, y = 102.0, w = 111.0, h = 74.0)

@GpuStruct
data class Particle(
    val position: Vector3f,
    val color: Vector4f
)

val particleSchema = gpuSchema<Particle>()
shaderTemplate["Particle"] = particleSchema.template

val particles = listOf(
    Particle(vec(-0.8f, -0.6f, 0f), vec(1f, 0.15f, 0.1f, 1f)),
    Particle(vec( 0.8f, -0.6f, 0f), vec(0.1f, 0.9f, 0.3f, 1f)),
    Particle(vec( 0.0f,  0.7f, 0f), vec(0.2f, 0.4f, 1.0f, 1f))
)

val particleData = defaultBuffer {
    size = (particles.size * particleSchema.strideBytes).bytes
}.buffer

particleData.mapAndWrite_bytes {
    particleSchema.writeArray(particles, this)
}

//%-
val renderer = root.children.named("window").getPropertyRaw("renderer") as Renderer

var module = moduleHere {
    primitiveType = triangles
}

var m = Mesh()
module += m named "gpu schema triangle"
renderer += module

// `particles` is a reflected physical pointer. Field writes particleData's device address into
// the push constant; the array itself stays in the addressable buffer.
m.particles = { particleData }
m.count = { particles.size.toUInt() }

m.resize(particles.size)
