#version 450
layout(location = 0) out vec4 color;
layout(location = 1) in vec2 ftc;
void main() {
    // red grows to the right, green upwards; bottom-left is dark blue
    color = vec4(ftc.x, ftc.y, 0.3, 1.0);
}
