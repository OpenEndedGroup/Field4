#version 450
layout(location = 0) out vec4 uFragColor;
layout(location = 1) out vec4 uFragColor1;
layout(location = 0) in vec2 ftc;

layout(set = 0, binding = 0) uniform sampler2D b0;

layout(location = 1) in vec4 radii;
layout(location = 2) in vec4 weights;

void main()
{
    // Choose three sample radii proportionally spaced within the radius

    vec2 texel = 1.0 / vec2(textureSize(b0, 0));
    vec2 dx1 = texel * vec2(radii.x, 0.0);
    vec2 dx2 = texel * vec2(radii.y, 0.0);
    vec2 dx3 = texel * vec2(radii.z, 0.0);

    vec4 c =
          weights.x * texture(b0, ftc)
        + weights.y * (texture(b0, ftc + dx1) + texture(b0, ftc - dx1))
        + weights.z * (texture(b0, ftc + dx2) + texture(b0, ftc - dx2))
        + weights.w * (texture(b0, ftc + dx3) + texture(b0, ftc - dx3));

    uFragColor = vec4(c.rgb / radii.w, 1.0);
    uFragColor1 = vec4(c.bgr / radii.w, 1.0);

}