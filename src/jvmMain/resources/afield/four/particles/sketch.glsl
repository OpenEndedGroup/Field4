// particles/sketch -- the harness behind a ParticleSketch's compute shader.
//
// Include it on the line after #version, then define the four hooks declared below. They are the
// madp Particles hooks, run on the GPU, one invocation per particle:
//
//   void spawn(vec2 at)                       a particle is painted at `at`: set up p
//   void eachPair(Particle other, Pair pair)  p and one neighbour within world.pairRadius
//   void update()                             p's own behaviour, once a frame (required work)
//   void draw()                               overlays, once a frame, by a single invocation
//
// `p` is the particle being worked on, a global, so the verbs below act on it the way madp's
// helpers act on the particle being updated: force(vec2(0.0, 40.0)) pushes p down.
//
// Coordinates are the canvas's: pixels (window points), origin top left, y down; `width` and
// `height` are the window's size. Colours are CSS colours in sRGB with straight alpha, blended the
// way a 2D canvas blends them: hsla(200.0, 90.0, 70.0, 0.9), rgba(120.0, 255.0, 210.0, 0.12).
//
// The frame, in madp's order:
//   spawn   queued paint positions become particles; the stroke list is cleared and draw()
//           runs (unless paused)
//   hash    the neighbour grid is rebuilt from every particle's position (72-pixel cells)
//   step    for each particle: acc = world.gravity, eachPair with every neighbour within
//           world.pairRadius, update(), then vel += acc*dt, vel *= damping, limit to maxSpeed,
//           pos += vel*dt
//   draw    the picture fades by 1 - trail, then links, dots and overlays are drawn into it
//
// Every other particle is read as it was at the top of the frame (a snapshot): only p changes.
// eachPair therefore sees each pair twice, once from each side, and each side applies its own
// half -- spring() and collide() are written that way, so the totals are madp's.

#extension GL_EXT_buffer_reference : require
layout(local_size_x = 64) in;

// ---- the particle ----

struct Particle {
    vec2 pos;       // pixels, y down
    vec2 vel;       // pixels per second
    vec2 acc;       // this frame's acceleration, cleared to world.gravity at the top of the frame
    vec2 home;      // where it was painted
    vec4 color;     // sRGB, straight alpha
    float radius;   // pixels
    float mass;     // forces divide by it
    float seed;     // 0..1000, different for every particle
    float age;      // seconds since it was painted
    int group;      // free: madp's presets use it for teams
    uint id;        // 1, 2, 3, ... in the order particles were painted
    float _reserved0;
    float _reserved1;
    vec4 data;      // free: anything else a sketch wants to remember
};

// What eachPair is told about a pair: d points from p to other.
struct Pair {
    vec2 d;
    float dist2;
    float dist;
    uint index;     // other's index, for particle(index) and comparisons
};

// One line, ring or disc in the stroke list. kind: 0 line a.xy -> a.zw, 1 ring, 2 disc
// (centre a.xy); shape = (width, radius, kind, layer). Layer 0 is drawn under the dots, 1 over.
struct Stroke {
    vec4 a;
    vec4 color;
    vec4 shape;
};

struct WorldState {
    vec4 particleColor;   // the default colour, sRGB straight alpha
    vec2 size;            // width, height
    vec2 mouse;           // the pointer, in the same pixels
    vec2 gravity;         // pixels per second per second
    vec2 gridMin;
    float time;           // seconds since the sketch started
    float dt;             // seconds this frame
    float damping;        // velocity kept per frame, after the forces
    float maxSpeed;       // pixels per second
    float pairRadius;     // eachPair's reach; 0 runs no pairs
    float particleRadius; // the default radius
    float invCellSize;
    int gridCells;
    int mouseInside;
    int mouseDown;
    uint count;           // particles alive this frame
    uint capacity;        // particles there is room for; painting past it replaces the oldest
    uint spawnBase;       // the slot the first particle painted this frame goes into
    uint spawnCount;      // particles painted this frame
    uint spawnId;         // the id the first of them gets
    uint strokeCapacity;
    uint frame;
    uint seed;
};

