package afield.petri

import java.io.File

object PetriEditorExtension {
    const val id: String = "petri"

    /**
     * The Petri editor extension. The single source of truth is the static resource
     * `src/commonMain/resources/petri.js`, which is served to the browser as the AMD module
     * `bithero/petri` (see bootstrapMonaco.js) and read verbatim here so the node-probe smoke
     * test exercises the exact code that ships. The file self-installs under bare `eval`
     * (no AMD loader) and registers an AMD module when a Monaco loader is present.
     */
    fun javascript(): String {
        PetriEditorExtension::class.java.getResourceAsStream("/petri.js")?.use { stream ->
            return stream.readBytes().toString(Charsets.UTF_8)
        }
        val onDisk = File("./src/commonMain/resources/petri.js")
        require(onDisk.isFile) { "petri.js not found on the classpath or at ${onDisk.absolutePath}" }
        return onDisk.readText(Charsets.UTF_8)
    }

    fun html(): String = """
        <div style="font-family:Iosevka Web, monospace; background:#050505; color:#d8d8d8; border:1px solid #303030; padding:10px; max-width:680px">
          <strong>Petri editor extension</strong>
          <div style="margin-top:4px; color:#a8a8a8">
            Registers <code>#petri</code> embedded Kotlin regions with syntax highlighting, lexical diagnostics, hover docs and completions.
          </div>
          <script>
        ${javascript()}
          </script>
        </div>
    """.trimIndent()
}

fun petriEditorExtensionJavascript(): String = PetriEditorExtension.javascript()

fun petriEditorExtensionHtml(): String = PetriEditorExtension.html()
