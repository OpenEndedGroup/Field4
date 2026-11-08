#version 450
#extension GL_EXT_buffer_reference : require
#extension GL_EXT_multiview : require
layout(std430, buffer_reference, buffer_reference_align = 4) readonly buffer SlugData {
    uint words[];
};
struct BlurEntry {
    SlugData slug;
    vec4 bounds;
    vec4 metrics;
};
layout(std430, buffer_reference) readonly buffer BlurEntries { BlurEntry item[]; };
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
    // Separable, normalized, four-sigma Gaussian. A small floor also prefilters downsampling.
    float sigma = max(entry.metrics.x * pc.pass.z * pixels / extent, .65);
    int radius = min(128, int(ceil(4 * sigma)));
    vec2 stepUV = horizontal ? vec2(1 / pc.pass.x, 0) : vec2(0, 1 / pc.pass.y);
    float sum = 0, weight = 0;
    for (int k = -radius; k <= radius; k++) {
        float w = exp(-.5 * float(k * k) / (sigma * sigma));
        sum += texture(source, vec3(uv + stepUV * float(k), float(eye))).r * w;
        weight += w;
    }
    outColor = vec4(sum / max(weight, 1e-8));
}
