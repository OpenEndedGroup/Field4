#version 450

// Carries the accumulation forward a frame, keeping 1 - fade of it.
layout(set = 0, binding = 0) uniform sampler2D previous;

layout(push_constant) uniform Push {
    float keep;
    float clearRequested;
} push;

layout(location = 0) out vec4 outColor;

void main() {
    ivec2 dims = textureSize(previous, 0);
    vec4 last = texelFetch(previous, clamp(ivec2(gl_FragCoord.xy), ivec2(0), dims - 1), 0);
    outColor = push.clearRequested > 0.5 ? vec4(0.0) : max(last * push.keep, vec4(0.0));
}
