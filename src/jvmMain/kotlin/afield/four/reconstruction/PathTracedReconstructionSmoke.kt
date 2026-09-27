package afield.four.reconstruction

import afield.Box
import afield.four.Renderer
import afield.four.Target
import afield.four.fabricated.*
import afield.four.util.Camera
import afield.four.util.Options
import afield.four.util.Window
import afield.stdlib.linalg.Vector3f
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.tan
import kotlin.system.exitProcess

/** Real GPU contract, quality, motion and lifetime check. Readback is diagnostic only. */
object PathTracedReconstructionSmoke {
    @JvmStatic fun main(args: Array<String>) {
        Options.enableVR=false;Options.enableHDR=false;Options.enableRayTracing=true;Options.doVulkanSDK()
        val folder=File("build/neural-tools/path-rr").also { it.mkdirs() }
        val window=Window("Field path-traced RR");val renderer=Renderer(window)
        val box=Box("path-rr-check");box.setPropertyRaw("renderer",renderer,doResync=false)
        try { Box.withBoxContextNow(box) {
            fun pump(n: Int=1) { val end=renderer.frameCounter+n;while(renderer.frameCounter<end){window.pollEvents();renderer.render()} }
            val report=JSONObject().put("sdk",NgxSupport.SDK_VERSION)
            val plane=PathTraceScene().apply { val m=material(PathTraceMaterial.emissive(.4f,.2f,.1f))
                quad(Vector3f(-100f,-100f,0f),Vector3f(100f,-100f,0f),Vector3f(100f,100f,0f),Vector3f(-100f,100f,0f),m) }
            val camera=Camera(renderer).also { it.at.set(0f,0f,3f);it.target.set(0f);it.p.fov=.7 }
            for(backend in listOf(PathTracingRenderer.Backend.COMPUTE_BVH,PathTracingRenderer.Backend.RAY_QUERY)) {
                PathTracingRenderer(renderer,plane,PathTraceEnvironment.constant(0f),64,48,camera,backend,name="contract").use { tracer ->
                    tracer.maxBounces=1;val frame=tracer.enableFrameOutput()
                    renderer.renderList.preTargets["contract"]=Target { c,i ->tracer.render(c,i);frame.render(c,i) }
                    camera.at.set(0f,0f,3f);camera.target.set(0f);pump(4)
                    val raw=ReconstructionCapture.read(renderer,frame.color)
                    check((raw.indices step 4).all { abs(raw[it]-.4f)<1e-5 && abs(raw[it+3]-1)<1e-5 })
                    val depths=ReconstructionCapture.read(renderer,frame.depth)
                    val expectedDepth=frame.far/(frame.far-frame.near)-frame.far*frame.near/((frame.far-frame.near)*3)
                    check(depths.all { abs(it-expectedDepth)<1e-6 })
                    val initial=ReconstructionCapture.read(renderer,frame.motion);check(initial.all { abs(it)<.001 })
                    camera.at.x=.1f;camera.target.x=.1f;pump()
                    val motion=ReconstructionCapture.read(renderer,frame.motion)
                    val expected=.1f*48/(2*tan(.35)*3)
                    check((motion.indices step 2).all { abs(motion[it]-expected)<.002 && abs(motion[it+1])<.002 }) { "Motion mismatch: ${motion.take(8)}, expected $expected" }
                    report.put("$backend-motionX",motion[0])
                    tracer.reset("cut");pump()
                    check(ReconstructionCapture.read(renderer,frame.motion).all { abs(it)<.001 })
                    renderer.renderList.preTargets.remove("contract")
                }
                pump(8)
            }
            val scene=PathTraceScene().apply {
                val floor=material(PathTraceMaterial.diffuse(.45f,.5f,.55f))
                val red=material(PathTraceMaterial.plastic(.65f,.055f,.025f,.22f))
                val gold=material(PathTraceMaterial.metal(.95f,.7f,.3f,.12f))
                val blue=material(PathTraceMaterial.metal(.3f,.5f,.8f,.5f))
                val light=material(PathTraceMaterial.emissive(12f,8f,5f))
                quad(Vector3f(-15f,0f,-15f),Vector3f(-15f,0f,15f),Vector3f(15f,0f,15f),Vector3f(15f,0f,-15f),floor)
                sphere(Vector3f(-1.7f,.8f,0f),.8f,red,48,24);sphere(Vector3f(0f,.8f,0f),.8f,gold,48,24)
                sphere(Vector3f(1.7f,.8f,0f),.8f,blue,48,24)
                quad(Vector3f(-2f,4f,-1f),Vector3f(0f,4f,-1f),Vector3f(0f,4f,1f),Vector3f(-2f,4f,1f),light)
            }
            for(size in listOf(ReconstructionSize(960,540),ReconstructionSize(1920,1080))) {
                camera.at.set(3.5f,2.4f,7.5f);camera.target.set(0f,.65f,0f);camera.p.fov=.62
                val tracer=PathTracingRenderer(renderer,scene,PathTraceEnvironment.constant(.08f,.1f,.15f),size.width,size.height,camera,name="rr-path")
                PathTracedReconstruction(tracer).use { lab ->
                    lab.install();pump(64)
                    val rr=ReconstructionCapture.read(renderer,lab.output);val raw=ReconstructionCapture.read(renderer,lab.source.color)
                    check(rr.all { it.isFinite() } && rr.max()>.3f)
                    val hit=ReconstructionCapture.read(renderer,lab.source.specularHitDistance)
                    check(hit.all { it.isFinite() && it>=0 } && hit.any { it>0 && it<100 })
                    val name="${size.width}x${size.height}"
                    ReconstructionCapture.save(File(folder,"$name-rr.png"),rr,size.width,size.height)
                    ReconstructionCapture.save(File(folder,"$name-raw.png"),raw,size.width,size.height)
                    tracer.samplesPerFrame=16;pump(64)
                    val reference=tracer.readLinear()
                    fun mse(p: FloatArray): Double { var sum=0.0;var count=0
                        for(i in p.indices)if(i%4!=3) { val d=(p[i]-reference[i]).toDouble();sum+=d*d;count++ };return sum/count }
                    val rrError=mse(rr);val rawError=mse(raw)
                    check(rrError<rawError) { "RR did not reduce error: $rrError >= $rawError" }
                    ReconstructionCapture.save(File(folder,"$name-reference.png"),reference,size.width,size.height)
                    tracer.samplesPerFrame=1
                    repeat(96) { f ->
                        camera.at.x=(3.5+sin(f*.03)*1.2).toFloat()
                        if(f==48){camera.at.z=6f;lab.reset("camera cut")}
                        pump()
                        if("--clip" in args && size.width==1920)ReconstructionCapture.save(File(folder,"clip/%04d.png".format(f)),
                            ReconstructionCapture.read(renderer,lab.output),size.width,size.height)
                    }
                    check(ReconstructionCapture.read(renderer,lab.output).all { it.isFinite() })
                    // Measure continuous GPU frames separately from diagnostic PNG readback.
                    pump(120)
                    fun distribution(values: List<Double>): JSONObject { val v=values.takeLast(80).sorted();check(v.isNotEmpty())
                        return JSONObject().put("count",v.size).put("p50",v[v.size/2]).put("p95",v[(v.size*.95).toInt()]) }
                    report.put(name,JSONObject().put("rawMse",rawError).put("rrMse",rrError)
                        .put("wholePassMs",distribution(lab.gpuMilliseconds)).put("ngxMs",distribution(lab.reconstruction.gpuMilliseconds))
                        .put("guideBytes",lab.source.allocatedBytes).put("sdkBytes",lab.reconstruction.sdkAllocatedBytes))
                }
                pump(16)
            }
            File(folder,"measurements.json").writeText(report.toString(2));println(report.toString(2))
        } } finally { renderer.cleanup();window.cleanup() }
        println("PATH RR CHECK DONE");exitProcess(0)
    }
}
