#version 450
#include "splat/insert"

/*
  Change these:
    pos     vec3   the splat's centre
    scales  vec3   its radii
    rgba    vec4   its colour and opacity
    quat    vec4   its rotation, a quaternion

  Read these:
    index   float  which splat, 0, 1, 2, ...
    t       float  seconds since the box ran
    pi

  Helpers: sat(x), rot2(a), hash31(p), noise31(p)

  Afterwards rgba is clamped to 0..1 and every scale kept above 0.0001.
  This is a passthrough. Copy lines from the other boxes, or write your own.
*/

#include "splat/end"
