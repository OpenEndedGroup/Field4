#version 450
layout(location = 0) in vec3 pos;
layout(location = 0) out vec2 uv;

void main() {
    // uv runs from 0 to 1 across the target. Field flips its viewports so that +y is up,
    // so uv.y = 0 is the bottom edge.
    uv = pos.xy * 0.5 + 0.5;
    gl_Position = vec4(pos, 1.0);
}
