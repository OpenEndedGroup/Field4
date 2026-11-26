#version 450
layout(binding=0) uniform sampler2D softColor;
layout(location=0) out vec4 tensor;
layout(push_constant) uniform TensorPush { float pixelScale; } pc;
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    ivec2 hi = textureSize(softColor, 0) - 1;
    vec3 gx = vec3(0), gy = vec3(0);
    for (int y = -1; y <= 1; ++y) {
        for (int x = -1; x <= 1; ++x) {
            vec3 c = texelFetch(softColor, clamp(p + ivec2(x,y), ivec2(0), hi), 0).rgb;
            gx += c * float(x * (2 - abs(y))) / 8.0;
            gy += c * float(y * (2 - abs(x))) / 8.0;
        }
    }
    gx *= pc.pixelScale;
    gy *= pc.pixelScale;
    // A color tensor keeps chromatic edges that have the same luminance.
    tensor = vec4(dot(gx,gx), dot(gy,gy), dot(gx,gy), 0);
}
