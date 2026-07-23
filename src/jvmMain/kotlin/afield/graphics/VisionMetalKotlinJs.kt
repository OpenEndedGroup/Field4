package afield.graphics

/**
 * Kotlin/JS declarations for the tiny JavaScriptCore runtime owned by Field4Vision's Live Metal
 * renderer.
 *
 * Runtime Kotlin/JS compilation currently accepts one source file, so this surface is appended to
 * the user's source rather than compiled as a separate library. Appending is deliberate: compiler
 * diagnostics keep the user's original line numbers. [completionDeclarations] mirrors the same
 * public surface in JVM-analysis-safe Kotlin because the completion sidecar intentionally does not
 * load the Kotlin/JS compiler frontend.
 *
 * This only describes values that really exist in `LiveMetalJavaScript.swift`. Shader source,
 * geometry, and the ordered uniform-name list still travel in the `visionmetal/program` payload;
 * Kotlin/JS code runs inside that installed program and updates its declared uniforms per frame.
 */
object VisionMetalKotlinJs {
    const val surfaceId: String = "visionmetal"

    /**
     * The user-facing layer intentionally wraps the raw JavaScript globals:
     *
     * ```kotlin
     * fun main() {
     *     uniform("amount", 0.025)
     *     onFrame { frame -> uniform("phase", frame.time * 3.0) }
     * }
     * ```
     *
     * The wrapper gives the callback named fields and gives uniform writes checked arities while
     * retaining a zero-allocation scalar path. DCE removes any unused overloads from each snippet.
     */
    val declarations: String = """
        data class VisionMetalFrame(
            val time: Double,
            val deltaTime: Double,
            val predictedDisplayTime: Double,
        )

        @kotlin.js.JsName("onFrame")
        private external fun __visionMetalOnFrame(
            callback: (time: Double, deltaTime: Double, predictedDisplayTime: Double) -> Unit,
        )

        @kotlin.js.JsName("uniform")
        private external fun __visionMetalUniform(
            name: String,
            x: Double,
            y: Double,
            z: Double,
            w: Double,
        )

        @kotlin.js.JsName("print")
        private external fun __visionMetalPrint(value: Any?)

        fun onFrame(callback: (VisionMetalFrame) -> Unit) {
            __visionMetalOnFrame { time, deltaTime, predictedDisplayTime ->
                callback(VisionMetalFrame(time, deltaTime, predictedDisplayTime))
            }
        }

        fun uniform(name: String, x: Double) =
            __visionMetalUniform(name, x, 0.0, 0.0, 0.0)

        fun uniform(name: String, x: Double, y: Double) =
            __visionMetalUniform(name, x, y, 0.0, 0.0)

        fun uniform(name: String, x: Double, y: Double, z: Double) =
            __visionMetalUniform(name, x, y, z, 0.0)

        fun uniform(name: String, x: Double, y: Double, z: Double, w: Double) =
            __visionMetalUniform(name, x, y, z, w)

        fun log(value: Any?) = __visionMetalPrint(value)
    """.trimIndent()

    /**
     * JVM-analysis-safe mirror of the user-facing surface. The completion sidecar deliberately runs
     * without the Kotlin/JS compiler frontend, so `external`, `dynamic`, `definedExternally`, and
     * `@JsName` cannot be its source model. Keep this small mirror beside [declarations]; the smoke
     * test exercises the public names and member types through the real sidecar.
     */
    val completionDeclarations: String = """
        data class VisionMetalFrame(
            val time: Double,
            val deltaTime: Double,
            val predictedDisplayTime: Double,
        )

        fun onFrame(callback: Function1<VisionMetalFrame, Unit>): Unit = TODO()
        fun uniform(name: String, x: Double, y: Double = 0.0, z: Double = 0.0, w: Double = 0.0): Unit = TODO()
        fun log(value: Any?): Unit = TODO()
    """.trimIndent()

    /** Add the external surface without changing any user-source line number. */
    fun appendTo(userSource: String): String = buildString(userSource.length + declarations.length + 2) {
        append(userSource.trimEnd())
        append("\n\n")
        append(declarations)
    }
}
