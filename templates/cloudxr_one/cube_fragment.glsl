#version 460
layout(location = 0) in vec3 worldNormal;
layout(location = 0) out vec4 outColor;

void main() {
    // Broad static lighting makes the cube's orientation legible without textures.
    float light = 0.25 + 0.75 * max(0.0, dot(normalize(worldNormal), normalize(vec3(-1,2,3))));
    outColor = vec4(vec3(0.85, 0.008, 0.004) * light, 1.0);
}
