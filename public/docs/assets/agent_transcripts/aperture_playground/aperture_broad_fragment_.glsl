#version 450
layout(location=0) in vec2 uv;
layout(location=0) out vec4 result;
layout(set=0,binding=0) uniform sampler2D paintQuarter;
layout(push_constant) uniform Aperture { vec4 aperture; } pc;
vec4 sampleBlack(vec2 p) {
    if (any(lessThan(p,vec2(0))) || any(greaterThan(p,vec2(1)))) return vec4(0);
    return texture(paintQuarter,p);
}
void main() {
    vec2 texel = 1.0/vec2(textureSize(paintQuarter,0));
    float rim = clamp(pc.aperture.z,0.05,1.0);
    result = vec4(0);
    vec4 weights = vec4(0);
    // A finite circular aperture with a one-texel soft rim. No random samples.
    // Half-resolution G/B radii 2/5 represent 4/10 screen pixels;
    // quarter-resolution A radius 6 represents 24 screen pixels.
    for (int y=-6;y<=6;y++) for (int x=-6;x<=6;x++) {
        float w = clamp((6.0+0.5*rim-length(vec2(x,y)))/rim,0.0,1.0);
        if (w <= 0.0) continue;
        result.a += w * sampleBlack(uv+texel*vec2(x,y)*pc.aperture.xy).a;
        weights.a += w;
    }
    result = pc.aperture.w * result / max(weights,vec4(0.000001));
}
