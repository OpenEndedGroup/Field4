#version 450
layout(location = 0) out vec4 uFragColor;

layout(location = 0) in vec2 tc;

void main()
{
    float d = 1-length(tc.xy - vec2(0.5))*2;
    uFragColor = vec4(1,1,1,d);
    
//    uFragColor = vec4(tc.xy, 0.0, 1);
}