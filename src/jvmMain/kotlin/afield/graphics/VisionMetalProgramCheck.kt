package afield.graphics

import afield.remotejs.KotlinJsHeadProfiles

/**
 * The offline half of "will this Live Metal program actually run?".
 *
 * A box names a uniform in three places, and nothing on the Mac used to notice when they disagreed:
 *
 *  1. the `// {{ visionmetal uniforms=… }}` marker, which becomes the head's slot order;
 *  2. the shader, which reads `u_NAME` — the head generates `#define u_NAME frame.user[i]` from that
 *     list, so an undeclared `u_NAME` is a **Metal compile error on the device**;
 *  3. the script, whose `uniform("name", …)` writes land in a slot only if the name is declared —
 *     an undeclared write is dropped, silently, and the shader just sees zero.
 *
 * All three failures show up as "it doesn't work in the headset", minutes away from the edit that
 * caused them. Every one is decidable from the box's own text plus a real run of its script, which is
 * what this does. It is the uniform-shaped companion to
 * [VisionGeometryPreview.CapturedMesh.windingAgreement].
 *
 * Nothing here compiles MSL — Metal's compiler is not available on this machine and this does not
 * pretend to replace it. These are the checks that need only the ABI the head documents.
 */
object VisionMetalProgramCheck {

    /** `u_NAME` occurrences in shader source, ignoring the ones inside `//` and `/* */` comments. */
    private val uniformReference = Regex("""\bu_([A-Za-z_][A-Za-z0-9_]*)\b""")
    private val lineComment = Regex("""//[^\n]*""")
    private val blockComment = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)

    /** One thing that will go wrong, and whether it stops the program dead. */
    data class Problem(val fatal: Boolean, val message: String) {
        override fun toString(): String = (if (fatal) "error: " else "warning: ") + message
    }

    /**
     * Check a whole box.
     *
     * [code] is the box's Kotlin (the `// {{ visionmetal uniforms=… }}` marker is read from it),
     * [msl] its shader source, and [uniformsWritten] the names its script actually wrote — pass
     * `capture.uniforms.map { it.name }` from a real [VisionGeometryPreview.capture] run to catch
     * writes that only happen on some code path.
     */
    fun problems(
        code: String,
        msl: String?,
        uniformsWritten: Collection<String> = emptyList(),
    ): List<Problem> {
        val declared = declaredUniforms(code)
        return problems(declared, msl, uniformsWritten)
    }

    /** As [problems], with the slot order supplied directly. */
    fun problems(
        declared: List<String>,
        msl: String?,
        uniformsWritten: Collection<String> = emptyList(),
    ): List<Problem> = buildList {
        if (declared.size > VISION_METAL_MAX_UNIFORMS) {
            add(Problem(true, "${declared.size} uniforms declared; the head has $VISION_METAL_MAX_UNIFORMS slots"))
        }
        declared.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach {
            add(Problem(true, "uniform '$it' is declared twice; slot order must be unambiguous"))
        }
        declared.filterNot { it.isNotBlank() && it.all { c -> c == '_' || c.isLetterOrDigit() } && !it.first().isDigit() }
            .forEach { add(Problem(true, "uniform name '$it' is not a Metal identifier")) }

        val declaredSet = declared.toSet()

        if (msl.isNullOrBlank()) {
            // Not a fault by itself — the head keeps whatever shader is already resident.
            if (declaredSet.isNotEmpty() || uniformsWritten.isNotEmpty()) {
                add(Problem(false, "no MSL in this box, so the uniforms above go to whatever shader is resident"))
            }
        } else {
            val stripped = blockComment.replace(msl, " ").let { lineComment.replace(it, " ") }
            if ("field_vertex" !in stripped) {
                add(Problem(true, "the shader has no `field_vertex`; the head refuses to build a pipeline without it"))
            }
            if ("field_fragment" !in stripped) {
                add(Problem(true, "the shader has no `field_fragment`; the head refuses to build a pipeline without it"))
            }
            val referenced = uniformReference.findAll(stripped).map { it.groupValues[1] }.toSet()
            (referenced - declaredSet).sorted().forEach {
                add(
                    Problem(
                        true,
                        "the shader reads `u_$it` but '$it' is not in the marker's uniforms= list, so the head " +
                            "never emits its #define and Metal fails to compile the shader",
                    ),
                )
            }
            (declaredSet - referenced).sorted().forEach {
                add(Problem(false, "uniform '$it' is declared but never read as `u_$it` in the shader"))
            }
        }

        (uniformsWritten.toSet() - declaredSet).sorted().forEach {
            add(
                Problem(
                    true,
                    "the script writes uniform(\"$it\", …) but '$it' is not declared, so the head has no slot " +
                        "for it and the write is dropped",
                ),
            )
        }
    }

    /** The marker's `uniforms=` list, in slot order; empty when the box declares none. */
    fun declaredUniforms(code: String): List<String> {
        val selection = runCatching { KotlinJsHeadProfiles.resolve(code) }.getOrNull() ?: return emptyList()
        if (!selection.explicit || selection.profile !== KotlinJsHeadProfiles.visionMetal) return emptyList()
        return selection.optionList("uniforms") ?: emptyList()
    }

    /** A one-line summary, or null when there is nothing to say. */
    fun summarise(problems: List<Problem>): String? {
        if (problems.isEmpty()) return null
        val fatal = problems.count { it.fatal }
        return if (fatal > 0) {
            "$fatal problem(s) that will stop this program on the head: " +
                problems.filter { it.fatal }.joinToString("; ") { it.message }
        } else {
            problems.joinToString("; ") { it.message }
        }
    }
}
