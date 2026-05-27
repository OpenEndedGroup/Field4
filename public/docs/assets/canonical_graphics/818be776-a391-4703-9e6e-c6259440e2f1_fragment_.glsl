#version 450
layout(location = 0) out vec4 uFragColor;

layout(push_constant) uniform matrices {
    mat4 p;
    mat4 m;
    vec4 finalColor;
} pc;


void main()
{
    // uFragColor = pc.finalColor;
   uFragColor = vec4(1,1,1,1);
}