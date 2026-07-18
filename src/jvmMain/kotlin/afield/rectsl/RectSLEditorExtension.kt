package afield.rectsl

import java.io.File

object RectSLEditorExtension {
    const val id: String = "rectsl"

    /**
     * The RectSL editor extension. The single source of truth is the static resource
     * `src/commonMain/resources/rectsl.js`, which is served to the browser as the AMD module
     * `bithero/rectsl` (see bootstrapMonaco.js) and read verbatim here so the node-probe smoke
     * test exercises the exact code that ships. The file self-installs under bare `eval`
     * (no AMD loader) and registers an AMD module when a Monaco loader is present.
     */
    fun javascript(): String {
        RectSLEditorExtension::class.java.getResourceAsStream("/rectsl.js")?.use { stream ->
            return stream.readBytes().toString(Charsets.UTF_8)
        }
        val onDisk = File("./src/commonMain/resources/rectsl.js")
        require(onDisk.isFile) { "rectsl.js not found on the classpath or at ${onDisk.absolutePath}" }
        return onDisk.readText(Charsets.UTF_8)
    }

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

fun rectSLEditorExtensionJavascript(): String = RectSLEditorExtension.javascript()

fun rectSLEditorExtensionHtml(): String = RectSLEditorExtension.html()
