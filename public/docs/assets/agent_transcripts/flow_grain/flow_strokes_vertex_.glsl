#version 450
#extension GL_EXT_buffer_reference : require
#include "flow/strokes.glsl"
#include "flow/motion.glsl"
layout(std430, buffer_reference) readonly buffer Field { vec4 v[]; };
layout(std430, buffer_reference) readonly buffer History { FlowFrameHistory frame; };
layout(push_constant) uniform Push {
    Field particles; // Pairs of position/generation and smoothed velocity.
    History history; // Camera matrices and target resolution.
    vec4 viewport; // xy current window dimensions; zw reserved.
    vec4 look; // x seconds of stroke; y half-width pixels; z gain; w reserved.
} pc;
layout(location=0) out vec2 edge;
layout(location=1) out vec2 world;
const vec2 corners[6] = vec2[6](vec2(0,-1),vec2(1,-1),vec2(1,1),vec2(0,-1),vec2(1,1),vec2(0,1));
void main() {
    int id = gl_VertexIndex / 6;
    vec2 p = pc.particles.v[id*2].xy;
    vec2 v = pc.particles.v[id*2+1].xy;
    edge = corners[gl_VertexIndex % 6];
    world = p;
    gl_Position = flowStrokeClip(p, v, vec2(0,-1), edge, pc.history.frame.current,
        pc.viewport.xy, pc.look.x, .035, .65, pc.look.y);
}
