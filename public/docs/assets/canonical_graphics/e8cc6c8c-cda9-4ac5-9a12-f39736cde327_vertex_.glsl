#version 450
#extension GL_EXT_buffer_reference : require
layout (location = 0) in vec3 pos;

layout (location = 0) out vec4 fcol;

layout(std430, buffer_reference) buffer Positions
{
    vec2 col[];
};

layout(push_constant) uniform push {
    float blue;
    Positions ref;
} pc;

void main()
{

    fcol = col;
    fcol.rg = pc.ref.positions[0].xy;
    fcol.b = 0;

    gl_Position = vec4(pos, 1);
}