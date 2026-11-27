#version 450
#extension GL_EXT_buffer_reference : require

// Analytic antialiasing of a whole run, blended once per pixel.
//
// The stroke of a run is the union of its segments' capsules, and the distance to a union
// is the smallest distance to any part of it. So each fragment measures its signed
// distance (distance minus that segment's radius, in pixels) to its own segment and to the
// NEIGHBOURS segments either side in the same run. Only the nearest segment draws the
// pixel (ties go to the earlier one), at the coverage the union's distance gives. Every
// pixel inside the run is drawn once, so translucent strokes do not darken where segments
// overlap, and the edge is antialiased from the union, not from one capsule.
//
// A run that comes back on itself further away than NEIGHBOURS segments still overlaps
// there. Raising it costs two more segment tests per pixel per step.
#define NEIGHBOURS 4

// Two segments see the same pixel at slightly different positions (each interpolates it
// over its own quad, and at thousands of pixels a float is only good to about 1e-3), so a
// tie is a tie within this many pixels; the earlier segment takes it. Without it a few
// pixels are drawn by both segments (a bright speck) or by neither (a hole).
#define TIE 1e-2

struct FLineVertex {
    vec4 position; // xyz: world position; w: thickness in pixels, negated on a run's first vertex
    vec4 color; // straight-alpha RGBA, 0..1
};

layout(std430, buffer_reference, buffer_reference_align = 16) readonly buffer FLineVertices {
    FLineVertex v[];
};

layout(push_constant) uniform StrokePC {
    mat4 p;
    mat4 m;
    FLineVertices vertices;
    uint count;
    vec2 viewport;
    float widthScale;
} pc;

layout(location = 0) in vec4 color;
layout(location = 1) flat in uint segment;
layout(location = 2) noperspective in vec2 pixelPosition;

layout(location = 0) out vec4 fragColor;

// A vertex in the vertex shader's pixel space; false when it is behind the camera.
bool toPixels(mat4 pm, vec3 p, out vec2 pixel) {
    vec4 c = pm * vec4(p, 1);
    pixel = (c.xy / c.w * 0.5 + 0.5) * pc.viewport;
    return c.w > 1e-4;
}

// Signed distance, in pixels, from q to segment i's capsule: negative inside it.
float segmentDistance(mat4 pm, uint i, vec2 q) {
    FLineVertex a = pc.vertices.v[i];
    FLineVertex b = pc.vertices.v[i + 1u];
    vec2 sa;
    vec2 sb;
    if (!toPixels(pm, a.position.xyz, sa) || !toPixels(pm, b.position.xyz, sb)) return 1e9;
    vec2 ab = sb - sa;
    float t = clamp(dot(q - sa, ab) / max(dot(ab, ab), 1e-12), 0.0, 1.0);
    float radius = 0.5 * max(abs(a.position.w), abs(b.position.w)) * pc.widthScale;
    return length(q - (sa + t * ab)) - radius;
}

void main() {
    mat4 pm = pc.p * pc.m;
    vec2 q = pixelPosition;
    uint s = segment;
    float own = segmentDistance(pm, s, q);

    // Earlier segments in this run: stop at the run's start. An earlier one as near (to within
    // TIE) wins.
    for (uint k = 1u; k <= uint(NEIGHBOURS) && k <= s; k++) {
        uint i = s - k;
        if (pc.vertices.v[i + 1u].position.w < 0.0) break; // segment i would end at our run's start
        if (segmentDistance(pm, i, q) <= own + TIE) discard;
    }
    // Later segments in this run: stop where the next run starts. Only a nearer one (by more
    // than TIE) wins.
    for (uint k = 1u; k <= uint(NEIGHBOURS); k++) {
        uint i = s + k;
        if (i + 1u >= pc.count || pc.vertices.v[i + 1u].position.w < 0.0) break;
        if (segmentDistance(pm, i, q) < own - TIE) discard;
    }

    // Box-filtered coverage of a straight edge at that distance; a stroke thinner than a
    // pixel comes out fainter rather than thinner.
    float coverage = clamp(0.5 - own, 0.0, 1.0);
    if (coverage <= 0.0) discard;
    fragColor = vec4(color.rgb, color.a * coverage);
}
