package afield.examples.four

import afield.four.Renderer
import afield.four.util.GpuProbe
import afield.four.util.GpuProbeNeverLanded
import afield.four.util.Options
import afield.four.util.Window
import afield.four.util.gpuProbe
import kotlin.system.exitProcess

/*
FIELD_TEST
id: four.gpu-probe.self-check
requiresDisplay: true
runner: jvm-main
entrypoint: afield.examples.four.GpuProbeSelfCheck
tags: [smoke, four, gpu, compute, probe, harness]
timeoutSec: 480
expect:
  exitCode: 0
  stdoutContains:
    - "gpu probe self-check PASSED"
*/
/**
 * That [GpuProbe]'s own guarantees actually fire — a negative control for the harness.
 *
 * ### Why a probe needs a probe
 *
 * A sentinel that is never triggered is indistinguishable from a sentinel that cannot trigger. The
 * pressure-chain probe passes sixteen claims and that says nothing at all about whether it *would
 * have* failed, because every mechanism it relies on — the sentinel scan, the deadline, the slot
 * validation, the teardown, the ordering of stages — is only ever exercised in its happy case.
 * This test puts each of them deliberately wrong and requires the harness to notice.
 *
 * That matters more than usual here. The failure mode the whole facility exists to close is a
 * compute module that **silently did not run**: `ComputeModulePointer.render` has its "not valid"
 * `println` commented out, so an uncompiled shader dispatches as a no-op and every buffer keeps
 * whatever it had. A harness that claimed to catch that and did not would be worse than no harness,
 * because it would launder the failure into a pass.
 *
 * ### What it asserts
 *
 * 1. **An under-dispatch is caught, with the index.** The same shader dispatched over half the
 *    grid must leave sentinels, and [GpuProbe.stillWaiting] must throw [GpuProbeNeverLanded]
 *    naming the fixture and the first unwritten index — which is the diagnosis, not decoration:
 *    index 0 is a binding, `CELLS * 2` is a dispatch that covered exactly half.
 * 2. **A misspelled slot is refused at bind time, not at dispatch time.** `Structure.bind` accepts
 *    any string at all, so today a typo binds nothing and the shader reads a null device pointer.
 *    The error must also *list the real slots*, because "no such slot" without them sends the
 *    reader to the GLSL and back.
 * 3. **A shader that does not compile is a failure and not a log line.** [GpuProbe.verdict] must
 *    come back non-zero for a stage whose GLSL is broken.
 * 4. **A foreign buffer cannot be armed.** [GpuProbe.arm] needs the size to prefill, so a buffer
 *    the probe did not allocate has to be rejected rather than silently skipped — a skipped
 *    witness is a sentinel that cannot trigger.
 * 5. **Teardown removes exactly what was added.** `renderer.computeList.modules` must be the same
 *    size after [GpuProbe.close] as before the probe existed, including when the block threw.
 * 6. **Stages run in order, and the barrier between them holds.** Stage A writes 5 into a buffer;
 *    stage B, created after it, reads that buffer and writes double. One frame later every cell
 *    must read exactly 10. If the stages ran in the other order, or if the barrier between the two
 *    modules were missing, B would read the sentinel and write -197530 instead — so this is one
 *    claim that covers both properties, and its wrong answer is unmistakable.
 *
 * ### Running it
 *
 * ```
 * tools/run_field_tests.py --id four.gpu-probe.self-check
 * ```
 */
object GpuProbeSelfCheck {

    private const val N = 64
    private const val CELLS = N * N
    private const val FIELD_FLOATS = CELLS * 4
    private const val GROUPS = CELLS / 64

    private const val PREAMBLE = """#version 450
#extension GL_EXT_buffer_reference : require
layout(std430, buffer_reference) buffer Field { vec4 v[]; };
layout(local_size_x = 64) in;
"""

    /** Writes a constant, so "was it written" is the only question this shader can raise. */
    private val WRITER = PREAMBLE + """
layout(push_constant) uniform Push {
    Field target;
    uint  side;
} pc;

void main() {
    uint id = gl_GlobalInvocationID.x;
    if (id >= pc.side * pc.side) return;
    pc.target.v[id] = vec4(5.0, 0.0, 0.0, 0.0);
}
"""

    /** Doubles what it reads, so reading a sentinel is visible as -197530 rather than as a zero. */
    private val DOUBLER = PREAMBLE + """
layout(push_constant) uniform Push {
    Field source;
    Field result;
    uint  side;
} pc;

void main() {
    uint id = gl_GlobalInvocationID.x;
    if (id >= pc.side * pc.side) return;
    pc.result.v[id] = vec4(pc.source.v[id].x * 2.0, 0.0, 0.0, 0.0);
}
"""

