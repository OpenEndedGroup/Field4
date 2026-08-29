package afield.datalog

import java.io.File

object DatalogEditorExtension {
    const val id: String = "datalog"

    /**
     * The Datalog editor extension. The single source of truth is the static resource
     * `src/commonMain/resources/datalog.js`, which is served to the browser as the AMD module
     * `bithero/datalog` (see bootstrapMonaco.js) and read verbatim here so the node-probe smoke
     * test exercises the exact code that ships. The file self-installs under bare `eval`
     * (no AMD loader) and registers an AMD module when a Monaco loader is present.
     */
    fun javascript(): String {
        DatalogEditorExtension::class.java.getResourceAsStream("/datalog.js")?.use { stream ->
            return stream.readBytes().toString(Charsets.UTF_8)
        }
        val onDisk = File("./src/commonMain/resources/datalog.js")
        require(onDisk.isFile) { "datalog.js not found on the classpath or at ${onDisk.absolutePath}" }
        return onDisk.readText(Charsets.UTF_8)
    }

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

fun datalogEditorExtensionJavascript(): String = DatalogEditorExtension.javascript()

fun datalogEditorExtensionHtml(): String = DatalogEditorExtension.html()
