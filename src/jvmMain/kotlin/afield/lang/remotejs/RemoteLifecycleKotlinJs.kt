package afield.lang.remotejs

import afield.geometry.path.div
import afield.geometry.path.minus
import afield.geometry.path.plus
import afield.geometry.path.times

/**
 * The `onStart` / `onFrame` / `onStop` / `keepAlive` callbacks that every remote Kotlin/JS head shares.
 *
 * [RemoteLifecycleKotlinJs] supplies them, with the `HeadFrame` timing record, as compiler
 * declarations and as a completion mirror. Callbacks register only during a Launch; a launched box
 * stays active until relaunch, error or Stop, and its `onStop` callbacks then run once.
 */

/**
 * Head-neutral lifecycle surface shared by remote Kotlin/JS profiles.
 *
 * The host opens a lifecycle registration scope only for Field's Launch operation. Consequently:
 *
 * - [onStart], [onFrame], [onStop], and [keepAlive] are inert during Execute/selection evaluation;
 * - a Launch that reaches [onStart] remains active until relaunch, error, or user Stop;
 * - relaunch and Stop run registered [onStop] callbacks exactly once.
 *
 * Frame callbacks execute on the head's own render clock. Browser heads use requestAnimationFrame;
 * Vision Metal supplies compositor-frame timing through the same callback shape.
 */
object RemoteLifecycleKotlinJs {
    val declarations: String = """
        data class HeadFrame(
            val time: Double,
            val deltaTime: Double,
            val frameNumber: Int,
            val predictedDisplayTime: Double?,
        )

        @kotlin.js.JsName("__fieldOnStart")
        private external fun __headOnStart(callback: () -> Unit)

        @kotlin.js.JsName("__fieldOnStop")
        private external fun __headOnStop(callback: () -> Unit)

        @kotlin.js.JsName("__fieldOnFrame")
        private external fun __headOnFrame(
            callback: (
                time: Double,
                deltaTime: Double,
                frameNumber: Int,
                predictedDisplayTime: Double?,
            ) -> Unit,
        )

        @kotlin.js.JsName("__fieldKeepAlive")
        private external fun __headKeepAlive()

        @kotlin.js.JsName("print")
        private external fun __fieldEditorPrint(message: Any?, line: Int = definedExternally)

        // Kotlin/JS's stdlib print defaults to the head's console. These package-local overloads
        // deliberately win over that default import and use Field's execution return channel.
        fun print(message: Any?) = __fieldEditorPrint(message)
        fun println(message: Any?) = __fieldEditorPrint(message)
        fun println() = __fieldEditorPrint("")

        // Source preparation rewrites direct print/println calls to these overloads so the head can
        // return a real one-based editor line instead of an invisible line-zero output.
        fun __fieldPrintAt(line: Int, message: Any?) = __fieldEditorPrint(message, line)
        fun __fieldPrintAt(line: Int) = __fieldEditorPrint("", line)

        fun onStart(callback: () -> Unit) = __headOnStart(callback)
        fun onStop(callback: () -> Unit) = __headOnStop(callback)
        fun keepAlive() = __headKeepAlive()

        fun onFrame(callback: (HeadFrame) -> Unit) {
            __headOnFrame { time, deltaTime, frameNumber, predictedDisplayTime ->
                callback(HeadFrame(time, deltaTime, frameNumber, predictedDisplayTime))
            }
        }

        fun animate(callback: (HeadFrame) -> Unit) = onFrame(callback)

        // The box surface field.js installs for Javascript boxes, bound rather than rebuilt: the
        // head names the box from the calling stack frame, so display and controls reach this box.
        @kotlin.js.JsName("display")
        private external fun __fieldDisplay(channel: String, html: String)

        @kotlin.js.JsName("slider")
        private external fun __fieldSlider(label: String, callback: (Double) -> Unit, options: dynamic): Double

        @kotlin.js.JsName("checkbox")
        private external fun __fieldCheckbox(label: String, callback: (Boolean) -> Unit, options: dynamic): Boolean

        /** The browser's own timer; output printed from it still reaches the box. */
        external fun setTimeout(callback: () -> Unit, milliseconds: Int): Int

        fun display(channel: String, html: String) = __fieldDisplay(channel, html)
        fun display(html: String) = __fieldDisplay("main", html)

        fun slider(
            label: String,
            min: Double = 0.0,
            max: Double = 1.0,
            step: Double = 0.01,
            initialValue: Double = (min + max) / 2,
            channel: String = "controls",
            onUpdate: (Double) -> Unit,
        ): Double {
            val options: dynamic = js("({})")
            options.min = min
            options.max = max
            options.step = step
            options.value = initialValue
            options.channel = channel
            return __fieldSlider(label, onUpdate, options)
        }

        fun checkbox(
            label: String,
            initialValue: Boolean = false,
            channel: String = "controls",
            onUpdate: (Boolean) -> Unit,
        ): Boolean {
            val options: dynamic = js("({})")
            options.value = initialValue
            options.channel = channel
            return __fieldCheckbox(label, onUpdate, options)
        }
    """.trimIndent()

    val completionDeclarations: String = """
        data class HeadFrame(
            val time: Double,
            val deltaTime: Double,
            val frameNumber: Int,
            val predictedDisplayTime: Double?,
        )

        fun onStart(callback: Function0<Unit>): Unit = TODO()
        fun onStop(callback: Function0<Unit>): Unit = TODO()
        fun keepAlive(): Unit = TODO()
        fun onFrame(callback: Function1<HeadFrame, Unit>): Unit = TODO()
        fun animate(callback: Function1<HeadFrame, Unit>): Unit = TODO()
        fun setTimeout(callback: Function0<Unit>, milliseconds: Int): Int = TODO()
        fun display(channel: String, html: String): Unit = TODO()
        fun display(html: String): Unit = TODO()
        fun slider(label: String, min: Double = 0.0, max: Double = 1.0, step: Double = 0.01, initialValue: Double = (min + max) / 2, channel: String = "controls", onUpdate: Function1<Double, Unit>): Double = TODO()
        fun checkbox(label: String, initialValue: Boolean = false, channel: String = "controls", onUpdate: Function1<Boolean, Unit>): Boolean = TODO()
    """.trimIndent()
}
