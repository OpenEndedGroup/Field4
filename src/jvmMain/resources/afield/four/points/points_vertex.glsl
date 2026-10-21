#version 450
#extension GL_EXT_buffer_reference : require

// Plots AccumulatedPoint records: each is a square of side max(size, 1) pixels at a world
// position, carrying a premultiplied colour scaled by its weight and, below a pixel, its area.
struct AccumulatedPoint {
    vec4 position;
    vec4 color;
};

layout(std430, buffer_reference) buffer Points { AccumulatedPoint p[]; };

layout(push_constant) uniform Push {
    Points points;
    vec4 viewport;   // xMin, yMin, xMax, yMax in world units
    vec2 targetSize; // pixels
    float intensity;
} push;

layout(location = 0) out vec4 pointColor;

const vec2 corner[6] = vec2[](
    vec2(-0.5, -0.5), vec2(0.5, -0.5), vec2(0.5, 0.5),
    vec2(-0.5, -0.5), vec2(0.5, 0.5), vec2(-0.5, 0.5)
);

void main() {
    AccumulatedPoint point = push.points.p[gl_VertexIndex / 6];
    float size = point.position.z;
    float weight = point.position.w;
    if (weight <= 0.0 || !(size > 0.0) || any(isnan(point.position.xy)) || any(isinf(point.position.xy))) {
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        pointColor = vec4(0.0);
        return;
    }
    float side = max(size, 1.0);
    float area = min(size * size, 1.0);

    vec2 span = push.viewport.zw - push.viewport.xy;
    vec2 t = (point.position.xy - push.viewport.xy) / span;
    // world y points up; the target's rows run down
    vec2 ndc = vec2(t.x * 2.0 - 1.0, 1.0 - t.y * 2.0);
    ndc += corner[gl_VertexIndex % 6] * side * 2.0 / push.targetSize;

    gl_Position = vec4(ndc, 0.0, 1.0);
    float a = clamp(point.color.a, 0.0, 1.0) * weight * area * push.intensity;
    pointColor = vec4(point.color.rgb * a, a);
}
