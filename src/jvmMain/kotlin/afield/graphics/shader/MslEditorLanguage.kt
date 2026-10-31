package afield.graphics.shader

import afield.geometry.path.div
import afield.geometry.path.minus
import afield.geometry.path.plus
import afield.geometry.path.times
import afield.graphics.visionos.msl
import afield.lang.remotejs.JsFun

import java.io.File

/**
 * The Monaco editor language for Metal Shading Language, used by the `msl` box property.
 */

/**
 * The Monaco language for Field's `msl` box property — Metal Shading Language for a visionOS head.
 *
 * The single source of truth is the static resource `src/commonMain/resources/msl.js`, served to the
 * browser as the AMD module `bithero/msl` (see bootstrapMonaco.js) and read verbatim here so the
 * node-probe smoke test exercises the exact code that ships.
 */
object MslEditorLanguage {
    /**
     * The language id, `msl`, as the same string `languageId` holds. Neither constant reaches the
     * browser: the id Monaco registers is declared inside `msl.js` itself.
     */
    const val id: String = "msl"

    /** The Monaco language id Field uses for the `msl` property, and for `.metal` / `.msl` files. */
    const val languageId: String = "msl"

    /**
     * The text of `msl.js`, read from the classpath, or from `src/commonMain/resources/msl.js`
     * relative to the working directory when the resource is not on it. Throws when neither is
     * there, rather than returning an empty grammar.
     */
    fun javascript(): String {
        MslEditorLanguage::class.java.getResourceAsStream("/msl.js")?.use { stream ->
            return stream.readBytes().toString(Charsets.UTF_8)
        }
        val onDisk = File("./src/commonMain/resources/msl.js")
        require(onDisk.isFile) { "msl.js not found on the classpath or at ${onDisk.absolutePath}" }
        return onDisk.readText(Charsets.UTF_8)
    }
}

/** The same text [MslEditorLanguage.javascript] returns, as a top-level function. */
fun mslEditorLanguageJavascript(): String = MslEditorLanguage.javascript()
