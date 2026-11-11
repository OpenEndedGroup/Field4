#version 450
layout(location = 0) in vec4 fcol;
layout(location = 0) out vec4 uFragColor;
void main()
{
    // a round dot with a soft edge
    float r = length(gl_PointCoord - vec2(0.5));
    if (r > 0.5) discard;
    uFragColor = vec4(fcol.rgb, fcol.a * smoothstep(0.5, 0.25, r));
}