layout(std430, buffer_reference) buffer Particles { Particle p[]; };
layout(std430, buffer_reference) buffer Strokes { uint count; uint _s0; uint _s1; uint _s2; Stroke s[]; };
layout(std430, buffer_reference) readonly buffer Spawns { vec2 at[]; };
layout(std430, buffer_reference) readonly buffer World { WorldState w; };
layout(std430, buffer_reference) readonly buffer GridUints { uint v[]; };

layout(push_constant) uniform SketchPush {
    Particles src;        // the particles at the top of the frame (a snapshot)
    Particles dst;        // where this frame's particles are written
    Strokes strokes;
    Spawns spawns;
    World world;
    GridUints gridValues;
    GridUints cellStart;
    GridUints cellEnd;
    uint phase;           // 0 spawn (and draw), 1 step
} sketch;

// ---- what a sketch defines ----

void spawn(vec2 at);
void eachPair(Particle other, Pair pair);
void update();
void draw();

// ---- globals, the madp names ----

Particle p;
uint selfIndex;
WorldState world;
float t;
float dt;
float width;
float height;
vec2 mouse;
bool mouseInside;
bool mouseDown;

const float PI = 3.14159265358979;
const float TAU = 6.28318530717959;

// ---- helpers: numbers ----

uint fieldRandomState;

uint fieldHash(uint v) {
    uint state = v * 747796405u + 2891336453u;
    uint word = ((state >> ((state >> 28u) + 4u)) ^ state) * 277803737u;
    return (word >> 22u) ^ word;
}

// A uniform random number in [0, 1), different for every particle, call and frame.
float random() {
    fieldRandomState = fieldHash(fieldRandomState);
    return float(fieldRandomState >> 8) * (1.0 / 16777216.0);
}

// madp's rand(min, max): uniform in [min, max).
float rand(float lo, float hi) {
    return lo + random() * (hi - lo);
}

// madp's randInt(min, max): an integer from min to max, both included.
int randInt(int lo, int hi) {
    return int(floor(rand(float(lo), float(hi + 1))));
}

float lerp(float a, float b, float x) {
    return a + (b - a) * x;
}

// A triangle wave between -1 and 1 with period 1, 1 at whole numbers.
float triWave(float x) {
    float f = x - floor(x);
    return 1.0 - 4.0 * abs(f - 0.5);
}

// triWave between 0 and 1.
float tri01(float x) {
    return 0.5 + 0.5 * triWave(x);
}

float fieldPerlinFade(float x) { return x * x * x * (x * (x * 6.0 - 15.0) + 10.0); }

uint fieldPerm(int i) { return fieldHash(uint(i & 511) & 255u ^ 0x5bd1u) & 255u; }

float fieldPerlinGrad(uint hash, float x, float y) {
    uint h = hash & 3u;
    float u = h < 2u ? x : y;
    float v = h < 2u ? y : x;
    return ((h & 1u) != 0u ? -u : u) + ((h & 2u) != 0u ? -v : v);
}

// madp's noise2D: classic 2D Perlin noise, about -1..1, smooth at the scale of 1.
float noise2D(float x, float y) {
    int xi = int(floor(x)) & 255;
    int yi = int(floor(y)) & 255;
    float xf = x - floor(x);
    float yf = y - floor(y);
    float u = fieldPerlinFade(xf);
    float v = fieldPerlinFade(yf);
    uint aa = fieldPerm(int(fieldPerm(xi)) + yi);
    uint ab = fieldPerm(int(fieldPerm(xi)) + yi + 1);
    uint ba = fieldPerm(int(fieldPerm(xi + 1)) + yi);
    uint bb = fieldPerm(int(fieldPerm(xi + 1)) + yi + 1);
    return lerp(
        lerp(fieldPerlinGrad(aa, xf, yf), fieldPerlinGrad(ba, xf - 1.0, yf), u),
        lerp(fieldPerlinGrad(ab, xf, yf - 1.0), fieldPerlinGrad(bb, xf - 1.0, yf - 1.0), u),
        v
    );
}

// ---- helpers: colour (sRGB, straight alpha, as CSS writes it) ----

