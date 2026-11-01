#version 450
// Per-vertex inputs: the locations match the `pos` and `col` attributes the box fills with `m.set`
layout (location = 0) in vec3 pos;

layout (location = 1) in vec4 col;
layout (location = 0) out vec4 fcol;

void main()
{
    // no camera: positions are used directly as clip-space coordinates (-1..1 across the window)
    fcol = col;
    gl_Position = vec4(pos, 1);
}