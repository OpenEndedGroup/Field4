#version 450
layout(set=0, binding = 0) buffer InputBuffer {
    vec4 dat[];
};

layout(location = 0) out vec2 tc;

layout(push_constant) uniform push {
    float aspectRatio;
} pc;

const vec3[6] part = {
    vec3(0,0,0),
    vec3(1,0,0),
    vec3(1,1,0),
    vec3(0,0,0),
    vec3(1,1,0),
    vec3(0,1,0)
};


void main()
{
    vec3 p = part[gl_VertexIndex%6];

    p.xy += dat[gl_VertexIndex/6].xy*10.0;
    p.xy *= vec2(1/pc.aspectRatio, 1);
    gl_Position = vec4(p*0.04, 1);

    tc = part[gl_VertexIndex%6].xy;
}