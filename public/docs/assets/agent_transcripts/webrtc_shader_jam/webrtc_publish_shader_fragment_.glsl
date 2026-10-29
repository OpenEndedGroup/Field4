#version 450
layout(location = 0) out vec4 color;

layout(push_constant) uniform constants {
    vec4 clock;
} pc;

// Falling masks invert an increasing smoothstep; reversed edges are undefined in GLSL.
void main() {
    vec2 uv = (gl_FragCoord.xy - vec2(320.0, 180.0)) / 180.0;
    float t = pc.clock.x;
    float r = length(uv);
    float a = atan(uv.y, uv.x);
    float rings = 0.5 + 0.5 * sin(10.0 * r - 3.0 * t + 4.0 * sin(3.0 * a + t));
    vec3 tint = 0.5 + 0.5 * cos(vec3(0.0, 2.1, 4.2) + t + 2.0 * r);
    color = vec4(tint * rings * (1.0 - smoothstep(0.2, 1.6, r)), 1.0);
}
