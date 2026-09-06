# Field Document: gpu_state

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory"]}
```

## 1. renderer

<!-- field-box id="gs_renderer" index="0" -->

```edn [field-box id="gs_renderer" role="properties"]
{"id" "gs_renderer" "index" 0 "name" "renderer" "parents" ["root"] "children" ["gs_sim"] "properties" {"children" #{#afield/box "gs_sim"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 200.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "gs_renderer_code_.kt" "lines" 11 "characters" 249 "sha256" "cb663790768b9b9d82b5859aa0d6a9178d777df8c51f8b6ba93017b50cf3c29b"}]}
```

```kotlin [field-property box="gs_renderer" name="code" path="gs_renderer_code_.kt"]
import afield.four.util.Options
prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
Options.profiling = false
once.renderer {
    window = Window("GPU state")
    Renderer(window)
}
println("renderer ready")
start(renderer::run)
```

## 2. simulate

<!-- field-box id="gs_sim" index="1" -->

```edn [field-box id="gs_sim" role="properties"]
{"id" "gs_sim" "index" 1 "name" "simulate" "parents" ["root" "gs_renderer"] "children" ["gs_draw"] "properties" {"children" #{#afield/box "gs_draw"} "parents" #{#afield/box "root" #afield/box "gs_renderer"} "name" "simulate" "_tombstone" false "frame" #afield/rect [240.0 40.0 200.0 50.0]} "blockProperties" [{"name" "compute" "language" "glsl" "path" "gs_sim_compute_.glsl" "lines" 53 "characters" 2045 "sha256" "2bb3bf4bc9c01e071f28cc2ef3c884a32f5d331d62a0d0f1b8ce56a803e009fb"} {"name" "code" "language" "kotlin" "path" "gs_sim_code_.kt" "lines" 107 "characters" 5188 "sha256" "596fd834b67573b3c07bc271c293d32f7cacb88990e5d91c4cb968db5f7d64b7"}]}
```

```glsl [field-property box="gs_sim" name="compute" path="gs_sim_compute_.glsl"]
#version 450
#extension GL_EXT_buffer_reference : require
layout(local_size_x = 64) in;

layout(std430, buffer_reference) buffer State   { vec4 a[]; };
layout(std430, buffer_reference) buffer Anchors { vec4 a[]; };
layout(std430, buffer_reference) buffer Heat    { vec4 a[]; };

{% include "fieldProbe" %}

layout(push_constant) uniform dat {
    State   state;        // this frame's half of the ping-pong — written
    State   prevState;    // last frame's half — read
    Anchors anchors;      // written once, read every frame, device-local
    Heat    heat;         // zero-initialised, device-local, shader-owned
    vec4    knobs;        // .x seconds, .y force gain
    uint    count;
    float   dt;
} pc;

// The live slot, and it goes AFTER the push block, not before: a program is free to read
// `?pc.knobs`, and the generated function is emitted exactly where this line is. Put it above
// and glslang says `'pc' : undeclared identifier` on a line number inside generated code.
// Until Kotlin binds it this is `vec4 force(vec4 force_in) { return vec4(0.0); }`, so the
// shader still compiles and the field simply sits still.
{{ stack.auto.force }}

void main() {
    uint i = gl_GlobalInvocationID.x;
    if (i >= pc.count) return;

    vec4 p0 = pc.prevState.a[i * 2u];
    vec4 v0 = pc.prevState.a[i * 2u + 1u];
    vec3 anchor = pc.anchors.a[i].xyz;

    // The StackSL program sees the particle's home position and the clock, and hands back a
    // force. Everything else here is fixed, so editing that one string is the whole knob.
    vec3 f = force(vec4(anchor, pc.knobs.x)).xyz * pc.knobs.y;

    f += -(p0.xyz - anchor) * 4.0;   // a linear pull home: critically boring, and what keeps
    f += -v0.xyz * 0.9;              // the rest stable

    vec3 v = v0.xyz + f * pc.dt;
    vec3 p = p0.xyz + v * pc.dt;

    float speed = length(v);
    pc.heat.a[i].x = mix(pc.heat.a[i].x, speed, 0.04);

    pc.state.a[i * 2u]      = vec4(p, p0.w + pc.dt);
    pc.state.a[i * 2u + 1u] = vec4(v, v0.w);

    fieldProbe(speed);
}
```

```kotlin [field-property box="gs_sim" name="code" path="gs_sim_code_.kt"]
var simModule = computeModuleHere {}
var sim = ComputeMesh()
simModule += "step" to sim
renderer += "gpu-state-sim" to simModule

val N = 40000

// ---- four buffers, four different answers to "who writes this, and how often" ----------
//
// State is host-visible: written once below, then written by the compute shader every frame.
// Staging it would be a lie about the access pattern.
var stateA  = defaultBuffer { size = (N * 2).vec4s }.buffer
var stateB  = defaultBuffer { size = (N * 2).vec4s }.buffer
// Anchors are writeOnce(): written here, read by every invocation of every frame, which is
// exactly what device-local memory is for.
var anchors = defaultBuffer { size = N.vec4s; writeOnce() }.buffer
// Heat is zeroInit(): never uploaded at all. The device zeroes it and the shader owns it.
var heat    = defaultBuffer { size = N.vec4s; zeroInit() }.buffer

// One list of home positions, used for the anchors and for both halves of the state, so the
// simulation starts exactly at rest rather than nearly at rest.
val rng = kotlin.random.Random(0x9f7231)
val home = (0 until N).map {
    val angle = rng.nextDouble() * Math.PI * 2
    val radius = Math.sqrt(rng.nextDouble()) * 0.86
    Triple(radius * Math.cos(angle), radius * Math.sin(angle), rng.nextDouble() * 0.5 - 0.25)
}

anchors.write { for (h in home) put(vec(h.first, h.second, h.third, 0.0)) }

// BOTH halves of the ping-pong. The shader reads one and writes the other from frame one, so
// an unwritten second buffer means the first frame integrates from zeroes.
for (target in listOf(stateA, stateB)) {
    target.write {
        for (h in home) {
            put(vec(h.first, h.second, h.third, 0.0))   // position, age
            put(vec(0.0, 0.0, 0.0, 0.0))                // velocity
        }
    }
}

// ---- bindings. Lambdas, evaluated once per dispatch, so they go in once, here. ----------
//
// Parity comes from renderer.frameCounter and nothing else: it is constant for the whole of
// one frame's recording, so this box and the draw box agree without either depending on
// which one's loop ran first.
sim.state     = { if (renderer.frameCounter % 2L == 0L) stateA else stateB }
sim.prevState = { if (renderer.frameCounter % 2L == 0L) stateB else stateA }
sim.anchors   = { anchors }
sim.heat      = { heat }
sim.count     = { N.toUInt() }
sim.dt        = { 1f / 60f }        // FIXED, not measured: stability depends on it
// The clock comes off the same counter as the parity, and not off the wall: a fixed dt
// integrator that walks its force field at wall-clock speed is two different timebases in
// one loop. A slow frame should slow the field, not tear it — and a frame-synchronous docs
// capture of this scene then looks like the scene.
sim.knobs     = { vec(renderer.frameCounter / 60.0, 1.0, 0.0, 0.0) }

// ---- the live slot. On the MODULE, not the mesh. ----------------------------------------
//
// `sim.force = ...` would be a receiver-type mismatch naming both ModulePointer overloads,
// which is a confusing way to learn that a stack.auto slot is a property of the compiled
// shader while the mesh only carries push constants.
//
// StackSL's plain `fbm` is two-dimensional: it lowers to fbm(vec2) over
// coord.xy + coord.zw * 0.07, so feeding it a 3D position gives a field in (x, y) with z
// entering at a fourteenth of the weight of x, which is a shear and not a third dimension.
// fbm4 is the volumetric family's four-dimensional member and reads the whole vec4 as a
// coordinate. The shader hands this program vec4(anchor, seconds) — StackSL's own input
// convention — so one componentwise multiply sets the spatial scale and the rate the field
// evolves at, and time being an axis of the noise rather than an offset into it means the
// volume changes in place instead of sliding past.
//
// Three offsets into one scalar field make a three-channel one. Every fractal word in the
// 3D/4D family divides its accumulated amplitude sum out at runtime, so each fbm4 is exactly
// 0..1 and `0.5 -` is unbiased; the 2D words sum to 0.96875 and would need a correction.
//
// `>c` and not `=c`: a definition must consume its input and leave its result. `=name` copies
// (a -- a) and `>name` moves (a --), so opening with `=c` leaves the input under the result
// and every call pushes two values. Try it and read the box's stack.auto channel.
simModule.force = """
    : vfield
      >c
      <c                     fbm4
      <c [11.3,4.1,7.7,0]  + fbm4
      <c [2.9,17.7,13.3,0] + fbm4
      vec3
    ;

    input [7.0,7.0,7.0,0.048] * vfield
    0.5 - 6.0 *
"""

sim.x = N / 64; sim.y = 1; sim.z = 1

// A GPU-side histogram of per-particle speed, reservoir-sampled in the shader and reduced on
// the card. No readback of the state buffers, and no CPU work per particle.
fieldProbeHere(samples = 4096).display("probe")
displayOpen("probe")

// Published for the draw box, which is a child of this one. Both halves, because the draw box
// has to ask the same parity question this box just asked.
prop<FrameBufferGroup>(gsStateA) = stateA
prop<FrameBufferGroup>(gsStateB) = stateB
prop<FrameBufferGroup>(gsHeat)   = heat

println("simulation launched, N=" + N)
```

## 3. draw

<!-- field-box id="gs_draw" index="2" -->

```edn [field-box id="gs_draw" role="properties"]
{"id" "gs_draw" "index" 2 "name" "draw" "parents" ["root" "gs_sim"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "gs_sim"} "name" "draw" "_tombstone" false "frame" #afield/rect [440.0 40.0 200.0 50.0]} "blockProperties" [{"name" "vertex" "language" "glsl" "path" "gs_draw_vertex_.glsl" "lines" 32 "characters" 852 "sha256" "9070f90741b585315a0d6434434d612aa719f91ebe440a279ad96af673217865"} {"name" "fragment" "language" "glsl" "path" "gs_draw_fragment_.glsl" "lines" 14 "characters" 424 "sha256" "b75dc03bd68870d1d195ed7ed012d29b65775b3abcd6524b196b1c7b7b679564"} {"name" "code" "language" "kotlin" "path" "gs_draw_code_.kt" "lines" 19 "characters" 824 "sha256" "18a82d744a6c7ee27107c75ef23b448878cd0d3ac568ebd6a319762ca3cb8439"}]}
```

```glsl [field-property box="gs_draw" name="vertex" path="gs_draw_vertex_.glsl"]
#version 450
#extension GL_EXT_buffer_reference : require

layout(std430, buffer_reference) buffer State { vec4 a[]; };
layout(std430, buffer_reference) buffer Heat  { vec4 a[]; };

layout(location = 0) out vec2 tc;
layout(location = 1) out float warmth;

layout(push_constant) uniform push {
    State state;
    Heat  heat;
    float aspectRatio;
} pc;

const vec3[6] corner = vec3[6](
    vec3(0,0,0), vec3(1,0,0), vec3(1,1,0),
    vec3(0,0,0), vec3(1,1,0), vec3(0,1,0)
);

void main() {
    uint id = uint(gl_VertexIndex / 6);
    vec3 p = pc.state.a[id * 2u].xyz;
    float h = pc.heat.a[id].x;

    vec3 q = (corner[gl_VertexIndex % 6] - vec3(0.5, 0.5, 0.0)) * (0.009 + h * 0.10);
    vec2 s = (p.xy + q.xy) * vec2(1.0 / pc.aspectRatio, 1.0);

    gl_Position = vec4(s * 0.88, 0.5, 1.0);
    tc = corner[gl_VertexIndex % 6].xy;
    warmth = h;
}
```

```glsl [field-property box="gs_draw" name="fragment" path="gs_draw_fragment_.glsl"]
#version 450
layout(location = 0) out vec4 uFragColor;
layout(location = 0) in vec2 tc;
layout(location = 1) in float warmth;

void main() {
    float d = length(tc - vec2(0.5));
    float a = smoothstep(0.5, 0.12, d);
    if (a < 0.02) discard;
    vec3 cold = vec3(0.16, 0.40, 0.95);
    vec3 hot  = vec3(1.00, 0.78, 0.30);
    vec3 c = mix(cold, hot, clamp(warmth * 5.5, 0.0, 1.0));
    uFragColor = vec4(c, a * 0.30);
}
```

```kotlin [field-property box="gs_draw" name="code" path="gs_draw_code_.kt"]
var drawModule = moduleHere { primitiveType = triangles }
var quads = Mesh()
drawModule += "quads" to quads
renderer += "gpu-state-draw" to drawModule

val N = 40000

// The same parity question the sim box asked, asked the same way and from the same source.
// Read the OTHER half and the picture is one frame stale; ask a private counter instead and
// the two boxes disagree whenever their loops start in different frames.
quads.state = { if (renderer.frameCounter % 2L == 0L) gsStateA else gsStateB }
quads.heat  = { gsHeat }
quads.aspectRatio = { window.width / window.height.toFloat() }

// Six vertices per particle with an empty writer: the vertex shader synthesizes each quad's
// corners from gl_VertexIndex, so there is nothing per-particle for the CPU to do.
quads.set(N * 2 * 3, 0) {}

println("draw launched")
```

