package drowning

/**
 * The GLSL half of `dust`: two compute shaders, three debug draws, a blur and a grade.
 *
 * ## Why the shaders live here as strings
 *
 * Same reason as [PsubShaders]: there is no CPU geometry step, so the layout in [DustLayout] and the
 * code that reads it want to be in one place, reviewable together. A box's `compute` / `vertex` /
 * `fragment` properties are still the right place to *develop* one of these — dump them with
 * `DustMain --shaders` and paste — and `build_starting_point.py` embeds exactly these strings into the
 * document, so the two can never disagree by more than a re-run.
 *
 * ## Assembly order
 *
 * GLSL is order-sensitive and these pieces have to go in exactly this sequence:
 *
 * 1. `#version 450`
 * 2. `{{ dust.stereo }}` — the eye decision, because it may declare `#extension GL_EXT_multiview`
 * 3. [DUST_DEFINES] — `#define` only, safe anywhere
 * 4. [DUST_REFS] — the remaining extensions and the `buffer_reference` block types
 * 5. the stage's `layout(push_constant)` block, which needs those types
 * 6. [DUST_LIB] — the accessors, which read `pc`
 *
 * Get 4 and 5 the wrong way round and it fails with `DustState` not naming a type; get 2 wrong and
 * `gl_ViewIndex` is undeclared.
 *
 * ## Stereo, in one slot
 *
 * Every shader here is written once and runs in both stereo modes. The difference is a single template
 * slot, `{{ dust.stereo }}`, which the stereo box fills with [STEREO_MULTIVIEW] or [STEREO_MONO] — see
 * [DustStereoMode]. After it, `DUST_EYE` is the eye index and `DUST_EYES` the eye count, and nothing
 * else in any shader knows which mode it is in:
 *
 * - the draws pick `pc.vpL` or `pc.vpR` by `DUST_EYE`
 * - the full-screen passes sample `vec3(uv, DUST_EYE)` out of a `sampler2DArray`
 *
 * The colour targets are **always** array images (`colorLayerCount = 2` under multiview, and
 * `forceColorArrayView` in mono), so the sampler type never changes either. The cost of that decision
 * is one wasted array layer in mono; the alternative is two copies of every shader.
 *
 * ## Push-constant budget
 *
 * The force block is 176 bytes and the draw blocks 192; Vulkan guarantees only 128, so all of this
 * needs `maxPushConstantsSize >= 256`. MoltenVK reports 4096 and every desktop driver at least 256, and
 * `PsubShaders` already runs a 212-byte block on this hardware — but it is still the first thing to
 * suspect somewhere unusual. Two `mat4`s of that is the two eyes' view-projections: passing them both
 * is what lets one multiview draw cover both eyes.
 */
object DustShaders {

    // ---- the stereo slot -----------------------------------------------------------------------

    /**
     * `{{ dust.stereo }}` for the multiview path: one draw covers both eyes and `gl_ViewIndex` says
     * which one this invocation is writing.
     */
    val STEREO_MULTIVIEW = """
#extension GL_EXT_multiview : require
#define DUST_EYE  int(gl_ViewIndex)
#define DUST_EYES 2
""".trimIndent()

    /**
     * `{{ dust.stereo }}` for the mono fallback: one layer, one eye, no extension.
     *
     * Not a lesser path so much as the honest one when `VK_KHR_multiview` is missing — the image is
     * still an array of one layer and every shader is byte-identical, so what you see is the left eye
     * of the same pipeline rather than a different renderer.
     */
    val STEREO_MONO = """
#define DUST_EYE  0
#define DUST_EYES 1
""".trimIndent()

    // ---- shared -------------------------------------------------------------------------------

    /** Constants both stages of everything want. `#define` only, so it can go before the extensions. */
    val DUST_DEFINES = """
#define DUST_NONE  0xFFFFFFFFu
#define DUST_EDGES 6

#define DUST_HISTORY_MAGIC   0x44555354u
#define DUST_HISTORY_VERSION 1u

// header[0] = (capacity, head, count, particles)
// header[1] = (frame, stride, magic, version)
#define DUST_H_CAPACITY  header[0].x
#define DUST_H_HEAD      header[0].y
#define DUST_H_COUNT     header[0].z
#define DUST_H_PARTICLES header[0].w
#define DUST_H_FRAME     header[1].x
#define DUST_H_STRIDE    header[1].y
#define DUST_H_MAGIC     header[1].z
#define DUST_H_VERSION   header[1].w

const vec2 DUST_QUAD[6] = vec2[6](
    vec2(-1.0, -1.0), vec2( 1.0, -1.0), vec2( 1.0,  1.0),
    vec2( 1.0,  1.0), vec2(-1.0,  1.0), vec2(-1.0, -1.0)
);
""".trimIndent()

    /**
     * The three buffer-reference blocks and the extensions they need. **Vertex and compute stages
     * only.** A fragment shader declares the same push block with those handles as `uvec2` under an
     * `Address` name instead — see [POINT_PUSH_FRAGMENT] and the note in [PsubShaders].
     *
     * The two structs are [DustLayout.PARTICLE_BYTES] and [DustLayout.LINKS_BYTES] wide, and
     * `DustWorkout` checks that against std430 rather than trusting the arithmetic here.
     */
    val DUST_REFS = """
#extension GL_EXT_buffer_reference : require
#extension GL_EXT_scalar_block_layout : require

struct DustParticle {
    vec4 pos;      // xyz position,          w = 1/mass  (0 pins the particle)
    vec4 prev;     // xyz previous position, w = per-particle hash 0..1
    vec4 aux;      // four free channels; the force programs own these
};

struct DustLinks {
    vec4  rest;    // xyz rest position, w = per-particle stiffness scale
    uvec4 nbrA;    // neighbours 0..3, DUST_NONE where the slot is empty
    uvec4 nbrB;    // neighbours 4,5; z = degree; w = flags
    vec4  lenA;    // rest lengths 0..3
    vec4  lenB;    // rest lengths 4,5; z = mean rest length; w = spare
};

layout(std430, buffer_reference) buffer DustState    { DustParticle p[]; };
layout(std430, buffer_reference) buffer DustTopology { DustLinks    l[]; };
layout(std430, buffer_reference) buffer DustHistory  { uvec4 header[2]; vec4 trail[]; };
""".trimIndent()

