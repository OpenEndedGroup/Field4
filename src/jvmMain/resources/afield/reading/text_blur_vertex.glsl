#version 450
#extension GL_EXT_multiview : require
// VIEWS
layout(push_constant) uniform LocalBlurPush {
    mat4 model;  // the exact current text-local to world transform, shared by both eyes
    vec4 bounds; // padded word rectangle in the same font units as Slug
    vec4 levels; // lower/upper cached levels, variance interpolation and reserved
    vec4 tint;   // white ink and live opacity, including the sharp/cache transition weight
    vec4 info;   // cache-array layer and reserved values
}
pc;
layout(location = 0) out vec2 uv;
const vec2 corners[6] =
    vec2[6](vec2(0, 0), vec2(1, 0), vec2(1, 1), vec2(0, 0), vec2(1, 1), vec2(0, 1));
void main() {
    vec2 corner = corners[gl_VertexIndex % 6];
    uv = vec2(corner.x, 1 - corner.y);
    vec2 q = mix(pc.bounds.xy, pc.bounds.zw, corner);
    gl_Position = (gl_ViewIndex == 0 ? stereoVpL : stereoVpR) * pc.model * vec4(q, 0, 1);
}
