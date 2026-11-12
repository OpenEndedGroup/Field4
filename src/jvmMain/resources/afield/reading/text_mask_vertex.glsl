#version 450
#extension GL_EXT_buffer_reference : require
#extension GL_EXT_multiview : require
layout(std430, buffer_reference, buffer_reference_align = 4) readonly buffer SlugData {
    uint words[]; // immutable Slug header, curve and band atlas in 32-bit words
};
struct BlurEntry {
    SlugData slug; // word-local outline atlas address
    vec4 bounds; // padded min XY / max XY in font units
    vec4 metrics; // maximum sigma in font units, tap radius limit, sigma cutoff, reserved
};
layout(std430, buffer_reference) readonly buffer BlurEntries {
    BlurEntry item[]; // paired immutable 48-byte word-cache entries
};
layout(push_constant) uniform MaskPush {
    BlurEntries entries; // two word-local Slug addresses and padded font-space rectangles
}
pc;
layout(location = 0) out vec2 emCoord;
layout(location = 1) flat out int layer;
void main() {
    layer = gl_ViewIndex;
    vec2 p = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    vec4 b = pc.entries.item[layer].bounds;
    emCoord = mix(b.xy, b.zw, p);
    gl_Position = vec4(p * 2 - 1, 0, 1);
}
