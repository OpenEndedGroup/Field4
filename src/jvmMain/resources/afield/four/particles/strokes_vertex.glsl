#version 450
#extension GL_EXT_buffer_reference : require

// Draws one layer of a ParticleSketch's stroke list (links, rings, discs, overlay lines) as
// quads; shape_fragment.glsl turns each into an antialiased shape. Records past the list's count,
// or on the other layer, are moved off screen.

struct Stroke {
    vec4 a;       // line: x0, y0, x1, y1; ring and disc: centre in xy
    vec4 color;   // sRGB, straight alpha
    vec4 shape;   // width, radius, kind (0 line, 1 ring, 2 disc), layer
};

layout(std430, buffer_reference) readonly buffer Strokes { uint count; uint _s0; uint _s1; uint _s2; Stroke s[]; };

layout(push_constant) uniform Push {
    Strokes strokes;
    vec2 size;        // the world, in points: canvas pixels
    vec2 targetSize;  // the accumulation, in device pixels
    uint capacity;
    float layer;
} push;

layout(location = 0) out vec2 local;       // device pixels, relative to the shape's origin
layout(location = 1) flat out vec4 shape;  // half width, radius, kind, length (device pixels)
layout(location = 2) flat out vec4 color;

const vec2 corner[6] = vec2[](
    vec2(0.0, -1.0), vec2(1.0, -1.0), vec2(1.0, 1.0),
    vec2(0.0, -1.0), vec2(1.0, 1.0), vec2(0.0, 1.0)
);

void hide() {
    gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
    local = vec2(0.0);
    shape = vec4(0.0);
    color = vec4(0.0);
}

void main() {
    uint i = uint(gl_VertexIndex) / 6u;
    if (i >= min(push.strokes.count, push.capacity)) { hide(); return; }
    Stroke s = push.strokes.s[i];
    if (abs(s.shape.w - push.layer) > 0.5) { hide(); return; }

    vec2 scale = push.targetSize / push.size;
    float halfWidth = max(s.shape.x * scale.x, 0.0) * 0.5;
    float radius = s.shape.y * scale.x;
    int kind = int(s.shape.z + 0.5);
    vec2 c = corner[gl_VertexIndex % 6];

    vec2 a = s.a.xy * scale;
    vec2 pixel;
    if (kind == 0) {
        // a quad along the segment, one pixel wider than the line on every side for the edge
        vec2 b = s.a.zw * scale;
        vec2 along = b - a;
        float len = length(along);
        vec2 u = len > 1e-4 ? along / len : vec2(1.0, 0.0);
        vec2 v = vec2(-u.y, u.x);
        float reach = halfWidth + 1.0;
        vec2 l = vec2(mix(-reach, len + reach, c.x), c.y * reach);
        pixel = a + u * l.x + v * l.y;
        local = l;
        shape = vec4(halfWidth, 0.0, 0.0, len);
    } else {
        float reach = radius + halfWidth + 1.0;
        vec2 l = vec2(c.x * 2.0 - 1.0, c.y) * reach;
        pixel = a + l;
        local = l;
        shape = vec4(halfWidth, radius, float(kind), 0.0);
    }
    color = s.color;
    gl_Position = vec4(pixel / push.targetSize * 2.0 - 1.0, 0.0, 1.0);
}
