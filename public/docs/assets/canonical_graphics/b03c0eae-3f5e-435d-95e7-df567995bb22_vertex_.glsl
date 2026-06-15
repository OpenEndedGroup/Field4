#version 450
layout (location = 0) in vec3 pos;

layout (location = 1) in vec4 col;
layout (location = 0) out vec4 fcol;

layout (set =0, binding = 0) uniform camera {
    mat4 p;
    mat4 m;
} cam;

layout (set = 0, binding = 1) uniform wiggle {
    vec3 offset;
} wig;

void main()
{
    gl_Position = cam.p*cam.m*vec4(pos+wig.offset, 1);

    fcol = col;
}