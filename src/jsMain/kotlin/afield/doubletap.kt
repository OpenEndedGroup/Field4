import js.date.Date
import kotlinx.browser.document
import org.w3c.dom.events.KeyboardEvent

var lastKey = ""
var lastKeyAt = 0.0
var lastKeyWasReleased = false

var doubleTap = mutableMapOf<String, () -> Unit>()

fun registerDoubleTap() {
    document.body!!.addEventListener("keydown", {
        val ke = (it as KeyboardEvent)
        val now = Date.now()
        val k = ke.key
        if (k == lastKey && now - lastKeyAt < 300 && lastKeyWasReleased) {
            console.log(" double tap ", k)
            doubleTap[k]?.invoke()
            lastKeyWasReleased = false
        } else {
            lastKey = k
            lastKeyAt = now
            lastKeyWasReleased = false
        }
    })
    
    document.body!!.addEventListener("keyup", {
        val ke = (it as KeyboardEvent)
        val k = ke.key
        if (k == lastKey) {
            lastKeyWasReleased = true
        }
    })
}
