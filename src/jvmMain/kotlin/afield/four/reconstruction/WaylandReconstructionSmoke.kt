package afield.four.reconstruction

import afield.Box
import afield.four.Renderer
import afield.four.util.Options
import afield.four.util.Window
import org.json.JSONObject
import java.io.File
import kotlin.system.exitProcess

/** Driven by tools/lighting/check_wayland.py: the compositor, not GLFW, confirms minimization. */
object WaylandReconstructionSmoke {
    @JvmStatic fun main(args: Array<String>) {
        val folder=File(args.firstOrNull() ?: "build/neural-tools/wayland").also { it.mkdirs() }
        Options.enableVR=false;Options.enableHDR=false;Options.enableRayTracing=false;Options.doVulkanSDK()
        val window=Window("Field Wayland reconstruction check")
        val renderer=Renderer(window)
        val box=Box("wayland-check");box.setPropertyRaw("renderer",renderer,doResync=false)
        try { Box.withBoxContextNow(box) {
            ReconstructionDemo(renderer,ReconstructionMode.DLAA,ReconstructionSize(960,540)).use { demo ->
                var iterations=0;var maxMs=0.0;var lastPhase="";val started=System.nanoTime()
                fun write(state: JSONObject) {
                    val temp=File(folder,"heartbeat.tmp");temp.writeText(state.toString())
                    java.nio.file.Files.move(temp.toPath(),File(folder,"heartbeat.json").toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
                while((System.nanoTime()-started)<90_000_000_000L) {
                    window.pollEvents();val before=System.nanoTime();renderer.render()
                    val elapsed=(System.nanoTime()-before)/1e6
                    if(iterations>40)maxMs=maxOf(maxMs,elapsed)
                    iterations++
                    val phase=File(folder,"phase.txt").takeIf { it.exists() }?.readText()?.trim() ?: "warmup"
                    if(phase!=lastPhase && phase.startsWith("restored")) {
                        demo.reset("compositor restore checkpoint")
                        repeat(12){window.pollEvents();renderer.render()}
                        val pixels=ReconstructionCapture.read(renderer,demo.output)
                        check(pixels.all { it.isFinite() } && pixels.max()>.25f)
                        ReconstructionCapture.save(File(folder,"$phase.png"),pixels,960,540)
                    }
                    lastPhase=phase
                    write(JSONObject().put("iterations",iterations).put("phase",phase).put("maxRenderMs",maxMs)
                        .put("resetCount",demo.reconstruction!!.resetCount).put("ready",iterations>45))
                    if(phase=="finish")break
                    Thread.sleep(4)
                }
                check(lastPhase=="finish") { "Compositor test timed out" }
                check(maxMs<2000) { "Render loop stalled for $maxMs ms" }
            }
        } } finally { renderer.cleanup();window.cleanup() }
        println("WAYLAND RECONSTRUCTION CHECK DONE");exitProcess(0)
    }
}
