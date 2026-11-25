#version 450
#extension GL_EXT_buffer_reference : require
layout(local_size_x = 64) in;

layout(std430, buffer_reference) buffer Weights {
    float w[];
};
layout(std430, buffer_reference) buffer State {
    float s[];
};

layout(push_constant) uniform dat {
    Weights weights; // units × units couplings, row-major
    State state; // header, beliefs A, beliefs B, v, biases, clue mask (HopfieldSudoku.gpuState)
    float tick; // step number; negative freezes the board
    uint n;
    float eta;
    float beta;
} pc;

void main() {
    uint c = gl_GlobalInvocationID.x;
    uint n = pc.n;
    uint cells = n * n;
    uint U = cells * n;
    if (c >= cells || pc.tick < 0.0) return;
    uint k = uint(pc.tick);
    uint src = 4u + (k % 2u) * U;
    uint dst = 4u + ((k + 1u) % 2u) * U;
    uint V = 4u + 2u * U;
    // a clue cell copies its one-hot belief across
    if (pc.state.s[4u + 4u * U + c] > 0.5) {
        for (uint d = 0u; d < n; d++) pc.state.s[dst + c * n + d] = pc.state.s[src + c * n + d];
        return;
    }
    // v <- (1 - eta) v - eta (W p + B), then p = softmax(beta v) over this cell's digits
    float mx = -1e30;
    for (uint d = 0u; d < n; d++) {
        uint u = c * n + d;
        float h = pc.state.s[4u + 3u * U + u];
        for (uint j = 0u; j < U; j++) h += pc.weights.w[u * U + j] * pc.state.s[src + j];
        float v = (1.0 - pc.eta) * pc.state.s[V + u] - pc.eta * h;
        pc.state.s[V + u] = v;
        mx = max(mx, pc.beta * v);
    }
    float z = 0.0;
    for (uint d = 0u; d < n; d++) z += exp(pc.beta * pc.state.s[V + c * n + d] - mx);
    for (uint d = 0u; d < n; d++) pc.state.s[dst + c * n + d] = exp(pc.beta * pc.state.s[V + c * n + d] - mx) / z;
}
