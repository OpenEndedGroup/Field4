#version 450
#extension GL_EXT_buffer_reference : require

// One FLineLayers vertex, 32 bytes. In the stroke layer, vertex i and i + 1 make a
// segment unless i + 1 starts a new run, which it says with a negative w.
struct FLineVertex {
    vec4 position; // xyz: world position; w: thickness in pixels, negated on a run's first vertex
    vec4 color; // straight-alpha RGBA, 0..1
};

layout(std430, buffer_reference, buffer_reference_align = 16) readonly buffer FLineVertices {
    FLineVertex v[];
};

layout(push_constant) uniform StrokePC {
    mat4 p; // projection
    mat4 m; // model-view
    FLineVertices vertices;
    uint count; // vertices in the layer
    vec2 viewport; // framebuffer size, pixels
    float widthScale; // multiplies every thickness
} pc;

layout(location = 0) out vec4 color;
layout(location = 1) out vec2 local; // pixels: x along the segment from its start, y across it
layout(location = 2) flat out vec2 lengthRadius; // pixels: segment length, half the stroke width

// Two triangles over a unit square; x runs along the segment, y across it.
const vec2 corners[6] = vec2[6](vec2(0, 0), vec2(1, 0), vec2(1, 1), vec2(0, 0), vec2(1, 1), vec2(0, 1));

void collapse() {
    gl_Position = vec4(0, 0, 2, 1); // outside the clip volume: the triangle is dropped
    color = vec4(0);
    local = vec2(0);
    lengthRadius = vec2(0);
}

void main() {
    uint s = uint(gl_VertexIndex) / 6u;
    vec2 corner = corners[gl_VertexIndex % 6];
    if (s + 1u >= pc.count) {
        collapse();
        return;
    }

    FLineVertex a = pc.vertices.v[s];
    FLineVertex b = pc.vertices.v[s + 1u];
    // b begins the next run.
    if (b.position.w < 0.0) {
        collapse();
        return;
    }

    // Clip both ends, then pull an end that is behind the camera onto the near side.
    mat4 pm = pc.p * pc.m;
    vec4 ca = pm * vec4(a.position.xyz, 1);
    vec4 cb = pm * vec4(b.position.xyz, 1);
    const float nearW = 1e-4;
    if (ca.w < nearW && cb.w < nearW) {
        collapse();
        return;
    }
    if (ca.w < nearW) ca = mix(ca, cb, (nearW - ca.w) / (cb.w - ca.w));
    if (cb.w < nearW) cb = mix(cb, ca, (nearW - cb.w) / (ca.w - cb.w));

    // Into pixels.
    vec2 sa = (ca.xy / ca.w * 0.5 + 0.5) * pc.viewport;
    vec2 sb = (cb.xy / cb.w * 0.5 + 0.5) * pc.viewport;
    vec2 d = sb - sa;
    float len = length(d);
    vec2 along = len > 1e-6 ? d / len : vec2(1, 0);
    vec2 across = vec2(-along.y, along.x);

    // A rectangle around the segment, one radius past each end for the round caps, plus a
    // pixel for the antialiased edge.
    float radius = 0.5 * max(abs(a.position.w), abs(b.position.w)) * pc.widthScale;
    float extent = radius + 1.0;
    float u = mix(-extent, len + extent, corner.x);
    float v = mix(-extent, extent, corner.y);
    vec2 pixel = sa + along * u + across * v;

    // Depth and colour follow the nearest point on the segment.
    float k = len > 1e-6 ? clamp(u / len, 0.0, 1.0) : 0.0;
    vec4 c = mix(ca, cb, k);

    gl_Position = vec4(pixel / pc.viewport * 2.0 - 1.0, c.z / c.w, 1.0);
    color = mix(a.color, b.color, k);
    local = vec2(u, v);
    lengthRadius = vec2(len, radius);
}
