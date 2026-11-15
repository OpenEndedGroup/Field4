#version 450
#extension GL_EXT_buffer_reference : require
layout(local_size_x = 64) in;

// BondedWorld.step, one invocation per cell. Every bond is stored at both its ends,
// so a cell only ever writes itself.
struct Cell {
    vec4 pv;
    vec4 e;
}; // (x, y, vx, vy), (energy, kind, owner, 0)
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
    CellBuf cells;
    BondBuf bonds;
    float tick; // the step number; -1 freezes the world
    uint count;
    uint capacity;
    float dt;
    float stiffness;
    float bondDamping;
    float dragNormal;
    float dragTangent;
    float omega;
    float repulsionRadius;
    float repulsionStiffness;
    float energyDiffusion;
    float muscleCost;
    float fullEnergy;
    float wallX;
    float wallY;
    float wallStiffness;
} pc;

void main() {
    uint i = gl_GlobalInvocationID.x;
    if (i >= pc.count || pc.tick < 0.0) return;
    uint k = uint(pc.tick);
    uint src = (k & 1u) * pc.capacity;
    uint dst = (1u - (k & 1u)) * pc.capacity;
    float t = pc.tick * pc.dt;
    Cell me = pc.cells.c[src + i];
    vec2 xi = me.pv.xy, vi = me.pv.zw;
    float ei = me.e.x;
    vec2 f = vec2(0);
    mat2 M = mat2(0);
    float nb = 0.0, de = 0.0, cost = 0.0;
    Bonds bd = pc.bonds.b[i];
    for (int s = 0; s < 4; s++) {
        int j = int(bd.partner[s]);
        if (j < 0) continue;
        Cell o = pc.cells.c[src + uint(j)];
        vec2 d = o.pv.xy - xi;
        float len = max(length(d), 1e-6);
        vec2 u = d / len;
        // a muscle's stroke shrinks as its two cells run out of energy
        float scale = clamp(0.5 * (ei + o.e.x) / pc.fullEnergy, 0.0, 1.0);
        float r = bd.rest[s] * (1.0 + bd.amp[s] * scale * sin(pc.omega * t + bd.phase[s]));
        // both ends see the same numbers, so both break the bond on the same step
        if (abs(len - r) / r > bd.limit[s]) {
            pc.bonds.b[i].partner[s] = -1.0;
            continue;
        }
        float rv = dot(o.pv.zw - vi, u);
        f += (pc.stiffness * (len - r) + pc.bondDamping * rv) * u;
        // rails set the drag axis; rungs and braces do not
        M += bd.axis[s] * outerProduct(u, u);
        nb += bd.axis[s];
        de += pc.energyDiffusion * (o.e.x - ei);
        cost += abs(bd.amp[s]) * scale;
    }
    float rr = pc.repulsionRadius;
    for (uint j = 0u; j < pc.count; j++) {
        if (j == i) continue;
        vec2 d = pc.cells.c[src + j].pv.xy - xi;
        float d2 = dot(d, d);
        if (d2 >= rr * rr || d2 < 1e-12) continue;
        float len = sqrt(d2);
        f += -pc.repulsionStiffness * (rr - len) * d / len;
    }
    if (pc.wallX > 0.0) {
        f.x -= pc.wallStiffness * (max(xi.x - pc.wallX, 0.0) + min(xi.x + pc.wallX, 0.0));
        f.y -= pc.wallStiffness * (max(xi.y - pc.wallY, 0.0) + min(xi.y + pc.wallY, 0.0));
    }
    // resistive-force drag: sideways to the bonds costs more than along them
    if (nb > 0.0) M /= nb;
    f += -pc.dragNormal * vi + (pc.dragNormal - pc.dragTangent) * (M * vi);
    vec2 nv = vi + f * pc.dt;
    pc.cells.c[dst + i].pv = vec4(xi + nv * pc.dt, nv);
    pc.cells.c[dst + i].e = vec4(max(ei + (de - pc.muscleCost * cost) * pc.dt, 0.0), me.e.yzw);
}
