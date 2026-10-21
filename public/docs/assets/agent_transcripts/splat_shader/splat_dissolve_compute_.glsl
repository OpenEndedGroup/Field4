#version 450
#include "splat/insert"

float n = noise31(pos * 3.2 + vec3(0.0, t * 0.22, 0.0));
float sweep = fract(t * 0.12);
float keep = smoothstep(sweep - 0.08, sweep + 0.12, n);

vec3 drift = normalize(pos + hash31(pos) * 0.4);
pos += drift * (1.0 - keep) * (0.45 + 0.25 * sin(t + index * 0.01));
rgba.rgb = mix(vec3(1.0, 0.35, 0.12), rgba.rgb, keep);
rgba.a *= keep;
scales *= mix(0.18, 1.0, keep);

#include "splat/end"
