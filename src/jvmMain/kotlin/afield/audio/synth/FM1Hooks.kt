@file:afield.runtime.completion.CompletionCategory("Synthesis")
package afield.audio.synth

import afield.lang.stacksl.StackSLCompileOptions
import afield.lang.stacksl.StackSLSeverity
import afield.lang.stacksl.compileStackSL
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Live-coding the FM-1's firmware: StackSL programs run inside it, on a track's samples.
 *
 * ```kotlin
 * val fm1 = FM1Sim.shared
 * fm1.hook(0, "in 30 * sin 0.12 *") // the 303 folded through a sine, from the next block on
 * ```
 *
 * [firmware] is the open firmware with [PATCHES] applied: after each track's voice renders, it
 * calls an imported function, `field.track`, with the track's block of samples. [compile] turns a
 * StackSL program into a hook module for that call: StackSL lowers to a GLSL function
 * `vec4 stacksl(vec4)`, [cpp] rewrites it lightly and wraps it in a loop over the block, and the
 * WASI SDK's clang compiles it as C++ against `fm1/glsl_prelude.hpp` (clang's vector extensions
 * give GLSL's swizzles and arithmetic). A hook is a few kilobytes and builds in about a tenth of a
 * second; [FM1Sim.hook] swaps it in between two blocks while the firmware keeps running, so
 * changing a hook does not touch the sequence, the voices or their state.
 */
object FM1Hooks {

    /** One edit of the firmware source: [find] (exactly once) in [file] becomes [replace]. */
    data class Patch(val file: String, val find: String, val replace: String)

    /**
     * The hook points, as edits of the firmware at [FM1Firmware.COMMIT]: the import declaration and
     * a frame clock in `web/engine/sound.c`, a call after each track's voice in both its render
     * paths (stereo and mono; the firmware picks one per block), and an export in `Simulator.c`
     * that says which tracks call out.
     */
    val PATCHES: List<Patch> = listOf(
        Patch(
            "web/engine/sound.c",
            "#include \"fixed.h\"\n#include <stdlib.h>\n",
            "#include \"fixed.h\"\n#include <stdlib.h>\n" +
                "/* Field: hook points. field.track is imported from the host and called with a track's block\n" +
                " * after its voice renders, for the tracks whose bit is set in field_hooked. */\n" +
                "__attribute__((import_module(\"field\"), import_name(\"track\")))\n" +
                "void field_track(unsigned track, int32_t *left, int32_t *right, unsigned frames, uint32_t clock);\n" +
                "unsigned field_hooked;\n" +
                "static uint32_t field_clock;\n",
        ),
        Patch(
            "web/engine/sound.c",
            "        memset(out, 0, frames * 2U * sizeof *out);\n        return;\n    }\n    groove_sequence_render();\n",
            "        memset(out, 0, frames * 2U * sizeof *out);\n        return;\n    }\n" +
                "    field_clock += FM1_BLOCK_FRAMES;\n    groove_sequence_render();\n",
        ),
        Patch(
            "web/engine/sound.c",
            "        unsigned modulated = lfo_stereo(t);\n",
            "        if (field_hooked & (1U << t))\n" +
                "            field_track(t, left, type == GROOVE_808 ? right : 0, FM1_BLOCK_FRAMES,\n" +
                "                        field_clock - FM1_BLOCK_FRAMES);\n" +
                "        unsigned modulated = lfo_stereo(t);\n",
        ),
        Patch(
            "web/engine/sound.c",
            "        lfo_render(t, mono, NULL, NULL, NULL);\n",
            "        if (sources[t].ptr && (field_hooked & (1U << t)))\n" +
                "            field_track(t, mono, 0, FM1_BLOCK_FRAMES, field_clock - FM1_BLOCK_FRAMES);\n" +
                "        lfo_render(t, mono, NULL, NULL, NULL);\n",
        ),
        Patch(
            "web/engine/Simulator.c",
            "EXPORT(\"sim_frame_revision\") uint32_t sim_frame_revision(void) { return frame_revision; }\n",
            "EXPORT(\"sim_frame_revision\") uint32_t sim_frame_revision(void) { return frame_revision; }\n" +
                "extern unsigned field_hooked;\n" +
                "EXPORT(\"sim_field_hooks\") void sim_field_hooks(unsigned mask) { field_hooked = mask & 0xffU; }\n",
        ),
    )

