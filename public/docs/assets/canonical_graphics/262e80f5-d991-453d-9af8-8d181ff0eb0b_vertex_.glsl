#version 450
#extension GL_EXT_buffer_reference : require

layout(std430, buffer_reference) buffer inData
{
    vec4 pos[];
};

layout(location = 0) out vec2 tc;

layout(push_constant) uniform push {
    inData inData;
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
    float scale = 1.0 * pc.inData.pos[gl_VertexIndex/6].w;
    vec3 p = (part[gl_VertexIndex%6]-vec3(0.5, 0.5, 0))*scale;

    p.xy += pc.inData.pos[gl_VertexIndex/6].xy*10.0;
    p.xy *= vec2(1/pc.aspectRatio, 1);
    gl_Position = vec4(p*0.04, 1);

    tc = part[gl_VertexIndex%6].xy;
}
