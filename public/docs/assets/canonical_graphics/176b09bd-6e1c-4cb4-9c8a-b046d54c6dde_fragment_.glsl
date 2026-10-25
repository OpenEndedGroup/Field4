#version 450

layout(set = 0, binding = 0) uniform sampler2D sourceTex;

layout(push_constant) uniform Push {
    int mode;
    float density;
    float eastboundFraction;
    int seed;
} push;

{% property "fieldProbe" %}

layout(location = 0) out vec4 outColor;

const vec4 EMPTY = vec4(0.0, 0.0, 0.0, 1.0);
const vec4 EAST = vec4(1.0, 0.0, 0.0, 1.0);
const vec4 SOUTH = vec4(0.0, 0.0, 1.0, 1.0);

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

// 0 empty, 1 eastbound, 2 southbound
int cellAt(ivec2 p, ivec2 dims) {
    vec4 c = texelFetch(sourceTex, (p + dims) % dims, 0);
    if (c.r > 0.5) return 1;
    if (c.b > 0.5) return 2;
    return 0;
}

vec4 colourOf(int cell) {
    return cell == 1 ? EAST : (cell == 2 ? SOUTH : EMPTY);
}

void main() {
    ivec2 dims = textureSize(sourceTex, 0);
    ivec2 p = ivec2(gl_FragCoord.xy);

    if (push.mode == 3) {
        uvec2 u = uvec2(p);
        if (random01(u, 1u) < push.density) {
            outColor = random01(u, 2u) < push.eastboundFraction ? EAST : SOUTH;
        }
        else {
            outColor = EMPTY;
        }
        return;
    }

    int here = cellAt(p, dims);
    if (push.mode == 2) {
        outColor = colourOf(here);
        return;
    }

    // even half-steps move eastbound cars +x, odd ones move southbound cars +y
    int mover = push.mode == 0 ? 1 : 2;
    ivec2 ahead = push.mode == 0 ? ivec2(1, 0) : ivec2(0, 1);

    int next = here;
    if (here == mover && cellAt(p + ahead, dims) == 0) {
        next = 0;
        fieldProbe(float(p.x + p.y));
        // a car moves out of this cell this half-step: one invocation per moving car,
        // which is where to tap it (fieldProbe, fieldStats) without counting it twice
    }
    else if (here == 0 && cellAt(p - ahead, dims) == mover) {
        next = mover;
    }

    outColor = colourOf(next);
}
