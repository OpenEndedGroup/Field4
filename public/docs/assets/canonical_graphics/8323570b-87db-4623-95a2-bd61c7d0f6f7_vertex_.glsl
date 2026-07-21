#version 450
layout (location = 0) in vec3 pos;

layout(push_constant) uniform matrices {
    mat4 p;
    mat4 m;
    vec4 finalColor;
} pc;

void main()
{
    gl_Position = pc.p * pc.m * vec4(pos + vec3(pc.finalColor.xy,0), 1);
}