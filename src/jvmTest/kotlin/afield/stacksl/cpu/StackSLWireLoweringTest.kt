package afield.stacksl.cpu

import afield.stacksl.StackSLCompiler
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Is a wired program the *same shader* as the program it came from?
 *
 * [StackSLWires.lower] rewrites the emitted GLSL: the wire array becomes scalar globals, the wire
 * parameter and argument disappear from every signature and call, and the count, the initialiser
 * and the overloaded forwarding wrapper are dropped. That is four textual rewrites on a generated
 * artifact, which is exactly the kind of change that looks right and is not.
 *
 * So it is not inspected, it is measured. Every gallery program that does not sample a texture is
 * compiled both ways — ordinary (`externalizeConstants = false`) and wired-then-lowered with every
 * wire at its recorded initial — and the two rasters are compared **bit for bit**. They are
 * supposed to be the same shader, so anything short of bit-identity is a defect in the lowering
 * and not a tolerance question.
 *
 * Red-first checked, and the two failures are usefully different. With the
 * `<prefix>_wires[i]` to `<prefix>_wire_i` substitution left out, 28 of the 29 programs die with
 * `undefined variable 'program_wires'` — the array *parses* as an index into a name that no longer
 * exists, which is why the second test below also asserts the name is gone rather than trusting
 * the render. With the forwarding wrapper left in, the same 28 die with
 * `GLSL arrays are not in the StackSL subset`, on the wrapper's own local array declaration. (The
 * 29th is `uv-coordinates`, which lifts no wires at all and is unaffected by either.)
 */
class StackSLWireLoweringTest {

    private val root = File(System.getProperty("user.dir"))

    @Test
    fun `a lowered wired program renders the same bits as the ordinary compile`() {
        val entries = StackSLCpuGallery.load(root)
        var compared = 0
        var wires = 0
        val mismatched = ArrayList<String>()
        val refused = ArrayList<String>()

        entries.forEach { entry ->
            if (StackSLCpuGallery.tierOf(entry) == AgreementTier.FEEDBACK) return@forEach
            val reference = runCatching {
                StackSLCpu.renderProgram(
                    entry.program, entry.fragmentTemplate, DIM, DIM,
                    pushParams = if (entry.animate) FloatArray(4) else null
                )
            }.getOrElse { refused += "${entry.name}: reference render: ${it.message}"; return@forEach }

            val wired = runCatching { StackSLWires.lift(entry.program) }
                .getOrElse { refused += "${entry.name}: lift: ${it.message}"; return@forEach }
            val source = StackSLCpu.substituteSlot(entry.fragmentTemplate, wired.glsl, wired.slot)
            val uniforms = HashMap<String, GlslValue>(wired.globals(wired.initial))
            if (entry.animate) uniforms["pc.params"] = GlslValue(GlslType.VEC4, FloatArray(4))
            val lowered = runCatching { StackSLCpu.renderFragment(source, DIM, DIM, uniforms) }
                .getOrElse { refused += "${entry.name}: lowered render: ${it.message}"; return@forEach }

            val differing = reference.rgba.indices.count {
                reference.rgba[it].toRawBits() != lowered.rgba[it].toRawBits()
            }
            compared++
            wires += wired.wires.size
            if (differing != 0) {
                mismatched += "${entry.name}: $differing of ${reference.rgba.size} floats differ " +
                    "(${wired.wires.size} wires)"
            }
        }

        println(
            "wire lowering: $compared gallery programs compared bit-for-bit, $wires wires lifted in " +
                "total, ${mismatched.size} mismatched, ${refused.size} refused"
        )
        assertTrue(refused.isEmpty(), "programs the wired path could not run: $refused")
        assertTrue(mismatched.isEmpty(), "lowered rasters that differ from the ordinary compile: $mismatched")
        assertTrue(
            compared >= MIN_PROGRAMS,
            "only $compared programs were compared, expected at least $MIN_PROGRAMS"
        )
        assertTrue(wires >= MIN_WIRES, "only $wires wires were lifted, expected at least $MIN_WIRES")
    }