// CSS hsla(): hue in degrees, saturation and lightness in percent, alpha 0..1.
vec4 hsla(float h, float s, float l, float a) {
    s = clamp(s / 100.0, 0.0, 1.0);
    l = clamp(l / 100.0, 0.0, 1.0);
    vec3 k = mod(vec3(0.0, 8.0, 4.0) + h / 30.0, 12.0);
    vec3 rgb = l - s * min(l, 1.0 - l) * clamp(min(k - 3.0, 9.0 - k), -1.0, 1.0);
    return vec4(rgb, clamp(a, 0.0, 1.0));
}

// CSS rgba(): r, g, b 0..255, alpha 0..1.
vec4 rgba(float r, float g, float b, float a) {
    return vec4(vec3(r, g, b) / 255.0, clamp(a, 0.0, 1.0));
}

// ---- reading other particles ----

// Particle i as it was at the top of the frame.
Particle particle(uint i) {
    return sketch.src.p[i];
}

// How many grid cells out from p's own a search of radius r has to look.
int fieldReach(float r) {
    return max(1, int(ceil(r * world.invCellSize)));
}

ivec2 fieldCellOf(vec2 at) {
    return clamp(ivec2(floor((at - world.gridMin) * world.invCellSize)), ivec2(0), ivec2(world.gridCells - 1));
}

// The neighbour walk every query below uses, written out once here for reading:
//
//   ivec2 home = fieldCellOf(p.pos);
//   int reach = fieldReach(radius);
//   for (int cy = max(home.y - reach, 0); cy <= min(home.y + reach, world.gridCells - 1); ++cy)
//   for (int cx = max(home.x - reach, 0); cx <= min(home.x + reach, world.gridCells - 1); ++cx) {
//       uint cell = uint(cy * world.gridCells + cx);
//       for (uint k = sketch.cellStart.v[cell]; k < sketch.cellEnd.v[cell]; ++k) {
//           uint j = sketch.gridValues.v[k];          // a particle in that cell
//           if (j == selfIndex) continue;
//           vec2 d = sketch.src.p[j].pos - p.pos;    // within radius when dot(d, d) <= radius^2
//       }
//   }

// madp's neighbors(radius).length: how many other particles are within radius of p.
int neighbors(float radius) {
    int n = 0;
    float r2 = radius * radius;
    ivec2 home = fieldCellOf(p.pos);
    int reach = fieldReach(radius);
    for (int cy = max(home.y - reach, 0); cy <= min(home.y + reach, world.gridCells - 1); ++cy)
    for (int cx = max(home.x - reach, 0); cx <= min(home.x + reach, world.gridCells - 1); ++cx) {
        uint cell = uint(cy * world.gridCells + cx);
        for (uint k = sketch.cellStart.v[cell]; k < sketch.cellEnd.v[cell]; ++k) {
            uint j = sketch.gridValues.v[k];
            if (j == selfIndex) continue;
            vec2 d = sketch.src.p[j].pos - p.pos;
            if (dot(d, d) <= r2) n++;
        }
    }
    return n;
}

// madp's nearest(radius): the index of the closest other particle within radius, or -1.
int nearest(float radius) {
    int best = -1;
    float bestD2 = radius * radius;
    ivec2 home = fieldCellOf(p.pos);
    int reach = fieldReach(radius);
    for (int cy = max(home.y - reach, 0); cy <= min(home.y + reach, world.gridCells - 1); ++cy)
    for (int cx = max(home.x - reach, 0); cx <= min(home.x + reach, world.gridCells - 1); ++cx) {
        uint cell = uint(cy * world.gridCells + cx);
        for (uint k = sketch.cellStart.v[cell]; k < sketch.cellEnd.v[cell]; ++k) {
            uint j = sketch.gridValues.v[k];
            if (j == selfIndex) continue;
            vec2 d = sketch.src.p[j].pos - p.pos;
            float d2 = dot(d, d);
            if (d2 <= bestD2) { bestD2 = d2; best = int(j); }
        }
    }
    return best;
}

// ---- forces and integration ----

// Push p by f (pixels/s^2 for a mass of 1): acceleration += f / mass.
void force(vec2 f) {
    p.acc += f / p.mass;
}

