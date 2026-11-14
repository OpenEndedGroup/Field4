@file:afield.runtime.completion.CompletionCategory("Internal")
package afield.media.reconstruction

import afield.document.graph.Box
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.Target
import afield.graphics.render.*
import afield.graphics.scene.fabricated.*
import afield.graphics.vulkan.util.Camera
import afield.graphics.vulkan.util.Options
import afield.graphics.vulkan.util.Window
import afield.math.linalg.Vector3f
import org.json.JSONArray
import org.json.JSONObject
import java.awt.image.BufferedImage
import java.io.File
import java.lang.foreign.MemorySegment
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.system.exitProcess

/**
 * Measurement harness for [OidnComputeDenoiser]: path-traces a 1080p scene, denoises it on the GPU at 8/32/128
 * samples, times the network with fenced submits, and compares it with native OIDN (CPU, through [Oidn]) on the
 * same inputs. Arguments: `<oidn lib dir> <weights .tza | default> <out dir> [--timing-only]`. Results: OIDN_COMPUTE_PORT.md.
 */
object OidnComputeSpike {
    private fun ms(t0: Long)=(System.nanoTime()-t0)/1e6
    private fun median(v: List<Double>)=v.sorted().let { if(it.isEmpty()) Double.NaN else it[it.size/2] }
    class Capture(val color: FloatArray,val albedo: FloatArray,val normal: FloatArray,val vulkan: FloatArray,val texture: FloatArray,val scale: Float)

    /** The box-facing path: install beside an installed tracer, denoise at N samples, show through `tracer.output`. */
    private fun apiCheck(folder: File) {
        Options.enableVR=false;Options.enableHDR=false;Options.enableRayTracing=false;Options.validate=false;Options.doVulkanSDK()
        val window=Window("Field OIDN api check");val renderer=Renderer(window)
        val box=Box("oidn-api-check");box.setPropertyRaw("renderer",renderer,doResync=false)
        val result=JSONObject()
        try { Box.withBoxContextNow(box) {
            fun pump(n: Int=1) { val end=renderer.frameCounter+n;while(renderer.frameCounter<end){window.pollEvents();renderer.render()} }
            val scene=PathTraceScene().apply {
                val floor=material(PathTraceMaterial.diffuse(.45f,.5f,.55f));val red=material(PathTraceMaterial.plastic(.65f,.055f,.025f,.22f))
                val light=material(PathTraceMaterial.emissive(40f,28f,18f))
                quad(Vector3f(-15f,0f,-15f),Vector3f(-15f,0f,15f),Vector3f(15f,0f,15f),Vector3f(15f,0f,-15f),floor)
                sphere(Vector3f(0f,.8f,0f),.8f,red,48,24)
                quad(Vector3f(-2.6f,3.2f,-.4f),Vector3f(-1.8f,3.2f,-.4f),Vector3f(-1.8f,3.2f,.4f),Vector3f(-2.6f,3.2f,.4f),light)
            }
            val camera=Camera(renderer).also { it.at.set(3.5f,2.4f,7.5f);it.target.set(0f,.65f,0f);it.p.fov=.62 }
            val tracer=PathTracingRenderer(renderer,scene,PathTraceEnvironment.constant(.02f,.025f,.04f),640,360,camera,name="api-path").install()
            tracer.blitToScreen()
            val den=OidnComputeDenoiser(tracer).install();den.denoiseAt=16
            while(tracer.samples<20)pump(1)
            pump(2)
            fun maxDiff(a: FloatArray,b: FloatArray)=a.indices.filter { it%4!=3 }.maxOf { kotlin.math.abs(a[it]-b[it]) }
            val shown=ReconstructionCapture.read(renderer,tracer.output);val denoised=den.read(den.denoised);val accum=tracer.readLinear()
            check(den.denoiseCount==1 && den.denoisedSamples==16) { "expected one denoise at 16 spp, got ${den.denoiseCount} at ${den.denoisedSamples}" }
            check(maxDiff(shown,denoised)<1e-6) { "tracer.output does not show the denoised snapshot" }
            result.put("denoisedAt",den.denoisedSamples).put("shownVsDenoisedMax",maxDiff(shown,denoised)).put("shownVsAccumulationMax",maxDiff(shown,accum))
            ReconstructionCapture.save(File(folder,"api-shown-denoised.png"),shown,640,360)
            tracer.reset("camera cut");pump(3)
            val after=ReconstructionCapture.read(renderer,tracer.output);val accum2=tracer.readLinear()
            check(!den.current && maxDiff(after,accum2)<1e-6) { "after a reset tracer.output should show the accumulation again" }
            while(tracer.samples<20)pump(1)
            check(den.denoiseCount==2) { "expected a second denoise after the reset, got ${den.denoiseCount}" }
            result.put("afterResetVsAccumulationMax",maxDiff(after,accum2)).put("denoiseCountAfterSecondRun",den.denoiseCount)
            den.close();tracer.close();pump(4)
        } } finally { renderer.cleanup();window.cleanup() }
        File(folder,"api-check.json").writeText(result.toString(2));println(result.toString(2));println("OIDN API CHECK DONE");exitProcess(0)
    }

