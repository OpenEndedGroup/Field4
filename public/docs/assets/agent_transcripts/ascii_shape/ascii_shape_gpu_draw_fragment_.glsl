#version 450
// The chosen glyph for each cell, every glyph's pixels side by side, and the scene itself.
layout(set = 0, binding = 0) uniform sampler2D cellsTex;
layout(set = 0, binding = 1) uniform sampler2D atlasTex;
layout(set = 0, binding = 2) uniform sampler2D sceneTex;

layout(push_constant) uniform Push {
    vec4 ink;
    vec4 background;
    vec2 cell;
} pc;

layout(location = 0) in vec2 uv;
layout(location = 0) out vec4 outColor;

void main() {
    // uv.y runs up the window; the cells and the atlas keep their top row first.
    vec2 p = vec2(uv.x, 1.0 - uv.y);

    // The scene, small, in the lower right corner.
    if (p.x > 0.76 && p.y > 0.76) {
        outColor = vec4(texture(sceneTex, (p - 0.76) / 0.24).rgb, 1.0);
        return;
    }

    // Which cell this pixel is in, and which glyph the match pass chose for it.
    vec2 grid = vec2(textureSize(cellsTex, 0));
    vec2 g = p * grid;
    float glyph = floor(texelFetch(cellsTex, ivec2(g), 0).r * 255.0 + 0.5);

    // Where this pixel falls in that glyph's cell of the atlas; kept half a pixel inside the cell
    // so filtering does not pull in the neighbouring glyph.
    vec2 inCell = clamp(fract(g) * pc.cell, vec2(0.5), pc.cell - 0.5);
    vec2 atlas = vec2(textureSize(atlasTex, 0));
    float ink = texture(atlasTex, (vec2(glyph * pc.cell.x, 0.0) + inCell) / atlas).r;
    outColor = mix(pc.background, pc.ink, ink);
}
