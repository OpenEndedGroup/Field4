package afield.graphics

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
    """.trimIndent()
}
