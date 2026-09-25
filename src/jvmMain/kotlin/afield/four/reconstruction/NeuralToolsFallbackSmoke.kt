package afield.four.reconstruction

import afield.Box
import afield.four.Renderer
import afield.four.neural.LearnedShader
import afield.four.util.Options
import afield.four.util.Window
import kotlin.system.exitProcess

/** Exercises the same renderer with every newly optional native/hardware feature disabled. */
object NeuralToolsFallbackSmoke {
    @JvmStatic fun main(args: Array<String>) {
        System.setProperty("afield.ngx.disabled","true")
        System.setProperty("afield.opticalFlow.disabled","true")
        System.setProperty("afield.cooperativeVector.disabled","true")
        Options.enableVR=false;Options.enableHDR=false;Options.enableRayTracing=false;Options.doVulkanSDK()
        val window=Window("Field neural tools fallback");val renderer=Renderer(window);val box=Box("fallback")
        box.setPropertyRaw("renderer",renderer,doResync=false)
        try {Box.withBoxContextNow(box){
            check(!renderer.device.supportsOpticalFlow && !renderer.device.supportsCooperativeVector)
            check(NgxSupport.availability(renderer.device).isFailure && NgxSupport.rayReconstructionAvailability(renderer.device).isFailure)
            fun pump(){val end=renderer.frameCounter+8;while(renderer.frameCounter<end){window.pollEvents();renderer.render()}}
            ReconstructionDemo(renderer,null,ReconstructionSize(960,540)).use {demo->pump()
                check(ReconstructionCapture.read(renderer,demo.output).any { it>.2f })}
            LearnedShader(renderer,960,540,cooperative=false).use {demo->pump()
                check(ReconstructionCapture.read(renderer,demo.output).all { it.isFinite() })}
        }} finally {renderer.cleanup();window.cleanup()}
        println("NEURAL TOOLS FALLBACK CHECK DONE");exitProcess(0)
    }
}
