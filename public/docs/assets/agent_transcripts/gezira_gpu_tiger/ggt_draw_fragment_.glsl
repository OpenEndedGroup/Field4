#version 450
#extension GL_EXT_buffer_reference : require

// The scene's image: premultiplied rgba per pixel, row-major, y down.
layout(std430, buffer_reference) buffer Image {
    vec4 p[];
};

layout(push_constant) uniform Push {
    Image image;
    int n;
    int win;
} pc;

layout(location = 0) out vec4 outColor;

float srgbToLinear(float c) {
    return c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4);
}

void main() {
    // the image fills the window's width, whatever its pixel density
    ivec2 q = ivec2(gl_FragCoord.xy * float(pc.n) / float(max(pc.win, 1)));
    vec4 c = q.x < pc.n && q.y < pc.n ? pc.image.p[q.y * pc.n + q.x] : vec4(0.0);
    // over white
    vec3 col = c.rgb + (1.0 - c.a) * vec3(1.0);
    outColor = vec4(srgbToLinear(col.r), srgbToLinear(col.g), srgbToLinear(col.b), 1.0);
}