    /**
     * The shared head of every dust push block, in this order and at these offsets.
     *
     * Every stage declares all four handles even when it uses one, exactly as the psub blocks do: push
     * constants are one pipeline-layout range and keeping the prefix identical means [DUST_LIB] can be
     * pasted into any of them without knowing which shader it landed in. Twenty-four bytes for that is
     * a good trade.
     */
    val DUST_PUSH_HEAD = """
    DustState    state;      //   0  written by the force shader; read by every draw
    DustState    prevState;  //   8  the frame before — what the force shader reads
    DustTopology links;      //  16  never changes
    DustHistory  history;    //  24  header then trail samples
    uint  count;             //  32  particles
    uint  frame;             //  36  global frame counter
    float time;              //  40  seconds
    float dt;                //  44  seconds per step""".trimStart('\n')

    /** [DUST_PUSH_HEAD] with the handles as `uvec2` for a fragment stage. Same size, same offsets. */
    val DUST_PUSH_HEAD_FRAGMENT = """
    uvec2 stateAddress;      //   0
    uvec2 prevStateAddress;  //   8
    uvec2 linksAddress;      //  16
    uvec2 historyAddress;    //  24
    uint  count;             //  32
    uint  frame;             //  36
    float time;              //  40
    float dt;                //  44""".trimStart('\n')

    /**
     * Accessors over the three buffers, plus the StackSL context globals.
     *
     * Reads `pc`, so it goes *after* the push-constant block. The `g*` globals are the whole reason the
     * force programs can be written in StackSL at all: a `stack.auto` slot compiles to a free function
     * `vec4 name(vec4 name_in)`, which sees no locals of the caller, so `?gEdgeLen` reaching a
     * module-scope global is the only way a program can read anything but its one argument. Set them,
     * call the program, read the result — see [FORCE_COMPUTE].
     */
    val DUST_LIB = """
// ---- the buffers -----------------------------------------------------------------------------
//
// These four read `prevState`, which is what the FORCE shader wants: it is reading the frame before
// while writing this one. Anything downstream of it — the history shader, the three draws — wants the
// freshly written `state` and says `pc.state.p[i]` directly.

vec4 dustPos(uint i)      { return pc.prevState.p[i].pos;  }
vec4 dustPrev(uint i)     { return pc.prevState.p[i].prev; }
vec4 dustAux(uint i)      { return pc.prevState.p[i].aux;  }
vec4 dustRest(uint i)     { return pc.links.l[i].rest;     }

uint  dustDegree(uint i)  { return pc.links.l[i].nbrB.z; }
uint  dustFlags(uint i)   { return pc.links.l[i].nbrB.w; }
float dustMeanLen(uint i) { return pc.links.l[i].lenB.z; }

uint dustNeighbour(uint i, uint slot) {
    uvec4 a = pc.links.l[i].nbrA;
    uvec4 b = pc.links.l[i].nbrB;
    return slot < 4u ? (slot == 0u ? a.x : slot == 1u ? a.y : slot == 2u ? a.z : a.w)
                     : (slot == 4u ? b.x : b.y);
}

float dustRestLength(uint i, uint slot) {
    vec4 a = pc.links.l[i].lenA;
    vec4 b = pc.links.l[i].lenB;
    return slot < 4u ? (slot == 0u ? a.x : slot == 1u ? a.y : slot == 2u ? a.z : a.w)
                     : (slot == 4u ? b.x : b.y);
}

// ---- the history circular buffer -------------------------------------------------------------
//
// slot-major: one frame of every particle is contiguous, so the write is coalesced. See the note in
// DustLayout for why the trail draw pays for that rather than the other way round.

uint dustTrailIndex(uint slot, uint particle) { return slot * pc.count + particle; }

/** Slot holding the sample written `age` frames ago, or DUST_NONE when the history is not that deep. */
uint dustTrailSlot(uint head, uint capacity, uint written, uint age) {
    if (age >= written) return DUST_NONE;
    return (head + capacity - 1u - age) % capacity;
}

// ---- the StackSL context ---------------------------------------------------------------------
//
// A stack.auto program reads these with `?gName` and can write them with `->?gName`. They are globals
// and not locals because a generated program is a free function and cannot see a caller's locals.

vec4  gP;         // this particle's position, w = 1/mass
vec4  gV;         // its velocity, pos - prev
vec4  gR;         // its rest position, w = stiffness scale
vec4  gA;         // its four free channels — writable, and written back
float gHash;      // its stable per-particle random, 0..1
float gTime;      // seconds
float gMeanLen;   // mean rest length of its edges — a natural length scale for a program
uint  gIndex;     // its index
uint  gDegree;    // how many edge slots it uses

vec4  gEdgeDir;   // unit vector from this particle toward the neighbour   } only meaningful
float gEdgeLen;   // current distance to it                                } inside an
float gRestLen;   // what that distance wants to be                        } edgeForce call
uint  gEdgeSlot;  // 0..DUST_EDGES-1
uint  gOther;     // the neighbour's index
""".trimIndent()

    // ---- force + integrate ---------------------------------------------------------------------

    /**
     * The force shader's push block. 192 bytes, knobs grouped into named `vec4`s — see the note on
     * [DRAW_PUSH] for why they are grouped rather than spelled out as scalars.
     */
    val FORCE_PUSH = """
layout(push_constant) uniform DustForcePush {
$DUST_PUSH_HEAD
    vec4  gains;             //  48  x edge  y noise  z rest  w damping
    vec4  limits;            //  64  x maxStep  y maxForce  z edgeSlots  w paused
    vec4  gravity;           //  80  xyz a constant acceleration
    vec4  bounds;            //  96  xyz half extent of the box, w elasticity (0 = off)
    vec4  centre;            // 112  xyz where that box sits
    vec4  probe;             // 128  x what to probe  y probe one particle in N
    vec4  knobA;             // 144  yours — read as ?pc.knobA
    vec4  knobB;             // 160  yours
    vec4  knobC;             // 176  yours
} pc;                        // 192 bytes
""".trimIndent()

