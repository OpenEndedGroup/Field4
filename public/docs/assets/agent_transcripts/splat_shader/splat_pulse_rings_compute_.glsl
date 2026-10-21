#version 450
#include "splat/insert"

float r = length(pos.xz);
float wave = sin(r * 18.0 - t * 3.5);
float ring = smoothstep(0.92, 1.0, wave);

pos.y += wave * 0.045;
pos.xz *= rot2(0.12 * sin(t + r * 4.0));

vec3 cyan = vec3(0.08, 0.85, 1.0);
vec3 ember = vec3(1.0, 0.54, 0.16);
rgba.rgb = mix(rgba.rgb, mix(cyan, ember, sat(pos.y * 0.55 + 0.5)), 0.55 + ring * 0.35);
rgba.a *= 0.52 + ring * 0.48;
scales *= 0.82 + ring * 1.45;

#include "splat/end"
