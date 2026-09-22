# Field Document: pattern_particles

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory"]}
```

## 1. renderer

<!-- field-box id="pp_renderer" index="0" -->

```edn [field-box id="pp_renderer" role="properties"]
{"id" "pp_renderer" "index" 0 "name" "renderer" "parents" ["root"] "children" ["pp_audio"] "properties" {"children" #{#afield/box "pp_audio"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 200.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "pp_renderer_code_.kt" "lines" 10 "characters" 252 "sha256" "ae9dba7df6748d93a116cf45bd683ef6d5c5b43a102d41dde92865b2977da591"}]}
```

```kotlin [field-property box="pp_renderer" name="code" path="pp_renderer_code_.kt"]
import afield.four.util.Options
prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
once.renderer {
    window = Window("Field · A pattern that moves particles")
    Renderer(window)
}
println("renderer ready")
start(renderer::run)
```

## 2. pattern, played and analysed

<!-- field-box id="pp_audio" index="1" -->

```edn [field-box id="pp_audio" role="properties"]
{"id" "pp_audio" "index" 1 "name" "pattern, played and analysed" "parents" ["root" "pp_renderer"] "children" ["pp_compute"] "properties" {"children" #{#afield/box "pp_compute"} "parents" #{#afield/box "root" #afield/box "pp_renderer"} "name" "pattern, played and analysed" "_tombstone" false "frame" #afield/rect [260.0 40.0 240.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "pp_audio_code_.kt" "lines" 43 "characters" 1817 "sha256" "5cf35fe63681a1537f0789906d572b6b51e609090802116717d30eb5010ecb38"}]}
```

```kotlin [field-property box="pp_audio" name="code" path="pp_audio_code_.kt"]
import afield.four.synth.BandSplit
import afield.four.synth.PatSynthLaunch
import afield.four.synth.PatWave
import afield.four.synth.audioEngine
import afield.four.synth.logBands
import afield.four.synth.pat
import afield.four.synth.patSynth
import afield.four.synth.patVoice
import afield.four.synth.snapshot
import afield.four.synth.stack

// The numbers the compute box reads. An inherited property rather than a global, so the
// box graph is what says which shader is listening to which pattern.
prop<DoubleArray>(bandEnergy)
prop<PatSynthLaunch>(patternSynth)

bandEnergy = DoubleArray(4)

// A kick on the beat, a mid-register square stab on the offbeats, and sixteen hats a
// cycle: three parts in three parts of the spectrum, so four bands have something to tell
// apart. This is `PatSynthDocs`' seam-bands scene.
patternSynth = patSynth(
    stack(pat("bd*4"), pat("~ 52 ~ 52"), pat("hh*16")),
    cps = 2.0,
    voice = patVoice(wave = PatWave.SQUARE, decay = 0.012, sustain = 0.08),
)

// Four band-passes, log-spaced. Field's own Biquad, owned by this box rather than by the
// synth graph, reading a snapshot the audio path published.
val split = logBands(4, lowHz = 70.0, highHz = 8000.0, sampleRate = patternSynth.bank.sampleRate)

// This box pulls the bank itself, so the analysis has samples whether or not the machine
// has an output device. To hear it, return `patternSynth` from the box instead and let the
// engine pull; do not do both, or the voices advance twice and the bar plays double speed.
println("output device present: ${audioEngine().outputActive}; this box is pulling the bank")
val perFrame = patternSynth.bank.sampleRate / 60

frames {
    while (frame()) {
        patternSynth.advanceOffline(perFrame)
        bandEnergy = split.measure(patternSynth.tap.snapshot())
    }
}
```

## 3. four bands into four push constants

<!-- field-box id="pp_compute" index="2" -->

```edn [field-box id="pp_compute" role="properties"]
{"id" "pp_compute" "index" 2 "name" "four bands into four push constants" "parents" ["root" "pp_audio"] "children" ["pp_draw"] "properties" {"children" #{#afield/box "pp_draw"} "parents" #{#afield/box "root" #afield/box "pp_audio"} "name" "four bands into four push constants" "_tombstone" false "frame" #afield/rect [260.0 120.0 240.0 50.0]} "blockProperties" [{"name" "compute" "language" "glsl" "path" "pp_compute_compute_.glsl" "lines" 44 "characters" 1731 "sha256" "a39f2af2de49c08d54d47188538768d048ec1a5c23f4d4d5448094a8cd1e8a5f"} {"name" "code" "language" "kotlin" "path" "pp_compute_code_.kt" "lines" 35 "characters" 1231 "sha256" "706692fffbe8117af9b08b2be27af5cde8dab7e18c458c1b8b166fef797258dc"}]}
```

```glsl [field-property box="pp_compute" name="compute" path="pp_compute_compute_.glsl"]
#version 450
#extension GL_EXT_buffer_reference : require
layout(local_size_x = 64) in;

layout(std430, buffer_reference) buffer Seeds { vec4 a[]; };
layout(std430, buffer_reference) buffer Out   { vec4 a[]; };

layout(push_constant) uniform dat {
    Seeds seeds;      // written once: one random phase and radius per particle
    Out   outData;    // this frame's positions, read by the draw box
    float time;       // seconds
    float band0;      // 70 Hz    — the kick
    float band1;      // 340 Hz
    float band2;      // 1.6 kHz  — the square stab's harmonics
    float band3;      // 8 kHz    — the hats
    uint  count;
} pc;

void main() {
    uint i = gl_GlobalInvocationID.x;
    if (i >= pc.count) return;

    vec4 seed = pc.seeds.a[i];
    uint ring = i & 3u;
    // One band per ring, so which ring moves says which band is loud.
    float energy = ring == 0u ? pc.band0
                 : ring == 1u ? pc.band1
                 : ring == 2u ? pc.band2
                              : pc.band3;

    // The ring's radius rises a little with its band and its *thickness* rises a lot, so
    // a loud band blooms into an annulus rather than sliding outwards. A ring of 20,000
    // particles all at one radius reads as a drawn circle, which is the wrong picture.
    float base = 0.14 + 0.17 * float(ring);
    float thickness = 0.012 + 0.10 * energy;
    float radius = base * (1.0 + 0.55 * energy) + thickness * (seed.y - 0.5) * 2.0;
    float spin = 0.22 + 0.15 * float(ring);
    float angle = seed.x * 6.2831853 + pc.time * spin;

    pc.outData.a[i].x = radius * cos(angle);
    pc.outData.a[i].y = radius * sin(angle);
    pc.outData.a[i].z = float(ring);
    pc.outData.a[i].w = 0.45 + 1.8 * energy;
}
```

```kotlin [field-property box="pp_compute" name="code" path="pp_compute_code_.kt"]
import afield.four.buffers.FrameBufferGroup

var particles = computeModuleHere {}
var step = ComputeMesh()
particles += "step" to step
renderer += "pattern-particles" to particles

val N = 24000

var seeds = defaultBuffer { size = N.vec4s; writeOnce() }.buffer
var positions = defaultBuffer { size = N.vec4s }.buffer
prop<FrameBufferGroup>(outputPositions) = positions

seeds.write {
    for (i in 0 until N) put(vec(Math.random(), Math.random(), 0.0, 0.0))
}

step.seeds = { seeds }
step.outData = { positions }
step.count = { N.toUInt() }   // a uint push member needs a UInt-returning lambda

// The whole point of the chain, in five lines: a push-constant float bound to a lambda that
// reads the band energy the audio box published. The renderer evaluates these once per
// frame, so the shader sees this frame's analysis and nothing is copied by hand.
var t = 0.0
step.time = { t += 1.0 / 60.0; t.f }
step.band0 = { (bandEnergy.getOrElse(0) { 0.0 } * 2.4).f }
step.band1 = { (bandEnergy.getOrElse(1) { 0.0 } * 2.4).f }
step.band2 = { (bandEnergy.getOrElse(2) { 0.0 } * 2.4).f }
step.band3 = { (bandEnergy.getOrElse(3) { 0.0 } * 2.4).f }

step.x = N / 64
step.y = 1
step.z = 1
println("$N particles, four rings, four bands")
```

## 4. draw

<!-- field-box id="pp_draw" index="3" -->

```edn [field-box id="pp_draw" role="properties"]
{"id" "pp_draw" "index" 3 "name" "draw" "parents" ["root" "pp_compute"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "pp_compute"} "name" "draw" "_tombstone" false "frame" #afield/rect [260.0 200.0 240.0 50.0]} "blockProperties" [{"name" "vertex" "language" "glsl" "path" "pp_draw_vertex_.glsl" "lines" 29 "characters" 835 "sha256" "07ec58340aad63b810b42bbbecf3e89d58abec9096e3191d28a6a70b5c9ee467"} {"name" "fragment" "language" "glsl" "path" "pp_draw_fragment_.glsl" "lines" 22 "characters" 744 "sha256" "ee67e964448d89fa0aebcdf1006a6b0110c8d960a8ed337961968e1952834aa9"} {"name" "code" "language" "kotlin" "path" "pp_draw_code_.kt" "lines" 21 "characters" 558 "sha256" "34cb35c34db39ac112eed2534d8958215c3cffce6cd39143795dd7e63b757314"}]}
```

```glsl [field-property box="pp_draw" name="vertex" path="pp_draw_vertex_.glsl"]
#version 450
#extension GL_EXT_buffer_reference : require

layout(std430, buffer_reference) buffer Positions { vec4 pos[]; };

layout(location = 0) out vec2 tc;
layout(location = 1) out float ring;

layout(push_constant) uniform push {
    Positions inData;
    float aspectRatio;
} pc;

const vec3[6] part = {
    vec3(0,0,0), vec3(1,0,0), vec3(1,1,0),
    vec3(0,0,0), vec3(1,1,0), vec3(0,1,0)
};

void main() {
    vec4 particle = pc.inData.pos[gl_VertexIndex / 6];
    float scale = particle.w;
    vec3 p = (part[gl_VertexIndex % 6] - vec3(0.5, 0.5, 0)) * scale;
    // Net scale 0.9, so the outermost ring at full energy still sits inside the frame.
    p.xy += particle.xy * 18.0;
    p.xy *= vec2(1.0 / pc.aspectRatio, 1.0);
    gl_Position = vec4(p * 0.05, 1.0);
    tc = part[gl_VertexIndex % 6].xy;
    ring = particle.z;
}
```

```glsl [field-property box="pp_draw" name="fragment" path="pp_draw_fragment_.glsl"]
#version 450
layout(location = 0) out vec4 uFragColor;
layout(location = 0) in vec2 tc;
layout(location = 1) in float ring;

// One colour per ring, and these four are `afield.visualization.signalPalette` in its own
// order, so a ring here and a trace on a showSignals plot of the same band are the same
// colour. #7ad9ff #ff9e4a #8fff9a #ffe066.
const vec3[4] tint = {
    vec3(0.478, 0.851, 1.000),
    vec3(1.000, 0.620, 0.290),
    vec3(0.561, 1.000, 0.604),
    vec3(1.000, 0.878, 0.400)
};

void main() {
    float d = max(0.0, 0.5 - length(tc - vec2(0.5)));
    float w = fwidth(d);
    d = smoothstep(0.1, 0.1 + w, d) * smoothstep(0.14 + w, 0.14, d);
    if (d < 0.01) discard;
    uFragColor = vec4(tint[int(ring) & 3], d * 0.14);
}
```

```kotlin [field-property box="pp_draw" name="code" path="pp_draw_code_.kt"]
var module = moduleHere {
    primitiveType = triangles
}

var quads = Mesh()
module += "quads" to quads
renderer += "pattern-particles-draw" to module

val N = 24000
quads.inData = { outputPositions }
quads.aspectRatio = { window.width / window.height.toFloat() }
quads.set(N * 2 * 3, 0) {}

// Printed from this box rather than from the compute box, because a docs capture waits on
// the output of the box it is capturing.
println(
    "$N particles drawn in four rings; bands now " +
        bandEnergy.joinToString(", ") { "%.3f".format(it) },
)

quads
```

