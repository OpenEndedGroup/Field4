#version 450
#include "dist/ring"
#include "dist/arc"
#include "gp/ridge"

layout(location = 0) in vec2 uv;
layout(location = 0) out vec4 color;

const int POINTS = 320;
const vec3 BLUE = vec3(0.0, 0.67, 1.0);
const vec3 ORANGE = vec3(1.0, 0.67, 0.0);

// the same POINTS draws at every pixel (seeded by the draw's number), splatted where they land
float splats(vec2 p, bool conditioned, float r) {
    float acc = 0.0;
    for (int k = 0; k < POINTS; k++) {
        uint s = nlSeed(uint(k), conditioned ? 23u : 11u);
        float v[2];
        if (conditioned) {
            if (!arc_try(s, v)) continue;
        }
        else ring(s, v);
        vec2 d = p - vec2(v[0], v[1]);
        acc += exp(-dot(d, d) / (2.0 * r * r));
    }
    return min(acc, 1.0);
}

void main() {
    // the window's size in pixels, from how fast uv changes across one pixel
    vec2 size = 1.0 / fwidth(uv);
    float W = size.x, H = size.y, panelW = W / 3.0;
    int panel = int(gl_FragCoord.x / panelW);
    vec2 local = vec2(gl_FragCoord.x - float(panel) * panelW, gl_FragCoord.y);
    float scale = min(panelW, H);
    vec3 c = vec3(0.0);
    if (panel < 2) {
        // a square view of [-1.5, 1.5] in the middle of the panel, y up
        vec2 p = (local - 0.5 * vec2(panelW, H)) / scale * 3.0;
        p.y = -p.y;
        float px = 3.0 / scale; // one pixel, in data units
        if (panel == 1 && abs(p.x + p.y - 0.5) < 0.7 * px * 1.4142) c = vec3(0.35); // the line x + y = 0.5
        c = max(c, (panel == 0 ? BLUE : ORANGE) * splats(p, panel == 1, 2.2 * px));
    }
    else {
        // the Gaussian process on x in [0, 8], y in [-1.5, 2.5]
        vec2 lo = vec2(0.0, -1.5), hi = vec2(8.0, 2.5);
        vec2 q = lo + (vec2(local.x, H - local.y) / vec2(panelW, H)) * (hi - lo);
        vec2 px = (hi - lo) / vec2(panelW, H);
        float m = ridge_mean(q.x), sd = ridge_sd(q.x);
        if (abs(q.y - m) < 2.0 * sd) c = vec3(0.0, 0.006, 0.014); // the window is sRGB: dark values stay dark only if they are very small
        for (int k = 0; k < 20; k++) {
            float f = ridge(q.x, uint(k));
            c = max(c, BLUE * 0.55 * exp(-pow((q.y - f) / (1.2 * px.y), 2.0)));
        }
        c = max(c, vec3(exp(-pow((q.y - m) / (1.1 * px.y), 2.0))));
        for (int i = 0; i < ridge_N; i++) {
            vec2 d = (q - vec2(ridge_POINTS[i], ridge_VALUES[i])) / px;
            if (dot(d, d) < 49.0) c = ORANGE;
        }
    }
    if (abs(gl_FragCoord.x - panelW) < 0.75 || abs(gl_FragCoord.x - 2.0 * panelW) < 0.75) c = vec3(0.25);
    color = vec4(c, 1.0);
}