// Kick p's velocity by i / mass, now.
void impulse(vec2 i) {
    p.vel += i / p.mass;
}

// Lose a fraction of p's velocity this frame: drag(0.02) keeps 98%.
void drag(float amount) {
    p.vel *= max(0.0, 1.0 - amount);
}

// Hold p's speed at or under maxSpeed (pixels per second).
void limitSpeed(float maxSpeed) {
    if (!(maxSpeed > 0.0)) return;
    float speed = length(p.vel);
    if (speed > maxSpeed) p.vel *= maxSpeed / speed;
}

// Bounce p off the window's edges, keeping `restitution` of its speed; pad moves the walls in.
void bounce(float restitution, float pad) {
    vec2 next = p.pos + p.vel * dt;
    vec2 lo = vec2(p.radius + pad);
    vec2 hi = world.size - vec2(p.radius + pad);
    if (next.x < lo.x) { p.pos.x = lo.x; p.vel.x = abs(p.vel.x) * restitution; }
    else if (next.x > hi.x) { p.pos.x = hi.x; p.vel.x = -abs(p.vel.x) * restitution; }
    if (next.y < lo.y) { p.pos.y = lo.y; p.vel.y = abs(p.vel.y) * restitution; }
    else if (next.y > hi.y) { p.pos.y = hi.y; p.vel.y = -abs(p.vel.y) * restitution; }
}

void bounce(float restitution) {
    bounce(restitution, 0.0);
}

// Leave one edge, come back in at the opposite one; pad lets p go that far out first.
void wrap(float pad) {
    if (p.pos.x < -pad) p.pos.x = width + pad;
    if (p.pos.x > width + pad) p.pos.x = -pad;
    if (p.pos.y < -pad) p.pos.y = height + pad;
    if (p.pos.y > height + pad) p.pos.y = -pad;
}

// ---- steering ----

// A force of `strength` toward target (negative pushes away).
void attract(vec2 target, float strength) {
    vec2 d = target - p.pos;
    float len = length(d);
    if (len == 0.0) len = 1.0;
    force(d / len * strength);
}

// A damped spring from p to a fixed point, wanting to be restLength long.
void springTo(vec2 target, float restLength, float stiffness, float damping) {
    vec2 d = target - p.pos;
    float dist = length(d);
    if (dist == 0.0) dist = 0.0001;
    vec2 n = d / dist;
    float stretch = dist - restLength;
    float relVel = dot(-p.vel, n);
    force(n * (stretch * stiffness + relVel * damping));
}

// A force along axis that swings as a sine: amplitude * sin((t * frequency + phase) * 2 pi).
void oscillate(vec2 axis, float amplitude, float frequency, float phase) {
    float len = length(axis);
    vec2 n = len > 0.0 ? axis / len : vec2(0.0, 1.0);
    float wave = sin((t * frequency + phase) * TAU);
    force(n * wave * amplitude);
}

// madp's clump: pull toward the centre of the neighbours within radius with `strength`, and push
// away from any closer than repelRadius with repelStrength. Returns how many neighbours there were.
int clump(float radius, float strength, float repelRadius, float repelStrength) {
    int n = 0;
    vec2 centre = vec2(0.0);
    vec2 repel = vec2(0.0);
    float r2 = radius * radius;
    ivec2 home = fieldCellOf(p.pos);
    int reach = fieldReach(radius);
    for (int cy = max(home.y - reach, 0); cy <= min(home.y + reach, world.gridCells - 1); ++cy)
    for (int cx = max(home.x - reach, 0); cx <= min(home.x + reach, world.gridCells - 1); ++cx) {
        uint cell = uint(cy * world.gridCells + cx);
        for (uint k = sketch.cellStart.v[cell]; k < sketch.cellEnd.v[cell]; ++k) {
            uint j = sketch.gridValues.v[k];
            if (j == selfIndex) continue;
            vec2 at = sketch.src.p[j].pos;
            vec2 d = at - p.pos;
            float d2 = dot(d, d);
            if (d2 > r2) continue;
            float dist = sqrt(d2);
            centre += at;
            if (dist < repelRadius && dist > 0.0) repel -= d / dist * repelStrength;
            n++;
        }
    }
    if (n == 0) return 0;
    force(repel);
    attract(centre / float(n), strength);
    return n;
}

