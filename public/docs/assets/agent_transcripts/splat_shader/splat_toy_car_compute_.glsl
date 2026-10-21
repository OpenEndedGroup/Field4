#version 450
#include "splat/insert"

float stripe = sin((pos.y + t * 0.28) * 38.0);
float gate = smoothstep(0.35, 0.9, stripe);

pos.x += sin(pos.y * 9.0 + t * 2.0) * 0.025;
rgba.rgb = mix(rgba.rgb * 0.18, vec3(0.2, 1.0, 0.58), gate);
rgba.a *= 0.18 + gate * 0.82;
scales.xy *= 0.55 + gate * 1.7;

#include "splat/end"
