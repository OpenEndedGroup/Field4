#version 450
#extension GL_EXT_buffer_reference : require

// Draws every boid's body as a quad facing along its heading; body_fragment.glsl cuts the shape
// (arrow, kite, dot or line, the page's drawBodies) out of it by signed distance, antialiased.

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
layout(std430, buffer_reference) readonly buffer World { WorldRecord w; };

layout(push_constant) uniform Push {
    Boids boids;
    World world;
    vec2 targetSize;
    uint count;
} push;

layout(location = 0) out vec2 local;         // device pixels, u along the heading, v across
layout(location = 1) flat out vec4 shape;    // shape, size (device pixels), line half width, -
layout(location = 2) flat out vec4 color;

void main() {
    uint i = uint(gl_VertexIndex) / 6u;
    Boid b = push.boids.b[min(i, max(push.count, 1u) - 1u)];
    WorldRecord w = push.world.w;
    KindRecord k = w.kinds[b.kind];
    if (i >= push.count || k.shape == 4 || any(isnan(b.pos))) {
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        local = vec2(0.0); shape = vec4(0.0); color = vec4(0.0);
        return;
    }
    vec2 scale = push.targetSize / w.size;
    float s = k.size * scale.x;
    float lineHalf = max(1.0, k.size * 0.35) * scale.x * 0.5;
    float reach = (k.shape == 2 ? s : s * 1.8) + lineHalf + 1.5;
    const vec2 corner[6] = vec2[](vec2(-1.0, -1.0), vec2(1.0, -1.0), vec2(1.0, 1.0),
                                  vec2(-1.0, -1.0), vec2(1.0, 1.0), vec2(-1.0, 1.0));
    vec2 l = corner[gl_VertexIndex % 6] * reach;
    vec2 h = vec2(cos(b.heading), sin(b.heading));
    vec2 p = vec2(-h.y, h.x);
    vec2 pixel = b.pos * scale + h * l.x + p * l.y;
    local = l;
    shape = vec4(float(k.shape), s, lineHalf, 0.0);
    color = b.color;
    gl_Position = vec4(pixel / push.targetSize * 2.0 - 1.0, 0.0, 1.0);
}
