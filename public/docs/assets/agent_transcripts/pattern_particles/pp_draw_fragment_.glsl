#version 450
layout(location = 0) out vec4 uFragColor;
layout(location = 0) in vec2 tc;
layout(location = 1) in float ring;

// One colour per ring, and these four are `afield.visualization.signalPalette` in its own
// order, so a ring here and a trace on a showSignals plot of the same band are the same
// colour. #7ad9ff #ff9e4a #8fff9a #ffe066.
const vec3[4] tint = {
    vec3(0.478, 0.851, 1.000),
    vec3(1.000, 0.620, 0.290),
    vec3(0.561, 1.000, 0.604),
    vec3(1.000, 0.878, 0.400)
};

void main() {
    float d = max(0.0, 0.5 - length(tc - vec2(0.5)));
    float w = fwidth(d);
    d = smoothstep(0.1, 0.1 + w, d) * smoothstep(0.14 + w, 0.14, d);
    if (d < 0.01) discard;
    uFragColor = vec4(tint[int(ring) & 3], d * 0.14);
}
