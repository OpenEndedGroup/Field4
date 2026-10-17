#version 450

layout(push_constant) uniform Push {
    vec2 center;
    float radius;
    float height;
} push;

layout(location = 0) out vec4 outColor;

// red: solid; green and blue: a push; alpha: a dye source.
// The blend is additive and the controls carry over, so this is a drawing, not a frame.
void main() {
    vec2 p = gl_FragCoord.xy;
    outColor = vec4(0.0);

    if (distance(p, push.center) < push.radius) outColor.r = 1.0;
    if (p.y < 3.0 || p.y > push.height - 3.0) outColor.r = 1.0;

    if (p.x < 4.0 && mod(floor(p.y / 12.0), 2.0) == 0.0) outColor.a = 1.0;
}
