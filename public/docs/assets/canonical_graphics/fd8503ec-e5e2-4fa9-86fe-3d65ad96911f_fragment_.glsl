#version 450

layout(location = 0) out vec4 uFragColor;
layout(location = 1) in vec2 ftc;

#include "lygia/generative/gerstnerWave.glsl"
#include "lygia/generative/fbm.glsl"

void main()
{
    vec2 uv = ftc.xy * 1.0;
    vec3 f = gerstnerWave(uv, vec2(1.0, 1.2), 10.3, 0.3, 0.3);

    float n = fbm(uv*2 + f.yx * 0.75);
    float n2 = fbm(uv * 2.0 - 
                    f.yx * 0.5 + 
                    vec2(-f.y, f.x)*n*9);

    vec3 col = abs(f) * (0.6 + 0.8 * n) + vec3(n2 * 0.25, n * 0.2, n * 0.35);
    uFragColor = vec4(col, 1.0);
}