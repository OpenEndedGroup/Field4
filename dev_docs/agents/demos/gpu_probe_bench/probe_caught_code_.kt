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
