package afield.four.reconstruction

import afield.Box
import afield.four.Renderer
import afield.four.util.Options
import afield.four.util.Window
import org.json.JSONObject
import java.io.File
import kotlin.system.exitProcess

object RayReconstructionSmoke {
    @JvmStatic fun main(args: Array<String>) {
        Options.enableVR=false;Options.enableHDR=false;Options.enableRayTracing=true;Options.doVulkanSDK()
        val folder=File("build/neural-tools/rr").also { it.mkdirs() }
        val window=Window("Field Ray Reconstruction");val renderer=Renderer(window)
        val box=Box("rr-check");box.setPropertyRaw("renderer",renderer,doResync=false)
        try { Box.withBoxContextNow(box) {
            println(NgxSupport.rayReconstructionAvailability(renderer.device).getOrThrow())
            RayReconstructionDemo(renderer).use { demo ->
                fun pump(n: Int=1){val end=renderer.frameCounter+n;while(renderer.frameCounter<end){window.pollEvents();renderer.render()}}
                val report=JSONObject().put("sdk",NgxSupport.SDK_VERSION).put("output",listOf(demo.size.width,demo.size.height))
                val staticImages=mutableMapOf<String,FloatArray>()
                for(path in RayReconstructionDemo.Path.entries) {
                    demo.path=path;demo.time=0.0;demo.animate=false;demo.cameraMotion=false;demo.reset("comparison restart")
                    demo.scene.animate(0.0);demo.scene.bundle.scene.camera.at.set(5f,4f,6f)
                    pump(64)
                    val pixels=ReconstructionCapture.read(renderer,demo.output)
                    check(pixels.all { it.isFinite() } && pixels.max()>.2f)
                    staticImages[path.name.lowercase()]=pixels
                    ReconstructionCapture.save(File(folder,"${path.name.lowercase()}.png"),pixels,demo.size.width,demo.size.height)
                    val adapter=when(path){RayReconstructionDemo.Path.RAY_RECONSTRUCTION->demo.rr;RayReconstructionDemo.Path.SPATIAL_DLAA->demo.sr;else->null}
                    val start=adapter?.gpuMilliseconds?.size ?: 0;pump(100)
                    val times=adapter?.gpuMilliseconds?.drop(start)?.sorted().orEmpty()
                    report.put(path.name.lowercase(),JSONObject().put("gpuMs",if(times.isEmpty())JSONObject.NULL else JSONObject()
                        .put("count",times.size).put("p50",times[times.size/2]).put("p95",times[(times.size*.95).toInt()]))
                        .put("sdkAllocatedBytes",adapter?.sdkAllocatedBytes ?: 0))
                    demo.animate=true;demo.cameraMotion=true
                    if("--clip" in args)repeat(120){frame->
                        if(frame==60){demo.time+=4;demo.reset("camera cut")}
                        if(frame==90){demo.scene.moveObject(1,afield.stdlib.linalg.Matrix4f().translation(-2f,.75f,1f));demo.reset("instance edit")}
                        pump();ReconstructionCapture.save(File(folder,"clips/${path.name.lowercase()}/%04d.png".format(frame)),
                            ReconstructionCapture.read(renderer,demo.output),demo.size.width,demo.size.height)
                    }
                    demo.scene.moveObject(1,afield.stdlib.linalg.Matrix4f().translation(-1.3f,.75f,0f));demo.reset("instance restored")
                }
                demo.path=RayReconstructionDemo.Path.RAW;demo.animate=false;demo.cameraMotion=false
                demo.scene.animate(0.0);demo.scene.bundle.scene.camera.at.set(5f,4f,6f);demo.reset("reference")
                demo.scene.lighting.mode=afield.four.lighting.DirectLighting.Mode.RTXDI_REFERENCE;demo.scene.lighting.samples=256
                val reference=FloatArray(demo.size.width*demo.size.height*4)
                repeat(16){pump();val sample=ReconstructionCapture.read(renderer,demo.output);for(i in reference.indices)reference[i]+=sample[i]/16}
                val errors=JSONObject();staticImages.forEach { (name,pixels) ->
                    var sum=0.0;for(i in pixels.indices)if(i%4!=3){val d=(pixels[i]-reference[i]).toDouble();sum+=d*d}
                    errors.put(name,sum/(pixels.size/4*3))
                };report.put("staticMseAgainst4096Candidates",errors)
                ReconstructionCapture.save(File(folder,"reference.png"),reference,demo.size.width,demo.size.height)
                demo.scene.lighting.mode=afield.four.lighting.DirectLighting.Mode.RTXDI;demo.scene.lighting.samples=8
                demo.path=RayReconstructionDemo.Path.RAY_RECONSTRUCTION;pump(8)
                for((name,image) in listOf("raw" to demo.raw,"diffuse" to demo.diffuseAlbedo,"specular" to demo.specularAlbedo,"normal" to demo.normalRoughness))
                    ReconstructionCapture.save(File(folder,"guide-$name.png"),ReconstructionCapture.read(renderer,image),demo.size.width,demo.size.height)
                check(ReconstructionCapture.read(renderer,demo.motion).all { it.isFinite() })
                val stationaryLight=demo.scene.lighting.emitter.center.x
                demo.animate=false;demo.cameraMotion=true;val cameraX=demo.scene.bundle.scene.camera.at.x;pump(8)
                check(demo.scene.bundle.scene.camera.at.x!=cameraX && demo.scene.lighting.emitter.center.x==stationaryLight) { "Camera and light controls must be independent" }
                demo.cameraMotion=false
                demo.scene.lighting.emitter.radiance.set(0f);demo.reset("light deleted");pump(16)
                val dark=ReconstructionCapture.read(renderer,demo.output)
                check(dark.indices.filter { it%4!=3 }.maxOf { kotlin.math.abs(dark[it]) }<.01f) { "RR retained deleted illumination" }
                File(folder,"measurements.json").writeText(report.toString(2))
            }
            repeat(32){window.pollEvents();renderer.render()}
        } } finally { renderer.cleanup();window.cleanup() }
        println("RAY RECONSTRUCTION CHECK DONE");exitProcess(0)
    }
}
