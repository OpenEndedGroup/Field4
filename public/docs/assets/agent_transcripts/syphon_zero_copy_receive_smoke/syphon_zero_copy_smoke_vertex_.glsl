#version 450
layout(location = 0) in vec3 pos;
layout(location = 1) in vec2 tc;
void main() {
    gl_Position = vec4(pos.xy, 0.5, 1.0);
}
