#version 450
#define CUSTOM_RULE
#include "boids/flock"

// The custom rule, in three parts: start, every neighbour in reach, then the force.
// Here: the closest boid ahead, and a point 9 pixels behind it along its heading.

float bestDist;
vec2 bestAim;
bool found;

void customStart(int rule, Boid me) {
    bestDist = 1e9;
    found = false;
}

void customNeighbour(int rule, Boid me, Boid other, vec2 d, float dist) {
    vec2 ahead = vec2(cos(me.heading), sin(me.heading));
    if (dot(d, ahead) > 0.0 && dist < bestDist) {
        bestDist = dist;
        bestAim = d - vec2(cos(other.heading), sin(other.heading)) * 9.0;
        found = true;
    }
}

vec2 customForce(int rule, Boid me) {
    return found ? steer(me, bestAim) : vec2(0.0);
}

vec4 color(Boid b) {
    return hsl(280.0 + float(b.n) * 25.0, 75.0, 65.0);
}
