#version 450

layout(location = 0) in vec2 textCoords;
layout(location = 0) out vec4 uFragColor;

layout(binding = 0) uniform sampler2D bbc;
void main()
{
    vec4 c = texture(bbc, textCoords.yx);
    uFragColor = vec4(c.rgb, 1.0);
}


