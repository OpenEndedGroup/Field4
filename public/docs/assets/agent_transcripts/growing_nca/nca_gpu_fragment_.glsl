#version 450
#extension GL_EXT_buffer_reference : require
layout(location = 0) out vec4 uFragColor;

// The simulation's two grids, one after the other: four vec4s per cell, the first being red,
// green, blue, alpha. `shown` picks the grid the last step wrote.
layout(std430, buffer_reference) buffer NcaCells {
    vec4 v[];
};

layout(push_constant) uniform pcs {
    NcaCells state;
    uint shown;
    vec2 viewport;
    uint gridSize;
} pc;

void main() {
    // The grid is drawn as the largest centred square that fits the window, one square per cell.
    float side = min(pc.viewport.x, pc.viewport.y);
    vec2 cell = (gl_FragCoord.xy - 0.5 * (pc.viewport - side)) / side * float(pc.gridSize);
    if (any(lessThan(cell, vec2(0))) || any(greaterThanEqual(cell, vec2(pc.gridSize)))) {
        uFragColor = vec4(0.12, 0.12, 0.12, 1);
        return;
    }
    ivec2 c = ivec2(cell);
    vec4 rgba = clamp(pc.state.v[int(pc.shown * pc.gridSize * pc.gridSize * 4u) + (c.y * int(pc.gridSize) + c.x) * 4], 0.0, 1.0);
    // Colours are premultiplied, so this is "over white", the way the article shows them. The window
    // is sRGB, so the colour is linearised here and comes out as the CPU picture's bytes.
    uFragColor = vec4(pow(clamp(1.0 - rgba.a + rgba.rgb, 0.0, 1.0), vec3(2.2)), 1.0);
}
