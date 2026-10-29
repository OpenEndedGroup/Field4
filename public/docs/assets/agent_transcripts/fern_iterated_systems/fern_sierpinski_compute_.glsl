#version 450
#include "points/system"

// data.x: the corner the last step went towards

const vec2 corner[3] = vec2[](vec2(0.0, 1.0), vec2(-0.866, -0.5), vec2(0.866, -0.5));

State initParticle(uint index, uint count) {
    return State(vec3(random() - 0.5, random() - 0.5, 0.0), 0.0, vec4(0.0));
}

void step(inout State s, float dt) {
    int k = min(int(random() * 3.0), 2);
    s.pos.xy = (s.pos.xy + corner[k]) * 0.5;
    s.data.x = float(k);
}

vec2 project(State s) {
    return s.pos.xy;
}

bool shouldDraw(State s) {
    return s.age > 8.0;
}

vec4 color(State s) {
    vec4 palette[3] = vec4[](
    rgba(255, 184, 108, 0.35),
    rgba(139, 233, 253, 0.35),
    rgba(255, 121, 198, 0.35)
    );
    return palette[int(s.data.x)];
}

float pointSize(State s) {
    return 1.0;
}
