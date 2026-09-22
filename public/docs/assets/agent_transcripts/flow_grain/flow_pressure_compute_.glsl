#version 450
#extension GL_EXT_buffer_reference : require
#include "flow_helpers.compute"
layout(local_size_x = 64) in;
void main() {
    int i = int(gl_GlobalInvocationID.x);
    if (i >= CELLS) return;
    ivec2 c = worldCell(i);
    float sum = readCell(pc.source, c + ivec2(1,0)).x + readCell(pc.source, c - ivec2(1,0)).x
              + readCell(pc.source, c + ivec2(0,1)).x + readCell(pc.source, c - ivec2(0,1)).x;
    float divergence = readCell(pc.auxiliary, c).x;
    storeCell(i, vec3((sum - divergence * H * H) * .25, 0, 0));
}
