#version 450
#include "particles/sketch"

void spawn(vec2 at) {
    p.pos = at + vec2(rand(-3.0, 3.0), rand(-3.0, 3.0));
    p.vel = vec2(rand(-18.0, 18.0), rand(-18.0, 18.0));
    p.radius = 2.0 + rand(0.0, 1.5);
    p.color = hsla(float(randInt(165, 205)), 88.0, 70.0, 0.92);
}

void eachPair(Particle other, Pair pair) {
    if (pair.dist < 60.0) {
        // restLength, stiffness, damping
        spring(other, pair, 30.0, 4.8, 0.18);
        link(other, rgba(115.0, 240.0, 255.0, 0.16), 1.0);
    }
}

void update() {
    // radius, strength, repelRadius, repelStrength
    clump(96.0, 12.0, 18.0, 50.0);
    drag(0.026);
    limitSpeed(90.0);
    bounce(0.92);
}

void draw() {
}
