#version 450
#include "boids/flock"

// A boid's colour, sRGB with straight alpha. b.heading, b.speed, b.n (flockmates in view),
// b.r (0..1, its own), b.cool (rest after eating) and b.kind are there to use.

vec4 color(Boid b) {
    return hsl(deg(b.heading) + 190.0, 65.0, 62.0);
}
