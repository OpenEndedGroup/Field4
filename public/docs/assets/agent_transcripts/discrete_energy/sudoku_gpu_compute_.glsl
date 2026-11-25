#version 450
#extension GL_EXT_buffer_reference : require
layout(local_size_x = 64) in;

layout(std430, buffer_reference) buffer Pairwise {
    float w[];
};
layout(std430, buffer_reference) buffer State {
    float s[];
};

layout(push_constant) uniform dat {
    Pairwise pairwise; // units × units costs W, row-major
    State state; // DiscreteEnergy.gpuState: header, beliefs A, beliefs B, v, unary, then offset/size/fixed per variable
    float tick; // step number; negative freezes the descent
    uint units;
    uint variables;
    float eta;
    float beta;
} pc;

void main() {
    uint a = gl_GlobalInvocationID.x;
    uint U = pc.units;
    if (a >= pc.variables || pc.tick < 0.0) return;
    uint k = uint(pc.tick);
    uint src = 4u + (k % 2u) * U;
    uint dst = 4u + ((k + 1u) % 2u) * U;
    uint V = 4u + 2u * U;
    uint B = 4u + 3u * U;
    uint meta = 4u + 4u * U + 3u * a;
    uint o = uint(pc.state.s[meta]);
    uint n = uint(pc.state.s[meta + 1u]);
    float fixedValue = pc.state.s[meta + 2u];
    // a fixed variable stays one-hot on its value
    if (fixedValue >= 0.0) {
        for (uint x = 0u; x < n; x++) pc.state.s[dst + o + x] = (float(x) == fixedValue) ? 1.0 : 0.0;
        return;
    }
    // v <- (1 - eta) v - eta (W p + B), then p = softmax(beta v) over this variable's values
    float mx = -1e30;
    for (uint x = 0u; x < n; x++) {
        uint u = o + x;
        float h = pc.state.s[B + u];
        for (uint j = 0u; j < U; j++) h += pc.pairwise.w[u * U + j] * pc.state.s[src + j];
        float v = (1.0 - pc.eta) * pc.state.s[V + u] - pc.eta * h;
        pc.state.s[V + u] = v;
        mx = max(mx, pc.beta * v);
    }
    float z = 0.0;
    for (uint x = 0u; x < n; x++) z += exp(pc.beta * pc.state.s[V + o + x] - mx);
    for (uint x = 0u; x < n; x++) pc.state.s[dst + o + x] = exp(pc.beta * pc.state.s[V + o + x] - mx) / z;
}
