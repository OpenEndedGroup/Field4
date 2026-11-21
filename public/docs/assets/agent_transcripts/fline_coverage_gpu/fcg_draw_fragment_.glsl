#version 450
#extension GL_EXT_buffer_reference : require

// Two floats per cell: coverage under non-zero, then under even-odd.
layout(std430, buffer_reference) buffer Mask {
    vec2 a[];
};

layout(push_constant) uniform Push {
    Mask mask;
    int w;
    int h;
    int zoom;
} pc;

layout(location = 0) out vec4 outColor;

float srgbToLinear(float c) {
    return c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4);
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy) / max(pc.zoom, 1);
    // the left copy reads channel 0, the right copy channel 1, with a one-cell gap
    int rule = p.x >= pc.w + 1 ? 1 : 0;
    ivec2 cell = ivec2(p.x - rule * (pc.w + 1), p.y);
    if (cell.x < 0 || cell.x >= pc.w || cell.y >= pc.h) discard;
    vec2 m = pc.mask.a[cell.y * pc.w + cell.x];
    float c = clamp(rule == 0 ? m.x : m.y, 0.0, 1.0);
    vec3 back = vec3(0.06, 0.07, 0.11);
    vec3 ink = rule == 0 ? vec3(1.0, 0.62, 0.25) : vec3(0.35, 0.85, 1.0);
    vec3 col = mix(back, ink, c);
    outColor = vec4(srgbToLinear(col.r), srgbToLinear(col.g), srgbToLinear(col.b), 1.0);
}
