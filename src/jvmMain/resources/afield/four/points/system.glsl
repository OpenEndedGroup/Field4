// points/system — the harness behind an IteratedSystem's compute shader.
//
// Include it on the line after #version, then define the six functions declared below. The
// harness's main() starts each particle with initParticle, steps it system.steps times a frame,
// and after every step writes one point for PointAccumulation to plot: project(s) is where it
// lands (world units, in the system's viewport), color(s) and pointSize(s) what it looks like,
// and shouldDraw(s) whether to plot it at all. A particle that leaves the finite numbers is
// started again with initParticle. age goes up by one after each step, so step need not.

#extension GL_EXT_buffer_reference : require
layout(local_size_x = 64) in;

// One particle. pos and age are the harness's; data is yours (a branch, a colour mix, ...).
struct State {
    vec3 pos;
    float age;
    vec4 data;
};

// What PointAccumulation plots: position = (world x, world y, size in pixels, weight), and a
// linear colour whose alpha is how much one point adds. weight 0 plots nothing.
struct AccumulatedPoint {
    vec4 position;
    vec4 color;
};

layout(std430, buffer_reference) buffer Particles { State s[]; };
layout(std430, buffer_reference) buffer Points { AccumulatedPoint p[]; };

layout(push_constant) uniform SystemPush {
    Particles particles;
    Points points;
    uint count;     // how many particles
    uint steps;     // steps (and points) per particle this frame
    uint maxSteps;  // the points buffer has room for this many per particle
    uint frame;     // frames stepped so far
    uint seed;
    uint reset;     // non-zero: start every particle again
    float dt;
} system;

// ---- what a system defines ----

State initParticle(uint index, uint count);
void step(inout State s, float dt);
vec2 project(State s);
bool shouldDraw(State s);
vec4 color(State s);
float pointSize(State s);

// ---- helpers ----

uint fieldRandomState;

uint fieldHash(uint v) {
    uint state = v * 747796405u + 2891336453u;
    uint word = ((state >> ((state >> 28u) + 4u)) ^ state) * 277803737u;
    return (word >> 22u) ^ word;
}

// A uniform random number in [0, 1), different for every particle, step and frame.
float random() {
    fieldRandomState = fieldHash(fieldRandomState);
    return float(fieldRandomState >> 8) * (1.0 / 16777216.0);
}

float srgbToLinear(float c) {
    return c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4);
}

// CSS rgba(): r, g, b in 0..255 (sRGB), alpha in 0..1. Returns a linear colour.
vec4 rgba(float r, float g, float b, float a) {
    return vec4(srgbToLinear(r / 255.0), srgbToLinear(g / 255.0), srgbToLinear(b / 255.0), a);
}

// CSS hsla(): hue in degrees, saturation and lightness in percent, alpha in 0..1.
vec4 hsla(float h, float s, float l, float a) {
    s = clamp(s / 100.0, 0.0, 1.0);
    l = clamp(l / 100.0, 0.0, 1.0);
    vec3 k = mod(vec3(0.0, 8.0, 4.0) + h / 30.0, 12.0);
    vec3 rgb = l - s * min(l, 1.0 - l) * clamp(min(k - 3.0, 9.0 - k), -1.0, 1.0);
    return vec4(srgbToLinear(rgb.r), srgbToLinear(rgb.g), srgbToLinear(rgb.b), a);
}

// An affine map in the IFS-table form: x' = a x + b y + e, y' = c x + d y + f.
vec2 affine(vec2 p, float a, float b, float c, float d, float e, float f) {
    return vec2(a * p.x + b * p.y + e, c * p.x + d * p.y + f);
}

// The flame variations, applied after a map's affine part.
vec2 linear(vec2 p) {
    return p;
}

vec2 swirl(vec2 p) {
    float r2 = dot(p, p);
    float sinr = sin(r2);
    float cosr = cos(r2);
    return vec2(p.x * sinr - p.y * cosr, p.x * cosr + p.y * sinr);
}

vec2 spherical(vec2 p) {
    float r2 = dot(p, p);
    if (r2 == 0.0) r2 = 1e-6;
    return p / r2;
}

vec2 handkerchief(vec2 p) {
    float r = length(p);
    float theta = atan(p.y, p.x);
    return r * vec2(sin(theta + r), cos(theta - r));
}

// ---- the harness ----

bool fieldEscaped(State s) {
    return any(isnan(s.pos)) || any(isinf(s.pos)) || dot(s.pos, s.pos) > 1e30;
}

void main() {
    uint index = gl_GlobalInvocationID.x;
    if (index >= system.count) return;

    fieldRandomState = fieldHash(index ^ fieldHash(system.frame ^ fieldHash(system.seed)));

    State s = system.reset != 0u ? initParticle(index, system.count) : system.particles.s[index];
    uint first = index * system.maxSteps;
    for (uint k = 0u; k < system.maxSteps; k++) {
        AccumulatedPoint point = AccumulatedPoint(vec4(0.0), vec4(0.0));
        if (k < system.steps) {
            step(s, system.dt);
            s.age += 1.0;
            if (fieldEscaped(s)) {
                s = initParticle(index, system.count);
            } else if (shouldDraw(s)) {
                point = AccumulatedPoint(vec4(project(s), pointSize(s), 1.0), color(s));
            }
        }
        system.points.p[first + k] = point;
    }
    system.particles.s[index] = s;
}
