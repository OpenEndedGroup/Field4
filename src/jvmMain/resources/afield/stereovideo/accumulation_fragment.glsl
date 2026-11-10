#version 450
layout(binding = 0) uniform sampler2D luma; // full-resolution packed SBS P010 luma, R16_UNORM
layout(binding = 2) uniform sampler2D previousImage; // previous linear-light running mean
layout(binding = 1) uniform sampler2D chroma; // half-resolution interleaved Cb/Cr, R16G16_UNORM
layout(push_constant) uniform AccumulationPush {
    vec4 image;       // packed width/height, reserved, reserved
    vec4 colorimetry; // Kr, Kb, full-range flag, reserved
    vec4 weights;     // incoming normalized weight, history valid, input shadow cooling, reserved
} pc;
layout(location = 0) in vec2 uv; // packed SBS coordinates, top-left origin
layout(location = 0) out vec4 color;
vec3 toLinear(vec3 c) {
    return mix(c / 12.92, pow((c + .055) / 1.055, vec3(2.4)), step(vec3(.04045), c));
}
// Input-only toning: new samples carry this colour into the running mean; history is never recoloured.
vec3 coolInputShadows(vec3 rgb) {
    float y = dot(rgb, vec3(.2126, .7152, .0722));
    float amount = pc.weights.z * (1 - smoothstep(.015, .25, y));
    if (amount <= 0 || y <= .000001) return rgb;
    const vec3 cool = vec3(-.4, .05, (.4 * .2126 - .05 * .7152) / .0722);
    vec3 chroma = rgb - vec3(y) + y * amount * cool;
    // Same-luminance gamut compression, matching the output toner's policy rather than channel clipping.
    float gamut = 1;
    for (int channel = 0; channel < 3; channel++) {
        if (chroma[channel] > 0) gamut = min(gamut, (1 - y) / chroma[channel]);
        if (chroma[channel] < 0) gamut = min(gamut, -y / chroma[channel]);
    }
    vec3 tinted = vec3(y) + clamp(gamut, 0, 1) * chroma;
    return mix(rgb, tinted, smoothstep(.000001, .0001, y));
}

void main() {
    vec2 q = uv;
    int sourceEye = q.x < .5 ? 0 : 1;
    // Bilinear filtering must never sample the other eye across the middle seam.
    vec2 lower = vec2(float(sourceEye) * .5, 0), upper = vec2(float(sourceEye + 1) * .5, 1);
    vec2 qY = clamp(q, lower + .5 / pc.image.xy, upper - .5 / pc.image.xy);
    vec2 qUV = clamp(q, lower + 1 / pc.image.xy, upper - 1 / pc.image.xy);
    // P010 stores 10-bit codes in the high ten bits of normalized 16-bit texels.
    float codeY = texture(luma, qY).r * (65535.0 / 64.0);
    vec2 codeUV = texture(chroma, qUV).rg * (65535.0 / 64.0);
    bool full = pc.colorimetry.z > .5;
    float y = full ? codeY / 1023.0 : (codeY - 64.0) / 876.0;
    vec2 cbcr = (codeUV - 512.0) / (full ? 1023.0 : 896.0);
    float kr = pc.colorimetry.x, kb = pc.colorimetry.y, kg = 1 - kr - kb;
    vec3 rgb = vec3(y + 2 * (1 - kr) * cbcr.y,
                    y - 2 * kb * (1 - kb) / kg * cbcr.x - 2 * kr * (1 - kr) / kg * cbcr.y,
                    y + 2 * (1 - kb) * cbcr.x);
    // Display-referred SDR code values -> linear; float eyes retain linear light until
    // display/stream encoding.
    vec3 incoming = coolInputShadows(toLinear(clamp(rgb, 0, 1)));
    vec3 previous = pc.weights.y > .5 ? texture(previousImage, qY).rgb : incoming;
    color = vec4(mix(previous, incoming, pc.weights.x), 1);

}
