#version 450
layout(binding=0) uniform sampler2D softTensor;
layout(location=0) out vec4 field;
void main() {
    vec3 j = texelFetch(softTensor, ivec2(gl_FragCoord.xy), 0).xyz;
    vec2 eigen = vec2(j.x - j.y, 2.0 * j.z);
    float contrast = sqrt(max(0.0, j.x + j.y));
    float coherence = clamp(length(eigen) / max(1e-8, j.x + j.y), 0.0, 1.0);
    // Encode the unsigned tangent at twice its angle for filtering across sign flips.
    vec2 tangent2 = -eigen / max(1e-8, length(eigen));
    float strength = coherence * smoothstep(0.0010, 0.010, contrast);
    field = vec4(tangent2 * strength, strength, contrast);
}
