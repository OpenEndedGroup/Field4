#version 450
#include "particles/sketch"

void spawn(vec2 at) {
    float angle = rand(0.0, TAU);
    float speed = rand(60.0, 110.0);
    p.pos = at + vec2(rand(-5.0, 5.0), rand(-5.0, 5.0));
    p.vel = vec2(cos(angle), sin(angle)) * speed;
    p.radius = 2.0 + rand(0.0, 1.0);
    p.group = randInt(0, 3);
    p.color = hsla(float(randInt(80, 140)), 95.0, 72.0, 0.94);
}

void eachPair(Particle other, Pair pair) {
    if (p.group == other.group && pair.dist < 18.0) {
        link(other, rgba(160.0, 255.0, 150.0, 0.1), 1.0);
    }
}

vec2 hunterAt() {
    return vec2(width * 0.5 + cos(t * 0.47) * 170.0, height * 0.5 + sin(t * 0.71) * 130.0);
}

void update() {
    vec2 hunter = hunterAt();

    // radius, separation, alignment, cohesion, maxForce
    int neighbours = flock(88.0, 2.4, 0.86, 0.48, 130.0);

    vec2 away = p.pos - hunter;
    float dist = length(away);
    if (dist == 0.0) dist = 1.0;
    vec2 n = away / dist;
    vec2 tangent = vec2(-n.y, n.x);

    if (dist < 210.0) {
        force(n * (210.0 - dist) * 1.9 + tangent * 26.0);
    }

    float drift = noise2D(p.seed * 0.004 + t * 0.25, float(p.group) * 0.7 + t * 0.09) * TAU;
    force(vec2(cos(drift), sin(drift)) * 20.0);

    float speed = length(p.vel);
    if (speed < 92.0) {
        force((tangent + n * 0.2) * (92.0 - speed));
    }

    drag(0.0015);
    limitSpeed(190.0);
    wrap(24.0);

    p.color = hsla(mod(95.0 + float(p.group) * 18.0 + float(neighbours) * 4.0 + t * 16.0, 360.0), 95.0, 72.0, 0.94);
}

void draw() {
    vec2 hunter = hunterAt();
    float pulse = 8.0 + tri01(t * 0.8) * 12.0;
    ring(hunter, 18.0 + pulse, rgba(255.0, 120.0, 120.0, 0.25), 2.0);
    ring(hunter, 8.0 + tri01(t * 1.3 + 0.2) * 4.0, rgba(255.0, 200.0, 160.0, 0.7), 2.0);
    cross(hunter, 8.0, rgba(255.0, 240.0, 220.0, 0.9), 1.5);
    circle(hunter, 2.8, rgba(255.0, 245.0, 225.0, 0.95));
}
