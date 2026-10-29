#version 450
#include "particles/sketch"

// p.group is the particle's band, which ring it settles into; p.data.x its phase.

void spawn(vec2 at) {
    p.pos = at;
    p.vel = vec2(rand(-18.0, 18.0), rand(-18.0, 18.0));
    p.radius = 2.0 + rand(0.0, 1.4);
    p.group = randInt(0, 4);
    p.data.x = rand(0.0, TAU);
    p.color = hsla(float(randInt(215, 300)), 95.0, 74.0, 0.92);
}

void eachPair(Particle other, Pair pair) {
}

void update() {
    vec2 anchor = mouseInside
    ? mouse
    : vec2(width * 0.5 + cos(t * 0.23) * 120.0, height * 0.5 + sin(t * 0.31) * 88.0);

    vec2 away = p.pos - anchor;
    float dist = length(away);
    if (dist == 0.0) dist = 1.0;
    vec2 n = away / dist;
    vec2 tangent = vec2(-n.y, n.x);
    float band = float(p.group);
    float phase = p.data.x;

    float targetRadius = 48.0 + band * 24.0 + tri01(t * 0.18 + phase) * 118.0;
    springTo(anchor, targetRadius, 2.8, 0.16);
    force(tangent * (42.0 + band * 7.0));
    oscillate(n, 8.0, 0.36, phase);

    drag(0.004);
    limitSpeed(178.0);
    wrap(28.0);

    p.color = hsla(mod(230.0 + band * 14.0 + dist * 0.2 + t * 22.0, 360.0), 95.0, 74.0, 0.9);
}

void draw() {
}
