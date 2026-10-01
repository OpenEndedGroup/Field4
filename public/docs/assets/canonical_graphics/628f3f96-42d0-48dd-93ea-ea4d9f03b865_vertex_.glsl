#version 450
#extension GL_EXT_buffer_reference : require
// Adapted Slug core: Copyright (c) 2026 AlphaPixel LLC. See LICENSE-slughorn.
layout(std430, buffer_reference, buffer_reference_align = 4) readonly buffer SlugData { uint words[]; };
layout(push_constant) uniform SlugPC {
    mat4 mvp;
    SlugData slugData;
    vec2 viewport;
    vec4 tint;
} pc;
vec4 data4(uint at) {
    return uintBitsToFloat(uvec4(pc.slugData.words[at], pc.slugData.words[at+1],
        pc.slugData.words[at+2], pc.slugData.words[at+3]));
}
layout(location=0) out vec2 emCoord;
const vec2 corners[6] = vec2[6](vec2(0,0), vec2(1,0), vec2(1,1), vec2(0,0), vec2(1,1), vec2(0,1));
void main() {
    vec4 bounds = data4(8u);
    vec2 corner = corners[gl_VertexIndex % 6];
    vec2 q = mix(bounds.xy, bounds.zw, corner);
    vec2 normal = 2.0 * corner - 1.0;
    mat4 matrix = pc.mvp;
    vec4 position = matrix * vec4(q, 0.0, 1.0);
    // Lengyel's perspective-dependent dilation, expressed in homogeneous vectors.
    // normal is the corner bisector scaled so both incident sides expand equally.
    vec4 direction = matrix * vec4(normalize(normal), 0.0, 0.0);
    float s = position.w;
    float t = direction.w;
    vec2 uv = pc.viewport * (s*direction.xy - t*position.xy);
    float denominator = length(uv) - s*t;
    float distance = denominator > 1e-12 ? s*s / denominator : 0.0;
    q += distance * normal;
    emCoord = q;
    gl_Position = matrix * vec4(q, 0.0, 1.0);
}
