#version 450
layout(location = 0) out vec2 fragUV;

void main() {
    // Fullscreen triangle from gl_VertexIndex, no vertex inputs.
    vec2 pos = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
    fragUV = pos;
}
