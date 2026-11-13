#version 460
#extension GL_EXT_multiview : require

// The shared eye buffer is supplied by MultiviewStereoTarget.configure.
layout(std140, set = 0, binding = 7) uniform StereoViews {
    mat4 stereoVpL; // left-eye view/projection
    mat4 stereoVpR; // right-eye view/projection
    vec4 stereoRightL; // left-eye world right
    vec4 stereoRightR; // right-eye world right
    vec4 stereoUpL; // left-eye world up
    vec4 stereoUpR; // right-eye world up
    vec4 stereoProjectionL; // left projection scale and raster size
    vec4 stereoProjectionR; // right projection scale and raster size
};

layout(push_constant) uniform Mark {
    vec4 a; // world start/centre, radius or half-width in metres
    vec4 b; // world end, 1 = circular joint / 0 = segment
    vec4 tint; // linear RGBA; zero alpha hides unavailable observations
} pc;

layout(location = 0) out vec2 uv; // local square coordinates for analytic coverage
layout(location = 1) flat out vec4 ink; // linear RGBA
layout(location = 2) flat out float roundMark; // 1 = circle / 0 = strip

const vec2 corners[6] = vec2[](
vec2(-1,-1), vec2(1,-1), vec2(1,1),
vec2(-1,-1), vec2(1,1), vec2(-1,1)
);

void main() {
    uv = corners[gl_VertexIndex];
    ink = pc.tint;
    roundMark = pc.b.w;
    vec3 right = (gl_ViewIndex == 0 ? stereoRightL : stereoRightR).xyz;
    vec3 up = (gl_ViewIndex == 0 ? stereoUpL : stereoUpR).xyz;
    vec3 p;

    // Expand on the GPU, independently for the current eye.
    if (pc.b.w > 0.5) {
        p = pc.a.xyz + (right * uv.x + up * uv.y) * pc.a.w;
    }
    else {
        vec3 d = pc.b.xyz - pc.a.xyz;
        vec3 side = cross(d, cross(right, up));
        side = dot(side, side) > 1e-12 ? normalize(side) : right;
        p = mix(pc.a.xyz, pc.b.xyz, uv.x * 0.5 + 0.5) + side * uv.y * pc.a.w;
    }
    gl_Position = pc.tint.a > 0.0
    ? (gl_ViewIndex == 0 ? stereoVpL : stereoVpR) * vec4(p, 1)
    : vec4(0, 0, -1, 1);
}
