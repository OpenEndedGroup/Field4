# Field Document: petri_sparks

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. renderer

<!-- field-box id="ps_renderer" index="0" -->

```edn [field-box id="ps_renderer" role="properties"]
{"id" "ps_renderer" "index" 0 "name" "renderer" "parents" ["root"] "children" ["ps_net"] "properties" {"children" #{#afield/box "ps_net"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 200.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ps_renderer_code_.kt" "lines" 11 "characters" 253 "sha256" "b69f2b250f836cae892866ac6eb3a5018f6076535a32a3f08010c0ff17071e3f"}]}
```

```kotlin [field-property box="ps_renderer" name="code" path="ps_renderer_code_.kt"]
import afield.four.util.Options

prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
once.renderer {
    window = Window("Field · A Petri net that throws sparks")
    Renderer(window)
}
println("renderer ready")
start(renderer::run)
```

## 2. the net

<!-- field-box id="ps_net" index="1" -->

```edn [field-box id="ps_net" role="properties"]
{"id" "ps_net" "index" 1 "name" "the net" "parents" ["root" "ps_renderer"] "children" ["ps_sparks"] "properties" {"children" #{#afield/box "ps_sparks"} "parents" #{#afield/box "root" #afield/box "ps_renderer"} "name" "the net" "_tombstone" false "frame" #afield/rect [260.0 40.0 240.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ps_net_code_.kt" "lines" 41 "characters" 1090 "sha256" "64cd8ea0db644df208825d1465cdae1570b029479d0e23419d365d4a7b8022ea"}]}
```

```kotlin [field-property box="ps_net" name="code" path="ps_net_code_.kt"]
import afield.petri.PetriNet
import afield.petri.PetriRun
import afield.petri.petri

// Inherited by the box below, which is all it takes for the particles to hear the net.
prop<PetriNet>(sparkNet)
prop<PetriRun>(sparkRun)

sparkNet = petri("""
    #petri
    !grid 1/8

    // four places round: a kick each lap, and a token into charge
    k0 : 1
    k0 -[ kick : bd ]-> k1 + charge
    k1 -[ k1.go ]-> k2
    k2 -[ k2.go ]-> k3
    k3 -[ k3.go ]-> k0

    // three places round: a snare each lap
    s0 : 1
    s0 -[ snare : sn ]-> s1
    s1 -[ s1.go ]-> s2
    s2 -[ s2.go ]-> s0

    // five places round: two hats each lap
    h0 : 1
    h0 -[ hat : hh ]-> h1
    h1 -[ h1.go ]-> h2
    h2 -[ hat.2 : hh ]-> h3
    h3 -[ h3.go ]-> h4
    h4 -[ h4.go ]-> h0

    // four kicks fill charge, and emptying it is a clap
    charge : 0 ^4
    4*charge -[ clap : cp ]->
""")

// One run, shared: a run remembers the steps it has taken, so every query below reuses them.
sparkRun = sparkNet.run()
println("net ready: ${sparkNet.places.size} places, ${sparkNet.transitions.size} transitions")
```

## 3. firings into sparks

<!-- field-box id="ps_sparks" index="2" -->

```edn [field-box id="ps_sparks" role="properties"]
{"id" "ps_sparks" "index" 2 "name" "firings into sparks" "parents" ["root" "ps_net"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "ps_net"} "name" "firings into sparks" "_tombstone" false "frame" #afield/rect [260.0 120.0 240.0 50.0]} "blockProperties" [{"name" "vertex" "language" "glsl" "path" "ps_sparks_vertex_.glsl" "lines" 107 "characters" 3767 "sha256" "6d70ab79446a0d88f31021762f85dc1641471719bd910965f9a7718593317993"} {"name" "fragment" "language" "glsl" "path" "ps_sparks_fragment_.glsl" "lines" 12 "characters" 343 "sha256" "32533ff454fe2a8e91c1c79fafaf0425966187df654994df6c08e0618b7299ca"} {"name" "code" "language" "kotlin" "path" "ps_sparks_code_.kt" "lines" 94 "characters" 3499 "sha256" "b8c010055bbe40b3e16d5809afd347ce9be13d5b153650d74a25e9c4467ef42b"}]}
```

```glsl [field-property box="ps_sparks" name="vertex" path="ps_sparks_vertex_.glsl"]
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
```

