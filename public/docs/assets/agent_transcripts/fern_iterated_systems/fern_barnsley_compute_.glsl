#version 450
#include "points/system"

// data.x: which of the four maps made this point

State initParticle(uint index, uint count) {
    return State(vec3(0.0), 0.0, vec4(0.0));
}

// each map in the IFS-table form: x' = a x + b y + e, y' = c x + d y + f
void step(inout State s, float dt) {
    vec2 p = s.pos.xy;
    float r = random();
    if (r < 0.01) {
        s.pos.xy = affine(p, 0.0, 0.0, 0.0, 0.16, 0.0, 0.0); // the stem
        s.data.x = 0.0;
    }
    else if (r < 0.86) {
        s.pos.xy = affine(p, 0.85, 0.04, -0.04, 0.85, 0.0, 1.6); // the next leaflet up
        s.data.x = 1.0;
    }
    else if (r < 0.93) {
        s.pos.xy = affine(p, 0.2, -0.26, 0.23, 0.22, 0.0, 1.6); // the left leaflet
        s.data.x = 2.0;
    }
    else {
        s.pos.xy = affine(p, -0.15, 0.28, 0.26, 0.24, 0.0, 0.44); // the right leaflet
        s.data.x = 3.0;
    }
}

vec2 project(State s) {
    return s.pos.xy;
}

bool shouldDraw(State s) {
    // the first few steps are still on their way to the fern
    return s.age > 8.0;
}

vec4 color(State s) {
    vec4 palette[4] = vec4[](
    rgba(140, 255, 190, 0.35),
    rgba(94, 255, 140, 0.40),
    rgba(195, 255, 140, 0.32),
    rgba(70, 220, 120, 0.34)
    );
    return palette[int(s.data.x)];
}

float pointSize(State s) {
    return s.data.x == 0.0 ? 0.7 : 1.15;
}
