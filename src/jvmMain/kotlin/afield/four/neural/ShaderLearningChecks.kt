package afield.four.neural

import afield.four.reconstruction.ReconstructionCapture
import org.json.JSONObject
import java.io.File
import kotlin.math.*

/** Called from a child of the actual moduleHere Starting Point, after its training process completes. */
object ShaderLearningChecks {
    /** Measure the bundled hard stripe separately: a whole-image mean can hide edge smoothing. */
    fun stripeMetrics(original: FloatArray,learned: FloatArray,width: Int,height: Int): JSONObject {
        require(original.size==width*height*4 && learned.size==original.size)
        check(original.all { it.isFinite() } && learned.all { it.isFinite() })
        val stripe=floatArrayOf(.95f,.04f,.8f)
        val center=((height/2)*width+width/2)*4
        check((0..2).all { abs(original[center+it]-stripe[it])<.001f }) { "Missing source stripe" }
        val inside=((height/2)*width+((-.11f+1)*.5f*width).toInt())*4
        val outside=((height/2)*width+((-.13f+1)*.5f*width).toInt())*4
        val jump=(0..2).sumOf { abs(original[inside+it]-original[outside+it]).toDouble() }
        check(jump>.4) { "Source stripe edge is not discontinuous: $jump" }
        var edgeError=0.0;var otherError=0.0;var edgeCount=0;var otherCount=0
        var maximum=0f
        for(y in 0 until height)for(x in 0 until width) {
            val edge=abs(abs((x+.5f)/width*2-1)-.12f)<.02f
            for(c in 0..2) {
                val i=(y*width+x)*4+c;val d=abs(original[i]-learned[i]);maximum=max(maximum,d)
                if(edge){edgeError+=d*d;edgeCount++}else{otherError+=d*d;otherCount++}
            }
        }
        return JSONObject().put("xRange",listOf(-.12,.12)).put("rgb",listOf(.95,.04,.8))
            .put("edgeBandHalfWidthNormalized",.02).put("sourceEdgeRgbL1",jump)
            .put("edgeBandPixelFraction",edgeCount.toDouble()/(edgeCount+otherCount))
            .put("edgeBandLinearRgbMse",edgeError/edgeCount).put("awayFromEdgesLinearRgbMse",otherError/otherCount)
            .put("fullImageLinearRgbMse",(edgeError+otherError)/(edgeCount+otherCount))
            .put("maxAbsoluteRgbError",maximum)
    }

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
            report.put("stripe",stripeMetrics(original,learned,lab.source.width,lab.source.height))
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