    /** Lane names for the force block. See [DUST_DRAW_LANES]. */
    val DUST_FORCE_LANES = """
#define DUST_EDGE_GAIN    pc.gains.x
#define DUST_NOISE_GAIN   pc.gains.y
#define DUST_REST_GAIN    pc.gains.z
#define DUST_DAMPING      pc.gains.w

#define DUST_MAX_STEP     pc.limits.x
#define DUST_MAX_FORCE    pc.limits.y
#define DUST_EDGE_SLOTS   uint(pc.limits.z + 0.5)
#define DUST_PAUSED       (pc.limits.w > 0.5)

#define DUST_ELASTICITY   pc.bounds.w

#define DUST_PROBE_WHAT   uint(pc.probe.x + 0.5)
#define DUST_PROBE_EVERY  max(uint(pc.probe.y + 0.5), 1u)

// What the field probe records, matching the DUST_PROBE_* constants in DustParams.kt.
#define DUST_WATCH_SPEED        0u
#define DUST_WATCH_FORCE        1u
#define DUST_WATCH_STRAIN       2u
#define DUST_WATCH_DISPLACEMENT 3u
""".trimIndent()

    /**
     * Force accumulation and Verlet integration, one invocation per particle.
     *
     * ## What is programmable
     *
     * Four `stack.auto` slots, each independently live-editable from Kotlin (`forces.edgeForce = "…"`)
     * and each falling back to a stable no-op when unbound:
     *
     * | slot | called | argument | uses |
     * |---|---|---|---|
     * | `edgeForce` | once per filled edge slot | `vec4(len - rest, rest, hash, time)` | `.x` as force along `gEdgeDir` |
     * | `noiseForce` | once per particle | `vec4(pos.xyz, time)` | `.xyz` as a force |
     * | `restForce` | once per particle | `vec4(rest - pos, time)` | `.xyz` as a force |
     * | `velocityShape` | once per particle, after integration | `vec4(vel.xyz, time)` | `.xyz` as the new velocity |
     *
     * `edgeForce` returning a *scalar* along the edge and not a vector is deliberate: a spring force
     * that is not along its own edge does not conserve anything, and the interesting programs are all
     * about the shape of the response curve, not its direction. Anything that does want to push sideways
     * has `noiseForce`, which is a free vector.
     *
     * **The sign.** `gEdgeDir` points from this particle *toward* its neighbour, so a **positive** result
     * pulls them together and a negative one pushes them apart. Hooke's law is therefore
     * `input x 12 *` and not `-12`: `input.x` is `len - rest`, positive when stretched, and a stretched
     * spring pulls. Getting this backwards gives a network that pushes hardest exactly where it is
     * already too long — it does not wobble, it detonates, and what you find afterwards is every
     * particle pinned motionless against the bounding box. `DustReference` pins the convention.
     *
     * `noiseForce`'s argument puts the position in `.xyz`, so StackSL's `uv` is `pos.xy`, `hash` is
     * `pos.z` and `noise`/`fbm` (which read `coord.xy + coord.zw * 0.07`) become genuinely
     * three-dimensional and time-varying without the program doing anything. The per-particle seed is
     * `?gHash` when a program wants it instead.
     *
     * ## Why it reads one buffer and writes another
     *
     * `dustPos(j)` reads `prevState`. Every edge force needs a *neighbour's* position, and a neighbour is
     * another invocation of this same dispatch — so reading the buffer being written is a race. The host
     * ping-pongs the two handles each frame; see [DustLayout].
     */
    val FORCE_COMPUTE = """
#version 450

$DUST_DEFINES

$DUST_REFS

{% include "fieldProbe" %}

$FORCE_PUSH

$DUST_FORCE_LANES

$DUST_LIB

{{ stack.auto.edgeForce }}
{{ stack.auto.noiseForce }}
{{ stack.auto.restForce }}
{{ stack.auto.velocityShape }}

layout(local_size_x = 64) in;

void main() {
    uint i = gl_GlobalInvocationID.x;
    if (i >= pc.count) return;

    vec4 pos  = dustPos(i);
    vec4 prev = dustPrev(i);
    vec4 rest = dustRest(i);

    // The StackSL context. Set once, read by every program below.
    gP       = pos;
    gV       = vec4(pos.xyz - prev.xyz, 0.0);
    gR       = rest;
    gA       = dustAux(i);
    gHash    = prev.w;
    gTime    = pc.time;
    gMeanLen = dustMeanLen(i);
    gIndex   = i;
    gDegree  = dustDegree(i);

    vec3 force = pc.gravity.xyz;
    float strainSum = 0.0;
    uint  strainCount = 0u;

    // ---- edges -------------------------------------------------------------------------------
    uint slots = min(DUST_EDGE_SLOTS, uint(DUST_EDGES));
    for (uint s = 0u; s < slots; s++) {
        uint j = dustNeighbour(i, s);
        if (j == DUST_NONE) continue;

        vec3  d   = dustPos(j).xyz - pos.xyz;
        float len = length(d);
        // A coincident pair has no direction to push along; skip rather than normalize a zero.
        if (len < 1e-8) continue;

        gEdgeDir  = vec4(d / len, 0.0);
        gEdgeLen  = len;
        gRestLen  = dustRestLength(i, s);
        gEdgeSlot = s;
        gOther    = j;

        float f = edgeForce(vec4(len - gRestLen, gRestLen, gHash, pc.time)).x;
        force += gEdgeDir.xyz * f * DUST_EDGE_GAIN * rest.w;

        if (gRestLen > 1e-8) { strainSum += (len - gRestLen) / gRestLen; strainCount++; }
    }

    // ---- the two per-particle fields ---------------------------------------------------------
    force += noiseForce(vec4(pos.xyz, pc.time)).xyz * DUST_NOISE_GAIN;
    force += restForce(vec4(rest.xyz - pos.xyz, pc.time)).xyz * DUST_REST_GAIN * rest.w;

    // ---- the probe ---------------------------------------------------------------------------
    //
    // Free statistics: a histogram, min/max, mean, and NaN/infinity counts, all reduced on the GPU and
    // shown in this box's output channel. Much better than reading the state buffer back to the CPU,
    // which is what dust_one did — that gave four numbers and no distribution, and it could not see a
    // NaN that had already been clamped away.
    //
    // It goes here, BEFORE the clamps, because what you want to know is whether the clamps are firing.
    if ((i % DUST_PROBE_EVERY) == 0u) {
        uint what = DUST_PROBE_WHAT;
        if      (what == DUST_WATCH_FORCE)        fieldProbe(length(force));
        else if (what == DUST_WATCH_STRAIN)       fieldProbe(strainCount > 0u ? strainSum / float(strainCount) : 0.0);
        else if (what == DUST_WATCH_DISPLACEMENT) fieldProbe(length(pos.xyz - rest.xyz));
        else                                     fieldProbe(length(gV.xyz));
    }

    if (DUST_MAX_FORCE > 0.0) {
        float fl = length(force);
        if (fl > DUST_MAX_FORCE) force *= DUST_MAX_FORCE / fl;
    }

    // ---- Verlet ------------------------------------------------------------------------------
    //
    // x(t+1) = x(t) + damping * (x(t) - x(t-1)) + a * dt^2, with a = F/m. There is no velocity field
    // to keep consistent with the positions, which is what makes the position clamp below safe: it
    // changes the implied velocity too, rather than leaving a stale one behind.
    vec3 velocity = (pos.xyz - prev.xyz) * DUST_DAMPING;
    vec3 accel    = force * pos.w;                  // pos.w is 1/mass; 0 pins the particle

    velocity = velocityShape(vec4(velocity, pc.time)).xyz;

    vec3 step = velocity + accel * pc.dt * pc.dt;
    if (DUST_MAX_STEP > 0.0) {
        float sl = length(step);
        if (sl > DUST_MAX_STEP) step *= DUST_MAX_STEP / sl;
    }

    vec3 next = pos.xyz + (DUST_PAUSED ? vec3(0.0) : step);

    // ---- the box -----------------------------------------------------------------------------
    //
    // Reflecting the *position* is not enough on its own: Verlet reads velocity out of the two
    // positions, so the previous position has to move with it or the particle walks straight back
    // through the wall on the next step.
    //
    // And it is the attempted STEP that gets reflected, not the old position's offset from the wall.
    // Reflecting the offset has a fixed point: a particle already sitting exactly on the wall has zero
    // offset, so it gets `prev == pos`, i.e. zero velocity, and stays pinned there for good. That is
    // not a theoretical worry — it is what a whole field looked like once, smeared over the six faces
    // with every velocity exactly zero.
    vec3 keep = pos.xyz;
    if (DUST_ELASTICITY != 0.0) {
        vec3 lo = pc.centre.xyz - abs(pc.bounds.xyz);
        vec3 hi = pc.centre.xyz + abs(pc.bounds.xyz);
        for (int a = 0; a < 3; a++) {
            if (next[a] < lo[a] || next[a] > hi[a]) {
                float wall    = next[a] < lo[a] ? lo[a] : hi[a];
                float attempt = next[a] - keep[a];
                next[a] = wall;
                keep[a] = wall + attempt * DUST_ELASTICITY;   // outgoing velocity = -attempt * elasticity
            }
        }
    }

    pc.state.p[i].pos  = vec4(next, pos.w);
    pc.state.p[i].prev = vec4(keep, prev.w);
    pc.state.p[i].aux  = gA;
}
""".trimIndent()

