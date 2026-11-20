#version 450
#extension GL_EXT_buffer_reference : require

layout(std430, buffer_reference) buffer State {
    vec2 a[];
};
layout(std430, buffer_reference) buffer Wall {
    float d[];
};

layout(push_constant) uniform Push {
    State state;
    Wall wall;
    int w;
    int h;
    int zoom;
} pc;

layout(location = 0) out vec4 outColor;

float srgbToLinear(float c) {
    return c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4);
}

void main() {
    ivec2 cell = ivec2(gl_FragCoord.xy) / max(pc.zoom, 1);
    if (cell.x >= pc.w || cell.y >= pc.h) discard;
    int i = cell.y * pc.w + cell.x;
    float v = clamp(pc.state.a[i].y / 0.5, 0.0, 1.0);
    float d = pc.wall.d[i];
    vec3 c = vec3(v);
    if (d > 0.0) c = mix(vec3(0.10, 0.12, 0.18), vec3(0.03), clamp(d / 30.0, 0.0, 1.0)); // outside: dark blue fading
    if (abs(d) < 0.75) c = vec3(1.0, 0.55, 0.1); // the outline itself, orange
    outColor = vec4(srgbToLinear(c.r), srgbToLinear(c.g), srgbToLinear(c.b), 1.0);
}
