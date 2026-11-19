#version 450
#extension GL_EXT_buffer_reference : require
layout(local_size_x = 8, local_size_y = 8) in;

// One vec2 per cell, row-major: .x is u (the feed chemical), .y is v (the one that patterns).
layout(std430, buffer_reference) buffer State {
    vec2 a[];
};

layout(push_constant) uniform dat {
    State state; // written this frame
    State prevState; // read this frame
    int w;
    int h;
    int run; // 0 while the CPU is editing: the shader then touches neither buffer
    float feed;
    float kill;
} pc;

vec2 at(ivec2 p) {
    p = (p + ivec2(pc.w, pc.h)) % ivec2(pc.w, pc.h); // the grid wraps
    return pc.prevState.a[p.y * pc.w + p.x];
}

void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (p.x >= pc.w || p.y >= pc.h || pc.run == 0) return;
    vec2 c = at(p);
    vec2 lap = at(p + ivec2(1, 0)) + at(p - ivec2(1, 0)) + at(p + ivec2(0, 1)) + at(p - ivec2(0, 1)) - 4.0 * c;
    float uvv = c.x * c.y * c.y;
    float u = c.x + 0.2 * lap.x - uvv + pc.feed * (1.0 - c.x);
    float v = c.y + 0.1 * lap.y + uvv - (pc.feed + pc.kill) * c.y;
    pc.state.a[p.y * pc.w + p.x] = vec2(u, v);
}
