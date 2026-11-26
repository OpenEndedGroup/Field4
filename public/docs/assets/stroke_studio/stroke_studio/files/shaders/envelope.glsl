// The StackSL envelope drives both draw and update so interrupted strokes keep their gain.
vec2 strokeEnvelope(float age, float attack, float releaseSeconds, float surfaceId) {
    vec2 envelope = lifeEnvelopeFn(vec4(age, attack, releaseSeconds, surfaceId)).xy;
    if (any(isnan(envelope)) || any(isinf(envelope))) return vec2(0);
    return clamp(envelope, 0.0, 1.0);
}
