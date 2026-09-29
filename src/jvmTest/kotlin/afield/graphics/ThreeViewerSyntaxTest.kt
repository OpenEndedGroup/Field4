package afield.graphics

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Source
import kotlin.test.Test

class ThreeViewerSyntaxTest {
    @Test fun completeViewerModuleParses() {
        val script = ViewerJavaScript.VIEWER.readText()
            .substringAfter("<script type=\"module\">").substringBefore("</script>")
        // Parse the complete module without executing DOM code or resolving its imports.
        // Region tests alone cannot detect conflicting declarations introduced by integration.
        Context.newBuilder("js").build().use {
            it.parse(Source.newBuilder("js", script, "viewer.mjs")
                .mimeType("application/javascript+module").build())
        }
    }
}
