#version 450
layout (location = 0) in vec3 pos;

layout (location = 1) in vec4 col;
layout (location = 0) out vec4 fcol;

// The camera matrices, written from Kotlin through `module.view.p` and `module.view.m`
layout (set=0, binding = 0) uniform view {
    mat4 p;
    mat4 m;
} cam;

void main()
{
    gl_Position = cam.p*cam.m*vec4(pos, 1);

    fcol = col;
}