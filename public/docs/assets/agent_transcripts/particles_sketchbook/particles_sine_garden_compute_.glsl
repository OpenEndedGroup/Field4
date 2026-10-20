#version 450
#include "particles/sketch"

void spawn(vec2 at) {
    p.pos = at;
    p.vel = vec2(rand(-12.0, 12.0), rand(-12.0, 12.0));
    p.radius = 2.0 + rand(0.0, 1.6);
    p.color = hsla(float(randInt(170, 240)), 95.0, 74.0, 0.92);
}

void eachPair(Particle other, Pair pair) {
}

void update() {
    vec2 centre = vec2(width, height) * 0.5;
    float rest = 110.0 + noise2D(p.seed * 0.01, t * 0.2) * 70.0;

    // target, restLength, stiffness, damping
    springTo(centre, rest, 1.8, 0.15);
    // axis, amplitude, frequency, phase
    oscillate(vec2(cos(p.seed), sin(p.seed)), 24.0, 0.48, p.seed * 0.03);
    // radius, strength, repelRadius, repelStrength
    clump(56.0, 6.0, 12.0, 35.0);

    drag(0.024);
    limitSpeed(110.0);
    wrap(18.0);

    p.color = hsla(mod(185.0 + p.seed * 0.08 + t * 28.0, 360.0), 95.0, 72.0, 0.9);
}

void draw() {
}
