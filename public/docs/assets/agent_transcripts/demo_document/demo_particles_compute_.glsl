#version 450
#extension GL_EXT_buffer_reference : require
layout(local_size_x = 64) in;
layout(std430, buffer_reference) buffer inData {
    vec4 a[];
};
layout(std430, buffer_reference) buffer outData {
    vec4 a[];
};
layout(push_constant) uniform dat {
    inData inData;
    outData outData;
    float something;
    uint particleCount;
} pc;
{% property "fieldProbe" %}
void main() {
    uint idx = gl_GlobalInvocationID.x;
    if (idx >= pc.particleCount) return;
    vec4 seed = pc.inData.a[idx];
    float phaseZ = pc.something * seed.z;
    float phaseW = pc.something * seed.w;
    vec2 moved = vec2(
    seed.x * cos(phaseZ) + seed.y * sin(phaseW),
    -seed.x * sin(phaseW) + seed.y * cos(phaseZ * 0.03)
    );
    pc.outData.a[idx] = vec4(moved, seed.zw);
    fieldProbe(length(moved - seed.xy));
}
