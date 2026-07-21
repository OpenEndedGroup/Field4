#version 450
layout(location = 0) out vec2 uv;
vec2 verts[3] = vec2[3](vec2(-1.0, -1.0), vec2(3.0, -1.0), vec2(-1.0, 3.0));
void main() {
    vec2 p = verts[gl_VertexIndex];
    uv = 0.5 * (p + vec2(1.0));
    gl_Position = vec4(p, 0.0, 1.0);
}
