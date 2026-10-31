#version 450
layout(location = 0) out vec4 uFragColor;
layout(location = 1) in vec2 ftc;

layout(set = 0, binding = 0) uniform sampler2D ta[3];

void main()
{
    // Each third of the quad, left to right, shows one entry of the array. Every entry is sampled and the
    // result chosen afterwards, so the array index stays a constant.
    // ftc.yx swaps the texture coordinates, which transposes the image on the quad.
    vec4 c0 = texture(ta[0], ftc.yx);
    vec4 c1 = texture(ta[1], ftc.yx);
    vec4 c2 = texture(ta[2], ftc.yx);
    vec4 c = ftc.x < 1.0 / 3.0 ? c0 : (ftc.x < 2.0 / 3.0 ? c1 : c2);
    uFragColor = vec4(c.rgb, 1.0);
}