    private val home: File get() = File(System.getProperty("user.home"))

    private fun digest(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    /**
     * The firmware with hook points: the source at [FM1Firmware.COMMIT] with [PATCHES] applied, built
     * once and kept in `~/.field4/fm1/` under a name that changes whenever the patches or the
     * compiler flags do. The first call clones the source and fetches the WASI SDK if they are not
     * there yet.
     */
    fun firmware(): ByteArray {
        val key = digest(FM1Firmware.COMMIT + PATCHES + FM1Firmware.COMPILE_FLAGS + FM1Firmware.LINK_FLAGS).take(16)
        val cached = File(home, ".field4/fm1/fm1-sim-hooks-$key.wasm")
        if (cached.isFile) return cached.readBytes()
        val copy = Files.createTempDirectory("fm1-hooks").toFile()
        try {
            FM1Firmware.checkout().copyRecursively(copy, overwrite = true)
            FM1Firmware.reset(copy)
            for (p in PATCHES) FM1Firmware.patch(copy, p.file, p.find, p.replace)
            val built = FM1Firmware.build(copy, File(copy, "hooks.wasm"))
            cached.parentFile.mkdirs()
            built.copyTo(cached, overwrite = true)
        } finally {
            copy.deleteRecursively()
        }
        return cached.readBytes()
    }

    /** The C++ the GLSL is compiled against: vector types, GLSL's built-ins, `mat2` and `mat3`. */
    val PRELUDE: String by lazy {
        FM1Hooks::class.java.getResourceAsStream("/fm1/glsl_prelude.hpp")?.use { it.readBytes().decodeToString() }
            ?: error("FM1Hooks: /fm1/glsl_prelude.hpp is not on the classpath")
    }

    private val CONSTRUCTOR = Regex("""\b(vec[234]|mat[23])\(""")
    private val FLOAT_LITERAL = Regex("""(?<![\w.])(\d+\.\d*(?:[eE][-+]?\d+)?|\.\d+(?:[eE][-+]?\d+)?|\d+[eE][-+]?\d+)(?![\w.])""")
    private val TEXTURE = Regex("""\bsampler2D\b|\btexture(?:Size|Gather|Lod|Offset)?\(""")

    /**
     * GLSL as the C++ [PRELUDE] reads it: `vec4(…)` and `mat2(…)` become calls to `vec4_(…)` and
     * `mat2_(…)` (a C++ type cannot be called with GLSL's constructor arguments), and each floating
     * literal gets an `f`, so `2.0 * v` stays in single precision as in GLSL.
     */
    fun glslToCpp(glsl: String): String =
        FLOAT_LITERAL.replace(CONSTRUCTOR.replace(glsl) { "${it.groupValues[1]}_(" }) { "${it.groupValues[1]}f" }

    /**
     * The whole hook source for [stacksl]: the prelude, the program's GLSL as C++, and the two exports
     * the firmware glue calls. `track` runs the program on each sample of a block in place (the
     * left channel, then the right for the 808's stereo kit); `eval` runs it once, for drawing.
     *
     * @throws FM1HookException if the program does not compile, or reads a texture
     */
    fun cpp(stacksl: String): String {
        val r = compileStackSL(
            stacksl,
            StackSLCompileOptions(includeHeader = false, declareSamplers = false, sampleTextures = emptyList()),
        )
        val errors = r.diagnostics.filter { it.severity == StackSLSeverity.ERROR }
        if (errors.isNotEmpty()) throw FM1HookException("StackSL: " + errors.joinToString("; ") { it.toString() })
        if (TEXTURE.containsMatchIn(r.glsl)) {
            throw FM1HookException(
                "StackSL: this program reads a texture (sample, gather, blur, laplace and the other image words); " +
                    "a hook runs on a stream of samples and has no picture to read"
            )
        }
        return buildString {
            append("#include \"glsl_prelude.hpp\"\n\n")
            append(glslToCpp(r.glsl))
            append(HARNESS)
        }
    }

    private const val HARNESS = """

// Field: run stacksl() on each sample of a block, in place. 1.0 is the voices' full scale, 2^24.
static int field_buf[256];
static float field_prev[8][2];
static float field_out[4];
extern "C" {
__attribute__((export_name("field_buf"))) int *field_buf_ptr() { return field_buf; }
__attribute__((export_name("track"))) void field_hook_track(int n, int t, unsigned clock, int stereo) {
    if (n > 128) n = 128;
    int k = t & 7;
    for (int c = 0; c <= (stereo ? 1 : 0); ++c) {
        int *b = field_buf + c * n;
        float prev = field_prev[k][c];
        for (int i = 0; i < n; ++i) {
            float x = (float)b[i] * (1.0f / 16777216.0f);
            vec4 v = stacksl(vec4{x, prev, (float)t, (float)((double)(clock + (unsigned)i) / 44100.0)});
            prev = x;
            float y = v.x;
            if (!(y == y)) y = 0.0f; else if (y > 64.0f) y = 64.0f; else if (y < -64.0f) y = -64.0f;
            b[i] = (int)(y * 16777216.0f);
        }
        field_prev[k][c] = prev;
    }
}
__attribute__((export_name("eval"))) float *field_hook_eval(float x, float y, float z, float w) {
    vec4 v = stacksl(vec4{x, y, z, w});
    field_out[0] = v.x; field_out[1] = v.y; field_out[2] = v.z; field_out[3] = v.w;
    return field_out;
}
}
"""

    /** The flags a hook compiles with: C++17, no exceptions or RTTI, a reactor module like the firmware. */
    val HOOK_FLAGS: List<String> = listOf(
        "--target=wasm32-wasip1", "-std=c++17", "-O2", "-fno-exceptions", "-fno-rtti", "-nostdlib++",
        "-fno-fast-math", "-ffp-contract=off", "-mexec-model=reactor", "-Wl,--strip-all",
    )

    /**
     * Compile [stacksl] into a hook module.
     *
     * @throws FM1HookException if the program does not compile as StackSL, reads a texture, or (a
     *   bug in this lowering, reported with the generated C++) does not compile as C++
     */
    fun compile(stacksl: String): FM1Hook = compileCpp(stacksl, cpp(stacksl))

    /**
     * Compile hook C++ written by hand (it must export `field_buf`, `track` and `eval` as [cpp]'s
     * harness does), labelled [label]. For hooks StackSL cannot say, and for testing the glue.
     */
    fun compileCpp(label: String, source: String): FM1Hook {
        val dir = Files.createTempDirectory("fm1-hook").toFile()
        try {
            File(dir, "glsl_prelude.hpp").writeText(PRELUDE)
            File(dir, "hook.cpp").writeText(source)
            val sdk = FM1Firmware.sdk()
            val command = listOf(FM1Firmware.compiler(sdk, cxx = true).absolutePath, "--sysroot=${File(sdk, "share/wasi-sysroot").absolutePath}") +
                HOOK_FLAGS + listOf("hook.cpp", "-o", "hook.wasm")
            val (code, output) = FM1Firmware.exec(dir, command)
            if (code != 0) throw FM1HookException("the generated C++ did not compile (a bug in FM1Hooks):\n${output.trim()}\n\n$source")
            return FM1Hook(label, source, File(dir, "hook.wasm").readBytes())
        } finally {
            dir.deleteRecursively()
        }
    }
}

/**
 * A StackSL program compiled to run inside the FM-1's firmware; give it to [FM1Sim.hook].
 *
 * @param stacksl the program
 * @param cpp the C++ it was compiled from
 * @param wasm the hook module
 */
class FM1Hook(val stacksl: String, val cpp: String, val wasm: ByteArray) {
    override fun toString(): String = "FM1Hook(\"$stacksl\", ${wasm.size} bytes)"
}

/** A hook could not be made; [message] says why, in StackSL's or the compiler's words. */
class FM1HookException(message: String) : RuntimeException(message)
