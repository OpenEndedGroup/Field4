#version 450
layout(location = 0) out vec4 uFragColor;
layout(location = 0) in vec2 tc;
layout(location = 1) in float phase;
void main()
{
    // A soft round dot, tinted by the particle's starting angle.
    float d = length(tc - vec2(0.5));
    float coverage = 1.0 - smoothstep(0.25, 0.5, d);
    if (coverage < 0.01) discard;
    vec3 tint = 0.6 + 0.4 * cos(phase + vec3(0.0, 2.1, 4.2));
    uFragColor = vec4(tint, coverage * 0.35);
}
