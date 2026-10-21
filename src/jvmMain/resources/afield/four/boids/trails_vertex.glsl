#version 450
#extension GL_EXT_buffer_reference : require

// Draws every boid's trail: the last trailLength x kind.trailFactor positions of its history, as
// butt-ended quads between consecutive samples, in four bands, the oldest faintest (the page's
// drawTrails). A segment that jumps across a wrapped edge is skipped. trail_fragment.glsl
// antialiases the sides.

const int MAX_KINDS = 8;
const int MAX_RULES = 64;
const int MAX_ROCKS = 8;
const int TRAIL_CAP = 121;

struct Boid {
    vec2 pos;       // pixels, y down
    vec2 vel;       // pixels per step
    float heading;  // radians, the direction of vel
    float speed;    // |vel|
    float wander;   // wander()'s drifting turn
    float cool;     // steps left resting after eat()
    float r;        // 0..1, different for every boid, kept for life
    float age;      // steps since it was placed
    int kind;       // which kind, in the order the Kotlin declares them
    int n;          // flockmates of its own kind in view this step
    vec4 color;     // what color() returned, for the trails and the body
};

struct KindRecord {
    float maxSpeed;
    float minSpeed;
    float maxForce;
    float drag;
    float cosHalfFov;   // -2 for a full circle
    float queryR;       // the largest rule radius
    float size;
    float trailFactor;
    float trailAlpha;
    float trailWidth;
    int shape;
    int spawnCentre;
    int ruleStart;
    int ruleCount;
    int first;          // boids first .. first + count - 1 are this kind
    int count;
    vec4 reachA;        // squared reach to kinds 0..3
    vec4 reachB;        // and 4..7
};

struct RuleRecord {
    int type;
    float radius;
    float weight;
    uint among;         // bit k: the rule sees kind k
    int useFov;
    int _r0;
    int _r1;
    int _r2;
    vec4 p;             // per type: seek/orbit point (px), orbit radius and dir, jitter, rest, margin, speed
};

struct WorldRecord {
    vec2 size;
    vec2 mouse;
    int mouseInside;
    int wrapEdges;
    int kindCount;
    int boidCount;
    int rockCount;
    int gridCols;       // cells across the window
    int gridRows;
    int inspecting;
    float cellSize;
    float invCellSize;
    int gridCells;      // cells along a side of the grid's square
    int pickRequested;
    vec2 pickAt;
    int trailLength;
    uint seed;
    vec4 rocks[MAX_ROCKS];         // x, y, r in pixels
    KindRecord kinds[MAX_KINDS];
    RuleRecord rules[MAX_RULES];
};

layout(std430, buffer_reference) readonly buffer Boids { Boid b[]; };
layout(std430, buffer_reference) readonly buffer History { vec2 at[]; };
layout(std430, buffer_reference) readonly buffer World { WorldRecord w; };

layout(push_constant) uniform Push {
    Boids boids;
    History history;
    World world;
    vec2 targetSize;  // the accumulation, in device pixels
    uint count;
} push;

layout(location = 0) out float across;          // device pixels from the centre line
layout(location = 1) flat out float halfWidth;  // device pixels
layout(location = 2) flat out vec4 color;

void hide() {
    gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
    across = 0.0; halfWidth = 0.0; color = vec4(0.0);
}

void main() {
    uint segmentsPerBoid = uint(TRAIL_CAP - 1);
    uint q = uint(gl_VertexIndex) / 6u;
    uint i = q / segmentsPerBoid;
    int seg = int(q % segmentsPerBoid);
    if (i >= push.count) { hide(); return; }
    WorldRecord w = push.world.w;
    Boid b = push.boids.b[i];
    KindRecord k = w.kinds[b.kind];
    int len = min(TRAIL_CAP, int(round(float(w.trailLength) * k.trailFactor)) + 1);
    int n = min(int(b.age), len);
    int segs = n - 1;
    if (len < 2 || seg >= segs) { hide(); return; }

    // sample `seg` of the last n, oldest first; age is how many have been written
    uint base = i * uint(TRAIL_CAP);
    int first = int(b.age) - n;
    vec2 a = push.history.at[base + uint((first + seg) % TRAIL_CAP)];
    vec2 c = push.history.at[base + uint((first + seg + 1) % TRAIL_CAP)];
    if (abs(c.x - a.x) > w.size.x * 0.5 || abs(c.y - a.y) > w.size.y * 0.5) { hide(); return; }

    int band = 0;
    for (int bb = 0; bb < 4; bb++) {
        int k0 = (bb * segs) / 4;
        int k1 = ((bb + 1) * segs) / 4;
        if (seg >= k0 && seg < k1) band = bb;
    }

    vec2 scale = push.targetSize / w.size;
    vec2 pa = a * scale;
    vec2 pc = c * scale;
    vec2 along = pc - pa;
    float l = length(along);
    if (l < 1e-4) { hide(); return; }
    vec2 u = along / l;
    vec2 v = vec2(-u.y, u.x);
    float hw = max(k.trailWidth * scale.x, 0.0) * 0.5;
    float reach = max(hw, 0.5) + 1.0;
    const vec2 corner[6] = vec2[](vec2(0.0, -1.0), vec2(1.0, -1.0), vec2(1.0, 1.0),
                                  vec2(0.0, -1.0), vec2(1.0, 1.0), vec2(0.0, 1.0));
    vec2 cr = corner[gl_VertexIndex % 6];
    vec2 pixel = pa + u * (cr.x * l) + v * (cr.y * reach);
    across = cr.y * reach;
    halfWidth = hw;
    color = vec4(b.color.rgb, b.color.a * k.trailAlpha * float(band + 1) / 4.0);
    gl_Position = vec4(pixel / push.targetSize * 2.0 - 1.0, 0.0, 1.0);
}
