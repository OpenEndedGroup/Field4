#version 450
#extension GL_EXT_buffer_reference : require
#extension GL_EXT_scalar_block_layout : require

struct Particle {
    vec2 positionPx;
    uint rngState;
    uint mode;
};

layout(std430, buffer_reference, scalar) buffer ParticleBuffer {
    Particle particles[];
};

layout(set = 0, binding = 0) uniform sampler2D sourceTex;

layout(push_constant) uniform Push {
    ParticleBuffer particleBuffer;
    float pointSize;
    float walkStepPx;
    float sampleStepPx;
    float solidThreshold;
    float walkerGreen;
    float stuckRed;
    float stuckBlue;
} push;

layout(location = 0) out vec4 vColor;

uint nextRandom(inout uint state) {
    state = state * 747796405u + 2891336453u;
    uint word = ((state >> ((state >> 28u) + 4u)) ^ state) * 277803737u;
    return (word >> 22u) ^ word;
}

float random01(inout uint state) {
    return float(nextRandom(state)) / 4294967295.0;
}

vec2 randomPosition(inout uint state, ivec2 dims) {
    return vec2(
        random01(state) * float(max(dims.x - 1, 0)),
        random01(state) * float(max(dims.y - 1, 0))
    );
}

vec2 randomWalkStep(inout uint state, float stepPx) {
    uint choice = nextRandom(state) & 3u;
    if (choice == 0u) return vec2(stepPx, 0.0);
    if (choice == 1u) return vec2(-stepPx, 0.0);
    if (choice == 2u) return vec2(0.0, stepPx);
    return vec2(0.0, -stepPx);
}

bool isSolidAt(ivec2 pixel, ivec2 dims, float threshold) {
    ivec2 clampedPixel = clamp(pixel, ivec2(0), dims - 1);
    return texelFetch(sourceTex, clampedPixel, 0).r >= threshold;
}

void main() {
    ivec2 dims = textureSize(sourceTex, 0);
    vec2 maxPos = vec2(max(dims.x - 1, 0), max(dims.y - 1, 0));

    Particle particle = push.particleBuffer.particles[gl_VertexIndex];
    uint rng = particle.rngState ^ (uint(gl_VertexIndex) * 2246822519u + 3266489917u);
    vec2 positionPx = clamp(particle.positionPx, vec2(0.0), maxPos);
    uint mode = particle.mode;
    bool becameSolid = false;

    if (mode != 0u) {
        positionPx = randomPosition(rng, dims);
        mode = 0u;
    } else {
        ivec2 center = clamp(ivec2(round(positionPx)), ivec2(0), dims - 1);
        int probe = max(int(round(push.sampleStepPx)), 1);
        bool solidNeighbor =
            isSolidAt(center + ivec2(probe, 0), dims, push.solidThreshold) ||
            isSolidAt(center + ivec2(-probe, 0), dims, push.solidThreshold) ||
            isSolidAt(center + ivec2(0, probe), dims, push.solidThreshold) ||
            isSolidAt(center + ivec2(0, -probe), dims, push.solidThreshold);

        if (solidNeighbor) {
            mode = 1u;
            becameSolid = true;
        } else {
            positionPx = clamp(positionPx + randomWalkStep(rng, push.walkStepPx), vec2(0.0), maxPos);
        }
    }

    push.particleBuffer.particles[gl_VertexIndex].positionPx = positionPx;
    push.particleBuffer.particles[gl_VertexIndex].rngState = max(rng, 1u);
    push.particleBuffer.particles[gl_VertexIndex].mode = mode;

    vec2 uv = (positionPx + vec2(0.5)) / vec2(max(dims.x, 1), max(dims.y, 1));
    vec2 clip = uv * 2.0 - 1.0;
    gl_Position = vec4(clip.x, -clip.y, 0.0, 1.0);
    gl_PointSize = push.pointSize;
    vColor = becameSolid
        ? vec4(push.stuckRed, 0.0, push.stuckBlue, 0.0)
        : vec4(0.0, push.walkerGreen, 0.0, 0.0);
}