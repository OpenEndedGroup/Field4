#version 450
layout(location = 0) out vec4 uFragColor;

// location 0 here needs to match location 0 in the vertexShader
layout(location = 0) in vec4 localColor;
void main()
{
    uFragColor = vec4(localColor.rgb*1, 0.3);
}