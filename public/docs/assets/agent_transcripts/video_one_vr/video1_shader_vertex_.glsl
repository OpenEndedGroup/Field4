#version 450
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
layout(push_constant) uniform MoviePush {
    mat4 model; // screen-local metres to room space
    vec4 surface; // arc width, height, bend radians, strip count
    vec4 image; // packed source width/height, swap eyes, vertical flip
    vec4 colorimetry; // Kr, Kb, full-range flag, reserved
    vec4 accumulation; // processed mix, valid image, spatial blur mix, linear darkening
}
pc;
layout(location = 0) out vec2 uv;
layout(location = 1) flat out int eye;
const vec2 corners[6] =
vec2[6](vec2(0, 0), vec2(0, 1), vec2(1, 1), vec2(0, 0), vec2(1, 1), vec2(1, 0));
void main() {
    int strip = gl_VertexIndex / 6;
    vec2 c = corners[gl_VertexIndex % 6];
    uv = vec2((float(strip) + c.x) / pc.surface.w, c.y);
    float x = (uv.x - .5) * pc.surface.x;
    float z = 0;
    float bend = pc.surface.z;
    if (abs(bend) > 1e-4) {
        float theta = (uv.x - .5) * bend;
        float radius = pc.surface.x / bend;
        x = radius * sin(theta);
        z = radius * 2 * sin(theta * .5) * sin(theta * .5);
    }
    eye = gl_ViewIndex;
    gl_Position = stereoViewProjection() * pc.model * vec4(x, (.5 - uv.y) * pc.surface.y, z, 1);
}
