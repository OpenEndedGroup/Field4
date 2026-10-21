#version 450
#include "boids/flock"

const int STARLING = 0;
const int HAWK = 1;

vec4 color(Boid b) {
    if (b.kind == HAWK) return b.cool > 0.0 ? hex(0x7a4038u) : hex(0xff6b5au);
    return hsl(210.0, 20.0 + float(b.n), 50.0 + float(b.n) * 0.4);
}
