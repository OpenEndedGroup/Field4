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
layout(location = 0) in vec2 uv;
layout(location = 1) flat in int eye;
layout(location = 0) out vec4 outColor;
layout(set = 0, binding = 0) uniform sampler2DArray source;
layout(push_constant) uniform GaussianPush {
    BlurEntries entries; // padded rectangles and maximum sigma in font units, one per layer
    vec4 pass; // source width/height, incremental sigma fraction, axis (0 horizontal, 1 vertical)
}
pc;
void main() {
    BlurEntry entry = pc.entries.item[eye];
    bool horizontal = pc.pass.w < .5;
    float extent = horizontal ? entry.bounds.z - entry.bounds.x : entry.bounds.w - entry.bounds.y;
    float pixels = horizontal ? pc.pass.x : pc.pass.y;
    // Separable, normalized Gaussian. A small floor also prefilters downsampling.
    float sigma = max(entry.metrics.x * pc.pass.z * pixels / extent, .65);
    float cutoff = entry.metrics.z > 0 ? clamp(entry.metrics.z, 2, 6) : 4;
    int limit = entry.metrics.y > 0 ? clamp(int(entry.metrics.y), 4, 512) : 128;
    int radius = min(limit, int(ceil(cutoff * sigma)));
    // A tap budget changes sampling density, not the requested Gaussian width.
    float stride = max(1, cutoff * sigma / float(limit));
    vec2 stepUV = horizontal ? vec2(1 / pc.pass.x, 0) : vec2(0, 1 / pc.pass.y);
    float sum = 0, weight = 0;
    for (int k = -radius; k <= radius; k++) {
        float offset = float(k) * stride;
        float w = exp(-.5 * offset * offset / (sigma * sigma));
        sum += texture(source, vec3(uv + stepUV * offset, float(eye))).r * w;
        weight += w;
    }
    outColor = vec4(sum / max(weight, 1e-8));
}
