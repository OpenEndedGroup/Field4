#version 450
layout(location = 0) in vec3 fnormal;
layout(location = 0) out vec4 outColor;

void main() {
    // a light up and to the left of the camera
    vec3 l = normalize(vec3(-0.5, 0.6, -0.65));
    float d = max(dot(normalize(fnormal), l), 0.0);
    outColor = vec4(vec3(0.04 + 0.96 * d), 1.0);
}
