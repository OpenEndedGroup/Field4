#version 450
#include "coverage/triangle.glsl"

layout(location = 0) out vec4 color;

layout(push_constant) uniform pcs {
    vec4 viewport; // width, height, fat pixel size (0 = fit), window sigma
} pc;

// The same eight triangles as the CPU box, on a 32 x 24 grid of fat pixels, y down.
const int N = 8;
const vec2 P[24] = vec2[](
vec2(2, 20), vec2(14, 3), vec2(20, 18),
vec2(18, 8), vec2(31, 11), vec2(24, 22),
vec2(3, 2), vec2(30, 4), vec2(30, 4.35),
vec2(6, 22), vec2(28, 21.6), vec2(28, 22),
vec2(2.05, 5.05), vec2(2.45, 5.1), vec2(2.1, 5.45),
vec2(4.05, 5.05), vec2(4.45, 5.1), vec2(4.1, 5.45),
vec2(6.05, 5.05), vec2(6.45, 5.1), vec2(6.1, 5.45),
vec2(8.05, 5.05), vec2(8.45, 5.1), vec2(8.1, 5.45)
);
const vec4 C[8] = vec4[](
vec4(1.0, 0.55, 0.1, 1.0), vec4(0.2, 0.8, 0.4, 0.7), vec4(0.3, 0.6, 1.0, 1.0), vec4(1.0),
vec4(1.0), vec4(1.0), vec4(1.0), vec4(1.0)
);

void main() {
    float w = pc.viewport.x;
    float h = pc.viewport.y;
    float halfW = floor(w * 0.5);
    float k = pc.viewport.z > 0.5 ? pc.viewport.z : floor(min(halfW / 33.0, h / 25.0));
    k = max(k, 1.0);
    float sigma = pc.viewport.w;

    bool exact = gl_FragCoord.x >= halfW;
    // Centre the 32 x 24 scene in each half.
    vec2 origin = floor(vec2(halfW - 32.0 * k, h - 24.0 * k) * 0.5);
    vec2 local = gl_FragCoord.xy - vec2(exact ? halfW + origin.x : origin.x, origin.y);
    vec2 cell = floor(local / k);

    vec3 c = vec3(0.0);
    for (int i = 0; i < N; i++) {
        vec2 a = P[3 * i];
        vec2 b = P[3 * i + 1];
        vec2 d = P[3 * i + 2];
        float cover = exact
        ? tcWindowCoverage(a, b, d, sigma, cell, cell + 1.0)
        : tcWindow(a, b, d, sigma, cell + 0.5);
        c = mix(c, C[i].rgb, clamp(cover * C[i].a, 0.0, 1.0));
    }
    // A faint outline on each fat pixel, and a bright divider between the halves.
    vec2 f = local - cell * k;
    if (k >= 6.0 && (f.x < 1.0 || f.y < 1.0)) c = mix(c, vec3(0.25), 0.35);
    if (abs(gl_FragCoord.x - halfW) < 1.5) c = vec3(1.0);
    // The window target is sRGB: decode so the colours show as the CPU figure's stored values.
    color = vec4(pow(c, vec3(2.2)), 1.0);
}
