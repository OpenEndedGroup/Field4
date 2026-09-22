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
