#version 450
layout(location = 0) out vec4 uFragColor;
layout(location = 3) in vec4 vColor;
layout(location = 2) in vec2 vUV;

layout(set = 0, binding = 1) uniform sampler2D uTextures[6];

void main() {
    // Show the asset's sampled texture; edit vUV here when inspecting its mapping.
    uFragColor = texture(uTextures[0], vUV);
}
