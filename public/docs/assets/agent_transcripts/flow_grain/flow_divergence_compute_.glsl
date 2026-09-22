#version 450
#extension GL_EXT_buffer_reference : require
#include "flow_helpers.compute"
layout(local_size_x = 64) in;
void main() {
    int i = int(gl_GlobalInvocationID.x);
    if (i >= CELLS) return;
    ivec2 c = worldCell(i);
    float divergence = (readCell(pc.source, c + ivec2(1,0)).x - readCell(pc.source, c - ivec2(1,0)).x
                      + readCell(pc.source, c + ivec2(0,1)).y - readCell(pc.source, c - ivec2(0,1)).y) / (2. * H);
    storeCell(i, vec3(divergence, 0, 0));
}
