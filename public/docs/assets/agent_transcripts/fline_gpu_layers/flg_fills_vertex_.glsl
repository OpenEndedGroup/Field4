#version 450
#extension GL_EXT_buffer_reference : require

// One FLineLayers vertex, 32 bytes. The fill layer is fans, restarted in the vertex data:
// w is how many vertices back this vertex's fan centre is, and 0 marks a centre.
struct FLineVertex {
    vec4 position; // xyz: world position; w: distance back to the fan centre, 0 at a centre
    vec4 color; // straight-alpha RGBA, 0..1 (the line's colour)
};

layout(std430, buffer_reference, buffer_reference_align = 16) readonly buffer FLineVertices {
    FLineVertex v[];
};

layout(push_constant) uniform FillPC {
    mat4 p; // projection
    mat4 m; // model-view
    FLineVertices vertices;
    uint count; // vertices in the layer
} pc;

layout(location = 0) out vec4 color;

void main() {
    // One triangle slot per stored vertex k: (centre, v[k], v[k + 1]). A centre, or the last
    // vertex of a fan (whose next is the following centre), has no triangle.
    uint k = uint(gl_VertexIndex) / 3u;
    uint corner = uint(gl_VertexIndex) % 3u;
    if (k + 1u >= pc.count) {
        gl_Position = vec4(0, 0, 2, 1);
        color = vec4(0);
        return;
    }

    FLineVertex here = pc.vertices.v[k];
    FLineVertex next = pc.vertices.v[k + 1u];
    uint back = uint(here.position.w);
    if (back == 0u || next.position.w == 0.0) {
        gl_Position = vec4(0, 0, 2, 1);
        color = vec4(0);
        return;
    }

    FLineVertex vertex = corner == 0u ? pc.vertices.v[k - back] : (corner == 1u ? here : next);
    gl_Position = pc.p * pc.m * vec4(vertex.position.xyz, 1);
    color = vertex.color;
}
