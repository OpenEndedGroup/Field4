#version 460
layout(location = 0) in vec2 fragUV;
layout(location = 0) out vec4 outColor;
layout(set = 0, binding = 0) uniform sampler2D panelTex;
void main() {
    vec4 c = texture(panelTex, fragUV);
    // Faint border so an all-black panel is still visibly a quad.
    float edge = min(min(fragUV.x, 1.0 - fragUV.x), min(fragUV.y, 1.0 - fragUV.y));
    if (edge < 0.004) c = vec4(0.25, 0.35, 0.5, 1.0);
    outColor = c;
}
