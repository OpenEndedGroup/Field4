#version 460
layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec2 inUV;
layout(location = 0) out vec2 fragUV;
layout(push_constant) uniform Push {
    mat4 mvp;
} push;
void main() {
    fragUV = inUV;
    gl_Position = push.mvp * vec4(inPosition, 1.0);
}
