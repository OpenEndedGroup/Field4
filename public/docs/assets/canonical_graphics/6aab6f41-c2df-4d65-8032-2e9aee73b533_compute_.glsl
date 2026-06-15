#version 450
#extension GL_EXT_buffer_reference : require
layout(local_size_x = 32) in;

layout(std430, buffer_reference) buffer SplatPositions
{
    vec4 positions[];
};

layout(std430, buffer_reference) buffer SplatIndices
{
    uint index[];
};

layout(std430, buffer_reference) buffer SplatDistances
{
    float distance[];
};

layout(std430, buffer_reference) buffer Indirect
{
    uint indexcount;
    uint instanceCount;
    uint firstIndex;
    uint vertexOffset;
    uint firstInstance;
    uint instanceOffset;
};

layout(push_constant) uniform push {
    SplatPositions splatPositions;
    SplatIndices splatIndices;
    SplatDistances splatDistances;
    Indirect indirect;
    uint splatNum;
    vec4 cameraPosition;
} pc;

uint encodeMinMaxFp32(float val)
{
    uint bits = floatBitsToUint(val);
    bits ^= (int(bits) >> 31) | 0x80000000u;
    return bits;
}

void main() {
    uint idx = gl_GlobalInvocationID.x;
    vec3 D = pc.splatPositions.positions[idx].xyz - pc.cameraPosition.xyz;
    float d = dot(D, D);

    if (idx<pc.splatNum) {
        pc.splatDistances.distance[idx] = encodeMinMaxFp32(-d);
        pc.splatIndices.index[idx] = idx;
        atomicAdd(pc.indirect.instanceCount, 1);
    }
}