    // ---- history -------------------------------------------------------------------------------

    /** The history shader's push block. 64 bytes. */
    val HISTORY_PUSH = """
layout(push_constant) uniform DustHistoryPush {
$DUST_PUSH_HEAD
    vec4  cursor;            //  48  x capacity  y the slot to write  z frames written so far
} pc;                        //  64 bytes
""".trimIndent()

    /**
     * Lane names for the history block.
     *
     * `RING` rather than `H`, because `DUST_H_*` is already taken by the header field accessors in
     * [DUST_DEFINES] and two `#define`s a letter apart addressing different things is how you write a
     * bug you cannot see.
     */
    val DUST_HISTORY_LANES = """
#define DUST_RING_CAPACITY  max(uint(pc.cursor.x + 0.5), 1u)
#define DUST_RING_SLOT      uint(pc.cursor.y + 0.5)
#define DUST_RING_WRITTEN   uint(pc.cursor.z + 0.5)
""".trimIndent()

    /**
     * One frame of every particle's position into its circular buffer, and the header that describes it.
     *
     * The slot arrives as a push constant rather than being read out of the header, which is the
     * difference between this being correct and being *nearly* correct: every invocation needs the same
     * slot, and reading it from a header that invocation 0 is also advancing is a race. So the host owns
     * the cursor, and invocation 0 writes the header purely as a record for whoever reads the buffer
     * back later ([DustHistoryHeader]).
     *
     * `.w` carries the particle's speed, which costs nothing here and is what lets the trail draw fade
     * or colour by speed without a second buffer.
     */
    val HISTORY_COMPUTE = """
#version 450

$DUST_DEFINES

$DUST_REFS

$HISTORY_PUSH

$DUST_HISTORY_LANES

$DUST_LIB

layout(local_size_x = 64) in;

void main() {
    uint i = gl_GlobalInvocationID.x;

    // One invocation writes the header. Not a race: nothing else here reads it, and every invocation
    // addresses its write from the push constants.
    if (i == 0u) {
        pc.history.DUST_H_CAPACITY  = DUST_RING_CAPACITY;
        pc.history.DUST_H_HEAD      = (DUST_RING_SLOT + 1u) % DUST_RING_CAPACITY;
        pc.history.DUST_H_COUNT     = DUST_RING_WRITTEN;
        pc.history.DUST_H_PARTICLES = pc.count;
        pc.history.DUST_H_FRAME     = pc.frame;
        pc.history.DUST_H_STRIDE    = 1u;
        pc.history.DUST_H_MAGIC     = DUST_HISTORY_MAGIC;
        pc.history.DUST_H_VERSION   = DUST_HISTORY_VERSION;
    }

    if (i >= pc.count) return;

    // The freshly integrated positions are in `state`, not `prevState` — this dispatch runs after the
    // force one, so `dustPos()` (which reads prevState, on purpose) is the wrong accessor here.
    vec4 pos  = pc.state.p[i].pos;
    vec4 prev = pc.state.p[i].prev;

    pc.history.trail[dustTrailIndex(DUST_RING_SLOT % DUST_RING_CAPACITY, i)] =
        vec4(pos.xyz, length(pos.xyz - prev.xyz));
}
""".trimIndent()

    // ---- the debug draws -----------------------------------------------------------------------

