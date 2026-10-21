#version 450

// One trail segment: covered across the line, butt-ended along it, so consecutive segments meet
// edge to edge and a trail is as even as a canvas stroke. A line thinner than a pixel is drawn a
// pixel wide and fainter. Premultiplied sRGB for "over" or additive blending.

layout(location = 0) in float across;
layout(location = 1) flat in float halfWidth;
layout(location = 2) flat in vec4 color;

layout(location = 0) out vec4 outColor;

void main() {
    float hw = max(halfWidth, 0.5);
    float coverage = clamp(hw + 0.5 - abs(across), 0.0, 1.0) * min(halfWidth / 0.5, 1.0);
    float a = color.a * coverage;
    if (a <= 0.0) discard;
    outColor = vec4(color.rgb * a, a);
}
