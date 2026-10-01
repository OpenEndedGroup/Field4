#version 460

{{ Vertex.all }}

layout(push_constant) uniform Push {
    VertexArray vertices;
    uint count;
} pc;

layout(location = 0) out vec4 fcol;

void main()
{
    uint i = uint(gl_VertexIndex);
    Vertex vertex = pc.vertices.elements[i];

    gl_Position = vec4(vertex.position, 1.0);
    fcol = vertex.color * float(pc.count > 0u);
}
