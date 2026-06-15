#version 450
layout(location = 0) out vec4 uFragColor;
layout(location = 0) in vec2 tc;
void main()
{
    float d = max(0.0, 0.5-length(tc-vec2(0.5)));
    float w = fwidth(d);
    d = smoothstep(0.1, 0.1+w, d)*smoothstep(0.14+w, 0.14, d);
    uFragColor = vec4(1.0, 0.8, 0.4, d*0.2);
    if (d<0.01) discard;
}