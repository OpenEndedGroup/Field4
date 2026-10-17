import js.date.Date
import kotlinx.browser.document
import org.w3c.dom.events.KeyboardEvent

var lastKey = ""
var lastKeyAt = 0.0
var lastKeyWasReleased = false
var lastKeyWasPure = false
var lastKeydownSerial = 0
var keydownSerial = 0
private var lastSuccessfulTapKey = ""
private var lastSuccessfulTapAt = 0.0
private var lastSuccessfulTapWasReleased = false
private var lastSuccessfulTapWasPure = false
private var lastSuccessfulTapKeydownSerial = 0

var doubleTap = mutableMapOf<String, () -> Unit>()
var tripleTap = mutableMapOf<String, () -> Unit>()

private fun resetPendingTapState() {
    lastKey = ""
    lastKeyAt = 0.0
    lastKeyWasReleased = false
    lastKeyWasPure = false
    lastKeydownSerial = 0
}

private fun resetSuccessfulTapState() {
    lastSuccessfulTapKey = ""
    lastSuccessfulTapAt = 0.0
    lastSuccessfulTapWasReleased = false
    lastSuccessfulTapWasPure = false
    lastSuccessfulTapKeydownSerial = 0
}

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

        val noInterveningKeyAfterSuccessfulTap =
            lastSuccessfulTapKeydownSerial != 0 && lastSuccessfulTapKeydownSerial == keydownSerial - 1
        if (
            tripleTap.containsKey(k) &&
            k == lastSuccessfulTapKey &&
            now - lastSuccessfulTapAt < 300 &&
            lastSuccessfulTapWasReleased &&
            lastSuccessfulTapWasPure &&
            isPure &&
            noInterveningKeyAfterSuccessfulTap
        ) {
            tripleTap[k]?.invoke()
            resetPendingTapState()
            resetSuccessfulTapState()
            return@addEventListener
        }

        if (
            lastSuccessfulTapKey.isNotEmpty() &&
            (
                k != lastSuccessfulTapKey ||
                    now - lastSuccessfulTapAt >= 300 ||
                    !noInterveningKeyAfterSuccessfulTap
                )
        ) {
            resetSuccessfulTapState()
        }

        val noInterveningKey = lastKeydownSerial != 0 && lastKeydownSerial == keydownSerial - 1

        if (k == lastKey && now - lastKeyAt < 300 && lastKeyWasReleased && lastKeyWasPure && isPure && noInterveningKey) {
            doubleTap[k]?.invoke()
            lastSuccessfulTapKey = k
            lastSuccessfulTapAt = now
            lastSuccessfulTapWasReleased = false
            lastSuccessfulTapWasPure = isPure
            lastSuccessfulTapKeydownSerial = keydownSerial
            resetPendingTapState()
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
        if (k == lastSuccessfulTapKey) {
            lastSuccessfulTapWasReleased = true
        }
    })
}
