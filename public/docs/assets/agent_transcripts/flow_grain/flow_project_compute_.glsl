#version 450
#extension GL_EXT_buffer_reference : require
#include "flow_helpers.compute"
layout(local_size_x = 64) in;
void main() {
    int i = int(gl_GlobalInvocationID.x);
    if (i >= CELLS) return;
    ivec2 c = worldCell(i);
    vec2 gradient = vec2(readCell(pc.auxiliary, c + ivec2(1,0)).x - readCell(pc.auxiliary, c - ivec2(1,0)).x,
                         readCell(pc.auxiliary, c + ivec2(0,1)).x - readCell(pc.auxiliary, c - ivec2(0,1)).x) / (2. * H);
    vec2 velocity = readCell(pc.source, c).xy - gradient;
    if (rock(worldPosition(i))) velocity = vec2(0);
    // Enforce no-through-flow against the grid stencil. Drawing uses the exact mask below.
    if (rock((vec2(c + ivec2(1,0)) + .5) * H) && velocity.x > 0.) velocity.x = 0.;
    if (rock((vec2(c - ivec2(1,0)) + .5) * H) && velocity.x < 0.) velocity.x = 0.;
    if (rock((vec2(c + ivec2(0,1)) + .5) * H) && velocity.y > 0.) velocity.y = 0.;
    if (rock((vec2(c - ivec2(0,1)) + .5) * H) && velocity.y < 0.) velocity.y = 0.;
    storeCell(i, vec3(velocity, 0));
}
