package afield.stacksl.cpu

import afield.stacksl.StackSLCompileOptions
import afield.stacksl.StackSLCompiler
import afield.stacksl.stackSLDocumentableWords
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Can the CPU backend run **every** documentable StackSL word?
 *
 * `stackSLDocumentableWords` is the compiler's own list — `builtinWordsCore` plus the named
 * colours — so this iterates the language rather than a hand-kept sample, and a word added
 * tomorrow is covered tomorrow without anyone remembering to add it here.
 *
 * WHAT THE ASSERTION IS, PRECISELY, because "coverage" is the word this codebase most often uses
 * loosely. Each word `w` is compiled as the one-word program `input w` and the emitted GLSL is
 * substituted into a **committed** fragment template, then evaluated over a 4x4 raster. Passing
 * means: it compiled, it parsed, every construct in it was implemented, and it produced a finite
 * colour. It does **not** mean the answer was checked against a GPU — only 29 programs have GPU
 * ground truth, and that is `StackSLCpuGalleryTest`'s job. Two different claims, deliberately in
 * two different files.
 *
 * The floor is 100%, not a percentage with slack. That is affordable precisely because the failure
 * mode is cheap to fix: a word this cannot run means one missing GLSL builtin or one unhandled
 * construct, both of which are a few lines. A loose floor here would let the backend rot one word
 * at a time — which is the shape of the "98.6% reported, 6% asserted" problem this project keeps
 * finding in its own coverage numbers.
 */
class StackSLCpuWordCoverageTest {

    private val root = File(System.getProperty("user.dir"))

    /**
     * The feedback template, because it is the committed template that declares a `sampler2D`.
     *
     * 61 of the 325 words sample a texture, and the *still* template declares no sampler, so
     * running them through it fails on the shader's own interface rather than on anything the CPU
     * backend does or does not implement. Using the committed feedback template instead keeps this
     * about the backend. Both templates are real files that a real GPU has consumed.
     */
    private val template: String
        get() = File(root, "${StackSLCpuGallery.WORKSPACE_DIR}/feedback-trails_fragment_.glsl").readText()

    /**
     * All four sampler slots bound to the same declared texture.
     *
     * The multi-texture words (`sample2`, `gather3`, `texelsize1`, …) refuse to compile unless the
     * compiler is told how many textures exist, and the committed template declares one. Naming
     * `source` four times is honest about what happens on the CPU anyway: the [CpuTexture2D] below
     * answers every sampler identically, so binding four slots to one texture is the situation,
     * not a workaround for it.
     */
    private val sampleTextures = listOf("source", "source", "source", "source")

    private val texture = CpuTexture2D(
        4, 4,
        FloatArray(4 * 4 * 4) { i ->
            // A deterministic non-constant texture, so a sampling word has something to read and
            // a word that ignores its sample cannot accidentally look like one that uses it.
            val pixel = i / 4
            when (i % 4) {
                0 -> (pixel % 4) / 3f
                1 -> (pixel / 4) / 3f
                2 -> 0.5f
                else -> 1f
            }
        }
    )

    @Test
    fun `every documentable StackSL word runs on the CPU backend`() {
        val words = stackSLDocumentableWords.sorted()
        assertTrue(words.size > 300, "expected the whole vocabulary, got ${words.size} words")

        val didNotCompile = sortedMapOf<String, String>()
        val didNotRun = sortedMapOf<String, String>()
        var ran = 0

        words.forEach { word ->
            val result = StackSLCompiler.compile(
                "input $word",
                StackSLCompileOptions(
                    prefix = "program",
                    includeHeader = false,
                    declareSamplers = false,
                    sampleTextures = sampleTextures
                )
            )
            if (!result.ok) {
                didNotCompile[word] = result.diagnostics.joinToString("; ")
                return@forEach
            }
            val expanded = template.replace("{{ stack.auto.program }}", result.glsl)
            runCatching {
                StackSLCpu.renderFragment(
                    expanded,
                    width = 4, height = 4,
                    uniforms = mapOf("pc.params" to GlslValue.vec4(0.5f, 0f, 0f, 0f)),
                    samplers = texture,
                    parallel = false
                )
            }.onSuccess { raster ->
                val finite = raster.rgba.all { it.isFinite() }
                if (finite) ran++ else didNotRun[word] = "produced a non-finite colour"
            }.onFailure { t -> didNotRun[word] = "${t::class.simpleName}: ${t.message}" }
        }

        println(
            "StackSL CPU backend: ${words.size} documentable word(s), " +
                "${words.size - didNotCompile.size} compiled, $ran ran"
        )
        assertEquals(emptyMap(), didNotCompile.toMap(), "words the compiler rejected")
        assertEquals(emptyMap(), didNotRun.toMap(), "words the CPU backend could not run")
        assertEquals(words.size, ran, "every documentable word must run on the CPU backend")
    }

    /**
     * The builtin table covers the emitter, not just the gallery.
     *
     * The word sweep above already proves this operationally; this states the same fact as a set
     * comparison so that a failure names the missing builtin instead of naming a word.
     */
    @Test
    fun `every GLSL builtin the emitter calls is implemented`() {
        val callRegex = Regex("""\b([a-zA-Z_][a-zA-Z0-9_]*)\s*\(""")
        val commentRegex = Regex("""//[^\n]*|/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        val keywords = setOf("if", "for", "while", "return", "switch", "do")
        val missing = sortedSetOf<String>()
        stackSLDocumentableWords.forEach { word ->
            val result = StackSLCompiler.compile(
                "input $word",
                StackSLCompileOptions(
                    prefix = "program",
                    includeHeader = false,
                    declareSamplers = false,
                    sampleTextures = sampleTextures
                )
            )
            if (!result.ok) return@forEach
            // Comments first. `worley3` carries the line `// .x = F1, .y = F2, ...`, and a naive
            // scan reads `F1 (` in it as a call to an unimplemented builtin named `F1` — a false
            // positive that says nothing about the backend and would have to be suppressed by
            // name, which is how a guard starts accumulating exceptions instead of signal.
            val glsl = commentRegex.replace(result.glsl, " ")
            val defined = Regex("""\b(?:void|float|u?int|bool|[iub]?vec[234]|mat[234])\s+([a-zA-Z_][a-zA-Z0-9_]*)\s*\(""")
                .findAll(glsl).map { it.groupValues[1] }.toSet()
            callRegex.findAll(glsl).map { it.groupValues[1] }.forEach { name ->
                if (name in keywords || name in defined) return@forEach
                if (GlslType.byName(name) != null) return@forEach
                if (name !in GlslBuiltins.names) missing += name
            }
        }
        assertEquals(emptySet(), missing.toSet(), "GLSL builtins the emitter calls but the CPU backend lacks")
    }
}
