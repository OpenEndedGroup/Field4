#version 450
#include "boids/flock"

const int RED = 0;
const int BLUE = 1;

vec4 color(Boid b) {
    if (b.kind == RED) return hsl(4.0, 80.0, 48.0 + float(b.n) * 0.5);
    return hsl(212.0, 80.0, 50.0 + float(b.n) * 0.5);
}
