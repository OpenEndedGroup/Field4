#version 450

layout(location = 0) in vec4 color;
layout(location = 1) in vec2 local;
layout(location = 2) flat in vec2 lengthRadius;

layout(location = 0) out vec4 fragColor;

void main() {
    // Distance in pixels from the segment (a capsule, so joins and ends come out round).
    float len = lengthRadius.x;
    float radius = max(lengthRadius.y, 0.5); // thinner than a pixel draws a pixel, fainter
    float x = clamp(local.x, 0.0, len);
    float d = length(vec2(local.x - x, local.y));

    float coverage = clamp(radius + 0.5 - d, 0.0, 1.0) * min(1.0, lengthRadius.y / 0.5);
    if (coverage <= 0.0) discard;
    // Translucent strokes overlap at their joins, so a join reads a little darker there.
    fragColor = vec4(color.rgb, color.a * coverage);
}
