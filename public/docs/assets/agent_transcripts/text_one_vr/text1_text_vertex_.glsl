#version 450
#extension GL_EXT_buffer_reference : require
#extension GL_EXT_multiview : require
#extension GL_EXT_multiview : require
layout(std140, set=0, binding=7) uniform StereoViews {
    mat4 stereoVpL; // left-eye projection/view
    mat4 stereoVpR; // right-eye projection/view
    vec4 stereoRightL; // left-eye camera right
    vec4 stereoRightR; // right-eye camera right
    vec4 stereoUpL; // left-eye camera up
    vec4 stereoUpR; // right-eye camera up
    vec4 stereoProjectionL; // projection XY and viewport size
    vec4 stereoProjectionR; // projection XY and viewport size
};

mat4 stereoViewProjection() {
    return gl_ViewIndex==0 ? stereoVpL : stereoVpR;
}
vec3 stereoCameraRight() {
    return (gl_ViewIndex==0 ? stereoRightL : stereoRightR).xyz;
}
vec3 stereoCameraUp() {
    return (gl_ViewIndex==0 ? stereoUpL : stereoUpR).xyz;
}
vec4 stereoProjection() {
    return gl_ViewIndex==0 ? stereoProjectionL : stereoProjectionR;
}
// Adapted Slug core: Copyright (c) 2026 AlphaPixel LLC. See LICENSE-slughorn.
layout(std430, buffer_reference, buffer_reference_align = 4) readonly buffer SlugData {
    uint words[]; // immutable Slug header, curve and band atlas in 32-bit words
};
layout(push_constant) uniform SlugPC {
    mat4 model; // local glyph plane to world; eye VP is applied in this vertex shader
    SlugData slugData; // immutable word-outline atlas
    vec2 viewport; // dimensions of this eye, for perspective-dependent quad dilation
    vec4 tint; // linear RGBA, with score-driven word opacity
    vec4 clipPlane; // world-space backdrop clipping, zero for the main scene
} pc;
vec4 data4(uint at) {
    return uintBitsToFloat(uvec4(pc.slugData.words[at], pc.slugData.words[at+1],
    pc.slugData.words[at+2], pc.slugData.words[at+3]));
}
layout(location=0) out vec2 emCoord;
const vec2 corners[6] = vec2[6](vec2(0,0), vec2(1,0), vec2(1,1), vec2(0,0), vec2(1,1), vec2(0,1));
layout(location=1) out float backdropDistance;
void main() {
    backdropDistance=0;
    vec4 bounds = data4(8u);
    // A closed SlugBuffer's empty atlas: nothing to cover, so put every corner outside the clip volume.
    if (!(bounds.z > bounds.x && bounds.w > bounds.y)) {
        emCoord = vec2(0.0);
        gl_Position = vec4(0.0, 0.0, 2.0, 1.0);
        return;
    }
    // Entire words crossing the eye/near plane are hidden rather than generating unstable quads.
    for (int i=0;i<4;i++) {
        vec2 p=mix(bounds.xy,bounds.zw,vec2(i&1,(i>>1)&1));
        vec4 checkClip=(gl_ViewIndex==0?stereoVpL:stereoVpR)*pc.model*vec4(p,0,1);
        if(checkClip.w<=1e-5||checkClip.z<=0) {
            gl_Position=vec4(0,0,2,1);
            emCoord=vec2(0);
            return;
        }
    }
    vec2 corner = corners[gl_VertexIndex % 6];
    vec2 q = mix(bounds.xy, bounds.zw, corner);
    vec2 normal = 2.0 * corner - 1.0;
    mat4 matrix = (gl_ViewIndex == 0 ? stereoVpL : stereoVpR) * pc.model;
    vec4 position = matrix * vec4(q, 0.0, 1.0);
    // Lengyel's perspective-dependent dilation, expressed in homogeneous vectors.
    // normal is the corner bisector scaled so both incident sides expand equally.
    vec4 direction = matrix * vec4(normalize(normal), 0.0, 0.0);
    float s = position.w;
    float t = direction.w;
    vec2 uv = pc.viewport * (s*direction.xy - t*position.xy);
    float denominator = length(uv) - s*t;
    float distance = denominator > 1e-12 ? s*s / denominator : 0.0;
    q += distance * normal;
    emCoord = q;
    gl_Position = matrix * vec4(q, 0.0, 1.0);
    backdropDistance=dot(pc.clipPlane,pc.model*vec4(q,0,1));
}
