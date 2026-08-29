#version 450
#extension GL_EXT_buffer_reference : require
layout(local_size_x = 64) in;

layout(std430, buffer_reference) buffer State   { vec4 a[]; };
layout(std430, buffer_reference) buffer Anchors { vec4 a[]; };
layout(std430, buffer_reference) buffer Heat    { vec4 a[]; };

{% include "fieldProbe" %}

layout(push_constant) uniform dat {
    State   state;        // this frame's half of the ping-pong — written
    State   prevState;    // last frame's half — read
    Anchors anchors;      // written once, read every frame, device-local
    Heat    heat;         // zero-initialised, device-local, shader-owned
    vec4    knobs;        // .x seconds, .y force gain
    uint    count;
    float   dt;
} pc;

// The live slot, and it goes AFTER the push block, not before: a program is free to read
// `?pc.knobs`, and the generated function is emitted exactly where this line is. Put it above
// and glslang says `'pc' : undeclared identifier` on a line number inside generated code.
// Until Kotlin binds it this is `vec4 force(vec4 force_in) { return vec4(0.0); }`, so the
// shader still compiles and the field simply sits still.
{{ stack.auto.force }}

void main() {
    uint i = gl_GlobalInvocationID.x;
    if (i >= pc.count) return;

    vec4 p0 = pc.prevState.a[i * 2u];
    vec4 v0 = pc.prevState.a[i * 2u + 1u];
    vec3 anchor = pc.anchors.a[i].xyz;

    // The StackSL program sees the particle's home position and the clock, and hands back a
    // force. Everything else here is fixed, so editing that one string is the whole knob.
    vec3 f = force(vec4(anchor, pc.knobs.x)).xyz * pc.knobs.y;

    f += -(p0.xyz - anchor) * 4.0;   // a linear pull home: critically boring, and what keeps
    f += -v0.xyz * 0.9;              // the rest stable

    vec3 v = v0.xyz + f * pc.dt;
    vec3 p = p0.xyz + v * pc.dt;

    float speed = length(v);
    pc.heat.a[i].x = mix(pc.heat.a[i].x, speed, 0.04);

    pc.state.a[i * 2u]      = vec4(p, p0.w + pc.dt);
    pc.state.a[i * 2u + 1u] = vec4(v, v0.w);

    fieldProbe(speed);
}
