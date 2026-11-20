#version 450
#extension GL_EXT_buffer_reference : require
layout(local_size_x = 8, local_size_y = 8) in;

// One vec2 per cell, row-major: .x is u (the feed chemical), .y is v (the one that patterns).
layout(std430, buffer_reference) buffer State {
    vec2 a[];
};
// One float per cell: the signed distance to the drawn outline, in cells, negative inside.
layout(std430, buffer_reference) buffer Wall {
    float d[];
};

layout(push_constant) uniform dat {
    State state; // written this frame
    State prevState; // read this frame
    Wall wall;
    int w;
    int h;
    int run; // 0 while the CPU is editing the state
    float feed;
    float kill;
} pc;

vec2 at(ivec2 p) {
    p = clamp(p, ivec2(0), ivec2(pc.w - 1, pc.h - 1));
    return pc.prevState.a[p.y * pc.w + p.x];
}

void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (p.x >= pc.w || p.y >= pc.h || pc.run == 0) return;
    int i = p.y * pc.w + p.x;
    float d = pc.wall.d[i];
    if (d > 0.0) {
        pc.state.a[i] = vec2(1.0, 0.0); // outside the drawing: held empty
        return;
    }
    // within a few cells of the wall the kill rate rises, so stripes run along the edge
    float edge = smoothstep(-6.0, 0.0, d);
    vec2 c = at(p);
    vec2 lap = at(p + ivec2(1, 0)) + at(p - ivec2(1, 0)) + at(p + ivec2(0, 1)) + at(p - ivec2(0, 1)) - 4.0 * c;
    float uvv = c.x * c.y * c.y;
    float kill = mix(pc.kill, pc.kill + 0.004, edge);
    float u = c.x + 0.2 * lap.x - uvv + pc.feed * (1.0 - c.x);
    float v = c.y + 0.1 * lap.y + uvv - (pc.feed + kill) * c.y;
    pc.state.a[i] = vec2(u, v);
}
