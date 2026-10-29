#version 450
#include "particles/sketch"

// p is the particle being worked on; force(), flock(), wrap() ... act on it.
// p.group picks one of three schools; links only join a school to itself.

void spawn(vec2 at) {
    float angle = rand(0.0, TAU);
    float speed = rand(70.0, 120.0);
    p.pos = at + vec2(rand(-6.0, 6.0), rand(-6.0, 6.0));
    p.vel = vec2(cos(angle), sin(angle)) * speed;
    p.radius = 2.0 + rand(0.0, 1.2);
    p.color = hsla(float(randInt(150, 210)), 92.0, 72.0, 0.95);
    p.group = randInt(0, 2);
}

void eachPair(Particle other, Pair pair) {
    if (p.group == other.group && pair.dist < 20.0) {
        link(other, rgba(120.0, 255.0, 210.0, 0.12), 1.0);
    }
}

void update() {
    // radius, separation, alignment, cohesion, maxForce
    int neighbours = flock(82.0, 2.2, 0.82, 0.52, 125.0);

    float flow = noise2D(
    p.pos.x * 0.004 + t * 0.18 + float(p.group) * 7.3,
    p.pos.y * 0.004 - t * 0.14 + p.seed * 0.002
    );
    float flowAngle = flow * TAU + float(p.group) * 1.4;
    vec2 along = vec2(cos(flowAngle), sin(flowAngle));

    vec2 fromCentre = p.pos - vec2(width, height) * 0.5;
    float dist = length(fromCentre);
    if (dist == 0.0) dist = 1.0;
    vec2 swirl = vec2(-fromCentre.y, fromCentre.x) / dist;

    force(along * 24.0 + swirl * 10.0);

    // a speed floor: anything slower than 78 is pushed back up to cruising
    float speed = length(p.vel);
    if (speed < 78.0) {
        force((along + swirl) * (78.0 - speed) * 0.9);
    }

    drag(0.002);
    limitSpeed(175.0);
    wrap(16.0);

    p.color = hsla(mod(155.0 + float(p.group) * 28.0 + float(neighbours) * 7.0 + t * 18.0, 360.0), 92.0, 72.0, 0.94);
}

void draw() {
}