// Reynolds flocking over the neighbours within radius, as madp writes it: separation sums
// (p - n) / dist^2, alignment matches their mean velocity, cohesion pulls toward their centre;
// the total is held under maxForce. Returns how many neighbours there were.
int flock(float radius, float separation, float alignment, float cohesion, float maxForce) {
    int n = 0;
    vec2 sep = vec2(0.0);
    vec2 ali = vec2(0.0);
    vec2 coh = vec2(0.0);
    float r2 = radius * radius;
    ivec2 home = fieldCellOf(p.pos);
    int reach = fieldReach(radius);
    for (int cy = max(home.y - reach, 0); cy <= min(home.y + reach, world.gridCells - 1); ++cy)
    for (int cx = max(home.x - reach, 0); cx <= min(home.x + reach, world.gridCells - 1); ++cx) {
        uint cell = uint(cy * world.gridCells + cx);
        for (uint k = sketch.cellStart.v[cell]; k < sketch.cellEnd.v[cell]; ++k) {
            uint j = sketch.gridValues.v[k];
            if (j == selfIndex) continue;
            vec2 at = sketch.src.p[j].pos;
            vec2 d = at - p.pos;
            float d2 = dot(d, d);
            if (d2 > r2) continue;
            float dist = sqrt(d2);
            if (dist == 0.0) dist = 1.0;
            sep -= d / (dist * dist);
            ali += sketch.src.p[j].vel;
            coh += at;
            n++;
        }
    }
    if (n == 0) return 0;
    ali /= float(n);
    coh /= float(n);
    vec2 f = sep * separation + (ali - p.vel) * alignment + (coh - p.pos) * cohesion * 0.12;
    float len = length(f);
    if (len > maxForce) f = f / len * maxForce;
    force(f);
    return n;
}

// ---- pairs: call these from eachPair(other, pair) ----

// A damped spring between p and other, wanting to be restLength long. Applies p's half; other
// applies the opposite half when its own eachPair sees p.
void spring(Particle other, Pair pair, float restLength, float stiffness, float damping) {
    float dist = pair.dist == 0.0 ? 0.0001 : pair.dist;
    vec2 n = pair.d / dist;
    float stretch = dist - restLength;
    float relVel = dot(other.vel - p.vel, n);
    force(n * (stretch * stiffness + relVel * damping));
}

// Two balls that touch push apart (weighted by mass) and bounce, keeping `restitution` of their
// approach speed; padding adds to the radii. Applies p's half, as spring does.
void collide(Particle other, Pair pair, float restitution, float padding) {
    float dist = pair.dist == 0.0 ? 0.0001 : pair.dist;
    float minDist = p.radius + other.radius + padding;
    if (dist >= minDist) return;
    vec2 n = pair.d / dist;
    float overlap = minDist - dist;
    float total = p.mass + other.mass;
    p.pos -= n * overlap * (other.mass / total);
    float sepVel = dot(other.vel - p.vel, n);
    if (sepVel > 0.0) return;
    float j = -(1.0 + restitution) * sepVel / (1.0 / p.mass + 1.0 / other.mass);
    p.vel -= j * n / p.mass;
}

void collide(Particle other, Pair pair, float restitution) {
    collide(other, pair, restitution, 0.0);
}

// ---- drawing ----

void fieldStroke(vec4 a, vec4 color, float width, float radius, float kind, float layer) {
    if (color.a <= 0.0) return;
    uint i = atomicAdd(sketch.strokes.count, 1u);
    if (i < world.strokeCapacity) sketch.strokes.s[i] = Stroke(a, color, vec4(width, radius, kind, layer));
}

// A thread from p to other, under the dots: madp's link(a, b, {color, width}). eachPair sees a
// pair from both sides, so it is drawn only from the side with the smaller id.
void link(Particle other, vec4 color, float width) {
    if (p.id < other.id) fieldStroke(vec4(p.pos, other.pos), color, width, 0.0, 0.0, 0.0);
}

