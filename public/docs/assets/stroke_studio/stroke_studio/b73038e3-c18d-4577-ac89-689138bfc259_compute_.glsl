#version 450
#extension GL_EXT_buffer_reference : require
{% property "fieldStats" %}
layout(local_size_x=32) in;
layout(binding=0) uniform sampler2D directionLayer;
layout(binding=1) uniform sampler2D worldLayer;
layout(std430, buffer_reference) buffer Calibration {
    vec4 bands; // low, high, gate, sample count
};
layout(push_constant) uniform SketchPush {
    Calibration calibration;
    float percentile;
} pc;

void main() {
    // One lane per group keeps these unrelated pixels as individual sketch samples.
    if (gl_LocalInvocationIndex != 0u) return;
    uint i = gl_WorkGroupID.x;
    if (i == 0u) {
        // The Field Stats reset leaves the last reduced slot readable during this dispatch.
        uint count = fieldStatsRead(0u).finiteCount;
        if (count > 32u) {
            vec3 next = vec3(fieldStatsQuantile(0u, 0.42), fieldStatsQuantile(0u, 0.93),
                clamp(fieldStatsQuantile(0u, pc.percentile), 0.10, 0.78));
            vec4 previous = pc.calibration.bands;
            pc.calibration.bands = vec4(previous.w > 0.0 ? mix(previous.xyz, next, 0.3) : next, float(count));
        }
    }
    vec2 uv = vec2((float(i) + 0.5) / float(gl_NumWorkGroups.x),
                   float(bitfieldReverse(i)) * 2.3283064365386963e-10);
    ivec2 p = ivec2(uv * vec2(textureSize(directionLayer, 0)));
    // The black enclosure supplies depth and world positions outside the subjects.
    if (texelFetch(worldLayer, p, 0).w > 1.0)
        fieldStatsRecord(0, texelFetch(directionLayer, p, 0).z);
}
