#version 450
#extension GL_EXT_buffer_reference : require
struct Cell {
    vec4 pv; // world position xy, velocity zw
    vec4 e; // energy, cell kind, organism id, padding
};
struct Bonds {
    vec4 partner;
    vec4 rest;
    vec4 amp;
    vec4 phase;
    vec4 limit;
    vec4 axis;
};
layout(std430, buffer_reference) buffer CellBuf {
    Cell c[];
};
layout(std430, buffer_reference) buffer BondBuf {
    Bonds b[];
};
layout(push_constant) uniform P {
    CellBuf cells; // two capacity-sized halves; step parity selects the source
    BondBuf bonds; // four neighbour slots per cell; -1 marks an empty slot
    uint latest; // most recently written cell half, 0 or 1
    uint capacity; // stride between the two cell halves
    float viewX;
    float viewY;
    float viewHalfHeight; // visible world units above and below the view centre
    float aspect;
} pc;
layout(location = 0) out vec2 tc;
layout(location = 1) out vec4 col;
layout(location = 2) flat out int isBond;

const vec2 corner[6] = vec2[](vec2(0, 0), vec2(1, 0), vec2(1, 1), vec2(0, 0), vec2(1, 1), vec2(0, 1));
vec3 hue(float h) {
    return clamp(abs(fract(h + vec3(0, 2, 1) / 3.0) * 6.0 - 3.0) - 1.0, 0.0, 1.0);
}

void main() {
    int ci = gl_VertexIndex / 30, r = gl_VertexIndex % 30;
    uint base = pc.latest * pc.capacity;
    Cell me = pc.cells.c[base + uint(ci)];
    vec2 q = corner[r % 6];
    float kind = me.e.y;
    // free matter is grey; each organism gets its own hue, muscles brightest
    vec3 c = kind > 2.5 ? vec3(0.32, 0.36, 0.42) : hue(me.e.z * 0.27 + 0.03);
    vec2 world;
    if (r < 6) {
        float size = kind > 2.5 ? 0.4 : 0.75;
        world = me.pv.xy + (q - 0.5) * size;
        isBond = 0;
        float bright = kind > 2.5 ? 0.7 : (kind > 0.5 ? 1.0 : 0.55) * (0.35 + 0.65 * clamp(me.e.x, 0.0, 1.0));
        col = vec4(c * bright, 1.0);
    }
    else {
        int s = (r - 6) / 6;
        int j = int(pc.bonds.b[ci].partner[s]);
        isBond = 1;
        tc = q;
        col = vec4(c * 0.5, 1.0);
        // each bond is drawn once, from its lower-numbered end
        if (j <= ci) {
            gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
            return;
        }
        vec2 a = me.pv.xy, b = pc.cells.c[base + uint(j)].pv.xy;
        vec2 d = b - a;
        vec2 n = normalize(vec2(-d.y, d.x)) * 0.07;
        world = a + d * q.x + n * (q.y * 2.0 - 1.0);
    }
    tc = q;
    vec2 ndc = (world - vec2(pc.viewX, pc.viewY)) / pc.viewHalfHeight;
    ndc.x /= pc.aspect;
    gl_Position = vec4(ndc.x, -ndc.y, 0.0, 1.0);
}
