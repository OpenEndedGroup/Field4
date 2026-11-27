#version 450
#extension GL_EXT_buffer_reference : require

// One FLineLayers vertex, 32 bytes. In the stroke layer, vertex i and i + 1 make a
// segment unless i + 1 starts a new run, which it says with a negative w.
struct FLineVertex {
    vec4 position; // xyz: world position; w: thickness, negated on a run's first vertex (unused here)
    vec4 color; // straight-alpha RGBA, 0..1
};

layout(std430, buffer_reference, buffer_reference_align = 16) readonly buffer FLineVertices {
    FLineVertex v[];
};

layout(push_constant) uniform LinePC {
    mat4 p; // projection
    mat4 m; // model-view
    FLineVertices vertices;
    uint count; // vertices in the layer
} pc;

layout(location = 0) out vec4 color;

void main() {
    // A line list: two vertices per segment, segment s joining v[s] and v[s + 1]. A segment
    // into a run's first vertex is the restart: both its ends go to the same point outside
    // the clip volume, and the line is dropped.
    uint s = uint(gl_VertexIndex) / 2u;
    uint end = uint(gl_VertexIndex) % 2u;
    if (s + 1u >= pc.count || pc.vertices.v[s + 1u].position.w < 0.0) {
        gl_Position = vec4(0, 0, 2, 1);
        color = vec4(0);
        return;
    }

    FLineVertex vertex = pc.vertices.v[s + end];
    gl_Position = pc.p * pc.m * vec4(vertex.position.xyz, 1);
    color = vertex.color;
}
