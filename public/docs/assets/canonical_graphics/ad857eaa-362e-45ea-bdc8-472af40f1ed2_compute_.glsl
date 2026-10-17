#version 450
#extension GL_EXT_buffer_reference : require
layout(local_size_x = 64) in;

layout(std430, buffer_reference) buffer inData
{
    vec4 a[];
};

layout(std430, buffer_reference) buffer outData
{
    vec4 a[];
};

{% property "fieldProbe" %}

layout(push_constant) uniform dat {
    inData inData;
    outData outData;
    float something;
    uint count;
} pc;

void main() {
    uint idx = gl_GlobalInvocationID.x;
    // Ceiling dispatch includes extra lanes; none may access the particle buffers.
    if (idx >= pc.count) return;
    pc.outData.a[idx].x = pc.inData.a[idx].x*cos(pc.something*pc.inData.a[idx].z) + pc.inData.a[idx].y*sin(pc.something*pc.inData.a[idx].w);
    pc.outData.a[idx].y = -pc.inData.a[idx].x*sin(pc.something*pc.inData.a[idx].w) + pc.inData.a[idx].y*cos(pc.something*pc.inData.a[idx].z*0.03);

    fieldProbe(pc.outData.a[idx].y);

    pc.outData.a[idx].zw = pc.inData.a[idx].zw;
}
