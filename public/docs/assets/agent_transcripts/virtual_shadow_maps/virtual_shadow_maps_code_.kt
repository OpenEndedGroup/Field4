import afield.graphics.scene.fabricated.StandardRendererDocsHarness
import java.io.File

/*
Fixed 1024² shadow map | virtual shadow map | the virtual map's debug view, close to the
contact edge of a torus standing on a disk. Debug colours: green is the finest level, then
blue, orange, magenta; brightness steps from one physical page to the next.
*/
val out = "/tmp/field-virtual-shadows.png"

val compare: suspend () -> Unit = {
    val file = StandardRendererDocsHarness.renderVirtualShadowComparison(out, renderer = renderer)
    display("main", StandardRendererDocsHarness.htmlForImage(file, "fixed | virtual | virtual debug view"))
    println(File("$out.txt").readText())
}
start(compare)
