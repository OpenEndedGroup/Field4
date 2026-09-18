package afield.rectsl

import java.io.File

/**
 * Serves `rectsl.js`, the browser-side RectSL editor support, to the page and to the test that
 * checks it.
 *
 * RectSL runs in Kotlin, in `RectSL.kt`; a RectSL program is a triple-quoted string beginning
 * `#rectsl` inside an ordinary Kotlin box. So there is no execution path to hook, and this file is
 * the whole of the language's presence in the editor. `rectsl.js` lives in
 * `src/commonMain/resources`, reaches the browser as the AMD module `bithero/rectsl` (one of the
 * modules `bootstrapMonaco.js` requires by name while the editor starts), and is read back verbatim
 * here, so the smoke test `rectsl.editor-extension.workout` runs the same bytes the editor does. A
 * browser-side language that can only be tested in a browser does not stay correct.
 *
 * Once installed, the extension brings syntax highlighting, flow decorations, lexical diagnostics,
 * a context badge and completions to a `#rectsl` region. Colouring is decoration-based rather than a
 * Monarch grammar, so there is no grammar here that can fail to compile and take Monaco's startup
 * down with it.
 *
 * `rectsl.js` installs itself either way it is loaded: as an AMD module when a Monaco loader
 * is present, and immediately when it is evaluated bare, which is the path `html()` uses. The two
 * differ in one respect. The AMD path also registers a standalone `rectsl` Monaco language, so that
 * a loose `.rectsl` file opened in the editor gets `#` comment toggling and bracket handling; it
 * carries no tokenizer of its own, because the decoration highlighter already treats a whole
 * `rectsl` model as one region.
 */

/**
 * The RectSL editor extension, as bytes: the resource itself, and a page fragment that installs
 * it.
 */
object RectSLEditorExtension {
    /** The extension's id, which is also the `extensionId` `rectsl.js` registers itself under. */
    const val id: String = "rectsl"

    /**
     * The bytes of `rectsl.js`.
     *
     * Read off the classpath, and from `src/commonMain/resources/rectsl.js` on disk when the
     * classpath has no copy, so this works from a source tree as well as from a jar. It fails rather
     * than returning an empty extension when neither is there.
     */
    fun javascript(): String {
        RectSLEditorExtension::class.java.getResourceAsStream("/rectsl.js")?.use { stream ->
            return stream.readBytes().toString(Charsets.UTF_8)
        }
        val onDisk = File("./src/commonMain/resources/rectsl.js")
        require(onDisk.isFile) { "rectsl.js not found on the classpath or at ${onDisk.absolutePath}" }
        return onDisk.readText(Charsets.UTF_8)
    }

    /**
     * The extension as a drop-in HTML fragment: a titled line of prose saying what it does, with the
     * whole of `rectsl.js` inlined in a script tag.
     *
     * Nothing in `src/` calls this. `rectSLEditorExtensionHtml` is the unqualified name for it.
     */
    fun html(): String = """
        <div style="font-family:Iosevka Web, monospace; background:#050505; color:#d8d8d8; border:1px solid #303030; padding:10px; max-width:680px">
          <strong>RectSL editor extension</strong>
          <div style="margin-top:4px; color:#a8a8a8">
            Registers <code>#rectsl</code> embedded Kotlin regions with syntax highlighting, flow decorations, and a context badge.
          </div>
          <script>
        ${javascript()}
          </script>
        </div>
    """.trimIndent()
}

/** `RectSLEditorExtension.javascript`, as a top-level function. */
fun rectSLEditorExtensionJavascript(): String = RectSLEditorExtension.javascript()

/** `RectSLEditorExtension.html`, as a top-level function. */
fun rectSLEditorExtensionHtml(): String = RectSLEditorExtension.html()
