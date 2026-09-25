package afield.four.neural

import afield.four.reconstruction.ReconstructionCapture
import org.json.JSONObject
import java.io.File
import kotlin.math.*

/** Called from a child of the actual moduleHere Starting Point, after its training process completes. */
object ShaderLearningChecks {
    fun run(lab: ShaderLearningView,directory: File=File("build/neural-tools/from-module")) {
        directory.mkdirs();val renderer=lab.renderer;val model=requireNotNull(lab.model)
        val oldView=lab.view;val oldParameter=lab.parameter
        fun pump(n: Int=1){val end=renderer.frameCounter+n;while(renderer.frameCounter<end){renderer.windows.forEach { it.pollEvents() };renderer.render()}}
        try {
            val report=JSONObject().put("cooperative",model.cooperative);lab.parameter=.173f
            lab.view=2;pump(16)
            val original=ReconstructionCapture.read(renderer,lab.source);val learned=ReconstructionCapture.read(renderer,model.output)
            check(original.all { it.isFinite() } && learned.all { it.isFinite() })
            var error=0.0;var count=0;var maximum=0f
            for(i in original.indices)if(i%4<3){val d=abs(original[i]-learned[i]);maximum=max(maximum,d);error+=d*d;count++}
            val mse=error/count;report.put("heldOutParameter",lab.parameter).put("fullImageLinearRgbMse",mse).put("maxAbsoluteRgbError",maximum)
            check(mse<.003) { "Bundled editable teacher did not fit: $mse" }
            ReconstructionCapture.save(File(directory,"source.png"),original,lab.source.width,lab.source.height)
            ReconstructionCapture.save(File(directory,"learned.png"),learned,lab.source.width,lab.source.height)
            for(view in listOf(0,1)) {
                lab.view=view;pump(32);val first=lab.viewGpuMilliseconds.size
                val computeFirst=model.gpuMilliseconds.size;val renders=lab.sourceRenderCount;pump(100)
                if(view==1)check(renders==lab.sourceRenderCount) { "Learned replacement still rendered the teacher" }
                val times=lab.viewGpuMilliseconds.drop(first).sorted();check(times.size>=95)
                report.put(if(view==0)"sourceViewGpuMs" else "learnedViewGpuMs",JSONObject().put("count",times.size)
                    .put("p50",times[times.size/2]).put("p95",times[(times.size*.95).toInt()]))
                if(view==1) {
                    val compute=model.gpuMilliseconds.drop(computeFirst).sorted();check(compute.size>=95)
                    report.put("learnedComputeGpuMs",JSONObject().put("count",compute.size)
                        .put("p50",compute[compute.size/2]).put("p95",compute[(compute.size*.95).toInt()]))
                }
            }
            check(runCatching { model.setParameter(2f) }.isFailure)
            val rejected=File(directory,"rejected-model").also { it.mkdirs() }
            File(rejected,"weights.json").writeText(JSONObject().put("sourceKind","captured-module")
                .put("sourceShaderDigest","a different shader").toString())
            check(runCatching { lab.load(rejected) }.isFailure && lab.model===model)
            report.put("incompatibleModelPreservedWorkingView",true)
            lab.view=2
            repeat(120){i->lab.parameter=sin(i/120f*2*PI).toFloat();pump()
                val image=ReconstructionCapture.read(renderer,lab.output)
                ReconstructionCapture.save(File(directory,"clip/%04d.png".format(i)),image,lab.source.width,lab.source.height)
                if(i==24)ReconstructionCapture.save(File(directory,"comparison.png"),image,lab.source.width,lab.source.height)
            }
            report.put("output",listOf(lab.source.width,lab.source.height)).put("sourcePassSkippedInReplacement",true)
                .put("sourceShaderDigest",ShaderTraining.sourceDigest(lab.source)).put("frameCount",120)
            File(directory,"measurements.json").writeText(report.toString(2)+"\n")
            println("MODULE SHADER LEARNING CHECK DONE")
        } finally {lab.view=oldView;lab.parameter=oldParameter}
    }
}
