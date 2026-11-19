#version 450
#extension GL_EXT_buffer_reference : require

layout(std430, buffer_reference) buffer State {
    vec2 a[];
};

layout(push_constant) uniform Push {
    State state;
    int w;
    int h;
    int zoom; // whole framebuffer pixels per cell
} pc;

layout(location = 0) out vec4 outColor;

// The swapchain is sRGB, so it encodes what this shader writes. Writing the decoded value
// makes the stored byte the map's own value, m * 255, with no transfer curve on top.
float srgbToLinear(float c) {
    return c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4);
}

void main() {
    ivec2 cell = ivec2(gl_FragCoord.xy) / max(pc.zoom, 1);
    if (cell.x >= pc.w || cell.y >= pc.h) {
        discard; // outside the buffer: the renderer's clear colour shows
    }
    // v over 0..0.5, the same explicit map as the box's readback view; marked, not clamped
    float m = pc.state.a[cell.y * pc.w + cell.x].y / 0.5;
    if (m > 1.0) outColor = vec4(1.0, 0.19, 0.13, 1.0);
    else if (m < 0.0) outColor = vec4(0.13, 0.31, 1.0, 1.0);
    else outColor = vec4(vec3(srgbToLinear(m)), 1.0);
}
