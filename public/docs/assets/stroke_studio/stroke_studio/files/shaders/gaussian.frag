#version 450
layout(binding=0) uniform sampler2D sourceLayer;
layout(push_constant) uniform GaussianPush { vec4 taps; } pc; // uv step xy, output reciprocal extent zw
layout(location=0) out vec4 gaussian;

void main() {
    vec2 uv = gl_FragCoord.xy * pc.taps.zw;
    vec2 d = pc.taps.xy;
    // Dust-style nine-tap Gaussian approximated by five bilinear samples.
    gaussian = texture(sourceLayer, uv) * 0.2270270270;
    gaussian += (texture(sourceLayer, uv + d*1.3846153846)
               + texture(sourceLayer, uv - d*1.3846153846)) * 0.3162162162;
    gaussian += (texture(sourceLayer, uv + d*3.2307692308)
               + texture(sourceLayer, uv - d*3.2307692308)) * 0.0702702703;
}
