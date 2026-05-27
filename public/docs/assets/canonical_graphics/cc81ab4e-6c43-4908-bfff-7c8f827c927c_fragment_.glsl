#version 450
layout(location = 0) out vec4 uFragColor;

layout(location = 0) in vec4 fcol;
void main()
{
    uFragColor = vec4(1.0, 0.8, 0.4, 1.0)* fcol;
}