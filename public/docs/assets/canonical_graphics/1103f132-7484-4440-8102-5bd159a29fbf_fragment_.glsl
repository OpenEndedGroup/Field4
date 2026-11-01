#version 450
layout(location = 0) out vec4 uFragColor;

layout(location = 0) in vec4 fcol;
void main()
{
    // {{ col.fixedColor }} is replaced by the source text from shaderTemplate["col"].
    uFragColor = vec4({{ col.fixedColor }});
}