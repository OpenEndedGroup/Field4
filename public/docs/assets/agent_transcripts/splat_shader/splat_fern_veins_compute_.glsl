#version 450
#include "splat/insert"

float rib = exp(-7.5 * abs(pos.x));
float leaf = sat(1.0 - abs(pos.y) * 0.62);
float flutter = sin(pos.y * 8.0 + pos.z * 5.0 + t * 1.1);

pos.x += 0.18 * rib * sin(pos.y * 6.5 + t * 0.9);
pos.z += 0.06 * flutter * leaf;
rgba.rgb = mix(vec3(0.02, 0.07, 0.03), vec3(0.46, 1.0, 0.38), rib * leaf);
rgba.a *= 0.16 + 0.84 * rib * leaf;
scales.xy *= mix(0.48, 1.95, rib * leaf);

#include "splat/end"
