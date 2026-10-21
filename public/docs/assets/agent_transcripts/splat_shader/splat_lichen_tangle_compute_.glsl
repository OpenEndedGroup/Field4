#version 450
#include "splat/insert"

vec3 p = pos;
float branch = 0.0;
float amp = 0.58;
for (int i = 0; i < 5; i++) {
  branch += amp * abs(noise31(p * 4.2) - 0.5);
  p = p.yzx * 1.73 + vec3(0.19, -0.07, 0.11);
  amp *= 0.6;
}

float canopy = sat(1.0 - branch * 1.7);
vec3 dir = normalize(hash31(floor(pos * 9.0)) - 0.5 + vec3(0.0, 0.35, 0.0));
pos += dir * canopy * 0.12;
rgba.rgb = mix(vec3(0.04, 0.03, 0.02), vec3(0.78, 0.96, 0.52), canopy);
rgba.a *= 0.08 + 0.92 * canopy;
scales *= mix(0.16, 1.85, canopy);

#include "splat/end"
