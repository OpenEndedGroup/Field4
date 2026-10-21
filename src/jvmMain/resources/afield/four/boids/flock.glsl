// boids/flock -- the harness behind a BoidSketch's compute shader.
//
// Include it on the line after #version and define how boids look:
//
//   vec4 color(Boid b)        their colour, sRGB with straight alpha: hsl(...), hex(0xff6b5a)
//
// and, when the flock's code uses them, declare and define the optional hooks:
//
//   #define FLOW_FIELD         before the include, for flow(weight):
//   float flowAngle(vec2 uv, float t)      the heading, in radians, at uv (0..1 across the window)
//
//   #define CUSTOM_RULE        before the include, for rule(radius, weight, ...):
//   void customStart(int rule, Boid me)                          before the neighbours
//   void customNeighbour(int rule, Boid me, Boid other, vec2 d, float dist)   each one in reach
//   vec2 customForce(int rule, Boid me)                          the force, or vec2(0.0)
//
// Everything else -- the kinds, their rules, speeds, fields of view and looks -- is in the flock's
// Kotlin, which writes it into the tables below. Units are the page's: pixels (window points),
// y down, speeds in pixels per step, t in steps. `rule` in the hooks is the rule's index among its
// kind's rules, in the order the Kotlin lists them; kinds are numbered in the order they are
// declared, from 0.
//
// One step, for every boid at once: walk the neighbours in reach (on the torus when edges wrap),
// feed each to every rule of the boid's kind that accepts it, turn each rule into a Reynolds
// steering force (desired velocity at full speed minus the current one, capped at maxForce), sum
// them by weight, then add the sum to the velocity, apply drag and the [minSpeed, maxSpeed] clamp,
// move, and keep to the window. Every other boid is read as it was at the top of the step.

#extension GL_EXT_buffer_reference : require
layout(local_size_x = 64) in;

// ---- the boid ----

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

// ---- the tables the Kotlin writes ----

const int MAX_KINDS = 8;
const int MAX_RULES = 64;
const int MAX_ROCKS = 8;
const int MAX_RULES_PER_KIND = 12;
const int TRAIL_CAP = 121;

const int RULE_SEPARATE = 0;
const int RULE_ALIGN = 1;
const int RULE_COHERE = 2;
const int RULE_FLEE = 3;
const int RULE_CHASE = 4;
const int RULE_EAT = 5;
const int RULE_WANDER = 6;
const int RULE_SEEK = 7;
const int RULE_ORBIT = 8;
const int RULE_FLOW = 9;
const int RULE_AVOID_ROCKS = 10;
const int RULE_AVOID_EDGES = 11;
const int RULE_MOUSE = 12;
const int RULE_CRUISE = 13;
const int RULE_CUSTOM = 14;

const int SHAPE_ARROW = 0;
const int SHAPE_KITE = 1;
const int SHAPE_DOT = 2;
const int SHAPE_LINE = 3;
const int SHAPE_NONE = 4;

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

struct Stroke {
    vec4 a;
    vec4 color;
    vec4 shape;
};

layout(std430, buffer_reference) buffer Boids { Boid b[]; };
layout(std430, buffer_reference) buffer History { vec2 at[]; };
layout(std430, buffer_reference) buffer Flags { uint v[]; };
layout(std430, buffer_reference) buffer Stats { int v[]; };
layout(std430, buffer_reference) buffer Strokes { uint count; uint _s0; uint _s1; uint _s2; Stroke s[]; };
layout(std430, buffer_reference) readonly buffer World { WorldRecord w; };
layout(std430, buffer_reference) readonly buffer GridUints { uint v[]; };

layout(push_constant) uniform FlockPush {
    Boids src;          // the boids at the top of the step
    Boids dst;          // where this step's boids go
    History history;    // TRAIL_CAP positions per boid
    Flags eaten;        // 0 alive, 1 eaten this step, 2 respawned this step
    Flags pick;         // 0: the bid for a click, 1: the inspected boid, 2: caught, 3: steps
    Stats stats;
    Strokes strokes;
    World world;
    GridUints gridValues;
    GridUints cellStart;
    GridUints cellEnd;
    uint phase;         // 0 reset, 1 frame, 2 step, 3 settle
    uint step;          // the step within this frame
    uint lastStep;      // non-zero on the frame's last step: colour, metrics and the inspector
    float t;            // steps since the flock started
} flock;

