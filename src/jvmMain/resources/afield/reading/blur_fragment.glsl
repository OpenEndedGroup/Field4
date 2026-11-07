#version 450
layout(location = 0) in vec2 uv;
layout(location = 1) flat in int eye;
layout(location = 0) out vec4 outColor;
layout(set = 0, binding = 0) uniform sampler2DArray source;
layout(push_constant) uniform BlurPush {
    vec4 tapStep; // XY step in output pixels, kernel radius and gain
} pc;

// Nine-tap Gaussian approximation, using bilinear pairing for five texture fetches.
// The output texel size makes H/V isotropic even while the first pass downsamples.
vec4 sampleOffset(float offset) {
    return texture(source, vec3(uv + pc.tapStep.xy * pc.tapStep.z * offset, float(eye)));
}
void main() {
    vec4 c = sampleOffset(0.0) * 0.2270270270;
    c += (sampleOffset(1.3846153846) + sampleOffset(-1.3846153846)) * 0.3162162162;
    c += (sampleOffset(3.2307692308) + sampleOffset(-3.2307692308)) * 0.0702702703;
    outColor = c * pc.tapStep.w;
}
