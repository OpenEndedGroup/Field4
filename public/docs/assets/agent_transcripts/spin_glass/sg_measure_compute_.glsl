#version 450
#extension GL_EXT_buffer_reference : require
layout(local_size_x = 64) in;

layout(std430, buffer_reference) buffer Spins { float s[]; };
layout(std430, buffer_reference) buffer Bonds { float j[]; };

layout(push_constant) uniform PushC {
    Spins spins;
    Bonds jRight;
    Bonds jDown;
    uint L;
    float field;
} pc;

{% include "fieldProbe" %}

void main() {
    uint L = pc.L;
    uint N = L * L;
    uint idx = gl_GlobalInvocationID.x;
    if (idx >= N) return;
    uint x = idx % L;
    uint y = idx / L;
    uint xr = (x + 1u) % L;
    uint yd = (y + 1u) % L;
    float s  = pc.spins.s[idx];
    float sr = pc.spins.s[y * L + xr];
    float sd = pc.spins.s[yd * L + x];
    // count each bond once (right + down), so sum over sites is the total energy
    float e = -(pc.jRight.j[idx] * s * sr + pc.jDown.j[idx] * s * sd) - pc.field * s;
    fieldProbe(vec2(s, e));
}
