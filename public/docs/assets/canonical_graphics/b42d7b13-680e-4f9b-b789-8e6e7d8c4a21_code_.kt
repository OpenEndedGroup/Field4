import afield.four.Renderer
import afield.four.buffers.*
import afield.four.modules.*
import afield.stdlib.linalg.*

// Three position/color vertices form one triangle.
// The schema shares their memory layout between Kotlin and GLSL.
name = "5d. GPU schema"

@GpuStruct
data class Vertex(
    val position: Vector3f,
    val color: Vector4f
)

val vertexSchema = gpuSchema<Vertex>()
shaderTemplate["Vertex"] = vertexSchema.template

val vertices = listOf(
    Vertex(vec(-0.8f, -0.6f, 0f), vec(1f, 0.15f, 0.1f, 1f)),
    Vertex(vec( 0.8f, -0.6f, 0f), vec(0.1f, 0.9f, 0.3f, 1f)),
    Vertex(vec( 0.0f,  0.7f, 0f), vec(0.2f, 0.4f, 1.0f, 1f))
)

val vertexData = defaultBuffer {
    size = (vertices.size * vertexSchema.strideBytes).bytes
}.buffer

vertexData.mapAndWrite_bytes {
    vertexSchema.writeArray(vertices, this)
}

//%-
val renderer: Renderer = fromContext()

var module = moduleHere {
    primitiveType = triangles
}

var m = Mesh()
module += m named "gpu schema triangle"
renderer += module

// `vertices` is a reflected physical pointer. Field writes vertexData's device address into
// the push constant; the array itself stays in the addressable buffer.
m.vertices = { vertexData }
m.count = { vertices.size.toUInt() }

m.resize(vertices.size)
