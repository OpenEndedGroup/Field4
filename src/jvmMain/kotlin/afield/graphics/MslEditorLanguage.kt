package afield.graphics

import java.io.File

/**
 * The Monaco language for Field's `msl` box property — Metal Shading Language for a visionOS head.
 *
 * The single source of truth is the static resource `src/commonMain/resources/msl.js`, served to the
 * browser as the AMD module `bithero/msl` (see bootstrapMonaco.js) and read verbatim here so the
 * node-probe smoke test exercises the exact code that ships.
 */
object MslEditorLanguage {
    const val id: String = "msl"

    /** The Monaco language id Field uses for the `msl` property, and for `.metal` / `.msl` files. */
    const val languageId: String = "msl"

    fun javascript(): String {
        MslEditorLanguage::class.java.getResourceAsStream("/msl.js")?.use { stream ->
            return stream.readBytes().toString(Charsets.UTF_8)
        }
        val onDisk = File("./src/commonMain/resources/msl.js")
        require(onDisk.isFile) { "msl.js not found on the classpath or at ${onDisk.absolutePath}" }
        return onDisk.readText(Charsets.UTF_8)
    }
}

fun mslEditorLanguageJavascript(): String = MslEditorLanguage.javascript()
