#version 450
layout(location = 0) out vec4 uFragColor;
layout(location = 0) in vec2 tc;

void main()
{
    float d = 1-1.8*length(tc-vec2(0.5));
    d = smoothstep(0.1, 0.2, d);
    uFragColor = vec4(0.0, 0.8, 1.0, d*0.1);
}