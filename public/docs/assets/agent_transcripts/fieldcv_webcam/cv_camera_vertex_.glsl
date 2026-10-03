#version 450
layout(location = 0) in vec3 pos;
layout(location = 1) in vec2 tc;
layout(location = 0) out vec2 ftc;
void main() { ftc = tc; gl_Position = vec4(pos, 1); }