    /** Not GLSL. Present so the "did it compile" path has something to fail on. */
    private val BROKEN = PREAMBLE + """
layout(push_constant) uniform Push { Field target; } pc;
void main() { this is not glsl at all; }
"""

    @JvmStatic
    fun main(args: Array<String>) {
        Options.enableVR = false
        val window = Window("GPU Probe Self-Check")
        val renderer = Renderer(window)
        val pump = { window.pollEvents(); renderer.render() }
        var failures = 0

        try {
            val modulesBefore = renderer.computeList.modules.size
            println("--- self-check: $modulesBefore compute module(s) on the renderer to begin with")

            failures += checkUnderDispatchIsCaught(renderer, pump)
            failures += checkMisspelledSlotIsRefused(renderer, pump)
            failures += checkBrokenShaderIsAFailure(renderer, pump)
            failures += checkForeignBufferCannotBeArmed(renderer, pump)
            failures += checkStageOrderAndBarrier(renderer, pump)

            val modulesAfter = renderer.computeList.modules.size
            println("  after five probes, all closed: $modulesAfter compute module(s)")
            if (modulesAfter != modulesBefore) {
                println(
                    "  FAIL: teardown leaked ${modulesAfter - modulesBefore} module(s): " +
                        renderer.computeList.modules.keys.joinToString(", ")
                )
                failures++
            } else {
                println("  ok: teardown removed exactly what the probes added")
            }
        } finally {
            renderer.cleanup()
            window.cleanup()
        }

        if (failures > 0) {
            println("gpu probe self-check FAILED ($failures)")
            exitProcess(1)
        }
        println("gpu probe self-check PASSED")
        exitProcess(0)
    }

    /**
     * Half the grid dispatched, so half the output must still hold [GpuProbe.SENTINEL].
     *
     * The deadline is deliberately short: this case is *meant* to time out, and the point of the
     * test is the message, not the wait.
     */
    private fun checkUnderDispatchIsCaught(renderer: Renderer, pump: () -> Unit): Int {
        println("--- 1: an under-dispatch leaves sentinels and is caught")
        var failures = 0
        val thrown = gpuProbe(renderer, "selfcheck-under", pump) { probe ->
            val out = probe.fixture("out", FIELD_FLOATS)
            probe.stage("writer", WRITER).dispatch(groups = GROUPS / 2) {
                bind("target") { out }
                bind("side") { N }
            }
            try {
                probe.settle(out, seconds = 3.0)
                null
            } catch (e: GpuProbeNeverLanded) {
                e
            }
        }
        if (thrown == null) {
            println("  FAIL: half a grid was dispatched and the probe reported it landed anyway")
            return 1
        }
        val message = thrown.message ?: ""
        println("  caught, and it says:")
        message.lines().forEach { println("    $it") }
        // The fixture name, so a probe with four outputs says which one.
        if (!message.contains("'out'")) {
            println("  FAIL: the message does not name the fixture"); failures++
        }
        // Exactly half of FIELD_FLOATS, because the shader writes a whole vec4 per invocation and
        // exactly half the invocations ran. This is the number that distinguishes "wrong dispatch
        // size" from "wrong binding", so it has to be right rather than merely present.
        if (!message.contains("${FIELD_FLOATS / 2} of $FIELD_FLOATS")) {
            println("  FAIL: the message does not report ${FIELD_FLOATS / 2} of $FIELD_FLOATS unwritten slots")
            failures++
        }
        if (!message.contains("from index ${FIELD_FLOATS / 2}")) {
            println("  FAIL: the message does not report the first unwritten index as ${FIELD_FLOATS / 2}")
            failures++
        }
        if (failures == 0) println("  ok: named the fixture, the count and the first unwritten index")
        return failures
    }

    private fun checkMisspelledSlotIsRefused(renderer: Renderer, pump: () -> Unit): Int {
        println("--- 2: a misspelled slot is refused at bind time, listing the real slots")
        val thrown = gpuProbe(renderer, "selfcheck-slot", pump) { probe ->
            val out = probe.fixture("out", FIELD_FLOATS)
            val stage = probe.stage("writer", WRITER)
            // The shader has to have compiled before its slots are knowable, and the compile is
            // asynchronous, so give it a frame or two first. `slots` empty and `slots` wrong are
            // different failures and this test is about the second one.
            var waited = 0
            while (stage.slots.isEmpty() && waited < 240) { pump(); waited++ }
            println("  the stage reports slots ${stage.slots} after $waited frames")
            try {
                stage.dispatch(groups = GROUPS) {
                    bind("targt") { out }
                    bind("side") { N }
                }
                null
            } catch (e: IllegalArgumentException) {
                e
            }
        }
        if (thrown == null) {
            println("  FAIL: binding a slot named 'targt' was accepted")
            return 1
        }
        val message = thrown.message ?: ""
        println("  caught: $message")
        var failures = 0
        if (!message.contains("targt")) {
            println("  FAIL: the message does not quote the offending name"); failures++
        }
        if (!message.contains("target")) {
            println("  FAIL: the message does not list the real slot 'target'"); failures++
        }
        if (failures == 0) println("  ok: refused the typo and listed the real slots")
        return failures
    }

