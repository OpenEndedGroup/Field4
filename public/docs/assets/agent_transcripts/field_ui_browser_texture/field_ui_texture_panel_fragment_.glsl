#version 450
layout(location = 0) out vec4 color;
layout(location = 1) in vec2 ftc;
layout(set = 0, binding = 0) uniform sampler2D fieldUi;

void main() {
    color = vec4(texture(fieldUi, vec2(ftc.x, 1.0 - ftc.y)).rgb, 1.0);
}
