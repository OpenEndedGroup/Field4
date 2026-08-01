#version 450
layout (location = 0) in vec3 pos;
layout (location = 1) in vec4 col;
layout (location = 0) out vec4 fcol;

layout (set=0, binding = 0) uniform look {
    mat4 p;
    mat4 m;
    vec4 tint;
} lk;

void main()
{
    gl_Position = lk.p*lk.m*vec4(pos, 1);
    fcol = col*lk.tint;
}