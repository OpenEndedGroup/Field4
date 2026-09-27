package afield.four.neural

import afield.Box
import afield.four.Renderer
import afield.four.modules.*
import afield.four.modules.Module
import afield.four.reconstruction.ReconstructionCapture
import afield.four.targets.RenderToTexture
import afield.four.util.Options
import afield.four.util.Window
import afield.stdlib.linalg.Vector4f
import org.json.JSONObject
import org.lwjgl.vulkan.VK13.*
import java.io.File
import kotlin.system.exitProcess

object GpuShaderTrainingSmoke {
    @JvmStatic fun main(args: Array<String>) {
        Options.enableVR=false;Options.enableHDR=false;Options.doVulkanSDK()
        val folder=File("build/neural-tools/gpu-learning").also { it.mkdirs() }
        val window=Window("Field GPU shader learning");val renderer=Renderer(window)
        val box=Box("gpu-learning-check");box.setPropertyRaw("renderer",renderer,doResync=false)
        try {Box.withBoxContextNow(box) {
            fun pump(n: Int=1){val end=renderer.frameCounter+n;while(renderer.frameCounter<end){window.pollEvents();renderer.render()}}
            val source=RenderToTexture(renderer){width=1920;height=1080;useDepth=false;format=VK_FORMAT_R32G32B32A32_SFLOAT}
            val teacher=Module(parameters(renderer){name="editable-teacher";source.apply();depthTest=false;depthWrite=false;blend=false
                vertex="""#version 460
                    void main(){vec2 p=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);gl_Position=vec4(p*2-1,0,1);}
                """
                fragment=GpuShaderTrainingSmoke::class.java.getResource("/afield/four/neural/teacher.frag")!!.readText()
            })
            check(teacher.valid);val p=teacher.pointer();val mesh=Mesh(renderer,p);mesh.resize(3,0,p)
            mesh.push.setMembers(teacher.fi.pushConstants.single().members);teacher.meshes["quad"]=mesh;source.modules["teacher"]=p
            GpuShaderLearningView(renderer,source){parameter->mesh.push["state"]=Vector4f(source.width.toFloat(),source.height.toFloat(),parameter,0f)}.use { lab ->
                lab.install();lab.learning=false;lab.parameter=.173f;pump(4)
                fun mse(): Double {
                    val a=ReconstructionCapture.read(renderer,source);val b=ReconstructionCapture.read(renderer,lab.model.output)
                    check(a.all { it.isFinite() } && b.all { it.isFinite() })
                    var sum=0.0;var n=0
                    for(i in a.indices)if(i%4!=3){val d=(a[i]-b[i]).toDouble();sum+=d*d;n++};return sum/n
                }
                val initial=mse();println("GPU LEARNING INITIAL $initial")
                // First-step reference catches signed second moments and layout/packing mistakes.
                val old=ClusterGeometrySmoke.readBuffer(renderer,lab.model.weights).asShortBuffer()
                lab.learning=true;lab.iterationsPerFrame=1;lab.parameter=-1f;pump()
                val gradient=ClusterGeometrySmoke.readBuffer(renderer,lab.model.gradients).asShortBuffer()
                val state=ClusterGeometrySmoke.readBuffer(renderer,lab.model.optimizer).asFloatBuffer()
                var nonzero=0
                for(i in 0 until old.remaining()) {
                    val g=(java.lang.Float.float16ToFloat(gradient[i])/128f).coerceIn(-10f,10f)
                    val m=.1f*g;val v=.001f*g*g
                    val w=java.lang.Float.float16ToFloat(old[i])-.001f*g/(kotlin.math.abs(g)+1e-8f)
                    check(state[i*4].isFinite() && kotlin.math.abs(state[i*4]-w)<2e-5f)
                    check(kotlin.math.abs(state[i*4+1]-m)<1e-6f && kotlin.math.abs(state[i*4+2]-v)<1e-6f && state[i*4+2]>=0)
                    if(kotlin.math.abs(g)>.00001f)nonzero++
                }
                check(nonzero>100) { "Training did not produce useful gradients" }
                lab.learning=true;lab.iterationsPerFrame=8
                repeat(500){f->lab.parameter=(f%13)/6f-1;pump()
                    if(f%100==99)println("GPU LEARNING ${lab.model.steps} updates")}
                lab.learning=false;lab.parameter=.173f;pump(8)
                val fitted=mse();println("GPU LEARNING FITTED $fitted")
                check(fitted<.003 && fitted<initial*.05) { "GPU fit did not converge: initial=$initial fitted=$fitted" }
                val stripe=ShaderLearningChecks.stripeMetrics(ReconstructionCapture.read(renderer,source),
                    ReconstructionCapture.read(renderer,lab.model.output),source.width,source.height)
                ReconstructionCapture.save(File(folder,"comparison.png"),ReconstructionCapture.read(renderer,lab.output),source.width,source.height)
                ReconstructionCapture.save(File(folder,"learned.png"),ReconstructionCapture.read(renderer,lab.model.output),source.width,source.height)
                ReconstructionCapture.save(File(folder,"source.png"),ReconstructionCapture.read(renderer,source),source.width,source.height)
                val saved=lab.model.save(File(folder,"models/run-${System.nanoTime()}"),ShaderTraining.sourceDigest(source),
                    "Fixed fullscreen teacher; only normalized time varies")
                val live=ReconstructionCapture.read(renderer,lab.model.output)
                LearnedShader(renderer,source.width,source.height,true,File(saved,"weights.f16"),File(saved,"weights.json"),"checkpoint",false).use { frozen ->
                    frozen.time=lab.parameter;renderer.renderList.preTargets["checkpoint"]=frozen;pump(4)
                    val restored=ReconstructionCapture.read(renderer,frozen.output)
                    check(restored.all { it.isFinite() })
                    val delta=restored.indices.maxOf { kotlin.math.abs(restored[it]-live[it]) }
                    check(delta<.01f) { "Training-optimal to row-major checkpoint mismatch: $delta" }
                    println("GPU LEARNING CHECKPOINT maxAbs=$delta at $saved")
                }
                lab.view=1;val count=lab.sourceRenderCount;pump(16);check(count==lab.sourceRenderCount)
                lab.view=2
                if("--clip" in args)repeat(120){f->lab.parameter=kotlin.math.sin(f*.05236f);pump()
                    ReconstructionCapture.save(File(folder,"clip/%04d.png".format(f)),ReconstructionCapture.read(renderer,lab.output),source.width,source.height)}
                val times=lab.model.gpuMilliseconds.takeLast(100).sorted();check(times.size==100)
                val report=JSONObject().put("initialMse",initial).put("heldOutParameter",.173).put("fittedMse",fitted).put("steps",lab.model.steps)
                    .put("stripe",stripe)
                    .put("batch",lab.model.batchSize).put("iterationsPerFrame",lab.iterationsPerFrame).put("trainingBytes",lab.model.trainingBytes)
                    .put("allocatedBytes",lab.model.allocatedBytes).put("layouts",lab.model.layers.map { it.toString() })
                    .put("trainingMs",JSONObject().put("count",times.size).put("p50",times[50]).put("p95",times[95]))
                File(folder,"measurements.json").writeText(report.toString(2));println(report.toString(2))
                lab.model.reset();lab.view=2;pump(4);check(mse()>fitted*10) { "Reset retained trained model" }
            }
            renderer.cleanup+=Runnable {teacher.cleanup();source.imageViewArray.forEach { it.imageView.cleanup() };source.colorImageRefs.forEach { it?.cleanup() };source.defaultSampler?.cleanup()}
            pump(32)
        }}finally{renderer.cleanup();window.cleanup()}
        println("GPU SHADER TRAINING CHECK DONE");exitProcess(0)
    }
}
