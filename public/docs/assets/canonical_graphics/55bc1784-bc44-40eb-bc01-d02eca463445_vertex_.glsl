#version 450
layout (location = 0) in vec3 pos;
layout (location = 1) in vec2 tc;

layout (location = 1) out vec2 ftc;

layout(push_constant) uniform matrices {
    mat4 p;
    mat4 m;
} pc;

void main()
{
    gl_Position = pc.p * pc.m * vec4(pos, 1);
    ftc = tc;
}