    /** Peak cooperative-matrix throughput (fp32 and fp16 accumulators) and copy bandwidth, dispatched through Field's ComputeModule. */
    private fun devicePeaks(folder: File) {
        Options.enableVR=false;Options.enableHDR=false;Options.enableRayTracing=false;Options.validate=false;Options.doVulkanSDK()
        val window=Window("Field device peaks");val renderer=Renderer(window)
        val result=JSONObject().put("gpu",renderer.device.physicalDevice.vkPhysicalDeviceProperties.deviceNameString())
        try {
            fun buffer(bytes: Long)=afield.graphics.vulkan.buffers.VulkanBuffer(renderer,bytes,
                org.lwjgl.vulkan.VK13.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT or org.lwjgl.vulkan.VK13.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                org.lwjgl.vulkan.VK13.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,needsDeviceAddress=true)
            fun module(src: String,gx: Int,push: Map<String,Any>): Pair<afield.graphics.render.ComputeModulePointer,afield.graphics.render.ComputeMesh> {
                val errors=ArrayList<String>()
                val m=afield.graphics.render.ComputeModule(afield.graphics.render.Parameters(renderer,name="peak",compute=src,errorHandler={ e,_ -> errors+=e })).pointer()
                check(m.current.valid) { errors.joinToString("\n") }
                val mesh=afield.graphics.render.ComputeMesh(renderer,m).also { it.x=gx }
                m+="d" to mesh;mesh.push.setMembers(m.current.ci.pushConstants.flatMap { it.members });push.forEach { (k,v) -> mesh.push[k]=v }
                return m to mesh
            }
            fun time(m: afield.graphics.render.ComputeModulePointer): Double {
                fun once(): Double { val cmd=afield.graphics.vulkan.device.CommandBuffer(renderer.commandPool,true,true);val fence=afield.graphics.vulkan.device.Fence(renderer.device,false)
                    try { cmd.beginRecording();m.current.render(cmd,afield.math.linalg.Rectanglef());cmd.endRecording();val t0=System.nanoTime()
                        org.lwjgl.system.MemoryStack.stackPush().use { renderer.graphQueue.submit(it.pointers(cmd.vkCommandBuffer),null,null,null,fence) };fence.fenceWait()
                        return (System.nanoTime()-t0)/1e6 } finally { fence.cleanup();cmd.cleanup() } }
                repeat(3) { once() };return median((1..10).map { once() })
            }
            val sink=buffer(1L shl 20)
            if(renderer.device.supportsCooperativeMatrix) for(acc in listOf("float","float16_t")) {
                val iters=4096;val groups=4096
                val src="""#version 460
                    #extension GL_KHR_cooperative_matrix : require
                    #extension GL_KHR_memory_scope_semantics : require
                    #extension GL_EXT_shader_explicit_arithmetic_types_float16 : require
                    #extension GL_EXT_shader_16bit_storage : require
                    #extension GL_EXT_buffer_reference : require
                    layout(local_size_x=128) in;
                    layout(buffer_reference,std430,buffer_reference_align=16) buffer S { float v[]; };
                    layout(push_constant) uniform P { S sink; int iters; } pc;
                    void main(){
                      coopmat<float16_t,gl_ScopeSubgroup,16,16,gl_MatrixUseA> a=coopmat<float16_t,gl_ScopeSubgroup,16,16,gl_MatrixUseA>(float16_t(0.001*float(gl_WorkGroupID.x&7u)));
                      coopmat<float16_t,gl_ScopeSubgroup,16,16,gl_MatrixUseB> b=coopmat<float16_t,gl_ScopeSubgroup,16,16,gl_MatrixUseB>(float16_t(0.002));
                      coopmat<$acc,gl_ScopeSubgroup,16,16,gl_MatrixUseAccumulator> c0=coopmat<$acc,gl_ScopeSubgroup,16,16,gl_MatrixUseAccumulator>(0.0),c1=c0,c2=c0,c3=c0,c4=c0,c5=c0,c6=c0,c7=c0;
                      for(int i=0;i<pc.iters;i++){ c0=coopMatMulAdd(a,b,c0);c1=coopMatMulAdd(a,b,c1);c2=coopMatMulAdd(a,b,c2);c3=coopMatMulAdd(a,b,c3);
                        c4=coopMatMulAdd(a,b,c4);c5=coopMatMulAdd(a,b,c5);c6=coopMatMulAdd(a,b,c6);c7=coopMatMulAdd(a,b,c7); }
                      coopmat<float,gl_ScopeSubgroup,16,16,gl_MatrixUseAccumulator> s=coopmat<float,gl_ScopeSubgroup,16,16,gl_MatrixUseAccumulator>(c0)+coopmat<float,gl_ScopeSubgroup,16,16,gl_MatrixUseAccumulator>(c1)
                        +coopmat<float,gl_ScopeSubgroup,16,16,gl_MatrixUseAccumulator>(c2)+coopmat<float,gl_ScopeSubgroup,16,16,gl_MatrixUseAccumulator>(c3)
                        +coopmat<float,gl_ScopeSubgroup,16,16,gl_MatrixUseAccumulator>(c4)+coopmat<float,gl_ScopeSubgroup,16,16,gl_MatrixUseAccumulator>(c5)
                        +coopmat<float,gl_ScopeSubgroup,16,16,gl_MatrixUseAccumulator>(c6)+coopmat<float,gl_ScopeSubgroup,16,16,gl_MatrixUseAccumulator>(c7);
                      coopMatStore(s,pc.sink.v,(gl_WorkGroupID.x&63u)*256u,16u,gl_CooperativeMatrixLayoutRowMajor);
                    }"""
                val (m,_)=module(src,groups,mapOf("sink" to sink,"iters" to iters))
                val ms=time(m);val flops=groups.toDouble()*4*iters*8*2*16*16*16
                result.put("tensorTflops_acc_$acc",flops/(ms/1e3)/1e12).put("tensorMs_acc_$acc",ms)
                m.current.cleanup()
            }
            // Copy bandwidth: 1 GiB read + 1 GiB write of vec4.
            val n=1L shl 26;val a=buffer(n*16);val b=buffer(n*16)
            val (cm,_)=module("""#version 460
                #extension GL_EXT_buffer_reference : require
                layout(local_size_x=256) in;
                layout(buffer_reference,std430,buffer_reference_align=16) buffer V { vec4 v[]; };
                layout(push_constant) uniform P { V src; V dst; } pc;
                void main(){ uint i=gl_GlobalInvocationID.x; pc.dst.v[i]=pc.src.v[i]+vec4(1.0); }""",(n/256).toInt(),mapOf("src" to a,"dst" to b))
            val cms=time(cm);result.put("copyGBps",2.0*n*16/(cms/1e3)/1e9).put("copyMs",cms)
            cm.current.cleanup();a.cleanup();b.cleanup();sink.cleanup()
        } finally { renderer.cleanup();window.cleanup() }
        File(folder,"peaks.json").writeText(result.toString(2));println(result.toString(2));println("PEAKS DONE");exitProcess(0)
    }

