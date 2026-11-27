#version 450
#extension GL_EXT_buffer_reference : require

// One FLineLayers vertex, 32 bytes. The point layer has one per node of a pointed line.
struct FLineVertex {
    vec4 position; // xyz: world position; w: dot radius in pixels (pointSize * 3)
    vec4 color; // straight-alpha RGBA, 0..1
};

layout(std430, buffer_reference, buffer_reference_align = 16) readonly buffer FLineVertices {
    FLineVertex v[];
};

layout(push_constant) uniform PointPC {
    mat4 p; // projection
    mat4 m; // model-view
    FLineVertices vertices;
    uint count; // vertices in the layer
    vec2 viewport; // framebuffer size, pixels
    float sizeScale; // multiplies every radius
} pc;

layout(location = 0) out vec4 color;
layout(location = 1) out vec2 local; // pixels from the dot's centre
layout(location = 2) flat out float radius; // pixels

const vec2 corners[6] = vec2[6](vec2(-1, -1), vec2(1, -1), vec2(1, 1), vec2(-1, -1), vec2(1, 1), vec2(-1, 1));

void main() {
    uint i = uint(gl_VertexIndex) / 6u;
    vec2 corner = corners[gl_VertexIndex % 6];
    FLineVertex vertex = pc.vertices.v[min(i, max(pc.count, 1u) - 1u)];

    vec4 clip = pc.p * pc.m * vec4(vertex.position.xyz, 1);
    if (i >= pc.count || clip.w <= 0.0) {
        gl_Position = vec4(0, 0, 2, 1);
        color = vec4(0);
        return;
    }

    // A square one pixel bigger than the dot, in pixels, about the projected centre.
    radius = vertex.position.w * pc.sizeScale;
    local = corner * (radius + 1.0);
    gl_Position = clip + vec4(local / pc.viewport * 2.0 * clip.w, 0, 0);
    color = vertex.color;
}