    /**
     * The push block shared by all three debug draws, declared identically in both their stages.
     *
     * The knobs are **grouped into named `vec4`s**, not spelled out as scalars, and that is a
     * deliberate reversal of how `dust_one` did it. Two reasons, both practical:
     *
     * - **Adding a knob is a shader edit, hot-reloaded.** A new lane in an existing group plus a
     *   `#define` in [DUST_DRAW_LANES], and nothing on the Kotlin side has to be rebuilt. `dust_one`
     *   put the same knobs on a `DustLook` class in the personal jar, where a new field meant a Gradle
     *   build and a restart of the running sketch.
     * - **`KeyframeScope` binds named values in named shaders.** `target("dot", points.push)` reads and
     *   writes this block's `dot` member; `KValue` persists `vec4` natively. A knob that only exists as
     *   a field on a Kotlin class the shader never sees cannot be snapshotted or interpolated at all.
     *
     * The readable way to *write* one is a named-argument function in `DustParams.kt` that returns a
     * `Vec4` — `points.dot = { dustDot(size = 0.012f, minPixels = 0.9f, viewportHeight = h) }` — so the
     * defaults and the argument names live there and the lane names live in [DUST_DRAW_LANES].
     *
     * 256 bytes.
     */
    val DRAW_PUSH = """
layout(push_constant) uniform DustDrawPush {
$DUST_PUSH_HEAD
    mat4  vpL;               //  48  left eye's projection * view
    mat4  vpR;               // 112  right eye's
    vec4  camRight;          // 176  xyz the camera's right in world space, w spare
    vec4  camUp;             // 192  xyz the camera's up, w spare
    vec4  projScale;         // 208  per eye: (p00, p11) left then right — the PROJECTION's, not vp's
    vec4  tint;              // 224  rgba
    vec4  dot;               // 240  x size(world) y minPixels z viewportH
    vec4  ramp;              // 256  x speedGain y strainGain z fade w colorMode
    vec4  trail;             // 272  x capacity y head z written w everyNth
    vec4  stroke;            // 288  x trailFade y everyNth
} pc;                        // 304 bytes
""".trimIndent()

    /** [DRAW_PUSH] for a fragment stage: same layout, `uvec2 *Address` for the handles. */
    val DRAW_PUSH_FRAGMENT = """
layout(push_constant) uniform DustDrawPush {
$DUST_PUSH_HEAD_FRAGMENT
    mat4  vpL;
    mat4  vpR;
    vec4  camRight;
    vec4  camUp;
    vec4  projScale;
    vec4  tint;
    vec4  dot;
    vec4  ramp;
    vec4  trail;
    vec4  stroke;
} pc;
""".trimIndent()

    /**
     * Lane names for the draw block, so the shaders read `DUST_DOT_SIZE` rather than `pc.dot.x`.
     *
     * This is the other half of the grouping trade: a `vec4` is keyframable and a scalar is readable, so
     * the group is a `vec4` and the readability is a `#define`. `DustWorkout` asserts no two names alias
     * the same lane, which is the failure this shape invites.
     */
    val DUST_DRAW_LANES = """
#define DUST_DOT_SIZE       pc.dot.x
#define DUST_DOT_MIN_PIXELS pc.dot.y
#define DUST_VIEWPORT_H     pc.dot.z

#define DUST_SPEED_GAIN     pc.ramp.x
#define DUST_STRAIN_GAIN    pc.ramp.y
#define DUST_FADE           pc.ramp.z
#define DUST_COLOR_MODE     uint(pc.ramp.w + 0.5)

#define DUST_CAPACITY       uint(pc.trail.x + 0.5)
#define DUST_HEAD           uint(pc.trail.y + 0.5)
#define DUST_WRITTEN        uint(pc.trail.z + 0.5)
#define DUST_TRAIL_EVERY    max(uint(pc.trail.w + 0.5), 1u)

#define DUST_TRAIL_FADE     pc.stroke.x
#define DUST_EVERY_NTH      max(uint(pc.stroke.y + 0.5), 1u)

// Colour modes, matching the DUST_COLOR_* constants in DustParams.kt.
#define DUST_MODE_FLAT   0u
#define DUST_MODE_SPEED  1u
#define DUST_MODE_STRAIN 2u
#define DUST_MODE_DEGREE 3u
#define DUST_MODE_HASH   4u
""".trimIndent()

    /**
     * The eye selection, the billboard, and the colour ramps. Shared by the three draws' vertex stages.
     *
     * ## The billboard
     *
     * [dustBillboard] offsets the particle in **world space** along the camera's right and up, then
     * projects. That is a real screen-facing quad: it is perspective-correct for free, it stays facing
     * the camera however the camera turns, and swapping `camRight` for a velocity direction turns the
     * same code into a streak.
     *
     * `dust_one` did it the other way — offset `clip.xy` by `q * radius * vp[0][0]` — and that is
     * **wrong**, which is worth spelling out because the expression looks so plausible. `vp` is
     * `projection * view`, so `vp[0][0]` is not the projection's x scale; it is that scale mixed with
     * the view rotation. Sitting still it is very nearly right. The moment the camera turns, the quads
     * shear and stop facing the viewer, and what you see is a cloud whose sprites keep their original
     * orientation while everything else rotates.
     *
     * (`psub` does the clip-space version correctly, because it keeps `p` and `m` as separate push
     * members and multiplies by `pc.p[0][0]` — the projection alone. Premultiplying the two into one
     * `vp` to save push-constant space is what broke it here. The projection's own scale is still
     * needed for the pixel floor, so it comes along as [DUST_PROJ_SCALE].)
     */
    val DRAW_LIB = """
mat4 dustViewProjection() { return DUST_EYE == 0 ? pc.vpL : pc.vpR; }

/** The projection's own (p00, p11) for this eye — NOT `vp`'s, which carries the view rotation. */
vec2 dustProjScale() { return DUST_EYE == 0 ? pc.projScale.xy : pc.projScale.zw; }

/**
 * A screen-facing quad corner in world space: the particle, offset along the camera's right and up.
 *
 * `radius` is in world units. The pixel floor is applied by the caller, which needs the clip-space w
 * and so has to project first — see the point shader.
 */
vec3 dustBillboard(vec3 world, vec2 q, float radius) {
    return world + (q.x * pc.camRight.xyz + q.y * pc.camUp.xyz) * radius;
}

/**
 * The world radius that keeps a sprite at least `DUST_DOT_MIN_PIXELS` across on screen at clip depth
 * `w`, so distant particles stay visible instead of aliasing away.
 */
float dustRadiusFloor(float w) {
    return DUST_DOT_MIN_PIXELS * 2.0 * w / max(1.0, DUST_VIEWPORT_H * abs(dustProjScale().y));
}

/** Cool-to-warm through paper, for a signed quantity. */
vec3 dustDiverging(float x) {
    float t = clamp(x * 0.5 + 0.5, 0.0, 1.0);
    vec3 cool = vec3(0.20, 0.45, 0.85);
    vec3 warm = vec3(0.95, 0.42, 0.18);
    vec3 mid  = vec3(0.86, 0.84, 0.79);
    return t < 0.5 ? mix(cool, mid, t * 2.0) : mix(mid, warm, (t - 0.5) * 2.0);
}

/** A cosine palette, for an unsigned quantity. */
vec3 dustPalette(float t) {
    return clamp(vec3(0.55, 0.50, 0.48) +
                 vec3(0.42, 0.40, 0.45) * cos(6.28318 * (vec3(1.0, 0.94, 0.86) * t +
                                                         vec3(0.00, 0.14, 0.28))), 0.0, 1.0);
}

/** A vertex that is never rasterised: w = 0 clips it. Cheaper and safer than a degenerate primitive. */
vec4 dustClipped() { return vec4(0.0, 0.0, -1.0, 0.0); }
""".trimIndent()

