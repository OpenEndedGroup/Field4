#version 450
layout (location = 0) in vec3 pos;

layout (location = 1) in vec4 col;
layout (location = 0) out vec4 fcol;

layout (binding = 0) uniform camera {
    mat4 p;
    mat4 m;
} cam;

layout(binding = 1) buffer InputBuffer {
    float inData[];
};

void main()
{
    vec3 p = pos;
    p.x += inData[0+3*gl_VertexIndex];
    p.y += inData[1+3*gl_VertexIndex]/2;
    p.z += inData[2+3*gl_VertexIndex];
    gl_Position = cam.p*cam.m*vec4(p, 1);

    fcol = col;
}