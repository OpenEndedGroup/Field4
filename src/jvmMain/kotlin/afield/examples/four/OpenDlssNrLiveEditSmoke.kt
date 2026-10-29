package afield.examples.four

import afield.graphics.render.neural.OpenDlssNrColorEncoding
import afield.graphics.render.neural.OpenDlssNrFrame
import afield.graphics.render.neural.OpenDlssNrInputs
import afield.graphics.render.neural.OpenDlssNrPass
import afield.graphics.render.neural.OpenDlssNrNative
import afield.graphics.render.neural.OpenDlssNrSupport
import afield.graphics.render.neural.OpenDlssNrStudy
import afield.graphics.scene.fabricated.FabricatePostStack
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.util.Window
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.system.exitProcess

/*
FIELD_TEST
id: four.open-dlss-nr.live-edit
requiresDisplay: true
runner: jvm-main
entrypoint: afield.examples.four.OpenDlssNrLiveEditSmoke
tags: [smoke, four, gpu, neural]
timeoutSec: 180
expect:
  exitCode: 0
  stdoutContains:
    - "OpenDlssNr live edit PASSED"
  stdoutNotContains:
    - "VUID-"
    - "Validation Error"
  stderrNotContains:
    - "VUID-"
    - "Validation Error"
*/

/**
 * Live edits that used to need a box rerun. [OpenDlssNrStudy.reload] with a new varying between the
 * stages rebuilds the source and the next frames draw with it; with the native library present, a
 * pass added to a Fabricate post stack leaves that stack when it closes. Without the library the
 * reload half still runs, in source mode.
 */
object OpenDlssNrLiveEditSmoke {
    private val vertex = """
        #version 460
        layout(location=0) out vec2 uv;
        layout(location=1) out float tint;
        void main() {
            vec2 corner=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);
            uv=vec2(corner.x,1-corner.y);
            tint=0.5;
            gl_Position=vec4(corner*2-1,0,1);
        }
    """.trimIndent()
    private val fragment = """
        #version 460
        layout(location=0) in vec2 uv;
        layout(location=1) in float tint;
        layout(location=0) out vec4 color;
        layout(push_constant) uniform Study { vec4 frame; } pc;
        void main() { color=vec4(tint,0.25,0,1); }
    """.trimIndent()

    @JvmStatic
    fun main(args: Array<String>) {
        val window = Window("OpenDlssNr live edit")
        val renderer = Renderer(window)
        // With FIELD_OPEN_DLSS_NR_LIBRARY naming an absent file: build (copy) it after the device exists.
        args.indexOf("--built-after-device").takeIf { it >= 0 }?.let { at ->
            val built = java.nio.file.Path.of(args[at + 1])
            val library = java.nio.file.Path.of(System.getenv("FIELD_OPEN_DLSS_NR_LIBRARY"))
            check(OpenDlssNrSupport.availability(renderer).reason == OpenDlssNrNative.ABSENT)
            java.nio.file.Files.copy(built, library, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            val reason = OpenDlssNrSupport.availability(renderer).reason
            check(reason == OpenDlssNrSupport.BUILT_AFTER_DEVICE) { "unexpected reason: $reason" }
            check(OpenDlssNrNative.loaded.isSuccess) { "the newly built adapter did not load" }
            renderer.cleanup(); window.cleanup()
            // The advice holds: a renderer created now, in the same JVM, gets the NR features.
            val restartedWindow = Window("OpenDlssNr restarted")
            val restarted = Renderer(restartedWindow)
            val after = OpenDlssNrSupport.availability(restarted)
            check(after.ready) { "restarted renderer still lacks NR: ${after.reason}" }
            restarted.cleanup(); restartedWindow.cleanup()
            println("OpenDlssNr built-after-device PASSED: $reason; restarted renderer: ${after.reason}")
            exitProcess(0)
        }
        val study = OpenDlssNrStudy(renderer, size = 128)
        if ("--require-native" in args) check(study.pass != null) { study.availability.reason }
        println("NR availability: ${study.availability.reason}")
        fun frames(n: Int) = repeat(n) { window.pollEvents(); renderer.render() }
        frames(4)
        val reloaded = study.reload(vertex, fragment)
        check(reloaded.isSuccess) { "interface-changing reload failed: ${reloaded.exceptionOrNull()?.message}" }
        check(study.reloadPending) { "an interface change should queue a rebuilt source" }
        frames(4)
        check(!study.reloadPending) { "the rebuilt source never reached a frame" }
        val pixels = study.source.readPixelsAsync()
        var guard = 0
        while (!pixels.isDone && guard++ < 200) frames(1)
        val buffer = ByteBuffer.wrap(pixels.get()).order(ByteOrder.nativeOrder())
        val red = java.lang.Float.float16ToFloat(buffer.getShort(0))
        val green = java.lang.Float.float16ToFloat(buffer.getShort(2))
        check(kotlin.math.abs(red - 0.5f) < 0.01f && kotlin.math.abs(green - 0.25f) < 0.01f) {
            "rebuilt source did not draw the new varying: rgb=($red, $green)"
        }
        check(study.reload().isSuccess) { "reloading the original interface failed" }
        frames(4)

        var detached = "skipped (native NR absent)"
        if (study.pass != null) {
            val pass = OpenDlssNrPass(renderer, 128, 128)
            val stack = FabricatePostStack(study.source)
            stack.append(pass.postEffect("nr", { OpenDlssNrInputs(study.source, OpenDlssNrColorEncoding.LINEAR_LDR) },
                { OpenDlssNrFrame(0) }))
            check(stack.names() == listOf("nr"))
            pass.close()
            check(stack.names().isEmpty()) { "closing the pass left it in the post stack: ${stack.names()}" }
            check(stack.output() === study.source)
            detached = "closed pass left its post stack"
        }
        frames(2)
        study.close()
        renderer.cleanup()
        window.cleanup()
        println("OpenDlssNr live edit PASSED: interface reload rebuilt the source; $detached")
        exitProcess(0)
    }
}
