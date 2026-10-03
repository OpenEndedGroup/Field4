#version 450
layout(location = 0) in vec4 fcol;
layout(location = 0) out vec4 uFragColor;
void main() { uFragColor = fcol; }
