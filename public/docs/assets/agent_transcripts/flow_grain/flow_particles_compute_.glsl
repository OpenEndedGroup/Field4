#version 450
#extension GL_EXT_buffer_reference : require
#include "flow_helpers.compute"
#include "flow/strokes.glsl"
#include "flow/grain.glsl"
layout(local_size_x = 64) in;
vec2 flowVelocity(vec2 p) { return sampleField(pc.auxiliary, p).xy; }
float flowMask(vec2 p) { return rock(p) ? 0. : 1.; }
void main() {
    int id = int(gl_GlobalInvocationID.x);
    if (id >= 16000) return;
    int slot = id / 640;
    vec4 old = pc.source.v[id*2];
    vec4 smoothVelocity = pc.source.v[id*2+1];
    int generation = pc.tiles.v[slot].z;
    vec2 lower = vec2(pc.tiles.v[slot].xy) * TILE;
    vec2 p = old.xy;
    if (floatBitsToInt(old.w) != generation || any(lessThan(p,lower)) || any(greaterThanEqual(p,lower+TILE))) {
        p = lower + vec2(flowRandom(ivec2(id % 640, pc.tiles.v[slot].x), pc.tiles.v[slot].y), flowRandom(ivec2(id % 640 + 9923, pc.tiles.v[slot].x), pc.tiles.v[slot].y)) * TILE;
        smoothVelocity = vec4(0);
    }
    vec2 velocity = flowVelocity(p) + vec2(0,-.18);
    vec2 next = p + velocity * pc.clock.y;
    if (!rock(next)) p = next;
    else velocity *= 0.;
    pc.target.v[id*2] = vec4(p, 0, intBitsToFloat(generation));
    pc.target.v[id*2+1] = vec4(flowSmoothVelocity(smoothVelocity.xy, velocity, pc.clock.y, .12),0,0);
}
