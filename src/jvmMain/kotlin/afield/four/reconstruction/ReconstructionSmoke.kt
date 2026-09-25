package afield.four.reconstruction

import afield.Box
import afield.four.Renderer
import afield.four.device.lightingCapabilityReport
import afield.four.util.Options
import afield.four.util.Window
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.system.exitProcess

/** Actual NGX evaluation with RT disabled. --clip records consecutive scene frames, not wall time.
 * --fallback verifies ordinary rendering with FIELD_NGX_DISABLE=1, without initializing NGX.
 */
object ReconstructionSmoke {
    @JvmStatic fun main(args: Array<String>) {
        if("--fallback" in args)System.setProperty("afield.ngx.disabled","true")
        Options.enableVR=false;Options.enableHDR=false;Options.enableRayTracing=false;Options.doVulkanSDK()
        val stress="--stress" in args
        val folder=File(if(stress)"build/reconstruction/stress" else "build/reconstruction").also { it.mkdirs() }
        val window=Window("Field · neural reconstruction");val renderer=Renderer(window)
        val box=Box("reconstruction-check");box.setPropertyRaw("renderer",renderer,doResync=false);box.setPropertyRaw("window",window,doResync=false)
        var success=false
        try {
            Box.withBoxContextNow(box) {
                val report=renderer.device.lightingCapabilityReport()
                report.put("ngx",NgxSupport.availability(renderer.device).fold({it},{it.toString()}))
                report.put("sdkRevision",NgxSupport.SDK_REVISION)
                report.put("enabledInstanceExtensions",renderer.instance.enabledExtensions.toList())
                report.put("enabledDeviceExtensions",renderer.device.enabledExtensions.toList())
                fun pump(n: Int=1) { val end=renderer.frameCounter+n;while(renderer.frameCounter<end){window.pollEvents();renderer.render()} }
                val modes=when {
                    "--fallback" in args -> listOf(null)
                    stress -> listOf(null,ReconstructionMode.PERFORMANCE,ReconstructionMode.ULTRA_PERFORMANCE)
                    else -> listOf(null,ReconstructionMode.DLAA,ReconstructionMode.QUALITY,ReconstructionMode.PERFORMANCE)
                }
                if("--fallback" !in args)NgxSupport.availability(renderer.device).getOrThrow()
                val output=ReconstructionSize(1920,1080)
                val staticOutputs=mutableMapOf<String,FloatArray>()
                for(mode in modes) {
                    val name=mode?.name?.lowercase() ?: "bypass"
                    val recommendation=mode?.let { DlssReconstruction.recommend(renderer,output,it) }
                    ReconstructionDemo(renderer,mode,output).use { demo ->
                        val warmup=maxOf(40,kotlin.math.ceil(8.0*output.width/demo.inputSize.width*output.height/demo.inputSize.height).toInt())
                        println("RECONSTRUCTION $name: ${demo.inputSize}, recommendation=$recommendation, warmup=$warmup")
                        demo.animate=false;demo.cameraMotion=false;pump(warmup)
                        val pixels=ReconstructionCapture.read(renderer,demo.output)
                        staticOutputs[name]=pixels
                        check(pixels.indices.filter { it%4!=3 }.all { pixels[it].isFinite() }) { "Nonfinite $name output" }
                        check(pixels.max()>0.25f && pixels.min()<0.1f) { "Empty $name output" }
                        ReconstructionCapture.save(File(folder,"$name.png"),pixels,output.width,output.height)
                        ReconstructionCapture.save(File(folder,"$name-input.png"),ReconstructionCapture.read(renderer,demo.color),demo.inputSize.width,demo.inputSize.height)
                        // Translation by +4 input pixels must map back by exactly -4, with no jitter delta.
                        demo.horizontalShiftPixels=4f;pump()
                        val depth=ReconstructionCapture.read(renderer,demo.depth)
                        val velocity=ReconstructionCapture.read(renderer,demo.motion)
                        val rawColor=ReconstructionCapture.read(renderer,demo.color)
                        var count=0;var maxError=0f
                        for(i in depth.indices)if(depth[i]<.999f){count++;maxError=maxOf(maxError,abs(velocity[2*i]+4),abs(velocity[2*i+1]))}
                        check(count>minOf(10000,demo.inputSize.width*demo.inputSize.height/10) && maxError<.002f) { "Motion sign/scale/jitter error $maxError across $count pixels" }
                        for((signal,values) in listOf("color" to rawColor,"depth" to depth,"motion" to velocity))
                            File(folder,"$name-$signal.f32").outputStream().use { stream ->
                                val bytes=java.nio.ByteBuffer.allocate(values.size*4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                bytes.asFloatBuffer().put(values);stream.write(bytes.array())
                            }
                        val jitter=if(mode==null)ReconstructionJitter(0f,0f) else ReconstructionJitter.halton(warmup.toLong(),demo.inputSize,output)
                        File(folder,"$name-inputs.json").writeText(JSONObject().put("width",demo.inputSize.width).put("height",demo.inputSize.height)
                            .put("encoding","little-endian float32, row-major, top left").put("channels",JSONObject().put("color",4).put("depth",1).put("motion",2))
                            .put("jitterPixels",listOf(jitter.x,jitter.y)).put("frame",warmup).put("sceneTime",0).put("translationPixels",4).toString(2))
                        demo.horizontalShiftPixels=0f;demo.reset("translation check complete");demo.cameraMotion=true;demo.animate=true;pump(warmup)
                        val start=demo.reconstruction?.gpuMilliseconds?.size ?: 0;pump(100)
                        val timing=demo.reconstruction?.gpuMilliseconds?.drop(start)?.sorted().orEmpty()
                        val modeReport=JSONObject().put("inputWidth",demo.inputSize.width).put("inputHeight",demo.inputSize.height)
                            .put("outputWidth",output.width).put("outputHeight",output.height).put("motionMaxErrorPixels",maxError)
                            .put("warmupFrames",warmup)
                            .put("sdkAllocatedBytes",demo.reconstruction?.sdkAllocatedBytes ?: 0)
                            .put("outputAllocatedBytes",demo.reconstruction?.outputAllocatedBytes ?: 0)
                        if(timing.isNotEmpty())modeReport.put("gpuMs",JSONObject().put("count",timing.size).put("p50",timing[timing.size/2]).put("p95",timing[(timing.size*.95).toInt()]))
                        if(recommendation!=null)modeReport.put("sdkSizes",JSONObject()
                            .put("optimal",listOf(recommendation.optimal.width,recommendation.optimal.height))
                            .put("minimum",listOf(recommendation.minimum.width,recommendation.minimum.height))
                            .put("maximum",listOf(recommendation.maximum.width,recommendation.maximum.height)))
                        report.put(name,modeReport)
                        for(abstract in listOf(false,true)) {
                            demo.abstractScene=abstract;demo.time=0f;demo.reset("comparison clip restart");pump(40)
                            val scene=if(abstract)"abstract" else "geometry"
                            ReconstructionCapture.save(File(folder,"$name-$scene.png"),ReconstructionCapture.read(renderer,demo.output),output.width,output.height)
                            if("--clip" in args)repeat(120) { frame ->
                                // At the midpoint exercise an explicit discontinuity and immediate reset.
                                if(frame==60){demo.time+=4f;demo.reset("camera cut")}
                                pump();ReconstructionCapture.save(File(folder,"clips/$name-$scene/%04d.png".format(frame)),
                                    ReconstructionCapture.read(renderer,demo.output),output.width,output.height)
                                if(stress && mode==ReconstructionMode.ULTRA_PERFORMANCE)ReconstructionCapture.save(
                                    File(folder,"clips/$name-$scene-input/%04d.png".format(frame)),
                                    ReconstructionCapture.read(renderer,demo.color),demo.inputSize.width,demo.inputSize.height)
                            }
                        }
                        val resets=demo.reconstruction?.resetCount ?: 0
                        demo.reset("live edit");pump();check(mode==null || demo.reconstruction!!.resetCount==resets+1)
                        check(demo.reload(fragment="#version 460\nthis is invalid").isFailure)
                        check(demo.reload(vertex=ReconstructionDemo::class.java.getResource("/afield/four/reconstruction/scene.vert")!!.readText()
                            .replace("vec4 settings;","vec4 settings; vec4 incompatible;")).isFailure)
                        pump();check(ReconstructionCapture.read(renderer,demo.output).any { it>.2f })
                        demo.reload().getOrThrow();pump()
                    }
                    pump(8) // exercise deferred resource retirement before creating the next mode
                }
                if("--fallback" !in args) {
                    // Independent spatial reference: four raster samples per output pixel, no NGX.
                    ReconstructionDemo(renderer,null,ReconstructionSize(3840,2160)).use { reference ->
                        reference.animate=false;reference.cameraMotion=false;pump(2)
                        val high=ReconstructionCapture.read(renderer,reference.output)
                        val low=FloatArray(output.width*output.height*4)
                        for(y in 0 until output.height)for(x in 0 until output.width)for(c in 0..3) {
                            var sum=0f;for(dy in 0..1)for(dx in 0..1)sum+=high[((y*2+dy)*3840+x*2+dx)*4+c]
                            low[(y*output.width+x)*4+c]=sum*.25f
                        }
                        fun mse(pixels: FloatArray): Double {
                            var error=0.0;for(i in low.indices)if(i%4!=3){val d=(low[i]-pixels[i]).toDouble();error+=d*d}
                            return error/(output.width*output.height*3)
                        }
                        val errors=JSONObject();staticOutputs.forEach { (name,pixels) -> errors.put(name,mse(pixels)) }
                        report.put("staticMseAgainst4spp",errors)
                        ReconstructionCapture.save(File(folder,"reference-4spp.png"),low,output.width,output.height)
                        if(!stress)check(mse(staticOutputs.getValue("dlaa"))<mse(staticOutputs.getValue("bypass"))) { "DLAA does not improve error against the independent spatial reference" }
                    }
                    pump(8)
                }
                // Recreate at a different size after full-resolution evaluation, then disable again.
                if("--fallback" !in args)ReconstructionDemo(renderer,ReconstructionMode.DLAA,ReconstructionSize(960,540)).use { demo ->
                    pump(8);check(ReconstructionCapture.read(renderer,demo.output).size==960*540*4)
                }
                if(stress)ReconstructionDemo(renderer,ReconstructionMode.PERFORMANCE,inputSize=ReconstructionSize(1280,720)).use { demo ->
                    pump(8);check(ReconstructionCapture.read(renderer,demo.output).all { it.isFinite() })
                    report.put("nonOptimalInputCheck","Performance created at SDK optimal 960x540; evaluated at 1280x720")
                }
                pump(8);File(folder,if("--fallback" in args)"fallback.json" else "measurements.json").writeText(report.toString(2))
                success=true
            }
        } finally { renderer.cleanup();window.cleanup() }
        check(success);println("RECONSTRUCTION CHECK DONE");exitProcess(0)
    }
}
