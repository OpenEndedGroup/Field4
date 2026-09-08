#version 450
layout(location=0) in vec3 pos;
layout(location=1) in vec4 col;
layout(location=0) out vec4 fcol;
layout(push_constant) uniform Matrices { mat4 p; mat4 m; } pc;
void main() {
    // m = eyeViewLocal * localFromWorld * modelWorld
    gl_Position = pc.p * pc.m * vec4(pos, 1.0);
    fcol = col;
}
