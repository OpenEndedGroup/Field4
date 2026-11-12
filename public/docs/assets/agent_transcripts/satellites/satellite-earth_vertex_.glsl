#version 450
layout (location = 0) in vec3 pos;
layout (location = 1) in vec4 col;
layout (location = 0) out vec4 fcol;

layout(push_constant) uniform matrices {
    mat4 p; // Clip-from-view projection.
    mat4 m; // View-from-world camera transform.
    vec4 eye; // World-space camera position, in Earth radii; w is unused.
    vec4 spin; // x: Greenwich sidereal angle in radians; yzw unused.
} pc;

void main()
{
    // Earth-fixed to inertial: turn about north (+y) by Greenwich sidereal time
    float c = cos(pc.spin.x), s = sin(pc.spin.x);
    vec3 w = vec3(c * pos.x + s * pos.z, pos.y, -s * pos.x + c * pos.z);
    // points on the far side of the globe are drawn faintly
    float facing = dot(normalize(w), pc.eye.xyz - w);
    fcol = col * (facing > 0.0 ? 1.0 : 0.15);
    gl_Position = pc.p * pc.m * vec4(w, 1);
}
