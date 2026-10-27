#version 450
#include "points/system"

// data.x: the colour mix, carried from step to step; data.y: which map made this point

State initParticle(uint index, uint count) {
    return State(vec3(0.0), 0.0, vec4(random(), 0.0, 0.0, 0.0));
}

void step(inout State s, float dt) {
    vec2 p = s.pos.xy;
    float r = random();
    float tint;
    if (r < 0.5) {
        p = swirl(affine(p, 0.72, 0.07, -0.05, 0.72, 0.0, 0.55));
        tint = 0.08;
        s.data.y = 0.0;
    }
    else if (r < 0.82) {
        p = handkerchief(affine(p, 0.43, -0.45, 0.48, 0.45, 0.22, 0.92));
        tint = 0.42;
        s.data.y = 1.0;
    }
    else if (r < 0.94) {
        p = spherical(affine(p, -0.38, 0.34, 0.36, 0.42, -0.28, 1.18));
        tint = 0.68;
        s.data.y = 2.0;
    }
    else {
        p = linear(affine(p, 0.18, 0.0, 0.0, 0.18, 0.0, 0.14));
        tint = 0.94;
        s.data.y = 3.0;
    }
    s.pos.xy = p;
    s.data.x = s.data.x * 0.84 + tint * 0.16;
}

vec2 project(State s) {
    return s.pos.xy;
}

bool shouldDraw(State s) {
    return s.age > 12.0;
}

vec4 color(State s) {
    float m = s.data.x;
    return hsla(20.0 + m * 300.0, 100.0, 68.0, 0.18 + m * 0.26);
}

float pointSize(State s) {
    return s.data.y == 3.0 ? 0.7 : 1.0;
}
