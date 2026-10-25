#version 450
#include "particles/sketch"

void spawn(vec2 at) {
    float r = 4.0 + rand(0.0, 6.0);
    p.pos = at + vec2(rand(-4.0, 4.0), rand(-4.0, 4.0));
    p.vel = vec2(rand(-120.0, 120.0), rand(-40.0, 40.0));
    p.radius = r;
    p.mass = r * 0.7;
    p.color = hsla(float(randInt(10, 50)), 90.0, 64.0, 0.96);
}

void eachPair(Particle other, Pair pair) {
    collide(other, pair, 0.94); // restitution
}

void update() {
    drag(0.001);
    bounce(0.88);
    p.color = hsla(18.0 + p.radius * 6.0 + min(50.0, abs(p.vel.y) * 0.06), 90.0, 64.0, 0.96);
}

void draw() {
}
