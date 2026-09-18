package afield.datalog

import java.io.File

/**
 * Serves `datalog.js`, the browser-side Datalog editor support, to the page and to the test that
 * checks it.
 *
 * The JavaScript is the implementation and this file is a reader for it. `datalog.js` lives in
 * `src/commonMain/resources`, reaches the browser as the AMD module `bithero/datalog` (one of the
 * modules `bootstrapMonaco.js` requires by name while the editor starts), and is read back verbatim
 * here, so the smoke test `datalog.editor-extension.workout` runs the same bytes the editor does. A
 * browser-side language that can only be tested in a browser does not stay correct.
 *
 * Once installed, the extension brings syntax highlighting, lexical diagnostics, a context badge
 * and completions to `#datalog` regions embedded in Kotlin triple-quoted strings, and to whole
 * `.datalog` documents. Colouring is decoration-based rather than a Monarch grammar, so one tokenizer covers
 * both contexts and there is no grammar here that can fail to compile and take Monaco's startup down
 * with it. The same workout also compares the editor's vocabulary against `DatalogParser`'s, since
 * the editor is JavaScript in a browser and the parser is Kotlin on a server, and the two lists can
 * only be kept in step at build time.
 *
 * `datalog.js` installs itself either way it is loaded: as an AMD module when a Monaco loader
 * is present, and immediately when it is evaluated bare, which is the path `html()` uses.
 */

/**
 * The Datalog editor extension, as bytes: the resource itself, and a page fragment that installs
 * it.
 */
object DatalogEditorExtension {
    /** The extension's id, which is also the `extensionId` `datalog.js` registers itself under. */
    const val id: String = "datalog"

    /**
     * The bytes of `datalog.js`.
     *
     * Read off the classpath, and from `src/commonMain/resources/datalog.js` on disk when the
     * classpath has no copy, so this works from a source tree as well as from a jar. It fails rather
     * than returning an empty extension when neither is there.
     */
    fun javascript(): String {
        DatalogEditorExtension::class.java.getResourceAsStream("/datalog.js")?.use { stream ->
            return stream.readBytes().toString(Charsets.UTF_8)
        }
        val onDisk = File("./src/commonMain/resources/datalog.js")
        require(onDisk.isFile) { "datalog.js not found on the classpath or at ${onDisk.absolutePath}" }
        return onDisk.readText(Charsets.UTF_8)
    }

    /**
     * The extension as a drop-in HTML fragment: a titled line of prose saying what it does, with the
     * whole of `datalog.js` inlined in a script tag.
     *
     * Nothing in `src/` calls this. `datalogEditorExtensionHtml` is the unqualified name for it.
     */
    fun html(): String = """
        <div style="font-family:Iosevka Web, monospace; background:#050505; color:#d8d8d8; border:1px solid #303030; padding:10px; max-width:680px">
          <strong>Datalog editor extension</strong>
          <div style="margin-top:4px; color:#a8a8a8">
            Registers <code>#datalog</code> embedded Kotlin regions, and whole <code>.datalog</code> documents,
            with syntax highlighting, lexical diagnostics, a context badge and completions.
          </div>
          <script>
        ${javascript()}
          </script>
        </div>
    """.trimIndent()
}

/** `DatalogEditorExtension.javascript`, as a top-level function. */
fun datalogEditorExtensionJavascript(): String = DatalogEditorExtension.javascript()

/** `DatalogEditorExtension.html`, as a top-level function. */
fun datalogEditorExtensionHtml(): String = DatalogEditorExtension.html()
