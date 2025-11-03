#version 450

layout (location = 0) in vec3 pos;
layout (location = 1) in vec2 tc;

layout (location = 0) out vec2 textCoords;

layout(push_constant) uniform matrices {
    mat4 projectionMatrix;
    mat4 modelMatrix;
} push_constants;


void main()
{
    gl_Position = push_constants.projectionMatrix * push_constants.modelMatrix * vec4(pos, 1);

    textCoords = tc;
}

