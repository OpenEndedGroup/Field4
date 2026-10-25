#version 450

// Shows a PointAccumulation: the accumulated light, tone mapped, over the background.
layout(location = 0) in vec2 uv;
layout(set = 0, binding = 0) uniform sampler2D accumulation;

layout(push_constant) uniform Push {
    vec4 background; // linear
    float exposure;
    float whitePoint;
    int toneMap;     // 0 linear, 1 exponential, 2 reinhard, 3 log
    int over;        // 1: alpha-blended points cover the background; 0: additive light on it
} push;

layout(location = 0) out vec4 outColor;

vec3 toneMap(vec3 x) {
    x = max(x, vec3(0.0));
    if (push.toneMap == 1) return 1.0 - exp(-x);
    if (push.toneMap == 2) return x * (1.0 + x / (push.whitePoint * push.whitePoint)) / (1.0 + x);
    if (push.toneMap == 3) return clamp(log(1.0 + x) / log(1.0 + push.whitePoint), 0.0, 1.0);
    return clamp(x, 0.0, 1.0);
}

void main() {
    vec4 light = texture(accumulation, uv);
    vec3 shown = toneMap(light.rgb * push.exposure);
    vec3 ground = push.background.rgb;
    vec3 color = push.over != 0
        ? shown + ground * (1.0 - clamp(light.a, 0.0, 1.0))
        : 1.0 - (1.0 - ground) * (1.0 - shown);
    outColor = vec4(color, 1.0);
}
