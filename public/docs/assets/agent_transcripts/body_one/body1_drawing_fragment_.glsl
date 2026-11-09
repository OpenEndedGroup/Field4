#version 460

layout(location = 0) in vec2 uv; // local square coordinates
layout(location = 1) flat in vec4 ink; // linear RGBA
layout(location = 2) flat in float roundMark; // circle or strip coverage
layout(location = 0) out vec4 outColor; // straight alpha for the module's blend mode

void main() {
    float d = roundMark > 0.5 ? length(uv) : abs(uv.y);
    float aa = max(fwidth(d), 1e-5);
    float coverage = 1.0 - smoothstep(1.0 - aa, 1.0, d);
    outColor = vec4(ink.rgb, ink.a * coverage);
}
