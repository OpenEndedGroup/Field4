#version 450
layout(binding = 0) uniform sampler2D luma; // full-resolution packed SBS P010 luma, R16_UNORM
layout(binding = 1) uniform sampler2D chroma; // half-resolution interleaved Cb/Cr, R16G16_UNORM
layout(binding = 2) uniform sampler2D accumulatedImage; // float16 linear-light running mean
layout(binding = 3) uniform sampler2D softenedImage; // held exposure after per-eye Gaussian filtering
layout(push_constant) uniform MoviePush {
    mat4 model;       // screen-local metres to room space
    vec4 surface;     // arc width, height, bend radians, strip count
    vec4 image;       // packed source width/height, swap eyes, vertical flip
    vec4 colorimetry; // Kr, Kb, full-range flag, reserved
    vec4 accumulation; // processed mix, valid image, spatial blur mix, linear darkening
}
pc;
layout(location = 0) in vec2 uv; // per-eye image coordinates, top-left origin
layout(location = 1) flat in int eye; // current output eye: 0 left, 1 right
layout(location = 0) out vec4 color;
vec3 toLinear(vec3 c) {
    return mix(c / 12.92, pow((c + .055) / 1.055, vec3(2.4)), step(vec3(.04045), c));
}
void main() {
    int sourceEye = pc.image.z > .5 ? 1 - eye : eye;
    vec2 q = vec2((float(sourceEye) + uv.x) * .5, pc.image.w > .5 ? 1 - uv.y : uv.y);
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
    vec3 sharp = toLinear(clamp(rgb, 0, 1));
    vec3 blurred = pc.accumulation.y > .5 ? texture(accumulatedImage, qY).rgb : sharp;
    if (pc.accumulation.y > .5 && pc.accumulation.z > 0) {
        vec2 halfTexel = .5 / vec2(textureSize(softenedImage, 0));
        vec2 qSoft = clamp(q, lower + halfTexel, upper - halfTexel);
        blurred = mix(blurred, texture(softenedImage, qSoft).rgb, pc.accumulation.z);
    }
    blurred *= 1 - pc.accumulation.w; // darken only the exposure, in linear light
    color = vec4(mix(sharp, blurred, pc.accumulation.x), 1);
}