// ---- what a flock defines ----

vec4 color(Boid b);
#ifdef FLOW_FIELD
float flowAngle(vec2 uv, float t);
#endif
#ifdef CUSTOM_RULE
void customStart(int rule, Boid me);
void customNeighbour(int rule, Boid me, Boid other, vec2 d, float dist);
vec2 customForce(int rule, Boid me);
#endif

// ---- globals, the page's names ----

WorldRecord world;
float t;
vec2 size;
vec2 mouse;
bool mouseInside;

const float PI = 3.14159265358979;
const float TAU = 6.28318530717959;

// ---- helpers ----

uint fieldRandomState;

uint fieldHash(uint v) {
    uint state = v * 747796405u + 2891336453u;
    uint word = ((state >> ((state >> 28u) + 4u)) ^ state) * 277803737u;
    return (word >> 22u) ^ word;
}

// A uniform random number in [0, 1), different for every boid, call and step.
float random() {
    fieldRandomState = fieldHash(fieldRandomState);
    return float(fieldRandomState >> 8) * (1.0 / 16777216.0);
}

float deg(float radians) { return radians * 180.0 / PI; }
float lerp(float a, float b, float x) { return a + (b - a) * x; }

float fieldHash3(int i, int j, int k) {
    uint h = uint(i) * 374761393u + uint(j) * 668265263u + uint(k) * 1440662683u;
    h = (h ^ (h >> 13u)) * 1274126177u;
    h ^= h >> 16u;
    return float(h) / 4294967296.0;
}

float fieldSmooth(float x) { return x * x * (3.0 - 2.0 * x); }

// The page's noise(x, y, z): smooth value noise in [0, 1), the same field.
float noise(float x, float y, float z) {
    int xi = int(floor(x)), yi = int(floor(y)), zi = int(floor(z));
    float xf = fieldSmooth(x - floor(x)), yf = fieldSmooth(y - floor(y)), zf = fieldSmooth(z - floor(z));
    float c000 = fieldHash3(xi, yi, zi), c100 = fieldHash3(xi + 1, yi, zi);
    float c010 = fieldHash3(xi, yi + 1, zi), c110 = fieldHash3(xi + 1, yi + 1, zi);
    float c001 = fieldHash3(xi, yi, zi + 1), c101 = fieldHash3(xi + 1, yi, zi + 1);
    float c011 = fieldHash3(xi, yi + 1, zi + 1), c111 = fieldHash3(xi + 1, yi + 1, zi + 1);
    return mix(mix(mix(c000, c100, xf), mix(c010, c110, xf), yf),
               mix(mix(c001, c101, xf), mix(c011, c111, xf), yf), zf);
}

float noise(float x, float y) { return noise(x, y, 0.0); }

// CSS hsl(): hue in degrees, saturation and lightness in percent, alpha 0..1. sRGB.
vec4 hsl(float h, float s, float l, float a) {
    s = clamp(s / 100.0, 0.0, 1.0);
    l = clamp(l / 100.0, 0.0, 1.0);
    vec3 k = mod(vec3(0.0, 8.0, 4.0) + mod(h, 360.0) / 30.0, 12.0);
    vec3 rgb = l - s * min(l, 1.0 - l) * clamp(min(k - 3.0, 9.0 - k), -1.0, 1.0);
    return vec4(rgb, clamp(a, 0.0, 1.0));
}

vec4 hsl(float h, float s, float l) { return hsl(h, s, l, 1.0); }

// A CSS hex colour, hex(0xff6b5a) for '#ff6b5a'.
vec4 hex(uint rgb) {
    return vec4(float((rgb >> 16u) & 255u), float((rgb >> 8u) & 255u), float(rgb & 255u), 255.0) / 255.0;
}

KindRecord kindOf(Boid b) { return world.kinds[b.kind]; }

float fieldReachSq(KindRecord k, int other) {
    return other < 4 ? k.reachA[other] : k.reachB[other - 4];
}

// The offset from a to b, the short way round when the edges wrap.
vec2 fieldDelta(vec2 a, vec2 b) {
    vec2 d = b - a;
    if (world.wrapEdges != 0) {
        if (d.x > size.x * 0.5) d.x -= size.x; else if (d.x < -size.x * 0.5) d.x += size.x;
        if (d.y > size.y * 0.5) d.y -= size.y; else if (d.y < -size.y * 0.5) d.y += size.y;
    }
    return d;
}

