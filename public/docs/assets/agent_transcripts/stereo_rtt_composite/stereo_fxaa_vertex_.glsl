#version 450
layout(location = 0) out vec2 vUv;
const vec2 positions[3] = vec2[](
vec2(-1.0, -1.0),
vec2(3.0, -1.0),
vec2(-1.0, 3.0)
);
void main() {
    vec2 pos = positions[gl_VertexIndex];
    // Texel-aligned: v = 0 at clip y = +1, which the Y-flipped viewport puts at row 0.
    vUv = vec2(pos.x, -pos.y) * 0.5 + 0.5;
    gl_Position = vec4(pos, 0.0, 1.0);
}
