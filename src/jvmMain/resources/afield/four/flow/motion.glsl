#ifndef AFIELD_FLOW_MOTION
#define AFIELD_FLOW_MOTION

// Same layout as FrameHistory.packed (68 floats). Use buffer_reference/std430
// or a uniform block; the full layout exceeds Vulkan's minimum push-constant size.
struct FlowFrameHistory {
    mat4 current;
    mat4 previous;
    mat4 inverseCurrent;
    mat4 reprojection;
    vec4 viewport; // xy pixels, z valid history, w reserved.
};
vec2 flowPreviousPosition(vec2 world, vec2 currentCentre, vec4 previousAffine) {
    vec2 local = world - currentCentre;
    mat2 rotationScale = mat2(previousAffine.z, previousAffine.w, -previousAffine.w, previousAffine.z);
    return previousAffine.xy + rotationScale * local;
}
// Field's top-left texture coordinates with Vulkan's Y-flipped viewport.
vec2 flowClipToUV(vec4 clip) { return clip.xy / clip.w * vec2(.5, -.5) + .5; }
vec2 flowUVToWorld(vec2 uv, mat4 inverseCurrent) {
    vec4 world = inverseCurrent * vec4(uv * vec2(2., -2.) + vec2(-1., 1.), 0., 1.);
    return world.xy / world.w;
}
// Camera motion is removed before mixing image motion into a world carrier.
// sampledUV is the centre of the fetched MOTION texel, not the current pass texel.
vec2 flowImageVelocity(vec2 sampledUV, vec2 previousUV, mat4 inverseCurrent,
                       mat4 inversePrevious, float dt) {
    return (flowUVToWorld(sampledUV, inverseCurrent) - flowUVToWorld(previousUV, inversePrevious)) / max(dt, 1e-6);
}
#endif
