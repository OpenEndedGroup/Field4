#version 450
// The scene, and every glyph's six numbers: texel (g, 0) holds glyph g's first four, (g, 1) its
// last two.
layout(set = 0, binding = 0) uniform sampler2D sceneTex;
layout(set = 0, binding = 1) uniform sampler2D glyphVectors;

layout(push_constant) uniform Push {
    vec2 cell;
    float contrast;
    float directional;
} pc;

layout(location = 0) out vec4 outColor;

// The six sampling circles, in cell units: two columns of three, the right one a little higher.
// The glyph vectors were measured with these circles, so change them together.
const vec2 CENTRES[6] = vec2[6](
vec2(0.28, 0.20), vec2(0.72, 0.13),
vec2(0.28, 0.53), vec2(0.72, 0.47),
vec2(0.28, 0.87), vec2(0.72, 0.80)
);
// The circles' radius, as a share of the cell's width.
const float RADIUS = 0.24;
// The outer circles sit this many times further from the cell's centre than the inner ones.
const float OUTER_PUSH = 2.6;

// The mean brightness of the scene inside the circle centred at c (cell units) in the cell whose
// top-left pixel is origin. A pixel counts if its centre lies inside the circle.
float circle(ivec2 origin, vec2 c, ivec2 cell, ivec2 size) {
    float r = RADIUS * float(cell.x);
    vec2 centre = c * vec2(cell);
    float sum = 0.0;
    int n = 0;
    for (int y = int(floor(centre.y - r)) - 1; y <= int(centre.y + r) + 1; y++) {
        for (int x = int(floor(centre.x - r)) - 1; x <= int(centre.x + r) + 1; x++) {
            vec2 d = vec2(x, y) + 0.5 - centre;
            if (dot(d, d) <= r * r) {
                vec3 s = texelFetch(sceneTex, clamp(origin + ivec2(x, y), ivec2(0), size - 1), 0).rgb;
                sum += clamp((s.r + s.g + s.b) / 3.0, 0.0, 1.0);
                n++;
            }
        }
    }
    return sum / float(n);
}

void main() {
    // One fragment per cell: this cell's pixels in the scene start here.
    ivec2 cell = ivec2(pc.cell);
    ivec2 size = textureSize(sceneTex, 0);
    ivec2 origin = ivec2(gl_FragCoord.xy) * cell;

    float v[6];
    for (int k = 0; k < 6; k++) v[k] = circle(origin, CENTRES[k], cell, size);

    // Directional contrast: where the circle just outside the cell, in the same direction, is
    // brighter, darken this one.
    if (pc.directional != 1.0) {
        for (int k = 0; k < 6; k++) {
            float outside = circle(origin, 0.5 + (CENTRES[k] - 0.5) * OUTER_PUSH, cell, size);
            float m = max(v[k], outside);
            if (m > 0.0 && v[k] > 0.0) v[k] = pow(v[k] / m, pc.directional) * m;
        }
    }

    // Contrast: the brightest circle stays, the others fall towards zero.
    if (pc.contrast != 1.0) {
        float m = 0.0;
        for (int k = 0; k < 6; k++) m = max(m, v[k]);
        for (int k = 0; k < 6; k++) if (m > 0.0 && v[k] > 0.0) v[k] = pow(v[k] / m, pc.contrast) * m;
    }

    // The glyph whose six numbers are nearest, by squared distance; the first one wins a tie.
    int best = 0;
    float bestDistance = 1e30;
    for (int g = 0; g < textureSize(glyphVectors, 0).x; g++) {
        vec4 a = texelFetch(glyphVectors, ivec2(g, 0), 0);
        vec4 b = texelFetch(glyphVectors, ivec2(g, 1), 0);
        vec4 da = a - vec4(v[0], v[1], v[2], v[3]);
        vec2 db = b.xy - vec2(v[4], v[5]);
        float d = dot(da, da) + dot(db, db);
        if (d < bestDistance) {
            bestDistance = d;
            best = g;
        }
    }
    // The glyph's index, stored in an 8-bit channel.
    outColor = vec4(float(best) / 255.0, 0.0, 0.0, 1.0);
}
