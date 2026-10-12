#version 450
layout(location = 0) out vec4 uFragColor;
layout(location = 1) in vec2 ftc;

layout(set = 0, binding = 0) uniform sampler2D ta[3];

void main()
{
    vec4 c = texture(ta[0], ftc.yx);
    uFragColor = vec4(c.rgb/1.0, 1.0);
}