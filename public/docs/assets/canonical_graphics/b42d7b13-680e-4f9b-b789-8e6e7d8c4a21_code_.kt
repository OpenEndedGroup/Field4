// 5d. GPU schema: one Kotlin data class describes vertex memory for both Kotlin and GLSL.
// Parent: window. Three position/colour vertices form one triangle. The vertex shader (open it with
// the editor's property switcher) splices in the generated struct with {{ Vertex.all }} and reads the
// vertices from a buffer by gl_VertexIndex, so the mesh has no vertex attributes of its own.
name = "5d. GPU schema"

@GpuStruct
data class Vertex(
    val position: Vector3f,
    val color: Vector4f,
)

// gpuSchema reflects the @GpuStruct class into a layout; its template exposes the GLSL declarations.
val vertexSchema = gpuSchema<Vertex>()
shaderTemplate["Vertex"] = vertexSchema.template

val vertices = [
    Vertex(vec(-0.8f, -0.6f, 0f), vec(1f, 0.15f, 0.1f, 1f)),
    Vertex(vec(0.8f, -0.6f, 0f), vec(0.1f, 0.9f, 0.3f, 1f)),
    Vertex(vec(0.0f, 0.7f, 0f), vec(0.2f, 0.4f, 1.0f, 1f)),
]

// A buffer sized from the schema stride, filled with the vertices in the schema's byte layout.
val vertexData = defaultBuffer {
    size = (vertices.size * vertexSchema.strideBytes).bytes
}
    .buffer

vertexData.mapAndWrite_bytes {
    vertexSchema.writeArray(vertices, this)
}

// `//%-` is Field's execution break: the schema template exists before moduleHere reads the shader.
//%-

var module = moduleHere {
    primitiveType = triangles
}

var m = Mesh()
module += m named "gpu schema triangle"
renderer += module

// `vertices` is a reflected physical pointer. Field writes vertexData's device address into
// the push constant; the array itself stays in the addressable buffer.
m.vertices = { vertexData }
m.count = { vertices.size }

// Draw vertices.size vertices; the shader fetches each from the buffer.
m.resize(vertices.size)
