#version 450
layout(location = 0) in vec2 ftc;
layout(location = 0) out vec4 uFragColor;
layout(set = 0, binding = 0) uniform sampler2D camera;
void main() {
    uFragColor = vec4(texture(camera, ftc).rgb, 1.0);
}
