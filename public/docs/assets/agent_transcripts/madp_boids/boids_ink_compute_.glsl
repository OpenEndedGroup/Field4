#version 450
#define FLOW_FIELD
#include "boids/flock"

// The heading, in radians, at uv (0..1 across the window) and step t.
float flowAngle(vec2 uv, float t) {
    return noise(uv.x * 2.2, uv.y * 2.2, t * 0.0015) * TAU * 2.0;
}

vec4 color(Boid b) {
    return b.r < 0.85 ? hsl(220.0, 45.0, 22.0, 0.35) : hsl(8.0, 75.0, 48.0, 0.5);
}
