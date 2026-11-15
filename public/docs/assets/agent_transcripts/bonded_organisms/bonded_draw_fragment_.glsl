#version 450
layout(location = 0) in vec2 tc;
layout(location = 1) in vec4 col;
layout(location = 2) flat in int isBond;
layout(location = 0) out vec4 outColor;
void main() {
    if (isBond == 1) {
        outColor = col;
        return;
    }
    float d = length(tc - vec2(0.5));
    float a = 1.0 - smoothstep(0.42, 0.5, d);
    if (a < 0.01) discard;
    outColor = vec4(col.rgb, a);
}
