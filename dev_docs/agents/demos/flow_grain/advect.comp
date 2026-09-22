#version 450
#extension GL_EXT_buffer_reference : require
#include "flow_helpers.compute"
layout(local_size_x = 64) in;
void main() {
    int i = int(gl_GlobalInvocationID.x);
    if (i >= CELLS) return;
    vec2 p = worldPosition(i);
    if (rock(p)) { storeCell(i, vec3(0)); return; }
    vec2 velocity = readCell(pc.source, worldCell(i)).xy;
    vec2 back = p - velocity * pc.clock.y;
    if (rock(back)) back = p;
    velocity = sampleField(pc.source, back).xy * exp(-.2 * pc.clock.y);
    vec2 offset = p - pc.emitter.xy;
    float falloff = exp(-dot(offset, offset) / .8);
    vec2 force = vec2(-offset.y, offset.x) * 8. + vec2(0., 2.);
    velocity += force * pc.emitter.z * falloff * pc.clock.y;
    velocity *= min(1., 12. / max(length(velocity), 1e-6));
    storeCell(i, vec3(velocity, 0));
}
