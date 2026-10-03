#version 450
layout(location = 0) in vec2 ftc;
layout(location = 0) out vec4 uFragColor;
layout(set = 0, binding = 0) uniform sampler2D inset;
void main() { uFragColor = vec4(texture(inset, ftc).rgb, 1.0); }
