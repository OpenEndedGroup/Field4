#version 450
layout(location = 0) out vec4 uFragColor;
layout(location = 1) in vec2 ftc;

layout(set = 0, binding = 0) uniform sampler2D bbc;

// the shader side of the probe, and the warp-curve functions (fieldQuantileWarpMix)
{% property "fieldProbe" %}
{% property "quantileWarp" %}

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
// warpXLo ... warpYHi hold the eight knots of the warp curve, written from the box code each frame

void main()
{
    vec4 c = texture(bbc, ftc.yx)/10.0;
    uFragColor = vec4(c.rgb/1.0, 1.0);

    c.r += sin(ftc.x*10.0+ftc.y*123.3)*0.0001;

    // remap c.r through the warp curve; the second argument is the strength, 1 = fully warped
    float r = fieldQuantileWarpMix(
    c.r,
    1,
    pc.warpXLo,
    pc.warpXHi,
    pc.warpYLo,
    pc.warpYHi
    );

    // probe only a sparse, scattered subset of fragments, and probe the value before the warp
    if (length(fract(gl_FragCoord.xy*100.9123))<0.1)
    fieldProbe(c.r);

    uFragColor.rgb = vec3(r);
}
