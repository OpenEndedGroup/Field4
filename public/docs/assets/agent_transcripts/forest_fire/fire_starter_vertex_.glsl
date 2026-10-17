#version 450

// one triangle that covers the whole target; positions come from the vertex index
vec2 fullscreenVertex(uint index) {
    if (index == 0u) return vec2(-1.0, -1.0);
    if (index == 1u) return vec2(3.0, -1.0);
    return vec2(-1.0, 3.0);
}

void main() {
    gl_Position = vec4(fullscreenVertex(uint(gl_VertexIndex)), 0.0, 1.0);
}
