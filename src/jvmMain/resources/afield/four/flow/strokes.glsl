#ifndef AFIELD_FLOW_STROKES
#define AFIELD_FLOW_STROKES

// dt in seconds; smoothing is invariant to simulation frequency.
vec2 flowSmoothVelocity(vec2 before, vec2 now, float dt, float responseSeconds) {
    return mix(before, now, 1. - exp(-max(dt, 0.) / max(responseSeconds, 1e-5)));
}

// corner.x is 0 at the tail, 1 at the head; corner.y is -1 or +1.
// Length is in world units, half-width in pixels. The projected tangent sets
// the screen normal, so a falling stroke cannot accidentally become horizontal.
vec4 flowStrokeClip(vec2 position, vec2 velocity, vec2 fallbackDirection, vec2 corner,
                    mat4 worldToClip, vec2 viewport, float seconds,
                    float minLength, float maxLength, float halfWidthPixels) {
    float speed = length(velocity);
    vec2 direction = speed > 1e-5 ? velocity / speed : normalize(fallbackDirection + vec2(1e-8, 0));
    float extent = clamp(speed * seconds, minLength, maxLength);
    vec4 head = worldToClip * vec4(position, 0, 1);
    vec4 tail = worldToClip * vec4(position - direction * extent, 0, 1);
    vec2 tangent = (head.xy / head.w - tail.xy / tail.w) * viewport;
    tangent /= max(length(tangent), 1e-6);
    vec2 normal = vec2(-tangent.y, tangent.x);
    vec4 clip = mix(tail, head, corner.x);
    clip.xy += normal * corner.y * halfWidthPixels * 2. / viewport * clip.w;
    return clip;
}
#endif
