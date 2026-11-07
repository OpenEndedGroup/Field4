#version 450
#extension GL_EXT_multiview : require
layout(location = 0) out vec2 uv;
layout(location = 1) flat out int eye;
void main() {
    vec2 p = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    uv = vec2(p.x, 1.0 - p.y);
    eye = gl_ViewIndex;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
