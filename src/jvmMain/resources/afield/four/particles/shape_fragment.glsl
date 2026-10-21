#version 450

// The shapes a ParticleSketch draws, antialiased over about a device pixel: a line segment from
// the origin along +x, a ring, or a disc. Writes premultiplied colour for "over" blending; the
// colour stays in sRGB, as a 2D canvas blends it.

layout(location = 0) in vec2 local;
layout(location = 1) flat in vec4 shape;  // half width, radius, kind (0 line, 1 ring, 2 disc, 3 sector, 4 dashed ring, 5 rock), length
layout(location = 2) flat in vec4 color;  // sRGB, straight alpha
layout(location = 3) flat in vec2 extra;  // sector: centre angle, half angle; dashed ring: dash, gap

layout(location = 0) out vec4 outColor;

void main() {
    int kind = int(shape.z + 0.5);
    float coverage;
    if (kind == 0) {
        // distance to the segment; a line thinner than a pixel is drawn a pixel wide and fainter
        float along = clamp(local.x, 0.0, shape.w);
        float d = length(local - vec2(along, 0.0));
        float halfWidth = max(shape.x, 0.5);
        coverage = clamp(halfWidth + 0.5 - d, 0.0, 1.0) * min(shape.x / 0.5, 1.0);
    } else if (kind == 1) {
        float d = abs(length(local) - shape.y);
        float halfWidth = max(shape.x, 0.5);
        coverage = clamp(halfWidth + 0.5 - d, 0.0, 1.0) * min(shape.x / 0.5, 1.0);
    } else if (kind == 3) {
        // a filled wedge: the disc, between centre - half and centre + half (y down, as the canvas)
        float a = atan(local.y, local.x) - extra.x;
        a = mod(a + 3.14159265, 6.28318531) - 3.14159265;
        float edge = (extra.y - abs(a)) * max(length(local), 1.0);
        coverage = clamp(shape.y + 0.5 - length(local), 0.0, 1.0) * clamp(edge + 0.5, 0.0, 1.0);
    } else if (kind == 4) {
        // a dashed ring: dash and gap measured along the circumference
        float d = abs(length(local) - shape.y);
        float halfWidth = max(shape.x, 0.5);
        float along = (atan(local.y, local.x) + 3.14159265) * shape.y;
        float on = mod(along, extra.x + extra.y) < extra.x ? 1.0 : 0.0;
        coverage = clamp(halfWidth + 0.5 - d, 0.0, 1.0) * min(shape.x / 0.5, 1.0) * on;
    } else if (kind == 5) {
        // a rock: a disc shaded from rgb(70, 80, 78) near the upper left to the record's colour
        float r = length(local);
        coverage = clamp(shape.y + 0.5 - r, 0.0, 1.0);
        float g = clamp((length(local - vec2(-0.3, -0.3) * shape.y) - 0.1 * shape.y) / (0.9 * shape.y), 0.0, 1.0);
        vec3 lit = mix(vec3(70.0, 80.0, 78.0) / 255.0, color.rgb, g);
        float a5 = color.a * coverage;
        if (a5 <= 0.0) discard;
        outColor = vec4(lit * a5, a5);
        return;
    } else {
        coverage = clamp(shape.y + 0.5 - length(local), 0.0, 1.0);
    }
    float a = color.a * coverage;
    if (a <= 0.0) discard;
    outColor = vec4(color.rgb * a, a);
}
