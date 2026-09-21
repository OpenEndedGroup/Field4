@file:afield.CompletionCategory("Synthesis")
/**
 * The `#faust` editor extension and the vocabulary it completes against.
 *
 * Both are static resources rather than strings built here. `faust.js` is served to the browser as
 * the AMD module `bithero/faust`, which `bootstrapMonaco.js` lists alongside the other language
 * extensions, and it fetches `faust-vocabulary.json` itself at install time. This object reads the
 * two files verbatim, so the node probe in `FaustEditorExtensionWorkout` exercises the code and
 * data that ship rather than a copy of them.
 *
 * The vocabulary is extracted by `tools/generate_faust_vocabulary.py` from Faust's standard
 * libraries, which are not on disk: they ship packed inside the vendored compiler's
 * `libfaust-wasm.data`. Completion therefore offers the thousand-odd functions that the same
 * vendored compiler will resolve `import("stdfaust.lib")` against, with their real signatures.
 */

package afield.four.synth

import java.io.File

/**
 * The two resources, each read on demand and never cached.
 *
 * Both go through one lookup that tries the classpath first and then the file at its
 * `src/commonMain/resources` path relative to the working directory, so a run from a Gradle JVM in
 * the source tree and a run from a packaged build both find them. A resource that is in neither
 * place fails with a message naming both.
 */
object FaustEditorExtension {
    /** The extension's name in the editor: the `#faust` marker without its `#`. */
    const val id: String = "faust"

    /** The extension's source, `faust.js`. */
    fun javascript(): String = resource("/faust.js", "src/commonMain/resources/faust.js")

    /** The generated completion vocabulary, `faust-vocabulary.json`. */
    fun vocabularyJson(): String =
        resource("/faust-vocabulary.json", "src/commonMain/resources/faust-vocabulary.json")

    private fun resource(onClasspath: String, onDiskPath: String): String {
        FaustEditorExtension::class.java.getResourceAsStream(onClasspath)?.use { stream ->
            return stream.readBytes().toString(Charsets.UTF_8)
        }
        val onDisk = File("./$onDiskPath")
        require(onDisk.isFile) {
            "$onClasspath not found on the classpath or at ${onDisk.absolutePath}"
        }
        return onDisk.readText(Charsets.UTF_8)
    }
}

/** `FaustEditorExtension.javascript()`, as a top-level function. */
fun faustEditorExtensionJavascript(): String = FaustEditorExtension.javascript()
