@file:afield.runtime.completion.CompletionCategory("Synthesis")
package afield.audio.synth

import afield.document.graph.Box
import afield.runtime.lifecycle.AnimationClock
import afield.runtime.lifecycle.Frames
import afield.runtime.lifecycle.loop
import afield.ui.output.controlGroup

/**
 * The FM-1's front panel in a box: its LCD, redrawn as the firmware draws it, and rows of buttons
 * that press the panel's buttons, play its 27 keys and turn its seven encoders.
 */

/** The panel buttons in the order [fm1Panel] lays them out, with the index [FM1Sim.button] takes. */
private val PANEL_BUTTONS = listOf(
    "PLAY" to FM1Sim.Button.PLAY, "REC" to FM1Sim.Button.REC, "SEQ" to FM1Sim.Button.SEQ,
    "ARP" to FM1Sim.Button.ARP, "HOME" to FM1Sim.Button.HOME, "EDIT" to FM1Sim.Button.EDIT,
    "SEL" to FM1Sim.Button.SEL, "SAVE" to FM1Sim.Button.SAVE, "FX" to FM1Sim.Button.FX,
    "ENV" to FM1Sim.Button.ENV, "LFO" to FM1Sim.Button.LFO, "GLO" to FM1Sim.Button.GLO,
    "OCT-" to FM1Sim.Button.OCT_DOWN, "OCT+" to FM1Sim.Button.OCT_UP,
)

/** The 27 keys' labels, C to D two octaves up, sharps written `#`. */
private val KEY_NAMES: Array<String> = run {
    val names = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    Array(FM1Sim.KEYS) { names[it % 12] + (it / 12 + 1) }
}

/**
 * Show [fm1]'s panel in this box and keep its screen live: return the result (with the simulator's
 * node, if this box also plays it) and the LCD redraws in [channel] whenever the firmware draws.
 *
 * ```kotlin
 * val fm1 = FM1Sim.shared
 * fm1.preset(1)
 * fm1.node + fm1Panel(fm1)
 * ```
 *
 * The buttons below the screen are held while the mouse is down, as the real ones are, so a long
 * press works; an encoder's `◀` and `▶` turn it one detent each press.
 *
 * @param fm1 the simulator to show
 * @param channel the output channel the screen is drawn in
 * @param scale how many screen pixels each LCD pixel becomes (the LCD is 240 by 240)
 * @param controls whether to add the button, key and encoder rows (in channel `fm1 panel`)
 * @param maxFps the most times a second the screen is redrawn; the firmware redraws more often
 * @param pull keep the simulator running from this panel's own clock, for a simulator no audio
 *   engine is pulling (one from [FM1Sim.create] rendered offline, or a docs capture). Leave it off
 *   when the box also returns `fm1.node`, or the simulator runs twice as fast.
 */
fun Box.fm1Panel(
    fm1: FM1Sim = FM1Sim.shared,
    channel: String = "screen",
    scale: Int = 2,
    controls: Boolean = true,
    maxFps: Double = 20.0,
    pull: Boolean = false,
): Frames {
    require(scale in 1..16) { "fm1Panel: scale is 1..16, not $scale" }
    require(maxFps > 0.0 && maxFps.isFinite()) { "fm1Panel: maxFps must be finite and > 0, was $maxFps" }
    // The screen first, so it sits above the buttons.
    display(channel, fm1.screenPng(scale))
    if (controls) {
        controlGroup("fm1 panel") {
            keys(*PANEL_BUTTONS.map { it.first }.toTypedArray()) { i, down -> fm1.button(PANEL_BUTTONS[i].second, down) }
            keys(*KEY_NAMES) { i, down -> fm1.key(i, down) }
            keys(*Array(14) { "E${it / 2 + 1}" + if (it % 2 == 0) "◀" else "▶" }) { i, down ->
                if (down) fm1.encoder(i / 2, if (i % 2 == 0) -1 else 1)
            }
            slider("level", 0.0, 1.0, 0.01, 0.8) { fm1.level(it) }
        }
    }
    val l = FloatArray(480)
    val r = FloatArray(480)
    var shown = -1L
    var lastDraw = Double.NEGATIVE_INFINITY
    var pulledTo = AnimationClock.nowSeconds()
    return loop {
        val now = AnimationClock.nowSeconds()
        if (pull) {
            // 480 frames at 48 kHz is 10 ms: the simulator advances in step with this clock.
            while (pulledTo < now) { fm1.render(l, r, 480, 48_000f); pulledTo += 0.01 }
        }
        if (now - lastDraw >= 1.0 / maxFps) {
            val revision = fm1.screenRevision
            if (revision != shown) {
                display(channel, fm1.screenPng(scale))
                shown = revision
                lastDraw = now
            }
        }
    }
}
