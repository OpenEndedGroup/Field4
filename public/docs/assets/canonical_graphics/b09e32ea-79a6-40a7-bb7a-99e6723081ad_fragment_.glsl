#version 450

layout(set = 0, binding = 0) uniform sampler2D fluid0Tex;
layout(set = 0, binding = 1) uniform sampler2D fluid1Tex;
layout(set = 0, binding = 2) uniform sampler2D fluid2Tex;
layout(set = 0, binding = 3) uniform sampler2D controlsTex;

layout(push_constant) uniform Push {
    float relaxation;
    float baseDensity;
    vec2 baseVelocity;
    float driveBlend;
    float controlVelocityScale;
    float maxVelocity;
    float wrapX;
    float wrapY;
    float borderSolidPx;
    float solidThreshold;
    float clearRequested;
    float initialNoise;
} push;

// directions 0-3, 4-7, and (8, density, velocity)
layout(location = 0) out vec4 out0;
layout(location = 1) out vec4 out1;
layout(location = 2) out vec4 out2;

const vec2 DIRS[9] = vec2[](
vec2(0.0, 0.0), vec2(1.0, 0.0), vec2(0.0, 1.0), vec2(-1.0, 0.0), vec2(0.0, -1.0),
vec2(1.0, 1.0), vec2(-1.0, 1.0), vec2(-1.0, -1.0), vec2(1.0, -1.0)
);
const float WEIGHTS[9] = float[](
4.0 / 9.0, 1.0 / 9.0, 1.0 / 9.0, 1.0 / 9.0, 1.0 / 9.0,
1.0 / 36.0, 1.0 / 36.0, 1.0 / 36.0, 1.0 / 36.0
);
const int OPPOSITE[9] = int[](0, 3, 4, 1, 2, 7, 8, 5, 6);

ivec2 wrapPixel(ivec2 pixel, ivec2 dims) {
    int x = push.wrapX > 0.5 ? ((pixel.x % dims.x) + dims.x) % dims.x : clamp(pixel.x, 0, dims.x - 1);
    int y = push.wrapY > 0.5 ? ((pixel.y % dims.y) + dims.y) % dims.y : clamp(pixel.y, 0, dims.y - 1);
    return ivec2(x, y);
}

bool isSolidAt(ivec2 pixel, ivec2 dims) {
    if (push.borderSolidPx > 0.5) {
        int border = int(round(push.borderSolidPx));
        if (pixel.x < border || pixel.y < border || pixel.x >= dims.x - border || pixel.y >= dims.y - border) {
            return true;
        }
    }
    return texelFetch(controlsTex, wrapPixel(pixel, dims), 0).r >= push.solidThreshold;
}

void loadPopulations(ivec2 pixel, ivec2 dims, out float f[9]) {
    ivec2 p = wrapPixel(pixel, dims);
    vec4 a = texelFetch(fluid0Tex, p, 0);
    vec4 b = texelFetch(fluid1Tex, p, 0);
    vec4 c = texelFetch(fluid2Tex, p, 0);
    f[0] = a.r;
    f[1] = a.g;
    f[2] = a.b;
    f[3] = a.a;
    f[4] = b.r;
    f[5] = b.g;
    f[6] = b.b;
    f[7] = b.a;
    f[8] = c.r;
}

float equilibrium(int i, float density, vec2 velocity) {
    float cu = dot(DIRS[i], velocity);
    return WEIGHTS[i] * density * (1.0 + 3.0 * cu + 4.5 * cu * cu - 1.5 * dot(velocity, velocity));
}

void macroscopic(float f[9], out float density, out vec2 velocity) {
    density = 0.0;
    for (int i = 0; i < 9; ++i) density += f[i];
    density = max(density, 1e-5);
    velocity = vec2(
    f[1] - f[3] + f[5] - f[6] - f[7] + f[8],
    f[2] - f[4] + f[5] + f[6] - f[7] - f[8]
    ) / density;
}

// the population heading in direction i after the site at pixel has collided
float postCollision(ivec2 pixel, int i, ivec2 dims) {
    if (isSolidAt(pixel, dims)) return equilibrium(i, push.baseDensity, vec2(0.0));

    float f[9];
    loadPopulations(pixel, dims, f);
    float density;
    vec2 velocity;
    macroscopic(f, density, velocity);

    vec4 control = texelFetch(controlsTex, wrapPixel(pixel, dims), 0);
    vec2 controlVelocity = control.gb * push.controlVelocityScale;
    // the wind pulls every site toward it; the controls push on top
    velocity = mix(velocity, push.baseVelocity, push.driveBlend) + controlVelocity;
    // a dye source is also an inlet: hold its velocity near the wind plus its push
    velocity = mix(velocity, push.baseVelocity + controlVelocity, clamp(control.a, 0.0, 1.0) * 0.85);

    float speed = length(velocity);
    if (speed > push.maxVelocity) velocity *= push.maxVelocity / speed;

    return mix(f[i], equilibrium(i, density, velocity), push.relaxation);
}

// pull-streaming: what arrives here along direction i, reflected back off a solid neighbour
float streamed(ivec2 pixel, int i, ivec2 dims) {
    ivec2 source = pixel - ivec2(DIRS[i]);
    if (isSolidAt(source, dims)) return postCollision(pixel, OPPOSITE[i], dims);
    return postCollision(source, i, dims);
}

uint hash(uint x) {
    x ^= x >> 16u;
    x *= 0x7feb352du;
    x ^= x >> 15u;
    x *= 0x846ca68bu;
    x ^= x >> 16u;
    return x;
}

vec2 noiseAt(ivec2 pixel) {
    uint h = hash(uint(pixel.x) ^ hash(uint(pixel.y) * 2654435761u));
    return (vec2(float(h & 0xffffu), float(h >> 16u)) / 65535.0 - 0.5) * 2.0;
}

void writeAtRest(vec2 u) {
    float rho = push.baseDensity;
    out0 = vec4(equilibrium(0, rho, u), equilibrium(1, rho, u), equilibrium(2, rho, u), equilibrium(3, rho, u));
    out1 = vec4(equilibrium(4, rho, u), equilibrium(5, rho, u), equilibrium(6, rho, u), equilibrium(7, rho, u));
    out2 = vec4(equilibrium(8, rho, u), rho, u);
}

void main() {
    ivec2 dims = textureSize(fluid0Tex, 0);
    ivec2 pixel = clamp(ivec2(gl_FragCoord.xy), ivec2(0), dims - 1);

    if (isSolidAt(pixel, dims)) {
        writeAtRest(vec2(0.0));
        return;
    }
    if (push.clearRequested > 0.5) {
        writeAtRest(push.baseVelocity + noiseAt(pixel) * push.initialNoise);
        return;
    }

    float f[9];
    for (int i = 0; i < 9; ++i) f[i] = streamed(pixel, i, dims);

    float density;
    vec2 velocity;
    macroscopic(f, density, velocity);
    out0 = vec4(f[0], f[1], f[2], f[3]);
    out1 = vec4(f[4], f[5], f[6], f[7]);
    out2 = vec4(f[8], density, velocity);
}