// The page's steerInto: desired velocity along d at full speed, minus the current velocity,
// capped at maxForce. vec2(0.0) when d has no direction.
vec2 steer(Boid me, vec2 d) {
    float len = length(d);
    if (len < 1e-9) return vec2(0.0);
    KindRecord k = kindOf(me);
    vec2 f = d / len * k.maxSpeed - me.vel;
    float m = length(f);
    if (m > k.maxForce) f *= k.maxForce / m;
    return f;
}

// ---- drawing into the stroke list (the same records Particles draws) ----

void fieldStroke(vec4 a, vec4 color, float width, float radius, float kind, float layer) {
    if (color.a <= 0.0) return;
    uint i = atomicAdd(flock.strokes.count, 1u);
    if (i < 65536u) flock.strokes.s[i] = Stroke(a, color, vec4(width, radius, kind, layer));
}

void fieldLine(vec2 a, vec2 b, vec4 c, float w) { fieldStroke(vec4(a, b), c, w, 0.0, 0.0, 1.0); }

void fieldRing(vec2 at, float r, vec4 c, float w) { fieldStroke(vec4(at, at), c, w, r, 1.0, 1.0); }

void fieldDashedRing(vec2 at, float r, vec4 c, float w, float dash, float gap) {
    fieldStroke(vec4(at, dash, gap), c, w, r, 4.0, 1.0);
}

void fieldSector(vec2 at, float r, float centre, float halfAngle, vec4 c) {
    fieldStroke(vec4(at, centre, halfAngle), c, 0.0, r, 3.0, 1.0);
}

// ---- placing a boid: the page's placeBoid ----

bool fieldInRock(vec2 p) {
    for (int i = 0; i < world.rockCount; i++) {
        vec4 rk = world.rocks[i];
        vec2 d = p - rk.xy;
        if (dot(d, d) < (rk.z + 4.0) * (rk.z + 4.0)) return true;
    }
    return false;
}

void fieldPlace(inout Boid b, bool fromEdge) {
    KindRecord k = kindOf(b);
    float a = random() * TAU;
    float s = lerp(max(k.minSpeed, 0.3), k.maxSpeed, 0.5 + random() * 0.5);
    if (fromEdge) {
        if (random() < 0.5) { b.pos = vec2(random() < 0.5 ? 0.0 : size.x - 1.0, random() * size.y); }
        else { b.pos = vec2(random() * size.x, random() < 0.5 ? 0.0 : size.y - 1.0); }
    } else if (k.spawnCentre != 0) {
        float rr = sqrt(random()) * min(size.x, size.y) * 0.15;
        b.pos = size * 0.5 + vec2(cos(a), sin(a)) * rr;
    } else {
        b.pos = vec2(random(), random()) * size;
    }
    for (int tries = 0; tries < 20 && fieldInRock(b.pos); tries++) b.pos = vec2(random(), random()) * size;
    float va = random() * TAU;
    b.vel = vec2(cos(va), sin(va)) * s;
    b.heading = va;
    b.speed = s;
    b.wander = 0.0;
    b.cool = 0.0;
    b.age = 0.0;
    b.n = 0;
}

// ---- the rules: what each keeps while the neighbours go by ----

vec2 fieldSum[MAX_RULES_PER_KIND];
float fieldCount[MAX_RULES_PER_KIND];
vec4 fieldBest[MAX_RULES_PER_KIND];   // chase: dx, dy, d2, the neighbour's index; eat: the meal

bool fieldAccepts(RuleRecord rl, int otherKind, float d2, bool seen) {
    if (rl.radius <= 0.0 || d2 > rl.radius * rl.radius) return false;
    if (rl.useFov != 0 && !seen) return false;
    return ((rl.among >> uint(otherKind)) & 1u) != 0u;
}

