#version 450
#include "splat/insert"

float r = length(pos.xz) + 0.001;
float pull = 0.22 / (0.22 + r * r);
float spin = t * 0.8 + pull * 3.0;

pos.xz *= rot2(spin);
pos.y -= pull * 0.45 * sin(t * 1.2 + r * 8.0);
rgba.rgb = mix(rgba.rgb, vec3(0.88, 0.68, 1.0), pull);
rgba.a *= 0.35 + sat(r * 1.4);
scales *= 0.65 + pull * 2.4;

#include "splat/end"
