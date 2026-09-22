# Field Document: gpu_probe_bench

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory"]}
```

## 1. 1 - a window, because there is no headless mode

<!-- field-box id="probe_renderer" index="0" -->

```edn [field-box id="probe_renderer" role="properties"]
{"id" "probe_renderer" "index" 0 "name" "1 - a window, because there is no headless mode" "parents" ["root"] "children" ["probe_hello" "probe_chain" "probe_caught"] "properties" {"children" #{#afield/box "probe_hello" #afield/box "probe_chain" #afield/box "probe_caught"} "parents" #{#afield/box "root"} "name" "1 - a window, because there is no headless mode" "_tombstone" false "frame" #afield/rect [40.0 40.0 360.0 110.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "probe_renderer_code_.kt" "lines" 16 "characters" 573 "sha256" "74c3dc7a20ab66f43ec5e4348cfa250d6c51ccb2beae20b38b5a60d914907644"}]}
```

```kotlin [field-property box="probe_renderer" name="code" path="probe_renderer_code_.kt"]
import afield.four.util.Options

// `afield.four` has no headless mode: a compute-only dispatch still needs a Window for
// the device to exist, and it needs an awake display. So every box below is a child of
// this one and resolves `renderer` from it.
//
// RUN THIS BOX FIRST. Before it has run there is no Renderer anywhere and the others
// fail with a context error rather than a GPU one.
prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
once.renderer {
    window = Window("Field · gpuProbe bench")
    Renderer(window)
}
start(renderer::run)
```

## 2. 3 - divergence, 12 Jacobi sweeps, project

<!-- field-box id="probe_chain" index="1" -->

```edn [field-box id="probe_chain" role="properties"]
{"id" "probe_chain" "index" 1 "name" "3 - divergence, 12 Jacobi sweeps, project" "parents" ["root" "probe_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "probe_renderer"} "name" "3 - divergence, 12 Jacobi sweeps, project" "_tombstone" false "frame" #afield/rect [520.0 200.0 460.0 160.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "probe_chain_code_.kt" "lines" 197 "characters" 8233 "sha256" "f01725117c5ea49d22f614698f0dbf5bc64fcdb02f8f1114facd61c64de793d5"}]}
```

```kotlin [field-property box="probe_chain" name="code" path="probe_chain_code_.kt"]
import afield.four.modules.fromContext
import afield.four.util.GpuProbe

val renderer: Renderer = fromContext()

// The chain the workspace document actually runs, at a size that fits in a box.
//
// A MAC (staggered) grid: u on the left x-face of each cell, v on the bottom y-face,
// both in the x and y of one vec4. Divergence is a forward difference and the pressure
// gradient is a backward difference, so the two are exact adjoints and div.grad is
// precisely the 5-point Laplacian the Jacobi sweeps invert.
val N = 32
val CELLS = N * N
val FLOATS = CELLS * 4
val GROUPS = CELLS / 64
val SWEEPS = 12

val head = """#version 450
#extension GL_EXT_buffer_reference : require
layout(std430, buffer_reference) buffer Field { vec4 v[]; };
layout(local_size_x = 64) in;
"""

val divergenceGlsl = head + """
layout(push_constant) uniform Push { Field velocity; Field divergence; uint side; } pc;
void main() {
    uint id = gl_GlobalInvocationID.x;
    uint n = pc.side;
    if (id >= n * n) return;
    uint i = id % n; uint j = id / n;
    float uL = (i == 0u)     ? 0.0 : pc.velocity.v[id].x;
    float uR = (i + 1u >= n) ? 0.0 : pc.velocity.v[id + 1u].x;
    float vB = (j == 0u)     ? 0.0 : pc.velocity.v[id].y;
    float vT = (j + 1u >= n) ? 0.0 : pc.velocity.v[id + n].y;
    pc.divergence.v[id] = vec4((uR - uL) + (vT - vB), 0.0, 0.0, 0.0);
}
"""

val jacobiGlsl = head + """
layout(push_constant) uniform Push { Field pressureIn; Field pressureOut; Field divergence; uint side; } pc;
void main() {
    uint id = gl_GlobalInvocationID.x;
    uint n = pc.side;
    if (id >= n * n) return;
    uint i = id % n; uint j = id / n;
    float pL = pc.pressureIn.v[(i == 0u)     ? id : id - 1u].x;
    float pR = pc.pressureIn.v[(i + 1u >= n) ? id : id + 1u].x;
    float pB = pc.pressureIn.v[(j == 0u)     ? id : id - n ].x;
    float pT = pc.pressureIn.v[(j + 1u >= n) ? id : id + n ].x;
    pc.pressureOut.v[id] = vec4((pL + pR + pB + pT - pc.divergence.v[id].x) * 0.25, 0.0, 0.0, 0.0);
}
"""

val projectGlsl = head + """
layout(push_constant) uniform Push { Field velocity; Field pressure; Field velocityOut; uint side; } pc;
void main() {
    uint id = gl_GlobalInvocationID.x;
    uint n = pc.side;
    if (id >= n * n) return;
    uint i = id % n; uint j = id / n;
    float pC = pc.pressure.v[id].x;
    float pL = pc.pressure.v[(i == 0u) ? id : id - 1u].x;
    float pB = pc.pressure.v[(j == 0u) ? id : id - n ].x;
    float u = (i == 0u) ? 0.0 : pc.velocity.v[id].x - (pC - pL);
    float v = (j == 0u) ? 0.0 : pc.velocity.v[id].y - (pC - pB);
    pc.velocityOut.v[id] = vec4(u, v, 0.0, 0.0);
}
"""

val probe = GpuProbe(renderer, "chain")
val velocity = probe.fixture("velocity", FLOATS)
val velocityOut = probe.fixture("velocityOut", FLOATS)
val divergence = probe.fixture("divergence", FLOATS)
val pressureA = probe.fixture("pressureA", FLOATS)
val pressureB = probe.fixture("pressureB", FLOATS)

// A multi-scale divergent field. Jacobi kills high-frequency error in a handful of
// sweeps and low frequency in hundreds, so a single-scale field would report either a
// spectacular reduction or a negligible one and neither would say anything.
val seeded = FloatArray(FLOATS)
for (j in 0 until N) for (i in 0 until N) {
    val x = (i + 0.5) / N
    val y = (j + 0.5) / N
    val o = (j * N + i) * 4
    seeded[o] = (kotlin.math.sin(2 * Math.PI * x * 3) * kotlin.math.cos(2 * Math.PI * y * 2) +
        0.5 * kotlin.math.sin(2 * Math.PI * x * 9)).toFloat()
    seeded[o + 1] = (kotlin.math.cos(2 * Math.PI * x * 2) * kotlin.math.sin(2 * Math.PI * y * 3) +
        0.5 * kotlin.math.sin(2 * Math.PI * y * 7)).toFloat()
}
probe.upload(velocity, seeded)
probe.fill(pressureA, 0f)
probe.fill(pressureB, 0f)

// The oracle: the divergence stencil again, in Kotlin, so the "after" number is not
// computed by the thing being graded.
fun divergenceOf(f: FloatArray): FloatArray {
    val d = FloatArray(CELLS)
    for (j in 0 until N) for (i in 0 until N) {
        val id = j * N + i
        val uL = if (i == 0) 0f else f[id * 4]
        val uR = if (i + 1 >= N) 0f else f[(id + 1) * 4]
        val vB = if (j == 0) 0f else f[id * 4 + 1]
        val vT = if (j + 1 >= N) 0f else f[(id + N) * 4 + 1]
        d[id] = (uR - uL) + (vT - vB)
    }
    return d
}

fun maxAbs(a: FloatArray): Float {
    var m = 0f
    for (v in a) if (v.isFinite()) m = maxOf(m, kotlin.math.abs(v))
    return m
}

// Three stages. Note there is ONE `stage` call for all twelve Jacobi sweeps: a Stage is
// one shader and one compile, and each `dispatch` adds a workgroup launch plus its own
// barrier, so sweep k reads what sweep k-1 wrote. Twelve compiles would have been the
// obvious mistake. SWEEPS is even, so the answer lands back in pressureA.
probe.stage("divergence", divergenceGlsl).dispatch(groups = GROUPS) {
    bind("velocity") { velocity }
    bind("divergence") { divergence }
    bind("side") { N }
}
val jacobi = probe.stage("jacobi", jacobiGlsl)
for (k in 0 until SWEEPS) {
    val from = if (k % 2 == 0) pressureA else pressureB
    val to = if (k % 2 == 0) pressureB else pressureA
    jacobi.dispatch(groups = GROUPS, label = "sweep" + k) {
        bind("pressureIn") { from }
        bind("pressureOut") { to }
        bind("divergence") { divergence }
        bind("side") { N }
    }
}
probe.stage("project", projectGlsl).dispatch(groups = GROUPS) {
    bind("velocity") { velocity }
    bind("pressure") { pressureA }
    bind("velocityOut") { velocityOut }
    bind("side") { N }
}

frames {
    try {
        probe.arm(velocityOut, divergence)
        while (probe.stillWaiting()) frame()
        println("chain: " + probe.stageList.size + " stages, " +
            probe.stageList.sumOf { it.dispatchCount } + " dispatches, landed after " +
            probe.framesToLand + " frame(s)")

        // Claim 1: the divergence pass against the closed form, at every cell.
        val gpuDiv = probe.read(divergence)
        val cpuDiv = divergenceOf(seeded)
        var worstErr = 0f
        var worstAt = -1
        for (id in 0 until CELLS) {
            val e = kotlin.math.abs(gpuDiv[id * 4] - cpuDiv[id])
            if (e > worstErr) { worstErr = e; worstAt = id }
        }
        val before = maxAbs(cpuDiv)
        probe.atMost("divergence pass vs closed form", worstErr, 1e-6f,
            buffer = "divergence", index = worstAt)
        // Measured 2.546231 on this fixture, which is deterministic CPU arithmetic.
        // A floor of 2.2 fails if somebody edits the seed into something flat, which
        // is the only way the rest of this box could pass while meaning nothing.
        probe.atLeast("input field max |div|", before, 2.2f, buffer = "velocity")

        // Claim 2: the sweep. Pressure re-zeroed each sample, so frames x SWEEPS is
        // the whole iteration count and the points are comparable.
        println("  frames  sweeps   max |div| after   reduction")
        var first = 0f
        var last = 0f
        for (f in listOf(1, 2, 4, 8)) {
            probe.idle()
            probe.fill(pressureA, 0f)
            probe.fill(pressureB, 0f)
            probe.arm(velocityOut)
            while (probe.stillWaiting()) frame()
            var pumped = probe.framesToLand
            while (pumped < f) { frame(); pumped++ }
            probe.idle()
            val after = maxAbs(divergenceOf(probe.read(velocityOut)))
            if (first == 0f) first = after
            last = after
            println("  %6d  %6d   %15.5f   %7.1fx".format(pumped, pumped * SWEEPS, after, before / after))
        }
        // Measured 2584.5x at 96 sweeps and 262.4x between the ends of the sweep.
        // These floors sit just under the measurements on purpose: `>= 30` against
        // 2584 is not a test, and a floor that cannot fail is this project's
        // recurring weakness. A chain that lost a barrier reports single digits.
        probe.atLeast("reduction, best sample", before / last, 1500f, buffer = "velocityOut")
        probe.atLeast("extra sweeps helped", first / last, 150f)
        probe.allFinite("projected field", probe.read(velocityOut), buffer = "velocityOut")
        probe.verdict("chain") { println(it) }
    } finally {
        probe.close()
    }
}
```

## 3. 4 - half a grid, so the sentinel fires

<!-- field-box id="probe_caught" index="2" -->

```edn [field-box id="probe_caught" role="properties"]
{"id" "probe_caught" "index" 2 "name" "4 - half a grid, so the sentinel fires" "parents" ["root" "probe_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "probe_renderer"} "name" "4 - half a grid, so the sentinel fires" "_tombstone" false "frame" #afield/rect [40.0 420.0 420.0 150.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "probe_caught_code_.kt" "lines" 44 "characters" 1594 "sha256" "30fdecb5c3fc52086014c843e44c030209052c6a7355f85cf3b93960e3ff9b57"}]}
```

```kotlin [field-property box="probe_caught" name="code" path="probe_caught_code_.kt"]
import afield.four.modules.fromContext
import afield.four.util.GpuProbe
import afield.four.util.GpuProbeNeverLanded

val renderer: Renderer = fromContext()

// The same shader as box 2, told to write half as many cells on purpose. This is the
// box that says whether the sentinel is load-bearing: a hand-rolled probe that waits
// `repeat(4) { frame() }` and then reads would report whatever was in the buffer, and
// the half that never ran looks exactly like agreement.
val probe = GpuProbe(renderer, "caught")
val out = probe.fixture("out", 256)

probe.stage("fives", """#version 450
#extension GL_EXT_buffer_reference : require
layout(std430, buffer_reference) buffer Field { vec4 v[]; };
layout(local_size_x = 64) in;
layout(push_constant) uniform Push { Field target; uint count; } pc;
void main() {
    uint id = gl_GlobalInvocationID.x;
    if (id >= pc.count) return;
    pc.target.v[id] = vec4(5.0, float(id), 0.0, 0.0);
}
""").dispatch(groups = 1) {
    bind("target") { out }
    bind("count") { 32 }
}

frames {
    try {
        // Four seconds, not the twelve-second default: this case is MEANT to time out
        // and the point is the message, not the wait.
        probe.arm(out, seconds = 4.0)
        try {
            while (probe.stillWaiting()) frame()
            println("NOT CAUGHT - the probe said it landed, which would be a bug in GpuProbe")
        } catch (e: GpuProbeNeverLanded) {
            println("caught, as it should be:")
            for (line in (e.message ?: "").lines()) println("  " + line)
        }
    } finally {
        probe.close()
    }
}
```

## 4. 2 - the whole idiom, on one screen

<!-- field-box id="probe_hello" index="3" -->

```edn [field-box id="probe_hello" role="properties"]
{"id" "probe_hello" "index" 3 "name" "2 - the whole idiom, on one screen" "parents" ["root" "probe_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "probe_renderer"} "name" "2 - the whole idiom, on one screen" "_tombstone" false "frame" #afield/rect [40.0 200.0 420.0 150.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "probe_hello_code_.kt" "lines" 53 "characters" 2454 "sha256" "651d7a1f60a0825f17778be3ed71062aa5f1db1746e68aae5e0fad4bb459d291"}]}
```

```kotlin [field-property box="probe_hello" name="code" path="probe_hello_code_.kt"]
import afield.four.modules.fromContext
import afield.four.util.GpuProbe

val renderer: Renderer = fromContext()

// A fixture, a shader, a dispatch, a wait, a readback, three claims. What matters is
// what is NOT here: no `defaultBuffer` whose access pattern you have to remember
// (`fixture` insists on streamReads(), because a host read out of write-combined
// memory is pathological); no `computeMemoryBarrier` task to append by hand; no frame
// count guessed by feel; and no `finally { renderer.computeList.modules.remove(key) }`.
val probe = GpuProbe(renderer, "hello")
val out = probe.fixture("out", 256)

probe.stage("fives", """#version 450
#extension GL_EXT_buffer_reference : require
layout(std430, buffer_reference) buffer Field { vec4 v[]; };
layout(local_size_x = 64) in;
layout(push_constant) uniform Push { Field target; uint count; } pc;
void main() {
    uint id = gl_GlobalInvocationID.x;
    if (id >= pc.count) return;
    pc.target.v[id] = vec4(5.0, float(id), 0.0, 0.0);
}
""").dispatch(groups = 1) {
    // Misspell `target` here and this line throws, listing the slots the shader does
    // have. `Structure.bind` on its own would have accepted the typo and bound nothing.
    bind("target") { out }
    bind("count") { 64 }
}

frames {
    try {
        // `arm` prefills the output with a sentinel and `stillWaiting` is a CONDITION,
        // so this loop ends when the GPU has demonstrably written every slot. A shader
        // compiles asynchronously and a module whose shader is not ready dispatches as
        // a SILENT no-op, which is why `repeat(4) { frame() }` is a hope and not a wait.
        probe.arm(out)
        while (probe.stillWaiting()) frame()

        val got = probe.read(out)
        println("landed after ${probe.framesToLand} pumped frame(s)")
        probe.exactly("cell 0 value", got[0], 5f, buffer = "out", index = 0)
        probe.exactly("cell 63 id tag", got[63 * 4 + 1], 63f, buffer = "out", index = 253)
        probe.allFinite("out", got, buffer = "out")
        // `{ println(it) }` rather than the default, and this is not cosmetic:
        // box `println` is rewritten by the K2 REPL into `_printlnHere`, which posts
        // to THIS box's output. A `println` inside the library is an ordinary one and
        // goes to the server's stdout, where nobody looking at this box can see it.
        probe.verdict("hello") { println(it) }
    } finally {
        probe.close()
    }
}
```

