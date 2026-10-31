#version 450

// Replaced by the GLSL in the `vertex_included` property: inputs pos and col, push constants pc.
{% property "vertex_included" %}

layout (location = 0) out vec4 fcol;

void main()
{
    gl_Position = pc.p * pc.m * vec4(pos, 1);

    fcol = col;
}