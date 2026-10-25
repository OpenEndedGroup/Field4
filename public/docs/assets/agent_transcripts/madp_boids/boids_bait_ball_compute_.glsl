#version 450
#include "boids/flock"

const int SARDINE = 0;
const int SHARK = 1;

vec4 color(Boid b) {
    if (b.kind == SHARK) return hex(0xa9c2d2u);
    return hsl(195.0 + b.r * 25.0, 50.0, 45.0 + b.speed * 6.0, 0.85);
}