    @JvmStatic fun main(args: Array<String>) {
        if("--peaks" in args) { devicePeaks(File(args.last { !it.startsWith("--") }).also { it.mkdirs() });return }
        if("--api-check" in args) { apiCheck(File(args.last { !it.startsWith("--") }).also { it.mkdirs() });return }
        val oidnDir=Path.of(args[0]);val weights=args[1].takeIf { it!="default" }?.let { Path.of(it) };val folder=File(args[2]).also { it.mkdirs() }
        val timingOnly="--timing-only" in args
        val width=1920;val height=1080;val budgets=if(timingOnly) listOf(8) else listOf(8,32,128);val referenceSpp=1024
        Options.enableVR=false;Options.enableHDR=false;Options.enableRayTracing=false;Options.validate=false;Options.doVulkanSDK()
        val window=Window("Field OIDN compute spike");val renderer=Renderer(window)
        val box=Box("oidn-compute-spike");box.setPropertyRaw("renderer",renderer,doResync=false)
        val report=JSONObject().put("width",width).put("height",height)
            .put("vkDriver",System.getenv("VK_DRIVER_FILES") ?: "default").put("gpu",renderer.device.physicalDevice.vkPhysicalDeviceProperties.deviceNameString())
        val captures=LinkedHashMap<Int,Capture>()
        lateinit var reference: FloatArray
        try { Box.withBoxContextNow(box) {
            fun pump(n: Int=1) { val end=renderer.frameCounter+n;while(renderer.frameCounter<end){window.pollEvents();renderer.render()} }
            val scene=PathTraceScene().apply {
                val floor=material(PathTraceMaterial.diffuse(.45f,.5f,.55f))
                val wall=material(PathTraceMaterial.diffuse(.7f,.68f,.62f))
                val red=material(PathTraceMaterial.plastic(.65f,.055f,.025f,.22f))
                val gold=material(PathTraceMaterial.metal(.95f,.7f,.3f,.12f))
                val blue=material(PathTraceMaterial.metal(.3f,.5f,.8f,.5f))
                val light=material(PathTraceMaterial.emissive(40f,28f,18f))
                quad(Vector3f(-15f,0f,-15f),Vector3f(-15f,0f,15f),Vector3f(15f,0f,15f),Vector3f(15f,0f,-15f),floor)
                quad(Vector3f(-15f,0f,-2.5f),Vector3f(15f,0f,-2.5f),Vector3f(15f,8f,-2.5f),Vector3f(-15f,8f,-2.5f),wall)
                sphere(Vector3f(-1.7f,.8f,0f),.8f,red,48,24);sphere(Vector3f(0f,.8f,0f),.8f,gold,48,24)
                sphere(Vector3f(1.7f,.8f,0f),.8f,blue,48,24)
                quad(Vector3f(-2.6f,3.2f,-.4f),Vector3f(-1.8f,3.2f,-.4f),Vector3f(-1.8f,3.2f,.4f),Vector3f(-2.6f,3.2f,.4f),light)
            }
            val camera=Camera(renderer).also { it.at.set(3.5f,2.4f,7.5f);it.target.set(0f,.65f,0f);it.p.fov=.62 }
            PathTracingRenderer(renderer,scene,PathTraceEnvironment.constant(.02f,.025f,.04f),width,height,camera,
                PathTracingRenderer.Backend.COMPUTE_BVH,name="oidn-path").use { tracer ->
                val c0=System.nanoTime();val den=OidnComputeDenoiser(tracer,weights).also { it.denoiseAt=0;it.show=false }
                report.put("denoiserBuildMs",ms(c0)).put("activationBytes",den.activationBytes).put("cooperativeMatrix",den.cooperativeMatrix)
                renderer.renderList.preTargets["oidn"]=Target { c,i -> tracer.render(c,i);den.render(c,i) }
                pump(2);tracer.reset("start");pump(1)
                val timing=JSONArray()
                while(true) {
                    val k=tracer.samples
                    if(k in budgets) {
                        tracer.paused=true
                        val w0=System.nanoTime();den.request(1);pump(1);val firstWall=ms(w0)
                        den.request(16);pump(20)
                        val totals=den.totalMilliseconds.drop(1)
                        den.profileLayers=true;den.request(8);pump(12);den.profileLayers=false
                        val layers=JSONObject();den.timers.forEach { (n,t) -> layers.put(n,median(t.samples.toList())) }
                        timing.put(JSONObject().put("spp",k).put("gpuMsMedian",median(totals)).put("gpuMsMin",totals.minOrNull() ?: Double.NaN)
                            .put("samples",totals.size).put("firstDenoiseFrameWallMs",firstWall).put("layersMs",layers))
                        // Fence-timed submits (the in-frame GPU timestamps read 0 on KosmicKrisp).
                        repeat(3) { den.denoiseNow() }
                        val fenced=(1..15).map { den.denoiseNow().single().second }
                        val empty=median((1..15).map { den.emptySubmitMs() })
                        val perPass=(1..5).map { den.denoiseNow(perPass=true) }
                        val passMs=JSONObject();perPass[0].indices.forEach { i -> passMs.put(perPass[0][i].first,median(perPass.map { it[i].second })-empty) }
                        // Steady-state frames with the tracer paused: with and without a denoise each frame.
                        pump(4);var f0=System.nanoTime();pump(16);val idle=ms(f0)/16
                        den.request(16);f0=System.nanoTime();pump(16);val busy=ms(f0)/16
                        timing.getJSONObject(timing.length()-1).put("fencedMsMedian",median(fenced)).put("fencedMsMin",fenced.min())
                            .put("emptySubmitMs",empty).put("perPassMsMinusSubmit",passMs).put("frameMsIdle",idle).put("frameMsWithDenoise",busy)
                        println("spp=$k fenced denoise median=${median(fenced)} ms, frames idle=$idle with=$busy")
                        captures[k]=Capture(tracer.readLinear(),den.read(den.albedo),den.read(den.normal),den.read(den.denoised),
                            ReconstructionCapture.read(renderer,den.output.imageViewArray[0]),den.read(den.scale)[0])
                        tracer.paused=false
                    }
                    if(k>=budgets.last())break
                    pump(1)
                }
                report.put("timing",timing)
                if(timingOnly) {
                    val c=captures.getValue(8);val nn=width*height*4
                    Oidn(Path.of(args[0])).Device(1).use { dev ->
                        val bs=listOf(c.color,c.albedo,c.normal).map { a -> dev.buffer(nn*4L).also { b -> dev.write(b,a) } };val bo=dev.buffer(nn*4L)
                        val f=dev.filter(width,height,mapOf("color" to bs[0],"albedo" to bs[1],"normal" to bs[2],"output" to bo),true,6);dev.run(f)
                        val nat=dev.read(bo,nn);var s=0.0;var k=0;var mx=0f
                        for(i in 0 until nn)if(i%4!=3){val d=c.vulkan[i]-nat[i];s+=d.toDouble()*d;k++;mx=maxOf(mx,kotlin.math.abs(d))}
                        report.put("check8spp",JSONObject().put("linearRmseVsNative",kotlin.math.sqrt(s/k)).put("maxAbsVsNative",mx))
                    }
                    File(folder,"timing.json").writeText(report.toString(2));println(report.toString(2));println("OIDN COMPUTE TIMING DONE");exitProcess(0)
                }
                den.accumulateGuides=false
                tracer.samplesPerFrame=4;tracer.reset("reference");val r0=System.nanoTime()
                while(tracer.samples<referenceSpp)pump(1)
                report.put("referenceSpp",tracer.samples).put("referenceWallMs",ms(r0))
                reference=tracer.readLinear()
                renderer.renderList.preTargets.remove("oidn");den.close()
                pump(4)
            }
        } } finally { renderer.cleanup();window.cleanup() }

        val n=width*height*4
        fun srgb(x: Float): Float { val c=x.coerceIn(0f,1f);return if(c<=.0031308f)c*12.92f else 1.055f*c.toDouble().pow(1/2.4).toFloat()-.055f }
        fun psnr(p: FloatArray,r: FloatArray): Double { var sum=0.0;var count=0
            for(i in 0 until n)if(i%4!=3) { val d=(srgb(p[i])-srgb(r[i])).toDouble();sum+=d*d;count++ };return 10*log10(1/(sum/count)) }
        fun maxDiff(a: FloatArray,b: FloatArray)=(0 until n).filter { it%4!=3 }.maxOf { abs(a[it]-b[it]) }
        fun meanDisplayDiff(a: FloatArray,b: FloatArray): Double { var s=0.0;var c=0;for(i in 0 until n)if(i%4!=3){s+=abs(srgb(a[i])-srgb(b[i]));c++};return s/c }

        val oidn=Oidn(oidnDir);val native=HashMap<Int,FloatArray>();val quality=JSONArray()
        oidn.Device(1).use { dev ->
            val bytes=n*4L;val bc=dev.buffer(bytes);val ba=dev.buffer(bytes);val bn=dev.buffer(bytes);val bo=dev.buffer(bytes)
            for((spp,c) in captures) {
                dev.write(bc,c.color);dev.write(ba,c.albedo);dev.write(bn,c.normal)
                val img=mapOf("color" to bc,"albedo" to ba,"normal" to bn,"output" to bo)
                var f: MemorySegment=dev.filter(width,height,img,true,6);dev.run(f);val auto=dev.read(bo,n);dev.release(f)
                f=dev.filter(width,height,img,true,6,inputScale=c.scale);dev.run(f);val fixed=dev.read(bo,n);dev.release(f)
                native[spp]=auto
                quality.put(JSONObject().put("spp",spp).put("vulkanInputScale",c.scale)
                    .put("psnrNoisy",psnr(c.color,reference)).put("psnrVulkan",psnr(c.vulkan,reference)).put("psnrNativeCpu",psnr(auto,reference))
                    .put("psnrVulkanVsNative",psnr(c.vulkan,auto)).put("meanDisplayAbsDiffVulkanVsNative",meanDisplayDiff(c.vulkan,auto))
                    .put("maxLinearAbsDiffVulkanVsNative",maxDiff(c.vulkan,auto))
                    .put("nativeAutoVsNativeWithVulkanScaleMaxDiff",maxDiff(auto,fixed))
                    .put("textureVsBufferMaxDiff",maxDiff(c.texture,c.vulkan)))
            }
            listOf(bc,ba,bn,bo).forEach { dev.releaseBuf(it) }
        }
        report.put("quality",quality)

        ReconstructionCapture.save(File(folder,"reference-${referenceSpp}spp.png"),reference,width,height)
        for((spp,c) in captures) {
            ReconstructionCapture.save(File(folder,"noisy-${spp}spp.png"),c.color,width,height)
            ReconstructionCapture.save(File(folder,"vulkan-${spp}spp.png"),c.texture,width,height)
            ReconstructionCapture.save(File(folder,"native-cpu-${spp}spp.png"),native.getValue(spp),width,height)
        }
        val cx=560;val cy=300;val cw=640;val ch=400;val gap=6;val cols=4
        val m=BufferedImage(cols*cw+(cols-1)*gap,budgets.size*ch+(budgets.size-1)*gap,BufferedImage.TYPE_INT_RGB)
        fun put(p: FloatArray,col: Int,row: Int) { for(y in 0 until ch)for(x in 0 until cw){val i=((cy+y)*width+cx+x)*4
            m.setRGB(col*(cw+gap)+x,row*(ch+gap)+y,((srgb(p[i])*255).toInt() shl 16) or ((srgb(p[i+1])*255).toInt() shl 8) or (srgb(p[i+2])*255).toInt())} }
        budgets.forEachIndexed { row,spp -> val c=captures.getValue(spp)
            put(c.color,0,row);put(c.texture,1,row);put(native.getValue(spp),2,row);put(reference,3,row) }
        ImageIO.write(m,"png",File(folder,"crops-montage.png"))
        File(folder,"measurements.json").writeText(report.toString(2));println(report.toString(2))
        println("OIDN COMPUTE SPIKE DONE");exitProcess(0)
    }
}