    private fun checkBrokenShaderIsAFailure(renderer: Renderer, pump: () -> Unit): Int {
        println("--- 3: a shader that does not compile is a verdict failure, not a log line")
        val verdict = gpuProbe(renderer, "selfcheck-broken", pump) { probe ->
            val stage = probe.stage("broken", BROKEN)
            repeat(8) { pump() }
            println("  the broken stage reports valid=${stage.valid}")
            if (stage.valid) probe.fail("a shader containing 'this is not glsl at all' compiled")
            probe.verdict("broken shader")
        }
        return if (verdict > 0) {
            println("  ok: the verdict reports $verdict failure(s)")
            0
        } else {
            println("  FAIL: a probe whose only shader did not compile returned a clean verdict")
            1
        }
    }

    private fun checkForeignBufferCannotBeArmed(renderer: Renderer, pump: () -> Unit): Int {
        println("--- 4: a buffer the probe did not allocate cannot be armed")
        // Allocated by a *different* probe, so it is a real, usable FrameBufferGroup that this
        // probe simply has no size for. A silently skipped witness is a sentinel that cannot fire.
        val foreign = gpuProbe(renderer, "selfcheck-owner", pump) { it.fixture("owned", 64) }
        val thrown = gpuProbe(renderer, "selfcheck-foreign", pump) { probe ->
            try {
                probe.arm(foreign)
                null
            } catch (e: IllegalStateException) {
                e
            }
        }
        return if (thrown != null) {
            println("  caught: ${thrown.message}")
            println("  ok")
            0
        } else {
            println("  FAIL: arming a foreign buffer was accepted, so its sentinel would never fire")
            1
        }
    }

    /**
     * Stage order and the barrier between two modules, in one claim.
     *
     * A writes 5, B doubles what A wrote. 10 means ordered and synchronised. `-197530` means B ran
     * first, or B ran without seeing A's writes — which is [GpuProbe.SENTINEL] times two, and is
     * the reason the doubler multiplies rather than copies.
     */
    private fun checkStageOrderAndBarrier(renderer: Renderer, pump: () -> Unit): Int {
        println("--- 5: stage A then stage B, in one frame, across a barrier")
        return gpuProbe(renderer, "selfcheck-order", pump) { probe ->
            val middle = probe.fixture("middle", FIELD_FLOATS)
            val result = probe.fixture("result", FIELD_FLOATS)

            probe.stage("writer", WRITER).dispatch(groups = GROUPS) {
                bind("target") { middle }
                bind("side") { N }
            }
            probe.stage("doubler", DOUBLER).dispatch(groups = GROUPS) {
                bind("source") { middle }
                bind("result") { result }
                bind("side") { N }
            }

            probe.settle(middle, result)
            val got = probe.read(result)
            var wrong = 0
            var firstWrong = -1
            var sawSentinelDoubled = 0
            for (id in 0 until CELLS) {
                val v = got[id * 4]
                if (v != 10.0f) {
                    wrong++
                    if (firstWrong < 0) firstWrong = id
                    if (v == GpuProbe.SENTINEL * 2f) sawSentinelDoubled++
                }
            }
            println(
                "  landed after ${probe.framesToLand} frame(s); $wrong of $CELLS cells are not " +
                    "exactly 10.0" +
                    (if (firstWrong >= 0) ", first at cell $firstWrong (value ${got[firstWrong * 4]})" else "") +
                    (if (sawSentinelDoubled > 0) "; $sawSentinelDoubled of them are the doubled sentinel, " +
                        "so stage B did not see stage A" else "")
            )
            probe.exactly("cells not exactly 10.0", wrong, 0, buffer = "result", index = firstWrong.takeIf { it >= 0 })
            probe.exactly("cells holding the doubled sentinel", sawSentinelDoubled, 0, buffer = "result")
            probe.verdict("stage order and barrier")
        }
    }
}
