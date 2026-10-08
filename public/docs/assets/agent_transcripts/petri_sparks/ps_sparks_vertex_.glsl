#version 450
#extension GL_EXT_buffer_reference : require

// One vec4 per recent firing: where it launched, its age in seconds (negative for an
// empty slot), and which transition it was in the integer part of w.
layout(std430, buffer_reference) buffer Bursts {
    vec4 b[];
};

layout(location = 0) out vec2 tc;
layout(location = 1) out vec3 colour;

layout(push_constant) uniform push {
    Bursts bursts;
    float aspectRatio;
    float charge;
    float token0;
    float token1;
    float token2;
} pc;

const uint SLOTS = 24u;
const uint PER = 1400u;
const uint DUST = 2400u;
const float LIFE = 1.6;

const vec2[6] corner = {
    vec2(-1, -1), vec2(1, -1), vec2(1, 1),
    vec2(-1, -1), vec2(1, 1), vec2(-1, 1)
};

// kick, snare, hat, hat.2, clap
const vec3[5] tint = {
    vec3(1.00, 0.52, 0.18),
    vec3(0.30, 0.78, 1.00),
    vec3(1.00, 0.86, 0.40),
    vec3(0.55, 1.00, 0.55),
    vec3(1.00, 0.35, 0.85)
};

// snare ring, kick ring, hat ring, from the centre out
const float[3] ringRadius = { 0.36, 0.56, 0.76 };
const vec3[3] ringTint = { vec3(0.30, 0.78, 1.00), vec3(1.00, 0.52, 0.18), vec3(1.00, 0.86, 0.40) };

float hash(uint x) {
    x ^= x >> 16; x *= 0x7feb352du;
    x ^= x >> 15; x *= 0x846ca68bu;
    x ^= x >> 16;
    return float(x) / 4294967295.0;
}

void main() {
    uint p = uint(gl_VertexIndex) / 6u;
    vec2 c = corner[gl_VertexIndex % 6];
    vec2 centre = vec2(0.0);
    vec2 along = vec2(1.0, 0.0);
    float halfLength = 0.004;
    float halfWidth = 0.004;

    if (p < SLOTS * PER) {
        vec4 burst = pc.bursts.b[p / PER];
        uint j = p % PER;
        uint kind = uint(burst.w);
        uint seed = j * 2654435761u + uint(fract(burst.w) * 4096.0) * 40503u;
        float lifetime = LIFE * (0.35 + 0.65 * hash(seed + 2u));
        float age = burst.z;
        if (age < 0.0 || age > lifetime) {
            gl_Position = vec4(0.0);
            colour = vec3(0.0);
            tc = c;
            return;
        }
        // A clap is thrown hardest, a kick next, the snare and hats are lighter.
        float punch = kind == 4u ? 1.5 : kind == 0u ? 1.0 : 0.7;
        float a = hash(seed) * 6.2831853;
        float speed = punch * (0.15 + 0.85 * pow(hash(seed + 1u), 0.7));
        vec2 v = vec2(cos(a), sin(a)) * speed;
        float drag = 2.4;
        vec2 gravity = vec2(0.0, -0.45);
        centre = burst.xy + v * (1.0 - exp(-drag * age)) / drag + 0.5 * gravity * age * age;
        vec2 velocity = v * exp(-drag * age) + gravity * age;
        along = normalize(velocity + vec2(1e-5));
        halfLength = 0.003 + 0.045 * length(velocity);
        halfWidth = 0.0028;
        float fade = 1.0 - age / lifetime;
        float twinkle = 0.55 + 0.45 * sin(age * 47.0 + hash(seed + 3u) * 6.2831853);
        colour = mix(vec3(1.0), tint[kind], clamp(age * 3.0, 0.0, 1.0)) * fade * fade * twinkle * 0.9;
    } else {
        uint q = p - SLOTS * PER;
        uint ring = min(q / DUST, 2u);
        uint j = q % DUST;
        float angle = hash(j * 31u + ring * 7919u) * 6.2831853;
        float r = ringRadius[ring] + (hash(j * 13u + ring * 101u + 5u) - 0.5) * 0.018;
        centre = r * vec2(cos(angle), sin(angle));
        float token = ring == 0u ? pc.token0 : ring == 1u ? pc.token1 : pc.token2;
        // How far behind the token this mote is, going the way the token travels.
        float behind = mod(angle - token, 6.2831853);
        float comet = exp(-behind * 5.0);
        halfLength = halfWidth = 0.0035 + 0.006 * comet;
        colour = ringTint[ring] * (0.05 + 0.10 * pc.charge + 1.4 * comet);
    }

    vec2 side = vec2(-along.y, along.x);
    vec2 xy = centre + along * c.x * halfLength + side * c.y * halfWidth;
    gl_Position = vec4(xy.x / pc.aspectRatio, xy.y, 0.0, 1.0);
    tc = c;
}