    /**
     * Debug points: six vertices per particle expanded into a screen-facing quad, so the mesh needs no
     * vertex attributes at all — `points.resize(count * 6)` and nothing else.
     *
     * The quad is built in **world space** along the camera's right and up ([dustBillboard]), which is
     * what makes it face the viewer however the camera turns. See the long note in [DRAW_LIB] for the
     * clip-space version that does not.
     *
     * Sizing needs two passes at the projection: project the centre to learn the clip depth, apply the
     * pixel floor at that depth, then build the quad at the final radius and project again. Two `mat4`
     * multiplies per vertex rather than one, and worth it — the alternative is a cloud that aliases
     * away at distance.
     */
    val POINT_VERTEX = """
#version 450

{{ dust.stereo }}

$DUST_DEFINES

$DUST_REFS

$DRAW_PUSH

$DUST_DRAW_LANES

$DUST_LIB

$DRAW_LIB

layout(location = 0) out vec3 vColor;
layout(location = 1) out vec2 vQuad;
layout(location = 2) out float vFade;

void main() {
    uint vertex   = uint(gl_VertexIndex);
    uint particle = vertex / 6u;
    uint corner   = vertex % 6u;

    if (particle >= pc.count || (particle % DUST_EVERY_NTH) != 0u) {
        gl_Position = dustClipped();
        vColor = vec3(0.0); vQuad = vec2(0.0); vFade = 0.0;
        return;
    }

    vec4 pos  = pc.state.p[particle].pos;
    vec4 prev = pc.state.p[particle].prev;
    float speed = length(pos.xyz - prev.xyz);

    uint mode = DUST_COLOR_MODE;
    vec3 tint;
    if      (mode == DUST_MODE_FLAT)   tint = pc.tint.rgb;
    else if (mode == DUST_MODE_SPEED)  tint = dustPalette(speed * DUST_SPEED_GAIN);
    else if (mode == DUST_MODE_STRAIN) tint = dustDiverging(
                                           length(pos.xyz - dustRest(particle).xyz) * DUST_STRAIN_GAIN);
    else if (mode == DUST_MODE_DEGREE) tint = dustPalette(float(dustDegree(particle)) / float(DUST_EDGES));
    else                               tint = dustPalette(prev.w);

    mat4 vp = dustViewProjection();

    // Project the centre first, only to learn the depth the pixel floor needs.
    float w = max((vp * vec4(pos.xyz, 1.0)).w, 1e-6);
    float radius = max(DUST_DOT_SIZE, dustRadiusFloor(w));

    vec2 q = DUST_QUAD[corner];
    gl_Position = vp * vec4(dustBillboard(pos.xyz, q, radius), 1.0);

    vColor = tint;
    vQuad  = q;
    vFade  = DUST_FADE <= 0.0 ? 1.0 : exp(-DUST_FADE * w);
}
""".trimIndent()

    /** Debug points: a soft round dot, `discard` outside the disc so the quad never shows. */
    val POINT_FRAGMENT = """
#version 450

{{ dust.stereo }}

$DUST_DEFINES

$DRAW_PUSH_FRAGMENT

$DUST_DRAW_LANES

layout(location = 0) in vec3 vColor;
layout(location = 1) in vec2 vQuad;
layout(location = 2) in float vFade;

layout(location = 0) out vec4 uFragColor;

void main() {
    float d = length(vQuad);
    if (d > 1.0) discard;
    float alpha = 1.0 - smoothstep(0.55, 1.0, d);
    if (alpha < 0.02) discard;
    uFragColor = vec4(vColor * vFade, alpha * pc.tint.a);
}
""".trimIndent()

    /**
     * Debug edges: two vertices per neighbour slot per particle, drawn as a line list.
     *
     * `count * DUST_EDGES * 2` vertices, and the empty slots collapse to a clipped vertex rather than
     * to a zero-length line — a degenerate line still rasterises a pixel, which on a 100k cloud is a
     * visible haze of dots that reads as a bug in the network.
     *
     * Each end is coloured by its own strain, so an edge under tension is warm at both ends and the
     * interpolation across it shows which end is being pulled harder.
     */
    val EDGE_VERTEX = """
#version 450

{{ dust.stereo }}

$DUST_DEFINES

$DUST_REFS

$DRAW_PUSH

$DUST_DRAW_LANES

$DUST_LIB

$DRAW_LIB

layout(location = 0) out vec3 vColor;
layout(location = 1) out float vFade;

void main() {
    uint vertex   = uint(gl_VertexIndex);
    uint particle = vertex / (uint(DUST_EDGES) * 2u);
    uint slot     = (vertex / 2u) % uint(DUST_EDGES);
    uint end      = vertex % 2u;

    uint other = particle < pc.count ? dustNeighbour(particle, slot) : DUST_NONE;

    if (other == DUST_NONE || (particle % DUST_EVERY_NTH) != 0u) {
        gl_Position = dustClipped();
        vColor = vec3(0.0); vFade = 0.0;
        return;
    }

    // Draw each undirected edge once. Without this every two-sided edge is drawn twice, which is
    // invisible until the blend is turned on and then reads as inconsistent line weight.
    if (other < particle) {
        for (uint s = 0u; s < uint(DUST_EDGES); s++) {
            if (dustNeighbour(other, s) == particle) {
                gl_Position = dustClipped();
                vColor = vec3(0.0); vFade = 0.0;
                return;
            }
        }
    }

    uint  which = end == 0u ? particle : other;
    vec4  pos   = pc.state.p[which].pos;
    float rest  = dustRestLength(particle, slot);
    float len   = length(pc.state.p[other].pos.xyz - pc.state.p[particle].pos.xyz);
    float strain = rest > 1e-8 ? (len - rest) / rest : 0.0;

    vec3 tint = DUST_COLOR_MODE == DUST_MODE_FLAT
        ? pc.tint.rgb
        : dustDiverging(strain * DUST_STRAIN_GAIN);

    vec4 clip = dustViewProjection() * vec4(pos.xyz, 1.0);
    gl_Position = clip;
    vColor = tint;
    vFade  = DUST_FADE <= 0.0 ? 1.0 : exp(-DUST_FADE * clip.w);
}
""".trimIndent()