void fieldVisit(int r, RuleRecord rl, Boid me, Boid o, uint j, vec2 d, float d2) {
    if (rl.type == RULE_SEPARATE) {
        fieldSum[r] -= d / max(d2, 0.25);
    } else if (rl.type == RULE_ALIGN) {
        fieldSum[r] += o.vel; fieldCount[r] += 1.0;
    } else if (rl.type == RULE_COHERE) {
        fieldSum[r] += d; fieldCount[r] += 1.0;
    } else if (rl.type == RULE_FLEE) {
        float dist = sqrt(d2);
        if (dist == 0.0) dist = 0.5;
        fieldSum[r] -= d / dist * (1.0 - dist / rl.radius); fieldCount[r] += 1.0;
    } else if (rl.type == RULE_CHASE) {
        if (d2 < fieldBest[r].z) fieldBest[r] = vec4(d, d2, float(j));
    } else if (rl.type == RULE_EAT) {
        if (fieldBest[r].w < 0.0) fieldBest[r].w = float(j);
#ifdef CUSTOM_RULE
    } else if (rl.type == RULE_CUSTOM) {
        customNeighbour(r, me, o, d, sqrt(d2));
#endif
    }
}

// The rule's force, after every neighbour has been seen.
vec2 fieldForce(int r, RuleRecord rl, inout Boid me, uint self) {
    KindRecord k = kindOf(me);
    if (rl.type == RULE_SEPARATE) return steer(me, fieldSum[r]);
    if (rl.type == RULE_ALIGN) return fieldCount[r] > 0.0 ? steer(me, fieldSum[r]) : vec2(0.0);
    if (rl.type == RULE_COHERE) return fieldCount[r] > 0.0 ? steer(me, fieldSum[r] / fieldCount[r]) : vec2(0.0);
    if (rl.type == RULE_FLEE) return fieldCount[r] > 0.0 ? steer(me, fieldSum[r]) : vec2(0.0);
    if (rl.type == RULE_CHASE) {
        if (me.cool > 0.0 || fieldBest[r].w < 0.0) return vec2(0.0);
        Boid target = flock.src.b[uint(fieldBest[r].w)];
        float lead = sqrt(fieldBest[r].z) / k.maxSpeed * 0.5;
        return steer(me, fieldBest[r].xy + target.vel * lead);
    }
    if (rl.type == RULE_EAT) {
        // A hawk cannot change a starling, so it marks it: one compare-and-swap, so one hawk wins
        // any starling, and the starling respawns at an edge on its next step.
        if (me.cool <= 0.0 && fieldBest[r].w >= 0.0) {
            if (atomicCompSwap(flock.eaten.v[uint(fieldBest[r].w)], 0u, 1u) == 0u) {
                me.cool = rl.p.x;
                atomicAdd(flock.pick.v[2], 1u);
            }
        }
        return vec2(0.0);
    }
    if (rl.type == RULE_WANDER) {
        me.wander = clamp(me.wander + (random() - 0.5) * rl.p.x, -1.2, 1.2);
        float a = me.heading + me.wander;
        return steer(me, vec2(cos(a), sin(a)));
    }
    if (rl.type == RULE_SEEK) return steer(me, fieldDelta(me.pos, rl.p.xy));
    if (rl.type == RULE_ORBIT) {
        vec2 rv = fieldDelta(rl.p.xy, me.pos);
        float dist = length(rv);
        if (dist == 0.0) dist = 1.0;
        vec2 u = rv / dist;
        float pull = clamp((rl.p.z - dist) / rl.p.z, -1.5, 1.5) * 1.6;
        float dir = rl.p.w;
        return steer(me, vec2(-u.y * dir + u.x * pull, u.x * dir + u.y * pull));
    }
    if (rl.type == RULE_FLOW) {
#ifdef FLOW_FIELD
        float a = flowAngle(me.pos / size, t);
        return steer(me, vec2(cos(a), sin(a)));
#else
        return vec2(0.0);
#endif
    }
    if (rl.type == RULE_AVOID_ROCKS) {
        vec2 s = vec2(0.0);
        bool any = false;
        float margin = rl.p.x;
        for (int i = 0; i < world.rockCount; i++) {
            vec4 rk = world.rocks[i];
            vec2 dv = me.pos - rk.xy;
            float dist = length(dv);
            if (dist == 0.0) dist = 1.0;
            float gap = dist - rk.z;
            if (gap > margin) continue;
            float w = 1.0 - max(0.0, gap) / margin;
            // push outward, plus sideways so they slide around rather than stall
            float side = (dv.x * me.vel.y - dv.y * me.vel.x) > 0.0 ? 1.0 : -1.0;
            s += (dv / dist + vec2(-dv.y, dv.x) / dist * side * 0.6) * w;
            any = true;
        }
        return any ? steer(me, s) : vec2(0.0);
    }
    if (rl.type == RULE_AVOID_EDGES) {
        float m = rl.p.x;
        vec2 s = vec2(0.0);
        if (me.pos.x < m) s.x += 1.0 - me.pos.x / m;
        if (me.pos.x > size.x - m) s.x -= 1.0 - (size.x - me.pos.x) / m;
        if (me.pos.y < m) s.y += 1.0 - me.pos.y / m;
        if (me.pos.y > size.y - m) s.y -= 1.0 - (size.y - me.pos.y) / m;
        return (s.x == 0.0 && s.y == 0.0) ? vec2(0.0) : steer(me, s);
    }
    if (rl.type == RULE_MOUSE) {
        if (!mouseInside) return vec2(0.0);
        vec2 dv = mouse - me.pos;
        float dist = length(dv);
        float radius = rl.p.x;
        if (dist > radius) return vec2(0.0);
        return steer(me, dv * rl.p.y) * (1.0 - dist / radius);
    }
    if (rl.type == RULE_CRUISE) {
        vec2 f = vec2(cos(me.heading), sin(me.heading)) * rl.p.x - me.vel;
        float m = length(f);
        if (m > k.maxForce) f *= k.maxForce / m;
        return f;
    }
#ifdef CUSTOM_RULE
    if (rl.type == RULE_CUSTOM) {
        vec2 f = customForce(r, me);
        return (any(isnan(f)) || any(isinf(f))) ? vec2(0.0) : f;
    }
#endif
    return vec2(0.0);
}