    /**
     * The refusal that makes [StackSLWires.lower] necessary, demonstrated rather than asserted.
     *
     * `dev_docs/STACKSL_DIFFERENTIABLE.md` claims the externalised emitted GLSL cannot be parsed by
     * this backend at all. That is a claim about a refusal, so it takes a reproducer: here it is.
     * Named in camelCase rather than the backticked style of its neighbours because
     * `dev_docs/impossibility_claims.md`'s citation anchors resolve a plain identifier.
     *
     * The refusal is **not** the one the two `GlslUnsupported` sites in `GlslParser` suggest, and
     * that was worth finding out: the wire array appears first in a *parameter list*
     * (`void program_init_wires(out float program_wires[program_wire_count])`), and the parameter
     * parser has no array branch at all, so it dies as an ordinary syntax error —
     * `GlslEvalError: GLSL parse: expected ')', found '['`. `GlslParser.kt:442`'s
     * `"GLSL arrays are not in the StackSL subset"` is what a *local* array declaration gets, which
     * is what the forwarding wrapper's body would hit if the signature ever parsed. So the exact
     * exception is not asserted; the refusal, and that it names the `[`, is.
     */
    @Test
    fun theUnloweredWiredGlslIsRefusedByTheParser() {
        val result = StackSLCompiler.compile("uv 3.0 * fbm palette", StackSLWires.wiredOptions("program"))
        assertTrue(result.ok, "the wired compile itself should succeed: ${result.diagnostics}")
        assertTrue(
            result.glsl.contains("program_wires[program_wire_count]"),
            "externalizeConstants no longer emits a wire array; this test's subject is gone"
        )
        val thrown = assertFailsWith<RuntimeException> {
            parseGlslShader(StackSLCpu.substituteSlot(StackSLCpu.stillTemplate(), result.glsl))
        }
        assertTrue(
            thrown is GlslUnsupported || thrown is GlslEvalError,
            "refused with an unrelated exception: $thrown"
        )
        assertTrue(
            thrown.message!!.contains("[") || thrown.message!!.contains("arrays"),
            "refused for the wrong reason: ${thrown.message}"
        )
        println("unlowered wired GLSL is refused: ${thrown::class.simpleName}: ${thrown.message}")

        // And the lowered form of the same compile parses, which is the other half of the claim.
        val lowered = StackSLWires.lower(result.glsl, "program", result.analysis.danglingWires.size)
        parseGlslShader(StackSLCpu.substituteSlot(StackSLCpu.stillTemplate(), lowered))
    }

    @Test
    fun `lowering leaves no trace of the wire array`() {
        val entries = StackSLCpuGallery.load(root)
        var checked = 0
        entries.forEach { entry ->
            val wired = runCatching { StackSLWires.lift(entry.program) }.getOrNull() ?: return@forEach
            val array = "${wired.prefix}_wires"
            assertTrue(
                !wired.glsl.contains(array),
                "'${entry.name}' still mentions '$array' after lowering — the CPU parser refuses " +
                    "arrays, so this would not even parse"
            )
            assertTrue(
                !wired.glsl.contains("${wired.prefix}_init_wires"),
                "'${entry.name}' still calls the wire initialiser after lowering"
            )
            if (wired.wires.isNotEmpty()) {
                assertTrue(
                    wired.glsl.startsWith("float ${StackSLWires.globalName(wired.prefix, 0)};"),
                    "'${entry.name}' does not declare its wires as globals: " +
                        wired.glsl.lines().first()
                )
            }
            checked++
        }
        println("wire lowering: $checked gallery programs lower with no array left behind")
        assertTrue(checked >= MIN_PROGRAMS, "only $checked programs lowered, expected $MIN_PROGRAMS")
    }

    private companion object {
        /** 32 square: bit-identity is a yes/no question and this asks it 3072 times per program. */
        const val DIM = 32
        const val MIN_PROGRAMS = 29
        const val MIN_WIRES = 200
    }
}