    /** Debug edges: flat, alpha from `tint.a`. */
    val EDGE_FRAGMENT = """
#version 450

{{ dust.stereo }}

$DUST_DEFINES

$DRAW_PUSH_FRAGMENT

$DUST_DRAW_LANES

layout(location = 0) in vec3 vColor;
layout(location = 1) in float vFade;

layout(location = 0) out vec4 uFragColor;

void main() {
    uFragColor = vec4(vColor * vFade, pc.tint.a);
}
""".trimIndent()

    /**
     * Debug trails: one line segment per adjacent pair of history samples, per particle.
     *
     * `count/everyNth * (capacity-1) * 2` vertices, which is why [DustLook.trailEveryNth] exists — at
     * 100k particles and 64 frames of history the untrimmed draw is 12.6M vertices for something that
     * is a debug view. The vertex count is fixed at whatever the box asked for; a segment whose sample
     * has not been written yet clips itself, so the trail grows in over the first `capacity` frames
     * rather than starting as a spray of lines through the origin.
     *
     * `written == 0` is the "no history yet" case and clips everything, which is what makes this box
     * safe to run before the history box.
     */
    val TRAIL_VERTEX = """
#version 450

{{ dust.stereo }}

$DUST_DEFINES

$DUST_REFS

$DRAW_PUSH

$DUST_DRAW_LANES

$DUST_LIB

$DRAW_LIB

layout(location = 0) out vec3 vColor;
layout(location = 1) out float vFade;

void main() {
    uint capacity = max(DUST_CAPACITY, 1u);
    uint written  = DUST_WRITTEN;
    uint segments = max(capacity - 1u, 1u);

    uint vertex   = uint(gl_VertexIndex);
    uint slotPair = (vertex / 2u) % segments;
    uint drawn    = vertex / (2u * segments);
    uint end      = vertex % 2u;

    uint particle = drawn * DUST_TRAIL_EVERY;

    uint age  = slotPair + end;                      // 0 is the newest sample
    uint slot = dustTrailSlot(DUST_HEAD, capacity, written, age);

    if (particle >= pc.count || written < 2u || slot == DUST_NONE) {
        gl_Position = dustClipped();
        vColor = vec3(0.0); vFade = 0.0;
        return;
    }

    vec4 sample_ = pc.history.trail[dustTrailIndex(slot, particle)];

    float t = float(age) / float(max(written - 1u, 1u));   // 0 newest, 1 oldest
    vec3 tint = DUST_COLOR_MODE == DUST_MODE_FLAT
        ? pc.tint.rgb
        : dustPalette(sample_.w * DUST_SPEED_GAIN);

    vec4 clip = dustViewProjection() * vec4(sample_.xyz, 1.0);
    gl_Position = clip;
    vColor = tint;
    vFade  = mix(1.0, 1.0 - DUST_TRAIL_FADE, t) *
             (DUST_FADE <= 0.0 ? 1.0 : exp(-DUST_FADE * clip.w));
}
""".trimIndent()

    /** Debug trails: flat, faded by age. */
    val TRAIL_FRAGMENT = """
#version 450

{{ dust.stereo }}

$DUST_DEFINES

$DRAW_PUSH_FRAGMENT

$DUST_DRAW_LANES

layout(location = 0) in vec3 vColor;
layout(location = 1) in float vFade;

layout(location = 0) out vec4 uFragColor;

void main() {
    uFragColor = vec4(vColor * vFade, pc.tint.a * vFade);
}
""".trimIndent()

    // ---- the full-screen passes ----------------------------------------------------------------

    /**
     * The vertex stage every full-screen pass shares: one triangle covering the viewport, `vUV` in
     * `0..2` so the fragment stage clamps it into `0..1`.
     *
     * Three vertices and no attributes. Under multiview this one draw covers both layers, and because
     * `vUV` does not depend on the eye there is nothing eye-specific in here at all.
     */
    val FULLSCREEN_VERTEX = """
#version 450

{{ dust.stereo }}

layout(location = 0) out vec2 vUV;

void main() {
    vec2 uv = vec2((gl_VertexIndex == 1) ? 2.0 : 0.0,
                   (gl_VertexIndex == 2) ? 2.0 : 0.0);
    vUV = uv;
    gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);
}
""".trimIndent()

    /**
     * One direction of a 7-tap separable Gaussian, in four bilinear samples.
     *
     * The seven taps are the binomial row `1 6 15 20 15 6 1`. Sampling with a linear filter lets each
     * off-centre *pair* be fetched as one bilinear sample placed at the pair's weighted centroid, so
     * seven taps cost four fetches: the centre, and `±1.2`, `±3` — the offsets below. That is the whole
     * trick, and it is why the constants are not integers.
     *
     * `direction` is `(1,0)` or `(0,1)`; the box runs the same module twice with it flipped, and again
     * at half the resolution for the second cascade level. `taps` widens the kernel without adding
     * fetches — the second level uses it to reach further, which is what makes the cascade a cascade
     * rather than two identical blurs.
     *
     * The layer is `DUST_EYE`, so neither eye can bleed into the other: they are separate array layers,
     * not two halves of one image, which is the one real advantage of doing stereo this way round.
     */
    val BLUR_FRAGMENT = """
#version 450

{{ dust.stereo }}

layout(location = 0) in vec2 vUV;
layout(location = 0) out vec4 outColor;

layout(set = 0, binding = 0) uniform sampler2DArray source;

layout(push_constant) uniform DustBlurPush {
    vec4  blur;         //  0  x horizontal(0/1)  y taps  z gain  w threshold
    vec4  blurSize;     // 16  xy the source's size in pixels
} pc;                   // 32 bytes

#define DUST_BLUR_DIR    (pc.blur.x > 0.5 ? vec2(1.0, 0.0) : vec2(0.0, 1.0))
#define DUST_BLUR_TAPS   pc.blur.y
#define DUST_BLUR_GAIN   pc.blur.z
#define DUST_BLUR_FLOOR  pc.blur.w
#define DUST_BLUR_SIZE   pc.blurSize.xy

// 1 6 15 20 15 6 1, normalised: the centre, then two bilinear pairs.
const float W0  = 20.0 / 64.0;
const float W12 = (15.0 + 6.0) / 64.0;
const float W3  = 1.0 / 64.0;

// (1*15 + 2*6) / 21
const float O12 = 27.0 / 21.0;
const float O3  = 3.0;

vec4 tap(vec2 uv, vec2 offset) {
    vec4 c = texture(source, vec3(clamp(uv + offset, vec2(0.0), vec2(1.0)), float(DUST_EYE)));
    return max(c - vec4(DUST_BLUR_FLOOR), vec4(0.0));
}

void main() {
    vec2 uv    = clamp(vUV, 0.0, 1.0);
    vec2 texel = DUST_BLUR_DIR * DUST_BLUR_TAPS / max(DUST_BLUR_SIZE, vec2(1.0));

    vec4 c = tap(uv, vec2(0.0)) * W0;
    c += tap(uv,  texel * O12) * W12;
    c += tap(uv, -texel * O12) * W12;
    c += tap(uv,  texel * O3)  * W3;
    c += tap(uv, -texel * O3)  * W3;

    outColor = c * DUST_BLUR_GAIN;
}
""".trimIndent()

