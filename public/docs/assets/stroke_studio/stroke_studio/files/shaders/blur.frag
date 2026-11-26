#version 450
layout(binding=0) uniform sampler2D sourceLayer;
layout(push_constant) uniform BlurPush { int radius; } pc;

layout(location=0) out vec4 filtered;
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    ivec2 hi = textureSize(sourceLayer, 0) - 1;
    const float kernel[5] = float[](1, 4, 6, 4, 1);
    filtered = vec4(0);
    for (int y = -2; y <= 2; ++y) {
        for (int x = -2; x <= 2; ++x) {
            filtered += texelFetch(sourceLayer, clamp(p + ivec2(x,y) * pc.radius, ivec2(0), hi), 0)
                * (kernel[x+2] * kernel[y+2] / 256.0);
        }
    }
}
