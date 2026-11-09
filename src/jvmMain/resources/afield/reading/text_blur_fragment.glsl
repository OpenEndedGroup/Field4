#version 450
layout(location = 0) in vec2 uv;
layout(location = 0) out vec4 outColor;
layout(push_constant) uniform LocalBlurPush {
    mat4 model;  // text-local to current world pose
    vec4 bounds; // padded font-space rectangle
    vec4 levels; // lower/upper levels and variance-matched interpolation
    vec4 tint;   // live colour/opacity; no fade is stored in the cached masks
    vec4 info;   // layer within this pair of cached words
}
pc;
layout(set = 0, binding = 8) uniform sampler2DArray mask1;
layout(set = 0, binding = 9) uniform sampler2DArray mask2;
layout(set = 0, binding = 10) uniform sampler2DArray mask3;
layout(set = 0, binding = 11) uniform sampler2DArray mask4;
layout(set = 0, binding = 12) uniform sampler2DArray mask5;
layout(set = 0, binding = 13) uniform sampler2DArray mask6;
layout(set = 0, binding = 14) uniform sampler2DArray mask7;
layout(set = 0, binding = 15) uniform sampler2DArray mask8;
layout(set = 0, binding = 16) uniform sampler2DArray mask9;
layout(set = 0, binding = 17) uniform sampler2DArray mask10;
layout(set = 0, binding = 18) uniform sampler2DArray mask11;
layout(set = 0, binding = 19) uniform sampler2DArray mask12;
layout(set = 0, binding = 20) uniform sampler2DArray mask13;
layout(set = 0, binding = 21) uniform sampler2DArray mask14;
layout(set = 0, binding = 22) uniform sampler2DArray mask15;
float sampleLevel(int level) {
    vec3 q = vec3(uv, pc.info.x);
    switch (level) {
    case 1:
        return texture(mask1, q).r;
    case 2:
        return texture(mask2, q).r;
    case 3:
        return texture(mask3, q).r;
    case 4:
        return texture(mask4, q).r;
    case 5:
        return texture(mask5, q).r;
    case 6:
        return texture(mask6, q).r;
    case 7:
        return texture(mask7, q).r;
    case 8:
        return texture(mask8, q).r;
    case 9:
        return texture(mask9, q).r;
    case 10:
        return texture(mask10, q).r;
    case 11:
        return texture(mask11, q).r;
    case 12:
        return texture(mask12, q).r;
    case 13:
        return texture(mask13, q).r;
    case 14:
        return texture(mask14, q).r;
    case 15:
        return texture(mask15, q).r;
    }
    return 0;
}
void main() {
    float coverage =
        mix(sampleLevel(int(pc.levels.x + .5)), sampleLevel(int(pc.levels.y + .5)), pc.levels.z);
    outColor = vec4(1, 1, 1, coverage * pc.tint.a); // matches text_one's white analytic ink
}
