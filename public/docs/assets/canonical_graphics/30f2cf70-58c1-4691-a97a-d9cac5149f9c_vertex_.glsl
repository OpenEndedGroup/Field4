#version 450

{% include "vertex_included" %}

layout (location = 0) out vec4 fcol;

void main()
{
    gl_Position = pc.p * pc.m * vec4(pos, 1);

    fcol = col;
}