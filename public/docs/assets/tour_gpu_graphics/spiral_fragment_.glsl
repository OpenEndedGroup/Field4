#version 450
layout(location = 0) out vec4 uFragColor;
layout(location = 1) in vec2 ftc;

layout(push_constant) uniform constants {
    float time;
} pc;

void main()
{
    vec2 p = ftc * 2.0 - 1.0;
    float r = length(p);
    float rings = 0.5 + 0.5 * sin(24.0 * r - 3.0 * pc.time + 5.0 * atan(p.y, p.x));
    vec3 col = mix(vec3(0.05, 0.10, 0.30), vec3(1.0, 0.65, 0.25), rings);
    uFragColor = vec4(col, 1.0);
}
