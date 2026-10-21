#version 450

// Shows a ParticleSketch's picture over its background. The picture holds premultiplied sRGB, as
// a 2D canvas does, so it is composited over the background in sRGB and converted to linear light
// only here, for the window.

layout(location = 0) in vec2 uv;
layout(set = 0, binding = 0) uniform sampler2D accumulation;

layout(push_constant) uniform Push {
    vec4 background; // sRGB
} push;

layout(location = 0) out vec4 outColor;

float toLinear(float c) {
    return c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4);
}

void main() {
    vec4 picture = texture(accumulation, uv);
    vec3 srgb = clamp(picture.rgb + push.background.rgb * (1.0 - clamp(picture.a, 0.0, 1.0)), 0.0, 1.0);
    outColor = vec4(toLinear(srgb.r), toLinear(srgb.g), toLinear(srgb.b), 1.0);
}
