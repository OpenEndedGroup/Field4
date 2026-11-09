#version 450
layout(location = 0) in vec3 pos;
layout(location = 1) in vec3 normal;
layout(location = 0) out vec3 fnormal;

layout(push_constant) uniform Push {
    float time;
    float aspect;
} pc;

void main() {
    // turn about y, then x
    float a = pc.time * 0.7;
    float b = pc.time * 0.45;
    mat3 ry = mat3(cos(a), 0.0, -sin(a), 0.0, 1.0, 0.0, sin(a), 0.0, cos(a));
    mat3 rx = mat3(1.0, 0.0, 0.0, 0.0, cos(b), sin(b), 0.0, -sin(b), cos(b));
    vec3 p = rx * ry * pos;
    fnormal = rx * ry * normal;
    // a camera 5 units back looking along +z; +y is up, since Field flips its viewports;
    // depth runs 0..1 from z = 1 to z = 8
    float z = p.z + 5.0;
    gl_Position = vec4(p.x * 2.4 / pc.aspect, p.y * 2.4, (z - 1.0) / 7.0 * z, z);
}
