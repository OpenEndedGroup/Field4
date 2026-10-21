#version 450
#include "points/system"

State initParticle(uint index, uint count) {
    vec3 p = vec3(-12.0 + random() * 24.0, -12.0 + random() * 24.0, 14.0 + random() * 18.0);
    return State(p, 0.0, vec4(0.0));
}

void step(inout State s, float dt) {
    float sigma = 10.0;
    float rho = 28.0;
    float beta = 8.0 / 3.0;
    vec3 p = s.pos;
    vec3 d = vec3(sigma * (p.y - p.x), p.x * (rho - p.z) - p.y, p.x * p.y - beta * p.z);
    s.pos += d * dt;
}

vec2 project(State s) {
    float angle = 0.55;
    return vec2(s.pos.x * cos(angle) - s.pos.y * sin(angle) * 0.28, s.pos.z + s.pos.y * 0.34);
}

bool shouldDraw(State s) {
    return true;
}

vec4 color(State s) {
    float hue = 180.0 + mod(s.pos.z * 3.2 + s.age * 0.08, 140.0);
    return hsla(hue, 92.0, 68.0, 0.42);
}

float pointSize(State s) {
    return 1.05;
}
