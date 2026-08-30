package afield.stacksl.cpu

import afield.SavingContext
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Will the demo document actually open?
 *
 * `public/docs/assets/stacksl_cpu_demo/` is a loadable Field document whose two boxes render a
 * StackSL program on the CPU backend. It is the one artifact a person can open and *feel*, so it
 * is worth more than a compile check — and the ordinary way of checking it is not available: the
 * backdoor's `open` verb reports `ok=true` with zero boxes even for a known-good committed bundle,
 * so it cannot tell a working document from a corrupt one.
 *
 * What can tell is [SavingContext.readEdnTransfers], which is the **real** codec Field's loader
 * uses. This drives it, with a known-good committed bundle beside it as a control, so a passing
 * result cannot be the reader silently returning nothing.
 *
 * It also checks the thing that actually goes wrong with these bundles, per
 * `afield.docs.AgentDemoDriftGuard`: a `#afield/file-inline` property has two copies, the inline
 * fallback in `metadata.edn` and the loose `_code_.kt` beside it, and **Field prefers the loose
 * file**. So an edited demo can run correctly while its canonical metadata is stale, and the two
 * are compared byte for byte here.
 */
class StackSLCpuDemoBundleTest {

    private val root = File(System.getProperty("user.dir"))
    private val demo = File(root, "public/docs/assets/stacksl_cpu_demo")

    /** A committed bundle that is known to work, so "the reader returned boxes" means something. */
    private val control = File(root, "public/docs/assets/mesh_repair_demo")

    @Test
    fun `the demo bundle parses through Field's own EDN codec`() {
        val saving = SavingContext()

        // Control first. If this comes back empty the test is not testing anything.
        val controlBoxes = saving.readEdnTransfers(control.absolutePath)
        assertTrue(controlBoxes.size >= 3, "control bundle read back only ${controlBoxes.size} boxes")

        val boxes = saving.readEdnTransfers(demo.absolutePath)
        val byId = boxes.associateBy { it.id }
        assertEquals(
            setOf("stacksl-cpu-still", "stacksl-cpu-feedback", "root"),
            byId.keys,
            "unexpected box set in the demo bundle"
        )

        // The root must own both boxes, or they load orphaned and invisible.
        val rootChildren = byId.getValue("root").properties["children"]?.toString() ?: ""
        assertTrue("stacksl-cpu-still" in rootChildren, "root does not parent the still box: $rootChildren")
        assertTrue("stacksl-cpu-feedback" in rootChildren, "root does not parent the feedback box: $rootChildren")

        listOf("stacksl-cpu-still", "stacksl-cpu-feedback").forEach { id ->
            val props = byId.getValue(id).properties
            assertTrue(props["name"].toString().isNotBlank(), "$id has no name")
            val code = props["code"]?.toString() ?: ""
            assertTrue(code.length > 500, "$id carries only ${code.length} characters of code")
            assertTrue("StackSLCpu" in code, "$id does not call the CPU backend")
        }
    }

    @Test
    fun `the inline fallback matches the loose source file byte for byte`() {
        val saving = SavingContext()
        val snapshots = saving.readInlineFileSnapshots(demo.absolutePath)
        assertEquals(2, snapshots.size, "expected two #afield/file-inline properties")
        snapshots.forEach { snapshot ->
            val loose = File(demo, snapshot.path)
            assertTrue(loose.isFile, "missing loose file ${snapshot.path}")
            val looseText = loose.readText()
            if (looseText != snapshot.inline) {
                val a = snapshot.inline.lines()
                val b = looseText.lines()
                val at = (0 until maxOf(a.size, b.size)).first { a.getOrNull(it) != b.getOrNull(it) }
                error(
                    "${snapshot.boxId}.${snapshot.property}: ${snapshot.path} differs from metadata.edn " +
                        "at line ${at + 1}\n  metadata: ${a.getOrNull(at)}\n  loose:    ${b.getOrNull(at)}"
                )
            }
        }
    }

    /**
     * And the code in the bundle really runs.
     *
     * Not the box bodies verbatim — `display()` needs a `Box` — but the calls they make, at a size
     * a test can afford. What this rules out is the failure that a bundle-parsing test cannot see:
     * a demo that loads perfectly and then throws the moment someone presses run.
     */
    @Test
    fun `both demo programs render on the CPU backend`() {
        val still = File(demo, "stacksl-cpu-still_code_.kt").readText()
        val stillProgram = Regex("""val program = ""\"(.*?)""\"""", RegexOption.DOT_MATCHES_ALL)
            .find(still)!!.groupValues[1].trimIndent().trim()
        val raster = StackSLCpu.renderStill(stillProgram, dim = 48, parallel = false)
        assertTrue(raster.rgba.all { it.isFinite() })
        assertTrue(raster.toArgb().toSet().size > 50, "the still demo program rendered a near-constant image")

        val feedback = File(demo, "stacksl-cpu-feedback_code_.kt").readText()
        val blocks = Regex("""""\"(.*?)""\"""", RegexOption.DOT_MATCHES_ALL).findAll(feedback)
            .map { it.groupValues[1].trimIndent() }.toList()
        assertEquals(2, blocks.size, "expected the program and the template as the two raw strings")
        val (feedbackProgram, template) = blocks
        assertTrue("{{ stack.auto.program }}" in template, "the demo template lost its slot")

        val dim = 24
        var history = CpuTexture2D(dim, dim, FloatArray(dim * dim * 4) { if (it % 4 == 3) 1f else 0f })
        repeat(6) { frame ->
            val out = StackSLCpu.renderProgram(
                program = feedbackProgram.trim(),
                template = template,
                width = dim, height = dim,
                pushParams = floatArrayOf(frame / 12f, 0f, 0f, 0f),
                samplers = history,
                parallel = false
            )
            assertTrue(out.rgba.all { it.isFinite() }, "frame $frame went non-finite")
            history = CpuTexture2D(out)
        }
    }
}
