#version 450
layout(location = 0) out vec4 uFragColor;
layout(location = 1) in vec2 ftc;

layout(set = 0, binding = 0) uniform sampler2D bbc;

{% include "fieldProbe" %}
{% include "quantileWarp" %}

layout(push_constant) uniform Controls {
    mat4 p;
    mat4 m;
    float time;
    float warpStrength;
    vec4 warpXLo;
    vec4 warpXHi;
    vec4 warpYLo;
    vec4 warpYHi;
} pc;

void main()
{
    vec4 c = texture(bbc, ftc.yx)/10.0;
    //c.r = (sin(ftc.x*123.2313+ftc.y*1231.2312)+1)/2;
    uFragColor = vec4(c.rgb/1.0, 1.0);

    c.r += sin(ftc.x*10.0+ftc.y*123.3)*0.0001;

    float r = fieldQuantileWarpMix(
                            c.r,
                            1,
                            pc.warpXLo,
                            pc.warpXHi,
                            pc.warpYLo,
                            pc.warpYHi
                        );

    if (length(fract(gl_FragCoord.xy*100.9123))<0.1)
        fieldProbe(c.r);

    uFragColor.rgb = vec3(r);
}