// ---- the walk ----

// The k-th column (or row) within `span` cells of `centre`, the page's neighbourSpan: every cell
// once when the span covers the grid, wrapped when the edges wrap, clipped when they do not.
// -1 skips a clipped cell; -2 is past the end.
int fieldSpan(int centre, int cells, int span, bool wrap, int k) {
    if (cells <= 2 * span + 1) return k < cells ? k : -2;
    if (k > 2 * span) return -2;
    int v = centre - span + k;
    if (wrap) return (v % cells + cells) % cells;
    return (v < 0 || v >= cells) ? -1 : v;
}

ivec2 fieldCellOf(vec2 p) {
    return clamp(ivec2(floor(p * world.invCellSize)), ivec2(0), ivec2(world.gridCols - 1, world.gridRows - 1));
}

void fieldStep(uint i) {
    Boid me = flock.src.b[i];

    // eaten last step: respawn at an edge, and stay off the menu for this step
    if (flock.eaten.v[i] == 1u) {
        fieldPlace(me, true);
        flock.eaten.v[i] = 2u;
        me.color = color(me);
        flock.dst.b[i] = me;
        return;
    }

    KindRecord k = kindOf(me);
    int ruleCount = min(k.ruleCount, MAX_RULES_PER_KIND);
    for (int r = 0; r < ruleCount; r++) {
        fieldSum[r] = vec2(0.0);
        fieldCount[r] = 0.0;
        fieldBest[r] = vec4(0.0, 0.0, 1e30, -1.0);
#ifdef CUSTOM_RULE
        if (world.rules[k.ruleStart + r].type == RULE_CUSTOM) customStart(r, me);
#endif
    }

    bool inspected = flock.lastStep != 0u && world.inspecting != 0 && i == flock.pick.v[1];
    vec2 h = vec2(cos(me.heading), sin(me.heading));
    int same = 0;
    if (k.queryR > 0.0) {
        bool wrap = world.wrapEdges != 0;
        ivec2 home = fieldCellOf(me.pos);
        // one more cell when wrapping: the last column and row can be narrower than a cell
        int spanX = int(ceil(k.queryR * world.invCellSize)) + (wrap ? 1 : 0);
        int spanY = spanX;
        for (int ky = 0; ky < 64; ky++) {
            int cy = fieldSpan(home.y, world.gridRows, spanY, wrap, ky);
            if (cy == -2) break;
            if (cy < 0) continue;
            for (int kx = 0; kx < 64; kx++) {
                int cx = fieldSpan(home.x, world.gridCols, spanX, wrap, kx);
                if (cx == -2) break;
                if (cx < 0) continue;
                uint cell = uint(cy * world.gridCells + cx);
                for (uint q = flock.cellStart.v[cell]; q < flock.cellEnd.v[cell]; ++q) {
                    uint j = flock.gridValues.v[q];
                    if (j == i || flock.eaten.v[j] != 0u) continue;
                    Boid o = flock.src.b[j];
                    vec2 d = fieldDelta(me.pos, o.pos);
                    float d2 = dot(d, d);
                    if (d2 >= fieldReachSq(k, o.kind)) continue;
                    bool seen = dot(d, h) >= k.cosHalfFov * sqrt(d2);
                    if (o.kind == me.kind && seen) same++;
                    if (inspected) {
                        vec4 c = !seen ? vec4(0.47, 0.47, 0.47, 0.25)
                               : o.kind == me.kind ? vec4(0.31, 0.98, 0.48, 0.55) : vec4(1.0, 0.47, 0.47, 0.6);
                        fieldLine(me.pos, me.pos + d, c, 1.0);
                    }
                    for (int r = 0; r < ruleCount; r++) {
                        RuleRecord rl = world.rules[k.ruleStart + r];
                        if (fieldAccepts(rl, o.kind, d2, seen)) fieldVisit(r, rl, me, o, j, d, d2);
                    }
                }
            }
        }
    }
    me.n = same;

    vec2 acc = vec2(0.0);
    for (int r = 0; r < ruleCount; r++) {
        RuleRecord rl = world.rules[k.ruleStart + r];
        vec2 f = fieldForce(r, rl, me, i);
        if (rl.weight != 0.0) acc += f * rl.weight;
    }

    if (inspected) {
        if (k.queryR > 0.0) {
            if (k.cosHalfFov < -1.5) fieldStroke(vec4(me.pos, me.pos), vec4(0.31, 0.98, 0.48, 0.06), 0.0, k.queryR, 2.0, 0.0);
            else fieldSector(me.pos, k.queryR, me.heading, acos(clamp(k.cosHalfFov, -1.0, 1.0)), vec4(0.31, 0.98, 0.48, 0.06));
        }
        for (int r = 0; r < ruleCount; r++) {
            float rr = world.rules[k.ruleStart + r].radius;
            bool shown = rr <= 0.0;
            for (int e = 0; e < r && !shown; e++) shown = world.rules[k.ruleStart + e].radius == rr;
            if (!shown) fieldDashedRing(me.pos, rr, vec4(1.0, 0.72, 0.42, 0.45), 1.0, 3.0, 4.0);
        }
        fieldLine(me.pos, me.pos + me.vel * 12.0, vec4(1.0), 1.5);
        fieldRing(me.pos, k.size + 4.0, vec4(1.0), 1.5);
    }

    // the page's integration: velocity, drag, the speed clamp, the move, the edges, the rocks
    me.vel += acc;
    if (k.drag != 0.0) me.vel *= 1.0 - k.drag;
    float sp = length(me.vel);
    if (sp > k.maxSpeed) { me.vel *= k.maxSpeed / sp; sp = k.maxSpeed; }
    else if (sp < k.minSpeed) {
        me.vel = sp < 1e-6 ? vec2(cos(me.heading), sin(me.heading)) * k.minSpeed : me.vel * (k.minSpeed / sp);
        sp = k.minSpeed;
    }
    me.pos += me.vel;
    if (world.wrapEdges != 0) {
        if (me.pos.x < 0.0) me.pos.x += size.x; else if (me.pos.x >= size.x) me.pos.x -= size.x;
        if (me.pos.y < 0.0) me.pos.y += size.y; else if (me.pos.y >= size.y) me.pos.y -= size.y;
    } else {
        if (me.pos.x < 0.0) { me.pos.x = -me.pos.x; me.vel.x = abs(me.vel.x); }
        else if (me.pos.x > size.x) { me.pos.x = 2.0 * size.x - me.pos.x; me.vel.x = -abs(me.vel.x); }
        if (me.pos.y < 0.0) { me.pos.y = -me.pos.y; me.vel.y = abs(me.vel.y); }
        else if (me.pos.y > size.y) { me.pos.y = 2.0 * size.y - me.pos.y; me.vel.y = -abs(me.vel.y); }
    }
    for (int ri = 0; ri < world.rockCount; ri++) {
        vec4 rk = world.rocks[ri];
        vec2 rv = me.pos - rk.xy;
        float dist = length(rv);
        if (dist < rk.z && dist > 1e-6) {
            me.pos = rk.xy + rv / dist * rk.z;
            float vn = dot(me.vel, rv) / dist;
            if (vn < 0.0) me.vel -= vn * rv / dist;
        }
    }
    if (sp > 0.05) me.heading = atan(me.vel.y, me.vel.x);
    me.speed = sp;
    if (me.cool > 0.0) me.cool -= 1.0;
    if (any(isnan(me.pos)) || any(isinf(me.pos))) fieldPlace(me, false);
    flock.history.at[i * uint(TRAIL_CAP) + uint(me.age) % uint(TRAIL_CAP)] = me.pos;
    me.age += 1.0;

    if (flock.lastStep != 0u) {
        me.color = color(me);
        // the metrics card: integer sums, so plain atomics add them up
        if (me.speed > 1e-6) {
            atomicAdd(flock.stats.v[1], int(round(me.vel.x / me.speed * 10000.0)));
            atomicAdd(flock.stats.v[2], int(round(me.vel.y / me.speed * 10000.0)));
        }
        atomicAdd(flock.stats.v[0], 1);
        atomicAdd(flock.stats.v[3], int(round(me.speed * 10000.0)));
        atomicAdd(flock.stats.v[4], me.n);
        atomicAdd(flock.stats.v[5 + me.kind], 1);
    }
    if (flock.step == 0u && world.pickRequested != 0) {
        vec2 dv = me.pos - world.pickAt;
        uint dq = uint(min(dot(dv, dv), 524287.0));
        atomicMin(flock.pick.v[0], (dq << 13u) | (i & 8191u));
    }
    flock.dst.b[i] = me;
}

