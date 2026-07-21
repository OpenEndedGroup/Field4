#version 450
layout(location = 0) out vec4 uFragColor;
layout(location = 1) in vec2 ftc;

layout(set=0, binding = 0) uniform sampler2DArray bbc;
layout(set=0, binding = 1) uniform sampler2DArray back;

void main()
{
    vec4 c = texture(back, vec3(ftc.yx,1.0), 0.0);
    uFragColor = vec4(c.rgb, 1.0);
} 