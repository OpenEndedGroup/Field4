#version 450
#extension GL_EXT_buffer_reference : require
#include "flow/grain.glsl"
#include "flow/motion.glsl"
#include "flow/tiles.glsl"
layout(std430, buffer_reference) readonly buffer Field { vec4 v[]; };
layout(std430, buffer_reference) readonly buffer Tiles { ivec4 v[]; };
layout(std430, buffer_reference) readonly buffer History { FlowFrameHistory frame; };
layout(push_constant) uniform Push {
    Field item; // Current pose and MotionItems previous affine/validity.
    Field velocity; // Current projected fluid velocity.
    Tiles tiles; // Resident world coordinates and generations.
    History history; // FrameHistory's complete 68-float layout.
    vec4 clock; // x time; y dt; zw reserved.
    vec4 look; // x world grain cell; y stroke seconds; z gain; w debug view.
} pc;
layout(location = 0) in vec2 uv;
layout(location = 0) out vec4 color;
layout(location = 1) out vec4 motion; // Previous UV, valid, coverage. No blending on this attachment.

const float H = 4. / 24.;
bool rock(vec2 p) { return (abs(p.y + 2.) < .10 && abs(p.x) < 4.) || length(p - vec2(2.4, 1.2)) < .8; }
float flowMask(vec2 p) { return rock(p) ? 0. : 1.; }
vec2 atCell(ivec2 cell) {
    ivec2 local = ivec2(flowFloorMod(cell.x,24),flowFloorMod(cell.y,24));
    ivec2 tile = (cell - local) / 24;
    int slot = flowTileSlot(tile,ivec2(5));
    if (!flowTileResident(pc.tiles.v[slot], tile)) return vec2(0);
    vec4 v = pc.velocity.v[slot * 576 + local.y * 24 + local.x];
    return floatBitsToInt(v.w) == pc.tiles.v[slot].z ? v.xy : vec2(0);
}
vec2 flowVelocity(vec2 p) {
    vec2 grid = p / H - .5, f = fract(grid);
    ivec2 c = ivec2(floor(grid));
    return mix(mix(atCell(c),atCell(c+ivec2(1,0)),f.x),mix(atCell(c+ivec2(0,1)),atCell(c+ivec2(1)),f.x),f.y);
}
void main() {
    vec2 world = flowUVToWorld(uv, pc.history.frame.inverseCurrent);
    vec4 clip = pc.history.frame.current * vec4(world,0,1);
    vec4 previousClip = pc.history.frame.reprojection * clip;
    motion = vec4(flowClipToUV(previousClip), pc.history.frame.viewport.z, 1);
    float pixelWorld = length(pc.history.frame.inverseCurrent[0].xy) * 2. / pc.history.frame.viewport.x;
    float grain = flowGrain(world, vec2(0), pixelWorld, pc.look.x, pc.look.y, .9, pc.clock.x, 1.6);
    // Light is evaluated after the noise integration, preserving a sharp geometric shadow.
    vec2 light = vec2(-3., 3.);
    vec2 ray = world - light;
    float illumination = 1. / (1. + dot(ray,ray) * .035);
    for (int i=1; i<=48; ++i) if (rock(light + ray * (float(i) / 48.))) illumination = .02;
    float ink = smoothstep(.08,.32,grain) * illumination * pc.look.z;
    color = vec4(vec3(ink),1);
    if (rock(world)) color.rgb = vec3(0);
    // The moving, rotating emitter has object motion in addition to the camera.
    vec4 pose = pc.item.v[0];
    float c = cos(pose.z), s = sin(pose.z);
    vec2 local = mat2(c,-s,s,c) * (world-pose.xy);
    if (abs(local.x)<.45 && abs(local.y)<.13) {
        color.rgb = vec3(.9);
        vec2 before = flowPreviousPosition(world,pose.xy,pc.item.v[1]);
        motion = vec4(flowClipToUV(pc.history.frame.previous*vec4(before,0,1)),pc.item.v[2].x,1);
    }
    if (pc.look.w > .5 && pc.look.w < 1.5) color.rgb = vec3(abs(flowVelocity(world)) * .2,0);
    if (pc.look.w > 1.5) {
        vec4 prior = pc.history.frame.reprojection * vec4(uv * vec2(2,-2) + vec2(-1,1),0,1);
        color.rgb = pc.history.frame.viewport.z > .5 ? vec3(flowClipToUV(prior),0) : vec3(1,0,0);
    }
}
