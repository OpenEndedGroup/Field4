#version 450
layout (location = 0) in vec3 pos;

layout(set=0, binding = 0) buffer InputBuffer {
    vec4 dat[];
};

layout(location = 0) out vec2 tc;

layout(push_constant) uniform push {
    float aspectRatio;
} pc;

void main()
{
    vec3 p = pos;
    p.xy += dat[gl_VertexIndex/4].xy*1.0;
    p.x /= pc.aspectRatio;
    gl_Position = vec4(p*.04, 1);

    tc.x = gl_VertexIndex%2;
    tc.y = ((gl_VertexIndex)%4)/2;

    if (tc.y==1) tc.x = 1-tc.x;
}