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

// Inserts the probe's GLSL, which defines `fieldProbe(x)`
{% property "fieldProbe" %}

// Set from Kotlin through m.inData, m.outData, m.something and m.count
layout(push_constant) uniform dat {
    inData inData;
    outData outData;
    float something;
    uint count;
} pc;

void main() {
    uint idx = gl_GlobalInvocationID.x;
    // The dispatch is rounded up to whole workgroups; invocations past the last particle do nothing.
    if (idx >= pc.count) return;
    pc.outData.a[idx].x = pc.inData.a[idx].x*cos(pc.something*pc.inData.a[idx].z) + pc.inData.a[idx].y*sin(pc.something*pc.inData.a[idx].w);
    pc.outData.a[idx].y = -pc.inData.a[idx].x*sin(pc.something*pc.inData.a[idx].w) + pc.inData.a[idx].y*cos(pc.something*pc.inData.a[idx].z*0.03);

    // sample the new y for the probe display
    fieldProbe(pc.outData.a[idx].y);

    // carry the per-particle rates through; the draw box uses w as the particle's size
    pc.outData.a[idx].zw = pc.inData.a[idx].zw;
}
