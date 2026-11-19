#ifndef FIELD_PARTICLE_SHAPE_FORCE_GLSL
#define FIELD_PARTICLE_SHAPE_FORCE_GLSL

const int PARTICLE_SHAPE_CIRCLE = 0;
const int PARTICLE_SHAPE_SEGMENT = 1;
const int PARTICLE_SHAPE_RECTANGLE = 2;

// Local XY, relative to the center and in the same units as the dimensions.
// Returns (outward unit normal, signed distance). A segment uses halfSize.x
// along local X and has unsigned distance. A circle uses radius only.
vec3 particleShapeBoundary2D(vec2 localPosition, int shape, vec2 halfSize, float radius) {
    if (shape == PARTICLE_SHAPE_CIRCLE) {
        float d = length(localPosition);
        vec2 outward = d > 0.0 ? localPosition / d : vec2(1.0, 0.0);
        return vec3(outward, d - radius);
    }

    vec2 extent = shape == PARTICLE_SHAPE_SEGMENT ? vec2(halfSize.x, 0.0) : halfSize;
    vec2 gap = abs(localPosition) - extent;
    vec2 outside = max(gap, 0.0);
    float outsideDistance = length(outside);
    vec2 side = mix(vec2(1.0), vec2(-1.0), lessThan(localPosition, vec2(0.0)));
    vec2 outward = gap.x > gap.y ? vec2(side.x, 0.0) : vec2(0.0, side.y);
    if (outsideDistance > 0.0)
        outward = outside * side / outsideDistance;
    return vec3(outward, outsideDistance + min(max(gap.x, gap.y), 0.0));
}

// Positive gain attracts to the boundary and negative gain repels. In outward
// mode positive gain pushes along the normal on both sides of the boundary.
// Band is the influence width on each side, in the same units as the distance.
vec2 particleShapeForce2D(vec3 boundary, float band, float gain, bool outward) {
    if (gain == 0.0 || band <= 0.0)
        return vec2(0.0);
    float distanceToSurface = boundary.z;
    float falloff = 1.0 - smoothstep(0.0, band, abs(distanceToSurface));
    float direction = outward ? 1.0 : -clamp(2.0 * distanceToSurface / band, -1.0, 1.0);
    return boundary.xy * (gain * direction * falloff);
}

#endif