    /**
     * The grade: the main buffer plus both blur levels, composited and graded, into the buffer that
     * actually reaches the screen.
     *
     * `lift / gain / gamma` is the same `pow(max((c + lift) * gain, 0), gamma)` triple that
     * StackSL's `grade` word implements and that colourists expect, applied *after* the bloom is added
     * so that the bloom is graded with the image rather than on top of it. `vignette` and `chroma` are
     * here because they are the two things that make a stereo pair read as a photograph rather than as
     * a render, and both are one line.
     */
    val GRADE_FRAGMENT = """
#version 450

{{ dust.stereo }}

layout(location = 0) in vec2 vUV;
layout(location = 0) out vec4 outColor;

layout(set = 0, binding = 0) uniform sampler2DArray source;
layout(set = 0, binding = 1) uniform sampler2DArray blurNear;
layout(set = 0, binding = 2) uniform sampler2DArray blurFar;

layout(push_constant) uniform DustGradePush {
    vec4  lift;         //   0  pow(max((c + lift) * gain, 0), gamma)
    vec4  gain;         //  16
    vec4  gamma;        //  32
    vec4  bloom;        //  48  x near level  y far level  z saturation  w tonemap 0..1
    vec4  lens;         //  64  x vignette  y chroma  z grain  w time
} pc;                   //  80 bytes

#define DUST_VIGNETTE  pc.lens.x
#define DUST_CHROMA    pc.lens.y
#define DUST_GRAIN     pc.lens.z
#define DUST_TIME      pc.lens.w

vec3 fetch(sampler2DArray t, vec2 uv) {
    return texture(t, vec3(clamp(uv, vec2(0.0), vec2(1.0)), float(DUST_EYE))).rgb;
}

void main() {
    vec2 uv = clamp(vUV, 0.0, 1.0);
    vec2 fromCentre = uv - 0.5;

    // Chromatic aberration: three fetches at slightly different radial scales. Cheap, and the one
    // artefact that reads as "lens" rather than as "post".
    vec3 c;
    if (DUST_CHROMA == 0.0) {
        c = fetch(source, uv);
    } else {
        float s = DUST_CHROMA * 0.001;
        c = vec3(fetch(source, 0.5 + fromCentre * (1.0 + s)).r,
                 fetch(source, uv).g,
                 fetch(source, 0.5 + fromCentre * (1.0 - s)).b);
    }

    c += fetch(blurNear, uv) * pc.bloom.x;
    c += fetch(blurFar,  uv) * pc.bloom.y;

    float luma = dot(c, vec3(0.2126, 0.7152, 0.0722));
    c = mix(vec3(luma), c, pc.bloom.z);

    c = pow(max((c + pc.lift.rgb) * pc.gain.rgb, vec3(0.0)), max(abs(pc.gamma.rgb), vec3(1e-4)));

    c = mix(c, c / (c + 1.0), pc.bloom.w);

    if (DUST_VIGNETTE != 0.0) {
        c *= 1.0 - DUST_VIGNETTE * dot(fromCentre, fromCentre) * 2.0;
    }

    if (DUST_GRAIN != 0.0) {
        // Per-eye seed, or the grain is identical in both eyes and fuses into a plane of dirt
        // hanging in front of the image.
        float n = fract(sin(dot(uv + DUST_TIME + float(DUST_EYE) * 7.31, vec2(12.9898, 78.233))) * 43758.5453);
        c += (n - 0.5) * DUST_GRAIN;
    }

    outColor = vec4(max(c, vec3(0.0)), 1.0);
}
""".trimIndent()

    /**
     * Every shader here, by the filename `DustMain --shaders` writes it under.
     *
     * The one place that enumerates them, so `DustWorkout` compiles all of them and
     * `build_starting_point.py` embeds all of them without either holding its own list.
     */
    val ALL: Map<String, Pair<String, ShaderStage>> = linkedMapOf(
        "dust_force.comp" to (FORCE_COMPUTE to ShaderStage.COMPUTE),
        "dust_history.comp" to (HISTORY_COMPUTE to ShaderStage.COMPUTE),
        "dust_point.vert" to (POINT_VERTEX to ShaderStage.VERTEX),
        "dust_point.frag" to (POINT_FRAGMENT to ShaderStage.FRAGMENT),
        "dust_edge.vert" to (EDGE_VERTEX to ShaderStage.VERTEX),
        "dust_edge.frag" to (EDGE_FRAGMENT to ShaderStage.FRAGMENT),
        "dust_trail.vert" to (TRAIL_VERTEX to ShaderStage.VERTEX),
        "dust_trail.frag" to (TRAIL_FRAGMENT to ShaderStage.FRAGMENT),
        "dust_fullscreen.vert" to (FULLSCREEN_VERTEX to ShaderStage.VERTEX),
        "dust_blur.frag" to (BLUR_FRAGMENT to ShaderStage.FRAGMENT),
        "dust_grade.frag" to (GRADE_FRAGMENT to ShaderStage.FRAGMENT)
    )

    /** Which stage a member of [ALL] is. */
    enum class ShaderStage { VERTEX, FRAGMENT, COMPUTE }
}
