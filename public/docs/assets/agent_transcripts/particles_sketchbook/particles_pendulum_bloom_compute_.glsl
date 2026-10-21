#version 450
#include "particles/sketch"

void spawn(vec2 at) {
    p.pos = at;
    p.vel = vec2(rand(-8.0, 8.0), rand(-8.0, 8.0));
    p.radius = 2.3 + rand(0.0, 1.8);
    p.color = hsla(float(randInt(175, 230)), 88.0, 74.0, 0.92);
}

void eachPair(Particle other, Pair pair) {
    float homeDist = length(p.home - other.home);
    if (homeDist < 32.0) {
        spring(other, pair, homeDist, 3.8, 0.24);
        link(other, rgba(140.0, 220.0, 255.0, 0.16), 1.0);
    }
}

bool showAnchor() {
    return int(mod(floor(p.home.x * 0.04) + floor(p.home.y * 0.04), 5.0)) == 0;
}

void update() {
    // one particle in thirty is tied back to where it was painted
    if (p.id % 30u == 9u) {
        springTo(p.home, 18.0, 7.2, 0.34);
    }

    oscillate(vec2(1.0, 0.0), 26.0, 0.1, p.home.y * 0.004 + p.seed * 0.002);
    oscillate(vec2(0.0, 1.0), 6.0, 0.16, p.home.x * 0.004 + p.seed * 0.001);

    drag(0.008);
    limitSpeed(104.0);
    bounce(0.92);

    p.color = hsla(mod(190.0 + p.home.x * 0.04 + t * 24.0, 360.0), 90.0, 74.0, 0.92);

    // an overlay per particle: the scaffold the fabric breathes around
    if (showAnchor()) {
        line(p.home, p.pos, rgba(150.0, 220.0, 255.0, 0.14), 1.0);
        circle(p.home, 1.6, rgba(220.0, 245.0, 255.0, 0.78));
    }
}

void draw() {
}
