#version 450
layout(location = 0) in vec2 tc;
layout(location = 1) in vec3 colour;
layout(location = 0) out vec4 uFragColor;

void main() {
    float d = length(tc);
    if (d > 1.0) discard;
    float glow = (1.0 - d) * (1.0 - d);
    // Added, not blended: where sparks cross, they get brighter.
    uFragColor = vec4(colour * glow, 0.0);
}
