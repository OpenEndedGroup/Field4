#version 450
#extension GL_EXT_buffer_reference : require
layout(location = 0) in vec2 uv;
layout(location = 0) out vec4 outColor;

layout(std430, buffer_reference) buffer Spins { float s[]; };
layout(std430, buffer_reference) buffer Bonds { float j[]; };

layout(push_constant) uniform DrawPC {
    Spins spins;
    Bonds jRight;
    Bonds jDown;
    uint L;
    float aspect;
    uint showFrustration;
} pc;

void main() {
    uint L = pc.L;
    vec2 p = uv;
    // keep the lattice square regardless of window aspect
    if (pc.aspect >= 1.0) { p.x = (uv.x - 0.5) * pc.aspect + 0.5; }
    else { p.y = (uv.y - 0.5) / pc.aspect + 0.5; }
    if (p.x < 0.0 || p.x > 1.0 || p.y < 0.0 || p.y > 1.0) { outColor = vec4(0.05, 0.05, 0.07, 1.0); return; }
    uint x = min(uint(p.x * float(L)), L - 1u);
    uint y = min(uint((1.0 - p.y) * float(L)), L - 1u);
    uint idx = y * L + x;
    float s = pc.spins.s[idx];
    vec3 up = vec3(0.98, 0.60, 0.16);
    vec3 dn = vec3(0.14, 0.44, 0.82);
    vec3 col = (s > 0.0) ? up : dn;
    if (pc.showFrustration == 1u) {
        uint xr = (x + 1u) % L; uint yd = (y + 1u) % L;
        float plaq = pc.jRight.j[idx] * pc.jDown.j[y * L + xr] * pc.jRight.j[yd * L + x] * pc.jDown.j[idx];
        if (plaq < 0.0) col = mix(col, vec3(1.0, 0.08, 0.08), 0.30);
    }
    outColor = vec4(col, 1.0);
}
