#version 450
#extension GL_EXT_buffer_reference : require
layout(local_size_x = 64) in;

layout(std430, buffer_reference) buffer Spins { float s[]; };
layout(std430, buffer_reference) buffer Bonds { float j[]; };

layout(push_constant) uniform PushC {
    Spins spins;
    Bonds jRight;
    Bonds jDown;
    uint L;
    uint color;
    uint seed;
    uint mode;
    float temperature;
    float field;
} pc;

uint hash(uint x){ x ^= x >> 16; x *= 0x7feb352du; x ^= x >> 15; x *= 0x846ca68bu; x ^= x >> 16; return x; }
float rnd(uint v){ return float(hash(v)) * (1.0 / 4294967296.0); }

void main() {
    uint L = pc.L;
    uint N = L * L;
    uint idx = gl_GlobalInvocationID.x;
    if (idx >= N) return;
    uint x = idx % L;
    uint y = idx / L;
    // red-black: this dispatch only touches one colour of the checkerboard,
    // so no two updated spins are ever nearest neighbours in a single pass.
    if (((x + y) & 1u) != pc.color) return;

    uint xl = (x + L - 1u) % L;
    uint xr = (x + 1u) % L;
    uint yu = (y + L - 1u) % L;
    uint yd = (y + 1u) % L;

    float s  = pc.spins.s[idx];
    float sl = pc.spins.s[y * L + xl];
    float sr = pc.spins.s[y * L + xr];
    float su = pc.spins.s[yu * L + x];
    float sd = pc.spins.s[yd * L + x];

    // jRight[i] is the bond i--(+x); the left bond is the left neighbour's jRight.
    // jDown[i]  is the bond i--(+y); the up   bond is the up   neighbour's jDown.
    float jl = pc.jRight.j[y * L + xl];
    float jr = pc.jRight.j[idx];
    float ju = pc.jDown.j[yu * L + x];
    float jd = pc.jDown.j[idx];

    float localField = jl * sl + jr * sr + ju * su + jd * sd + pc.field;
    float T = max(pc.temperature, 1e-4);
    float u = rnd(idx * 2654435761u + pc.seed * 40503u + pc.color * 19349663u);

    float ns = s;
    if (pc.mode == 0u) {
        // Metropolis
        float dE = 2.0 * s * localField;
        if (dE <= 0.0 || u < exp(-dE / T)) ns = -s;
    } else {
        // heat-bath
        float pPlus = 1.0 / (1.0 + exp(-2.0 * localField / T));
        ns = (u < pPlus) ? 1.0 : -1.0;
    }
    pc.spins.s[idx] = ns;
}
