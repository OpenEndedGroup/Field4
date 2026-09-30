#version 450
layout(location = 0) out vec4 uFragColor;
layout(location = 0) in vec4 fcol;

{{ pc.dec }}
void main()
{
    loadRegs();
    
    uFragColor = vec4(a());
}