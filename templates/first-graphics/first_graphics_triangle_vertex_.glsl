#version 450
layout (location = 0) in vec3 pos;

layout (location = 1) in vec4 col;
layout (location = 0) out vec4 fcol;

// pos is already in clip space; col is passed to the fragment shader.

void main()
{

    fcol = col;
    gl_Position = vec4(pos, 1);
}
