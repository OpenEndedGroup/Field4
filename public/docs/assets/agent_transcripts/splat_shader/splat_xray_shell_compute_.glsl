#version 450
#include "splat/insert"

float radius = length(pos);
float edge = smoothstep(0.2, 1.2, radius);
float shimmer = 0.5 + 0.5 * sin(radius * 24.0 - t * 4.0);

pos += normalize(pos + vec3(0.001)) * shimmer * 0.035;
rgba.rgb = mix(vec3(0.04, 0.08, 0.1), vec3(0.75, 0.96, 1.0), edge);
rgba.a *= 0.16 + edge * shimmer * 0.84;
scales *= vec3(0.55, 0.55, 1.8);

#include "splat/end"
