import js.date.Date
import kotlinx.browser.document
import org.w3c.dom.events.KeyboardEvent

var lastKey = ""
var lastKeyAt = 0.0
var lastKeyWasReleased = false
var lastKeyWasPure = false
var lastKeydownSerial = 0
var keydownSerial = 0

var doubleTap = mutableMapOf<String, () -> Unit>()

fun registerDoubleTap() {
    document.body!!.addEventListener("keydown", {
        val ke = (it as KeyboardEvent)
        if (ke.repeat) return@addEventListener
        keydownSerial += 1
        val now = Date.now()
        val k = ke.key

        val isPure = when (k) {
            "Shift" -> !(ke.metaKey || ke.ctrlKey || ke.altKey)
            "Meta" -> !(ke.shiftKey || ke.ctrlKey || ke.altKey)
            "Control" -> !(ke.shiftKey || ke.metaKey || ke.altKey)
            "Alt", "AltGraph" -> !(ke.shiftKey || ke.metaKey || ke.ctrlKey)
            else -> true
        }

        val noInterveningKey = lastKeydownSerial != 0 && lastKeydownSerial == keydownSerial - 1

        if (k == lastKey && now - lastKeyAt < 300 && lastKeyWasReleased && lastKeyWasPure && isPure && noInterveningKey) {
            doubleTap[k]?.invoke()
            // Reset all state after successful double-tap
            lastKey = ""
            lastKeyAt = 0.0
            lastKeyWasReleased = false
            lastKeyWasPure = false
            lastKeydownSerial = 0
        } else {
            // Track new key press
            lastKey = k
            lastKeyAt = now
            lastKeyWasReleased = false
            lastKeyWasPure = isPure
            lastKeydownSerial = keydownSerial
        }
    })
    
    document.body!!.addEventListener("keyup", {
        val ke = (it as KeyboardEvent)
        val k = ke.key
        // Mark key as released only if it matches the tracked key
        if (k == lastKey) {
            lastKeyWasReleased = true
        }
    })
}
