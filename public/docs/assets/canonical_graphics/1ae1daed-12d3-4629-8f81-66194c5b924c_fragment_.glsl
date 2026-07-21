#version 450
layout(location = 0) out vec4 uFragColor;
layout(location = 1) in vec2 ftc;

layout(set=0, binding = 0) uniform sampler2D bbc;

void main()
{
    vec4 c = texture(bbc, ftc.xy);
    uFragColor = vec4(c.rgb + vec3(0,0,0.2), 1.0);
} 