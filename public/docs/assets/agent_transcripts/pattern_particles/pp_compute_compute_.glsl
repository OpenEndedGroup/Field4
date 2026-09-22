#version 450
#extension GL_EXT_buffer_reference : require
layout(local_size_x = 64) in;

layout(std430, buffer_reference) buffer Seeds { vec4 a[]; };
layout(std430, buffer_reference) buffer Out   { vec4 a[]; };

layout(push_constant) uniform dat {
    Seeds seeds;      // written once: one random phase and radius per particle
    Out   outData;    // this frame's positions, read by the draw box
    float time;       // seconds
    float band0;      // 70 Hz    — the kick
    float band1;      // 340 Hz
    float band2;      // 1.6 kHz  — the square stab's harmonics
    float band3;      // 8 kHz    — the hats
    uint  count;
} pc;

void main() {
    uint i = gl_GlobalInvocationID.x;
    if (i >= pc.count) return;

    vec4 seed = pc.seeds.a[i];
    uint ring = i & 3u;
    // One band per ring, so which ring moves says which band is loud.
    float energy = ring == 0u ? pc.band0
                 : ring == 1u ? pc.band1
                 : ring == 2u ? pc.band2
                              : pc.band3;

    // The ring's radius rises a little with its band and its *thickness* rises a lot, so
    // a loud band blooms into an annulus rather than sliding outwards. A ring of 20,000
    // particles all at one radius reads as a drawn circle, which is the wrong picture.
    float base = 0.14 + 0.17 * float(ring);
    float thickness = 0.012 + 0.10 * energy;
    float radius = base * (1.0 + 0.55 * energy) + thickness * (seed.y - 0.5) * 2.0;
    float spin = 0.22 + 0.15 * float(ring);
    float angle = seed.x * 6.2831853 + pc.time * spin;

    pc.outData.a[i].x = radius * cos(angle);
    pc.outData.a[i].y = radius * sin(angle);
    pc.outData.a[i].z = float(ring);
    pc.outData.a[i].w = 0.45 + 1.8 * energy;
}
