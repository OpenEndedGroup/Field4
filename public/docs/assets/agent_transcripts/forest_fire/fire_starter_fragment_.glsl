#version 450

layout(push_constant) uniform Push {
    vec2 red;
    vec2 green;
    float radius;
} push;

layout(location = 0) out vec4 outColor;

// one kind of fire per channel; additive, so writing 1 into a channel lights that kind here
void main() {
    vec2 p = gl_FragCoord.xy;
    outColor = vec4(0.0);
    if (distance(p, push.red) < push.radius) outColor.r = 1.0;
    if (distance(p, push.green) < push.radius) outColor.g = 1.0;
}
