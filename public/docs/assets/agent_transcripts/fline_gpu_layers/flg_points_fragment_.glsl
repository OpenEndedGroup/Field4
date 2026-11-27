#version 450

layout(location = 0) in vec4 color;
layout(location = 1) in vec2 local;
layout(location = 2) flat in float radius;

layout(location = 0) out vec4 fragColor;

void main() {
    float coverage = clamp(radius + 0.5 - length(local), 0.0, 1.0);
    if (coverage <= 0.0) discard;
    fragColor = vec4(color.rgb, color.a * coverage);
}
