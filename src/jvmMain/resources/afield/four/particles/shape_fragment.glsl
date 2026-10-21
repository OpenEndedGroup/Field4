#version 450

// The shapes a ParticleSketch draws, antialiased over about a device pixel: a line segment from
// the origin along +x, a ring, or a disc. Writes premultiplied colour for "over" blending; the
// colour stays in sRGB, as a 2D canvas blends it.

layout(location = 0) in vec2 local;
layout(location = 1) flat in vec4 shape;  // half width, radius, kind (0 line, 1 ring, 2 disc), length
layout(location = 2) flat in vec4 color;  // sRGB, straight alpha

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
    } else {
        coverage = clamp(shape.y + 0.5 - length(local), 0.0, 1.0);
    }
    float a = color.a * coverage;
    if (a <= 0.0) discard;
    outColor = vec4(color.rgb * a, a);
}
