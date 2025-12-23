import js.date.Date
import kotlinx.browser.document
import org.w3c.dom.events.KeyboardEvent

var lastKey = ""
var lastKeyAt = 0.0

var doubleTap = mutableMapOf<String, () -> Unit>()

fun registerDoubleTap() {
    document.body!!.addEventListener("keydown", {
        val ke = (it as KeyboardEvent)
        val now = Date.now()
        val k = ke.key
        if (k == lastKey && now - lastKeyAt < 300) {
            console.log(" double tap ", k)
            doubleTap[k]?.invoke()
        }
        lastKey = k
        lastKeyAt = now
    })
}
