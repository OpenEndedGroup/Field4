#version 450

layout(set = 0, binding = 0) uniform sampler2D sourceTex;
layout(set = 0, binding = 1) uniform sampler2D ignitionTex;

layout(push_constant) uniform Push {
    vec4 refractoryDecay;
    float spreadStepPx;
    float spreadGain;
    float paintGain;
    float ignitionThreshold;
    float activeFireThreshold;
    float readyThreshold;
    float treeDensity;
    float treeScalePx;
    float treeJitter;
    int seed;
    float clearRequested;
    float spreadProbability;
    int frame;
} push;

layout(location = 0) out vec4 outColor;

uint hash(uint x) {
    x ^= x >> 16u;
    x *= 0x7feb352du;
    x ^= x >> 15u;
    x *= 0x846ca68bu;
    x ^= x >> 16u;
    return x;
}

float random01(uvec2 p, uint salt) {
    return float(hash(p.x ^ hash(p.y ^ hash(uint(push.seed) ^ salt)))) / 4294967295.0;
}

// clumps on a coarse grid, roughened per texel by treeJitter
bool isTree(ivec2 pixel) {
    uvec2 coarse = uvec2(floor(vec2(pixel) / max(push.treeScalePx, 1.0)));
    float mixed = mix(random01(coarse, 1u), random01(uvec2(pixel), 2u), clamp(push.treeJitter, 0.0, 1.0));
    return mixed <= push.treeDensity;
}

float max4(vec4 v) {
    return max(max(v.x, v.y), max(v.z, v.w));
}

// how strongly each kind is burning, 0 at activeFireThreshold, 1 at full
vec4 burning(ivec2 p, ivec2 dims) {
    vec4 s = texelFetch(sourceTex, clamp(p, ivec2(0), dims - 1), 0);
    return clamp((s - vec4(push.activeFireThreshold)) / max(1.0 - push.activeFireThreshold, 0.0001), vec4(0.0), vec4(1.0));
}

// a burning neighbour's fire, if this frame's draw lets it across to this tree
vec4 reaching(ivec2 pixel, ivec2 offset, ivec2 dims, uint salt) {
    uvec2 u = uvec2(pixel);
    float draw = random01(u, hash(uint(push.frame) * 8u + salt) | 4u);
    return draw < push.spreadProbability ? burning(pixel + offset, dims) : vec4(0.0);
}

// the kind with the strongest signal at or above the threshold, among those allowed; -1 for none
int strongest(vec4 signal, bvec4 allowed) {
    int winner = -1;
    float best = -1.0;
    for (int k = 0; k < 4; ++k) {
        if (allowed[k] && signal[k] >= push.ignitionThreshold && signal[k] > best) {
            best = signal[k];
            winner = k;
        }
    }
    return winner;
}

vec4 caught(int kind) {
    vec4 v = vec4(0.0);
    v[kind] = 1.0;
    return v;
}

void main() {
    ivec2 dims = textureSize(sourceTex, 0);
    ivec2 pixel = clamp(ivec2(gl_FragCoord.xy), ivec2(0), dims - 1);

    if (push.clearRequested > 0.5 || !isTree(pixel)) {
        outColor = vec4(0.0);
        return;
    }

    vec4 previous = texelFetch(sourceTex, pixel, 0);
    vec4 decayed = max(previous - push.refractoryDecay, vec4(0.0));

    // drawn ignition takes any tree, burning or not
    int lit = strongest(texelFetch(ignitionTex, pixel, 0) * push.paintGain, bvec4(true));
    if (lit >= 0) {
        outColor = caught(lit);
        return;
    }

    if (max4(previous) >= push.activeFireThreshold) {
        outColor = decayed;
        return;
    }

    // each of the eight neighbours gets its own draw against spreadProbability
    int s = max(int(round(push.spreadStepPx)), 1);
    vec4 neighbours = max(max(max(reaching(pixel, ivec2(s, 0), dims, 0u), reaching(pixel, ivec2(-s, 0), dims, 1u)),
    max(reaching(pixel, ivec2(0, s), dims, 2u), reaching(pixel, ivec2(0, -s), dims, 3u))),
    0.7071 * max(max(reaching(pixel, ivec2(s, s), dims, 4u), reaching(pixel, ivec2(-s, s), dims, 5u)),
    max(reaching(pixel, ivec2(s, -s), dims, 6u), reaching(pixel, ivec2(-s, -s), dims, 7u))));

    // a neighbour's fire takes a tree only if that kind has recovered there
    int spread = strongest(neighbours * push.spreadGain, lessThanEqual(decayed, vec4(push.readyThreshold)));
    outColor = spread >= 0 ? caught(spread) : decayed;
}