```glsl [field-property box="ps_sparks" name="fragment" path="ps_sparks_fragment_.glsl"]
#version 450
layout(location = 0) in vec2 tc;
layout(location = 1) in vec3 colour;
layout(location = 0) out vec4 uFragColor;

void main() {
    float d = length(tc);
    if (d > 1.0) discard;
    float glow = (1.0 - d) * (1.0 - d);
    // Added, not blended: where sparks cross, they get brighter.
    uFragColor = vec4(colour * glow, 0.0);
}
```

```kotlin [field-property box="ps_sparks" name="code" path="ps_sparks_code_.kt"]
import afield.four.synth.PatArc
import afield.four.synth.PatQ
import org.lwjgl.vulkan.VK10.*

val cps = 0.5     // cycles a second; the net's grid is an eighth of a cycle
val life = 1.6    // seconds a spark lives, LIFE in the vertex shader
val slots = 24    // SLOTS in the vertex shader: firings drawn at once
val particles = slots * 1400 + 3 * 2400

// The rings as the shader draws them, centre out, and where each sounding transition
// launches from: (colour, ring, place). The clap launches from the centre.
val rings = listOf(listOf("s0", "s1", "s2"), listOf("k0", "k1", "k2", "k3"), listOf("h0", "h1", "h2", "h3", "h4"))
val radius = listOf(0.36, 0.56, 0.76)
val launches = mapOf(
    "kick" to Triple(0, 1, 0),
    "snare" to Triple(1, 0, 0),
    "hat" to Triple(2, 2, 0),
    "hat.2" to Triple(3, 2, 2),
    "clap" to Triple(4, -1, 0),
)

// A place's angle on its ring, the token going clockwise. Each ring's first place is a
// third of a turn round from the last one's, so the three launch points spread out.
fun angle(ring: Int, place: Double) = Math.PI / 2 - 2 * Math.PI * (ring / 3.0 + place / rings[ring].size)

var module = moduleHere {
    primitiveType = triangles
    blend = true
    srcColor = VK_BLEND_FACTOR_ONE
    dstColor = VK_BLEND_FACTOR_ONE
    depthTest = false
    depthWrite = false
}
var sparks = Mesh()
module += "sparks" to sparks
renderer += "petri-sparks" to module

// perFrame: one buffer per frame in flight, so it can be rewritten every frame.
var bursts = defaultBuffer {
    perFrame = true
    size = slots.vec4s
}.buffer

var charge = 0.0
val tokens = DoubleArray(3)

sparks.bursts = { bursts }
sparks.aspectRatio = { window.width / window.height.toFloat() }
sparks.charge = { charge.f }
sparks.token0 = { angle(0, tokens[0]).f }
sparks.token1 = { angle(1, tokens[1]).f }
sparks.token2 = { angle(2, tokens[2]).f }
sparks.set(particles * 6, 0) {}

println("sparks ready: $particles particles, one burst per firing of ${launches.keys}")

var frame = 0
loop {
    // Where the net is, in cycles, counting frames at sixty a second.
    val now = ++frame / 60.0 * cps
    val at = PatQ.fromDouble(now)

    // Everything that fired in the last `life` seconds, newest last.
    val since = PatQ.fromDouble(maxOf(0.0, now - life * cps))
    val recent = sparkRun.firingsOver(PatArc(since, at))
        .filter { it.transition in launches }
        .takeLast(slots)

    bursts.write {
        for (s in 0 until slots) {
            val firing = recent.getOrNull(s)
            if (firing == null) {
                put(vec(0.0, 0.0, -1.0, 0.0))
            } else {
                val (colour, ring, place) = launches.getValue(firing.transition)
                val r = if (ring < 0) 0.0 else radius[ring]
                val a = if (ring < 0) 0.0 else angle(ring, place.toDouble())
                val age = (now - firing.at.toDouble()) / cps
                // Integer part: the colour. Fraction: a seed, so no two bursts match.
                put(vec(r * Math.cos(a), r * Math.sin(a), age, colour + (firing.index % 4093) / 4096.0))
            }
        }
    }

    // Each ring's token, between the place it is in and the next one.
    val marking = sparkRun.markingAt(at)
    val step = (now * 8) % 1.0
    rings.forEachIndexed { i, places ->
        tokens[i] = places.indexOfFirst { marking.count(it) > 0 } + step
    }
    // charge holds 0 to 4 tokens; the dust brightens as it fills.
    charge = sparkRun.countAt("charge", at) / 4.0
    true
}
```

