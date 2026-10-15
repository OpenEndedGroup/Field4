import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

// A one-step guide whose annotation points at line 3 of SlideTarget's code. The page-eval that
// follows pans and zooms the canvas and checks the arrow still ends on that line.
val target = children.first { it.name == "SlideTarget" }
val show = slideShow {
    step("Zoom") {
        note("<b>Line three</b> is where the value is set.", persistent = true)
        selectBox(target, "code", line = 3, highlight = 3)
        annotateToLine("zoom-line", target, "code", line = 3)
    }
}
GlobalScope.launch { show.start() }
