#version 450
layout(location=0) in vec2 brushUV;
layout(location=1) in vec4 deposit;
layout(location=2) flat in vec3 info; // reveal, stage, eraser
layout(location=3) flat in float bristleSeed;
layout(location=0) out vec4 paint;
layout(location=1) out vec4 diagnostic;

void main() {
    float u = brushUV.x;
    float v = brushUV.y;
    float edge = 1.0 - smoothstep(0.48, 1.0, abs(v));
    float ends = smoothstep(0.0, 0.13, u) * (1.0 - smoothstep(0.87, 1.0, u));
    float nib = 1.0 - smoothstep(info.x - 0.11, info.x, u);
    float bristle = 0.69 + 0.31 * sin(v * 31.0 + bristleSeed * 11.0 + 1.6 * sin(u * 23.0));
    float coverage = edge * ends * nib * bristle;
    paint = deposit * coverage;
    diagnostic = deposit.a * coverage * vec4(info.y == 1.0, info.y == 2.0, info.y == 3.0, info.z);
}
