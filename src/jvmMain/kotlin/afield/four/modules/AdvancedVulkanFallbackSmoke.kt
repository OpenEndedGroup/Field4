package afield.four.modules

import afield.Box
import afield.four.Renderer
import afield.four.neural.GpuShaderTraining
import afield.four.neural.LearnedShader
import afield.four.reconstruction.ReconstructionCapture
import afield.four.util.Options
import afield.four.util.Window
import kotlin.system.exitProcess

/** Force optional feature gates off on a supporting device and exercise ordinary shader inference. */
object AdvancedVulkanFallbackSmoke {
    @JvmStatic fun main(args: Array<String>) {
        System.setProperty("afield.clusterAccelerationStructure.disabled","true")
        System.setProperty("afield.cooperativeVector.disabled","true")
        Options.enableVR=false;Options.enableHDR=false;Options.doVulkanSDK()
        val window=Window("Field optional features disabled");val renderer=Renderer(window)
        val box=Box("advanced-fallback");box.setPropertyRaw("renderer",renderer,doResync=false)
        try { Box.withBoxContextNow(box) {
            check(!renderer.device.supportsClusterAccelerationStructure && !renderer.device.supportsCooperativeVectorTraining)
            check(runCatching { ClusterGeometry(renderer,1) }.exceptionOrNull()?.message?.contains("not enabled")==true)
            check(runCatching { GpuShaderTraining(renderer,64,48) }.exceptionOrNull()?.message?.contains("not enabled")==true)
            LearnedShader(renderer,320,180,cooperative=false).use { learned ->
                val end=renderer.frameCounter+12
                while(renderer.frameCounter<end){window.pollEvents();renderer.render()}
                val pixels=ReconstructionCapture.read(renderer,learned.output)
                check(pixels.all { it.isFinite() } && pixels.max()>.1f)
            }
        }} finally {renderer.cleanup();window.cleanup()}
        println("ADVANCED VULKAN FALLBACK CHECK DONE");exitProcess(0)
    }
}
