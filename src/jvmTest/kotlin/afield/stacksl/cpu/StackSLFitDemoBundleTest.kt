package afield.stacksl.cpu

import afield.SavingContext
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Will the differentiable-fit demo document actually open, and do its three boxes actually work?
 *
 * `public/docs/assets/stacksl_fit_demo/` is the one artifact a person opens and *feels*, so it is
 * worth more than a compile check — and the ordinary way of checking is not available: the
 * backdoor's `open` verb reports `ok=true` with zero boxes even for a known-good committed bundle,
 * so it cannot tell a working document from a corrupt one. What can tell is
 * [SavingContext.readEdnTransfers], the real codec Field's loader uses, driven here with a
 * known-good bundle beside it as a control so that "the reader returned boxes" means something.
 *
 * It also checks the thing that actually goes wrong with these bundles, per
 * `afield.docs.AgentDemoDriftGuard`: a `#afield/file-inline` property has two copies — the inline
 * fallback in `metadata.edn` and the loose `_code_.kt` beside it — and **Field prefers the loose
 * file**, so an edited demo can run correctly while its canonical metadata is stale.
 *
 * What it cannot check, and nothing in Gradle can: box code is compiled by the **K2 REPL against a
 * different import set** from `jvmMain`, so a box can compile in the library and fail in the box.
 * The three boxes here were each run one at a time through `tools/field_backdoor.py`, and
 * `dev_docs/STACKSL_DIFFERENTIABLE.md` records which. What *is* checked below is that each box's
 * *work* — the lift, the fit, the round trip — runs, at a small size, so a change that breaks the
 * demo's substance reddens here even if its imports still resolve.
 */
class StackSLFitDemoBundleTest {

    private val root = File(System.getProperty("user.dir"))
    private val demo = File(root, "public/docs/assets/stacksl_fit_demo")

    /** A committed bundle that is known to work, so "the reader returned boxes" means something. */
    private val control = File(root, "public/docs/assets/stacksl_cpu_demo")

    private val ids = listOf("stacksl-wires", "stacksl-fit", "stacksl-roundtrip")

    @Test
    fun `the demo bundle parses through Field's own EDN codec`() {
        val saving = SavingContext()

        val controlBoxes = saving.readEdnTransfers(control.absolutePath)
        assertTrue(controlBoxes.size >= 3, "control bundle read back only ${controlBoxes.size} boxes")

        val boxes = saving.readEdnTransfers(demo.absolutePath)
        val byId = boxes.associateBy { it.id }
        assertEquals((ids + "root").toSet(), byId.keys, "unexpected box set in the demo bundle")

        val rootChildren = byId.getValue("root").properties["children"]?.toString() ?: ""
        ids.forEach { id ->
            assertTrue(id in rootChildren, "root does not parent '$id', so it loads orphaned: $rootChildren")
            val props = byId.getValue(id).properties
            assertTrue(props["name"].toString().isNotBlank(), "$id has no name")
            val code = props["code"]?.toString() ?: ""
            assertTrue(code.length > 800, "$id carries only ${code.length} characters of code")
            assertTrue("afield.stacksl.cpu" in code, "$id does not import the CPU backend")
        }
    }

    @Test
    fun `the inline fallback matches the loose source file byte for byte`() {
        val saving = SavingContext()
        val snapshots = saving.readInlineFileSnapshots(demo.absolutePath)
        assertEquals(ids.size, snapshots.size, "expected ${ids.size} #afield/file-inline properties")
        snapshots.forEach { snapshot ->
            val loose = File(demo, snapshot.path)
            assertTrue(loose.isFile, "no loose file at ${loose.absolutePath}")
            assertEquals(
                loose.readText(), snapshot.inline,
                "'${snapshot.path}' has drifted from its inline copy in metadata.edn — Field prefers " +
                    "the loose file, so the document would run code the metadata does not carry"
            )
        }
    }

    @Test
    fun `each box's work runs at demo scale`() {
        val template = StackSLCpu.stillTemplate()
        val seed = """
            uv 3.0 * fbm
            0.5 0.5 0.5 1 vec4
            0.5 0.5 0.5 1 vec4
            1.0 1.0 1.0 1 vec4
            0.0 0.33 0.67 1 vec4
            palette
        """.trimIndent()

        // Box 1: the lift, and the table it prints.
        val wired = StackSLWires.lift(seed)
        assertEquals(17, wired.wires.size, "the demo's program should lift 17 wires")
        assertEquals(17, wired.literalWires.size, "all 17 should be literal-backed")
        assertTrue(
            !wired.glsl.contains("${wired.prefix}_wires"),
            "the demo's first box asserts the lowered GLSL has no array left; it does"
        )

        // Boxes 2 and 3: a short fit and a closed round trip.
        val dim = 24
        val target = StackSLCpu.renderProgram(
            seed.replace("0.0 0.33 0.67 1 vec4", "0.12 0.55 0.88 1 vec4"), template, dim, dim
        )
        val result = StackSLFit.fit(seed, target, template, dim = dim, iterations = 12, learningRate = 0.06)
        assertTrue(result.fittedLoss < result.seedLoss, "12 steps did not reduce the loss")
        val viaWires = result.loss.render(result.fittedWires)
        val viaSource = StackSLCpu.renderProgram(result.fittedProgram, template, dim, dim)
        val worst = (0 until dim * dim).flatMap { i ->
            (0 until 3).map { ch ->
                kotlin.math.abs(viaWires.rgba[i * 4 + ch] - viaSource.rgba[i * 4 + ch]).toDouble()
            }
        }.max()
        assertTrue(
            worst <= 1e-4,
            "the demo's round trip is ${"%.3e".format(worst)} apart over ${dim * dim * 3} samples"
        )
        println(
            ("demo scale check: 17 wires lifted, loss %.3e -> %.3e in 12 steps at ${dim}x$dim, " +
                "round trip %.3e").format(result.seedLoss, result.fittedLoss, worst)
        )
    }
}
