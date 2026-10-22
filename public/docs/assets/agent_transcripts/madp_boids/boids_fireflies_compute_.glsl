#version 450
#include "boids/flock"

const int GOLD = 0;
const int GREEN = 1;

vec4 color(Boid b) {
    if (b.kind == GOLD) return hsl(38.0 + b.r * 18.0, 100.0, 58.0, 0.9);
    return hsl(110.0 + b.r * 30.0, 90.0, 55.0, 0.9);
}
