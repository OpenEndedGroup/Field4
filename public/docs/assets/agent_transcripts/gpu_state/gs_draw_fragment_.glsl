#version 450
layout(location = 0) out vec4 uFragColor;
layout(location = 0) in vec2 tc;
layout(location = 1) in float warmth;

void main() {
    float radius = length(tc - vec2(0.5));
    // Ordered edges define a soft disc: full centre coverage, fading to zero at radius 0.5.
    float alpha = 1.0 - smoothstep(0.12, 0.5, radius);
    if (alpha < 0.02) discard;
    vec3 cold = vec3(0.16, 0.40, 0.95);
    vec3 hot = vec3(1.00, 0.78, 0.30);
    vec3 c = mix(cold, hot, clamp(warmth * 5.5, 0.0, 1.0));
    uFragColor = vec4(c, alpha * 0.30);
}
