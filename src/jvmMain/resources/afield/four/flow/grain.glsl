#ifndef AFIELD_FLOW_GRAIN
#define AFIELD_FLOW_GRAIN

// The box supplies these two functions in WORLD coordinates. Mask > 0 is open.
vec2 flowVelocity(vec2 world);
float flowMask(vec2 world);

uint flowHash(uint x) {
    x ^= x >> 16; x *= 0x7feb352du;
    x ^= x >> 15; x *= 0x846ca68bu;
    return x ^ (x >> 16);
}
float flowRandom(ivec2 cell, int phase) {
    uint key = uint(cell.x) * 73856093u ^ uint(cell.y) * 19349663u ^ uint(phase) * 83492791u;
    return float(flowHash(key)) / 4294967295.;
}
float flowNoise(vec2 p, int phase) {
    ivec2 cell = ivec2(floor(p));
    vec2 f = fract(p);
    f = f * f * (3. - 2. * f);
    float n = mix(mix(flowRandom(cell, phase), flowRandom(cell + ivec2(1, 0), phase), f.x),
                  mix(flowRandom(cell + ivec2(0, 1), phase), flowRandom(cell + ivec2(1), phase), f.x), f.y);
    return n * n * n;
}

// Footprints select fixed world grids; changing zoom must never scale noise about (0,0).
vec4 flowNoiseScales(float pixelWorld, float worldCell) {
    float lod = max(log2(max(pixelWorld * 2. / max(worldCell, 1e-6), 1.)), 0.);
    float octave = floor(lod);
    float cell = max(worldCell, 1e-6) * exp2(octave);
    return vec4(cell, cell * 2., smoothstep(0., 1., fract(lod)), octave);
}
float flowFilteredNoise(vec2 world, int phase, vec4 scales) {
    float a = flowNoise(world / scales.x, phase + int(scales.w) * 1009);
    float b = flowNoise(world / scales.y, phase + (int(scales.w) + 1) * 1009);
    return mix(a, b, scales.z);
}
float flowAdvectedNoise(vec2 world, vec2 velocity, vec4 scales, float time, float period) {
    period = max(period, 1e-4);
    float cycle = time / period;
    float a = fract(cycle), b = fract(cycle + .5);
    float weight = .5 - .5 * cos(a * 6.28318530718);
    return weight * flowFilteredNoise(world - velocity * a * period, int(floor(cycle)), scales)
         + (1. - weight) * flowFilteredNoise(world - velocity * b * period, int(floor(cycle + .5)) + 173, scales);
}

// Short symmetric streamline convolution. Integrate NOISE, then light it locally:
// gathering radiance here would soften shadows through opaque walls.
float flowGrain(vec2 world, vec2 imageVelocity, float pixelWorld, float worldCell,
                float strokeSeconds, float maxWorldLength, float time, float period) {
    if (flowMask(world) <= 0.) return 0.;
    vec4 scales = flowNoiseScales(pixelWorld, worldCell);
    vec2 velocity = flowVelocity(world);
    float extent = min(max(length(velocity + imageVelocity) * strokeSeconds, scales.x * 2.), maxWorldLength);
    float sum = flowAdvectedNoise(world, velocity, scales, time, period);
    float total = 1.;
    for (int side = -1; side <= 1; side += 2) {
        vec2 p = world;
        for (int k = 1; k <= 6; ++k) {
            vec2 v = flowVelocity(p) + imageVelocity;
            float speed = length(v);
            vec2 direction = speed > 1e-5 ? v / speed : vec2(0., -1.);
            p += float(side) * direction * extent / 6.;
            if (flowMask(p) <= 0.) break;
            float w = .5 + .5 * cos(float(k) / 7. * 3.14159265359);
            sum += w * flowAdvectedNoise(p, flowVelocity(p), scales, time, period);
            total += w;
        }
    }
    return sum / total;
}
#endif
