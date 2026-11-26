#extension GL_EXT_buffer_reference : require
const int POINTS = 9;
const int EMPTY = 0;
const int DRAWING = 1;
const int TRACKING = 2;
const int FADING = 3;
struct Stroke {
    vec4 point[POINTS]; // world xyz, world rest length from the previous knot
    vec4 pigment;      // signed linear RGB deposit, world half width
    vec4 reference;    // lit RGB at birth, surface ID
    vec4 timing;       // age, release duration, interrupted time left, unmatched time (seconds)
    vec4 shape;        // reveal fraction on fade, directional stroke, eraser, used knot count
    ivec4 state;       // phase, interrupted release seconds, attack seconds, gain at interruption (float bits)
};
layout(std430, buffer_reference) buffer StrokeBuffer { Stroke data[]; };