void fieldFrame() {
    // a click from last frame picks the nearest boid to inspect
    uint bid = flock.pick.v[0];
    if (bid != 0xffffffffu && (bid & 8191u) < uint(world.boidCount)) flock.pick.v[1] = bid & 8191u;
    flock.pick.v[0] = 0xffffffffu;
    flock.strokes.count = 0u;
    for (int s = 0; s < 16; s++) flock.stats.v[s] = 0;
    for (int i = 0; i < world.rockCount; i++) {
        vec4 rk = world.rocks[i];
        // a shaded rock (kind 5), lit from the upper left, with a faint rim
        fieldStroke(vec4(rk.xy, rk.xy), vec4(22.0, 30.0, 32.0, 255.0) / 255.0, 0.0, rk.z, 5.0, 0.0);
        fieldStroke(vec4(rk.xy, rk.xy), vec4(140.0, 160.0, 150.0, 64.0) / 255.0, 1.0, rk.z, 1.0, 0.0);
    }
}

void main() {
    uint index = gl_GlobalInvocationID.x;
    world = flock.world.w;
    t = flock.t;
    size = world.size;
    mouse = world.mouse;
    mouseInside = world.mouseInside != 0;
    fieldRandomState = fieldHash(index ^ fieldHash(floatBitsToUint(flock.t) ^ fieldHash(world.seed + flock.phase * 7919u)));

    if (flock.phase == 1u) {
        if (index == 0u) fieldFrame();
        return;
    }
    if (index >= uint(world.boidCount)) return;
    if (flock.phase == 0u) {
        Boid b;
        b.kind = 0;
        for (int k = 0; k < world.kindCount; k++) {
            if (int(index) >= world.kinds[k].first) b.kind = k;
        }
        b.n = 0;
        fieldRandomState = fieldHash(index * 2654435761u ^ world.seed);
        b.r = random();
        fieldPlace(b, false);
        b.color = color(b);
        flock.src.b[index] = b;
        flock.dst.b[index] = b;
        flock.eaten.v[index] = 0u;
        // a fresh buffer holds whatever the memory held: no bid, boid 0 inspected, nothing caught
        if (index == 0u) { flock.pick.v[0] = 0xffffffffu; flock.pick.v[1] = 0u; flock.pick.v[2] = 0u; }
    } else if (flock.phase == 2u) {
        fieldStep(index);
    } else {
        if (flock.eaten.v[index] == 2u) flock.eaten.v[index] = 0u;
    }
}