// Overlays, over the dots: madp's overlay.line / ring / circle / cross. Call them from draw()
// (once a frame) or from update() (once per particle).
void line(vec2 a, vec2 b, vec4 stroke, float width) {
    fieldStroke(vec4(a, b), stroke, width, 0.0, 0.0, 1.0);
}

void ring(vec2 centre, float radius, vec4 stroke, float width) {
    fieldStroke(vec4(centre, centre), stroke, width, radius, 1.0, 1.0);
}

void circle(vec2 centre, float radius, vec4 fill) {
    fieldStroke(vec4(centre, centre), fill, 0.0, radius, 2.0, 1.0);
}

void cross(vec2 centre, float size, vec4 stroke, float width) {
    line(centre - vec2(size, 0.0), centre + vec2(size, 0.0), stroke, width);
    line(centre - vec2(0.0, size), centre + vec2(0.0, size), stroke, width);
}

// ---- the harness ----

void fieldGlobals() {
    world = sketch.world.w;
    t = world.time;
    dt = world.dt;
    width = world.size.x;
    height = world.size.y;
    mouse = world.mouse;
    mouseInside = world.mouseInside != 0;
    mouseDown = world.mouseDown != 0;
}

void fieldSpawnPhase(uint index) {
    // Paused (dt is 0), the stroke list is left as the last step made it, so links and overlays
    // keep drawing over particles that are holding still, as madp's do.
    if (index == 0u && world.dt > 0.0) {
        sketch.strokes.count = 0u;
        p = Particle(vec2(0.0), vec2(0.0), vec2(0.0), vec2(0.0), world.particleColor, world.particleRadius,
                     1.0, 0.0, 0.0, 0, 0u, 0.0, 0.0, vec4(0.0));
        selfIndex = 0xffffffffu;
        draw();
    }
    if (index >= world.spawnCount) return;
    vec2 at = sketch.spawns.at[index];
    p = Particle(at, vec2(0.0), vec2(0.0), at, world.particleColor, world.particleRadius,
                 1.0, random() * 1000.0, 0.0, 0, world.spawnId + index, 0.0, 0.0, vec4(0.0));
    selfIndex = (world.spawnBase + index) % world.capacity;
    spawn(at);
    if (!(p.mass > 0.0)) p.mass = 1.0;
    sketch.src.p[selfIndex] = p;
}

void fieldStepPhase(uint index) {
    if (index >= world.count) return;
    selfIndex = index;
    p = sketch.src.p[index];
    p.acc = world.gravity;

    if (world.pairRadius > 0.0) {
        float r2 = world.pairRadius * world.pairRadius;
        ivec2 home = fieldCellOf(p.pos);
        int reach = fieldReach(world.pairRadius);
        for (int cy = max(home.y - reach, 0); cy <= min(home.y + reach, world.gridCells - 1); ++cy)
        for (int cx = max(home.x - reach, 0); cx <= min(home.x + reach, world.gridCells - 1); ++cx) {
            uint cell = uint(cy * world.gridCells + cx);
            for (uint k = sketch.cellStart.v[cell]; k < sketch.cellEnd.v[cell]; ++k) {
                uint j = sketch.gridValues.v[k];
                if (j == index) continue;
                Particle other = sketch.src.p[j];
                vec2 d = other.pos - p.pos;
                float d2 = dot(d, d);
                if (d2 > r2) continue;
                eachPair(other, Pair(d, d2, sqrt(d2), j));
            }
        }
    }

    update();

    // madp's integrateWorld: semi-implicit Euler, then damping and the world's speed limit
    p.age += dt;
    p.vel += p.acc * dt;
    p.vel *= world.damping;
    limitSpeed(world.maxSpeed);
    p.pos += p.vel * dt;
    if (any(isnan(p.pos)) || any(isinf(p.pos))) { p.pos = p.home; p.vel = vec2(0.0); }
    sketch.dst.p[index] = p;
}

void main() {
    uint index = gl_GlobalInvocationID.x;
    fieldGlobals();
    fieldRandomState = fieldHash(index ^ fieldHash(world.frame ^ fieldHash(world.seed + sketch.phase * 7919u)));
    if (sketch.phase == 0u) fieldSpawnPhase(index);
    else fieldStepPhase(index);
}
