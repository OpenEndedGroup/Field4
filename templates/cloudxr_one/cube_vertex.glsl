#version 460
#extension GL_EXT_multiview : require

layout(std140, set = 0, binding = 7) uniform StereoViews {
    mat4 stereoVpL; // Predicted left-eye view/projection for this frame.
    mat4 stereoVpR; // Predicted right-eye view/projection for this frame.
    vec4 stereoRightL; // Left-eye world right.
    vec4 stereoRightR; // Right-eye world right.
    vec4 stereoUpL; // Left-eye world up.
    vec4 stereoUpR; // Right-eye world up.
    vec4 stereoProjectionL; // Left projection scale and raster dimensions.
    vec4 stereoProjectionR; // Right projection scale and raster dimensions.
};

layout(push_constant) uniform Cube {
    vec4 centreAndSize; // World centre in metres, then cube side length in metres.
    vec4 rotation; // Fixed pitch and yaw in radians; remaining components reserved.
} pc;

layout(location = 0) out vec3 worldNormal;

const vec3 corners[8] = vec3[](
    vec3(-1,-1,-1), vec3(1,-1,-1), vec3(1,1,-1), vec3(-1,1,-1),
    vec3(-1,-1,1), vec3(1,-1,1), vec3(1,1,1), vec3(-1,1,1)
);
const int indices[36] = int[](
    0,2,1, 0,3,2, 4,5,6, 4,6,7,
    0,4,7, 0,7,3, 1,2,6, 1,6,5,
    0,1,5, 0,5,4, 3,7,6, 3,6,2
);
const vec3 normals[6] = vec3[](
    vec3(0,0,-1), vec3(0,0,1), vec3(-1,0,0),
    vec3(1,0,0), vec3(0,-1,0), vec3(0,1,0)
);

void main() {
    float a = pc.rotation.x, b = pc.rotation.y;
    mat3 pitch = mat3(1,0,0, 0,cos(a),sin(a), 0,-sin(a),cos(a));
    mat3 yaw = mat3(cos(b),0,-sin(b), 0,1,0, sin(b),0,cos(b));
    mat3 turn = yaw * pitch;
    vec3 p = pc.centreAndSize.xyz + turn * corners[indices[gl_VertexIndex]] * pc.centreAndSize.w * 0.5;
    worldNormal = turn * normals[gl_VertexIndex / 6];
    gl_Position = (gl_ViewIndex == 0 ? stereoVpL : stereoVpR) * vec4(p, 1);
}
