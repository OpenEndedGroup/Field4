package afield.four.neural

import afield.Box
import afield.four.Renderer
import afield.four.reconstruction.ReconstructionCapture
import afield.four.util.Options
import afield.four.util.Window
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*
import kotlin.system.exitProcess

object LearnedShaderSmoke {
    @JvmStatic fun main(args: Array<String>) {
        Options.enableVR=false;Options.enableHDR=false;Options.enableRayTracing=false;Options.doVulkanSDK()
        val dir=File("build/neural-tools/learned").also { it.mkdirs() }
        val window=Window("Field learned shader");val renderer=Renderer(window);val box=Box("learned-check")
        box.setPropertyRaw("renderer",renderer,doResync=false)
        val bytes=ByteBuffer.wrap(File("public/docs/assets/learned_shader/weights.f16").readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val weights=FloatArray(bytes.capacity()/2){java.lang.Float.float16ToFloat(bytes.getShort(it*2))}
        fun reference(x: Float,y: Float,t: Float): FloatArray {
            fun s(x: Float)=sin(x*PI).toFloat()
            fun c(x: Float)=cos(x*PI).toFloat()
            var v=floatArrayOf(x,y,t,1f,s(x),c(x),s(y),c(y),s(t),c(t),s(x+y),c(x-y),s(2*x),s(2*y),c(2*x),c(2*y))
            for((layer,shape) in listOf(16 to 32,32 to 32,32 to 3).withIndex()) {
                val w=listOf(0,1088,3200)[layer]/2;val b=listOf(1024,3136,3392)[layer]/2
                val old=v;v=FloatArray(shape.second){j ->var sum=weights[b+j];for(i in 0 until shape.first)sum+=old[i]*weights[w+j*shape.first+i];if(layer<2)max(0f,sum) else sum}
            };return v
        }
        try {Box.withBoxContextNow(box){
            fun pump(n: Int=1){val end=renderer.frameCounter+n;while(renderer.frameCounter<end){window.pollEvents();renderer.render()}}
            val report=JSONObject().put("enabledExtensions",renderer.device.enabledExtensions.toList())
                .put("cooperativeVectorInferenceEnabled",renderer.device.supportsCooperativeVector)
                .put("cooperativeVectorTrainingEnabled",false).put("opticalFlowEnabled",renderer.device.supportsOpticalFlow)
            for(cooperative in listOf(false,true)) {
                LearnedShader(renderer,cooperative=cooperative).use { demo ->
                    demo.time=.25f;demo.view=1;pump(12)
                    val pixels=ReconstructionCapture.read(renderer,demo.output);check(pixels.all { it.isFinite() })
                    var error=0f
                    repeat(64){i->val x=(i*317+21)%demo.width;val y=(i*193+37)%demo.height
                        val expected=reference((x+.5f)/demo.width*2-1,(y+.5f)/demo.height*2-1,demo.time)
                        for(c in 0..2)error=max(error,abs(expected[c]-pixels[(y*demo.width+x)*4+c]))}
                    check(error<if(cooperative).02f else .0001f) { "CPU/GPU network disagreement: $error" }
                    val result=JSONObject().put("cpuMaxError",error).put("allocatedBytes",demo.allocatedBytes)
                    for(view in listOf(0,1)) {
                        demo.view=view;pump(32);val start=demo.gpuMilliseconds.size;pump(100)
                        val times=demo.gpuMilliseconds.drop(start).sorted();check(times.size>=95)
                        result.put(if(view==0)"originalGpuMs" else "learnedGpuMs",JSONObject().put("count",times.size).put("p50",times[times.size/2]).put("p95",times[(times.size*.95).toInt()]))
                        if(view==0)result.put("stripe",ShaderLearningChecks.stripeMetrics(
                            ReconstructionCapture.read(renderer,demo.output),pixels,demo.width,demo.height))
                    }
                    demo.view=2;pump(2)
                    ReconstructionCapture.save(File(dir,if(cooperative)"cooperative.png" else "arithmetic.png"),ReconstructionCapture.read(renderer,demo.output),demo.width,demo.height)
                    if(cooperative && "--clip" in args)repeat(120){i->demo.time=sin(i/120f*2*PI).toFloat();pump()
                        ReconstructionCapture.save(File(dir,"clip/%04d.png".format(i)),ReconstructionCapture.read(renderer,demo.output),demo.width,demo.height)}
                    report.put(if(cooperative)"cooperative" else "arithmetic",result)
                };pump(32)
            }
            File(dir,"measurements.json").writeText(report.toString(2))
        }} finally {renderer.cleanup();window.cleanup()}
        println("LEARNED SHADER CHECK DONE");exitProcess(0)
    }
}
