// Same opt-in Server.kt carries at its own line 1: the five sibling control-message handlers all
// dispatch their callbacks through GlobalScope, and this handler was split out of that file, so it
// inherits the decision rather than making a new one.
@file:OptIn(DelicateCoroutinesApi::class)

package afield

import afield.logger.lprintln
import afield.server.broadcastControllerControlUpdates
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * The websocket command behind a momentary button, in its own file for two unrelated reasons.
 *
 * The first is a merge concern: `handlers` is a plain top-level map (`src/commonMain/kotlin/Messages.kt`),
 * so a handler can be registered from anywhere, and keeping the body out of `Server.kt` reduces this
 * feature's footprint there to a **single line**. That matters twice over — another unmerged branch is
 * rewriting `Server.kt`'s routing, and `dev_docs/small_language_anatomy.md` cites `Server.kt` by line
 * number with a ±2-line tolerance, which a 27-line insertion breaks and a one-line call does not. Line
 * numbers as an API is not a thing to admire, but it is the thing that is true.
 *
 * The second is that this handler is unlike its five neighbours in `Server.kt` in a way worth isolating:
 * it updates the control registry **in memory only**. See [updateControllerControlValueInMemory] — a
 * press written to the box's value cache would re-fire on the next rerun, which for a synth gate means
 * a note retriggering every time you edit the DSP.
 *
 * **Security, stated plainly rather than left to be discovered.** This is a websocket command, and
 * websockets are exempt from CORS and appear in no route census, so `AFIELD_API_REQUIRE_SAME_SITE` does
 * **not** cover it — exactly as it does not cover the `slider-update`, `checkbox-update`,
 * `vec2-update`, `string-update` or `color-update` commands it sits beside. Anything that can open a
 * websocket to a running Field can therefore invoke a user-registered Kotlin callback with a boolean.
 * That is a pre-existing hole in the control-message family and this widens it by one command; it is
 * not fixed here because fixing it means authenticating the whole family, which is one coherent change
 * and not a side effect of adding a button.
 */
internal fun registerButtonUpdateHandler() {
    handlers["button-update"] = mutableSetOf({ message, _ ->
        val update = Json.decodeFromJsonElement<ButtonUpdate>(message.payload!!)
        lprintln("debug,controls", "Received button update: ${update.controlId} = ${update.pressed}")
        val updatedControls =
            updateControllerControlValueInMemory(update.controlId) { it.copy(checked = update.pressed) }
        broadcastControllerControlUpdates(updatedControls, thisSessionInfo.get().filename)

        val callback = getButtonCallback(update.controlId)
        if (callback != null) {
            GlobalScope.launch {
                try {
                    callback(update.pressed)
                } catch (e: Exception) {
                    lprintln("error,controls", "Error in button callback: ${e.message}")
                    e.printStackTrace()
                }
            }
        } else {
            lprintln("warning,controls", "No callback registered for button: ${update.controlId}")
        }

        Handled.yes
    })
}
