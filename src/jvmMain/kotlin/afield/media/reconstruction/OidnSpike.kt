@file:afield.runtime.completion.CompletionCategory("Internal")
package afield.media.reconstruction

import afield.document.graph.Box
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.Target
import afield.graphics.scene.fabricated.*
import afield.graphics.vulkan.util.Camera
import afield.graphics.vulkan.util.Options
import afield.graphics.vulkan.util.Window
import afield.math.linalg.Vector3f
import org.json.JSONArray
import org.json.JSONObject
import java.awt.image.BufferedImage
import java.io.File
import java.lang.foreign.*
import java.lang.foreign.ValueLayout.*
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.log10
import kotlin.math.pow
import kotlin.system.exitProcess

/**
 * Native Intel Open Image Denoise (2.x, Apache-2.0) over FFM, used only as a yardstick for [OidnComputeDenoiser].
 * Field does not ship or download the library: pass the directory holding `libOpenImageDenoise.dylib`/`.so`.
 */
internal class Oidn(dir: Path) {
    private val lookup=SymbolLookup.libraryLookup(dir.resolve(if(System.getProperty("os.name").startsWith("Mac")) "libOpenImageDenoise.dylib" else "libOpenImageDenoise.so"),Arena.global())
    private fun h(name: String,result: MemoryLayout?,vararg args: MemoryLayout)=Linker.nativeLinker().downcallHandle(lookup.find(name).orElseThrow(),
        if(result==null)FunctionDescriptor.ofVoid(*args) else FunctionDescriptor.of(result,*args))
    private val newDevice=h("oidnNewDevice",ADDRESS,JAVA_INT)
    private val commitDevice=h("oidnCommitDevice",null,ADDRESS)
    private val releaseDevice=h("oidnReleaseDevice",null,ADDRESS)
    private val getError=h("oidnGetDeviceError",JAVA_INT,ADDRESS,ADDRESS)
    private val newBuffer=h("oidnNewBuffer",ADDRESS,ADDRESS,JAVA_LONG)
    private val writeBuffer=h("oidnWriteBuffer",null,ADDRESS,JAVA_LONG,JAVA_LONG,ADDRESS)
    private val readBuffer=h("oidnReadBuffer",null,ADDRESS,JAVA_LONG,JAVA_LONG,ADDRESS)
    private val releaseBuffer=h("oidnReleaseBuffer",null,ADDRESS)
    private val newFilter=h("oidnNewFilter",ADDRESS,ADDRESS,ADDRESS)
    private val setImage=h("oidnSetFilterImage",null,ADDRESS,ADDRESS,ADDRESS,JAVA_INT,JAVA_LONG,JAVA_LONG,JAVA_LONG,JAVA_LONG,JAVA_LONG)
    private val setBool=h("oidnSetFilterBool",null,ADDRESS,ADDRESS,JAVA_BOOLEAN)
    private val setInt=h("oidnSetFilterInt",null,ADDRESS,ADDRESS,JAVA_INT)
    private val setFloat=h("oidnSetFilterFloat",null,ADDRESS,ADDRESS,JAVA_FLOAT)
    private val commitFilter=h("oidnCommitFilter",null,ADDRESS)
    private val execute=h("oidnExecuteFilter",null,ADDRESS)
    private val releaseFilter=h("oidnReleaseFilter",null,ADDRESS)
    private val arena=Arena.ofShared()
    private fun s(text: String)=arena.allocateFrom(text)

    inner class Device(val type: Int) : AutoCloseable {
        val handle=newDevice.invokeWithArguments(type) as MemorySegment
        init { check(handle.address()!=0L) { "No OIDN device of type $type" };commitDevice.invokeWithArguments(handle);check() }
        fun check() { val msg=arena.allocate(ADDRESS);val code=getError.invokeWithArguments(handle,msg) as Int
            check(code==0) { "OIDN error $code: "+(msg.get(ADDRESS,0)).reinterpret(4096).getString(0) } }
        fun buffer(bytes: Long)=newBuffer.invokeWithArguments(handle,bytes) as MemorySegment
        fun write(buffer: MemorySegment,data: FloatArray) { Arena.ofConfined().use { a -> val m=a.allocateFrom(JAVA_FLOAT,*data);writeBuffer.invokeWithArguments(buffer,0L,m.byteSize(),m) } }
        fun read(buffer: MemorySegment,count: Int): FloatArray = Arena.ofConfined().use { a -> val m=a.allocate(count*4L,4)
            readBuffer.invokeWithArguments(buffer,0L,count*4L,m);m.toArray(JAVA_FLOAT) }
        fun filter(w: Int,h: Int,images: Map<String,MemorySegment>,hdr: Boolean,quality: Int,cleanAux: Boolean=false,inputScale: Float?=null): MemorySegment {
            val f=newFilter.invokeWithArguments(handle,s("RT")) as MemorySegment
            // FLOAT3 read from vec4 pixels: 16-byte stride, alpha ignored.
            for((name,b) in images)setImage.invokeWithArguments(f,s(name),b,3,w.toLong(),h.toLong(),0L,16L,w*16L)
            if(hdr)setBool.invokeWithArguments(f,s("hdr"),true)
            if(cleanAux)setBool.invokeWithArguments(f,s("cleanAux"),true)
            if(inputScale!=null)setFloat.invokeWithArguments(f,s("inputScale"),inputScale)
            setInt.invokeWithArguments(f,s("quality"),quality);commitFilter.invokeWithArguments(f);check();return f
        }
        fun run(f: MemorySegment) { execute.invokeWithArguments(f);check() }
        fun release(f: MemorySegment)=releaseFilter.invokeWithArguments(f)
        fun releaseBuf(b: MemorySegment)=releaseBuffer.invokeWithArguments(b)
        override fun close() { releaseDevice.invokeWithArguments(handle) }
    }
}

/** The first spike: native OIDN on CPU and Metal against readback of the path tracer. Results: OIDN_SPIKE.md. */
object OidnSpike {
    private fun ms(t0: Long)=(System.nanoTime()-t0)/1e6
    @JvmStatic fun main(args: Array<String>) {
        val oidnDir=Path.of(args.getOrNull(0) ?: error("usage: OidnSpike <oidn lib dir> <out dir>"))
        val folder=File(args.getOrNull(1) ?: "build/oidn-spike").also { it.mkdirs() }
        val width=1920;val height=1080;val budgets=listOf(8,32,128);val referenceSpp=1024
        Options.enableVR=false;Options.enableHDR=false;Options.enableRayTracing=false;Options.validate=false;Options.doVulkanSDK()
        val window=Window("Field OIDN spike");val renderer=Renderer(window)
        val box=Box("oidn-spike");box.setPropertyRaw("renderer",renderer,doResync=false)
        val report=JSONObject().put("width",width).put("height",height)
            .put("vkDriver",System.getenv("VK_DRIVER_FILES") ?: "default").put("gpu",renderer.device.physicalDevice.vkPhysicalDeviceProperties.deviceNameString())
        val captures=HashMap<Int,Triple<FloatArray,FloatArray,FloatArray>>()
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
                report.put("backend",tracer.backend.toString())
                val frame=tracer.enableFrameOutput()
                renderer.renderList.preTargets["oidn"]=Target { c,i -> tracer.render(c,i);frame.render(c,i) }
                pump(2);tracer.reset("start");pump(1)
                check(tracer.samples==1) { "expected 1 sample, got ${tracer.samples}" }
                // Aux: average primary albedo and normal over the same jittered samples as colour.
                val albedo=FloatArray(width*height*4);val normal=FloatArray(width*height*4)
                val t0=System.nanoTime();var readbackMs=0.0
                while(true) {
                    val r0=System.nanoTime()
                    val d=ReconstructionCapture.read(renderer,frame.diffuseAlbedo);val sp=ReconstructionCapture.read(renderer,frame.specularAlbedo)
                    val n=ReconstructionCapture.read(renderer,frame.normalRoughness)
                    for(i in albedo.indices) { albedo[i]+=(d[i]+sp[i]).coerceIn(0f,1f);normal[i]+=n[i] }
                    val k=tracer.samples
                    if(k in budgets) {
                        val color=tracer.readLinear()
                        val a=FloatArray(albedo.size) { albedo[it]/k };val nn=FloatArray(normal.size) { normal[it]/k }
                        captures[k]=Triple(color,a,nn)
                    }
                    readbackMs+=ms(r0)
                    if(k>=budgets.last())break
                    pump(1)
                }
                report.put("auxAccumulateWallMs",ms(t0)).put("auxReadbackMs",readbackMs)
                renderer.renderList.preTargets.remove("oidn")
                renderer.renderList.preTargets["oidn"]=tracer
                tracer.samplesPerFrame=4;tracer.reset("reference");val r0=System.nanoTime()
                while(tracer.samples<referenceSpp)pump(1)
                report.put("referenceSpp",tracer.samples).put("referenceWallMs",ms(r0))
                // One-shot timing of a single readback of the accumulated colour (what the route costs per denoise).
                val c0=System.nanoTime();reference=tracer.readLinear();report.put("colorReadbackMs",ms(c0))
                renderer.renderList.preTargets.remove("oidn")
                pump(4)
            }
        } } finally { renderer.cleanup();window.cleanup() }

        val n=width*height*4
        fun mse(p: FloatArray): Double { var sum=0.0;var count=0
            for(i in 0 until n)if(i%4!=3) { val d=(p[i]-reference[i]).toDouble();sum+=d*d;count++ };return sum/count }
        fun srgb(x: Float): Float { val c=x.coerceIn(0f,1f);return if(c<=.0031308f)c*12.92f else 1.055f*c.toDouble().pow(1/2.4).toFloat()-.055f }
        fun psnr(p: FloatArray): Double { var sum=0.0;var count=0
            for(i in 0 until n)if(i%4!=3) { val d=(srgb(p[i])-srgb(reference[i])).toDouble();sum+=d*d;count++ };return 10*log10(1/(sum/count)) }
        fun maxDiff(a: FloatArray,b: FloatArray)=(0 until n).filter { it%4!=3 }.maxOf { kotlin.math.abs(a[it]-b[it]) }

        val oidn=Oidn(oidnDir);val outputs=HashMap<String,FloatArray>()
        val devices=JSONArray()
        for((devName,type) in listOf("metal" to 5,"cpu" to 1)) {
            val load0=System.nanoTime();val dev=oidn.Device(type);val deviceMs=ms(load0)
            val bytes=n*4L
            val bc=dev.buffer(bytes);val ba=dev.buffer(bytes);val bn=dev.buffer(bytes);val bo=dev.buffer(bytes)
            val bpa=dev.buffer(bytes);val bpn=dev.buffer(bytes)
            val entry=JSONObject().put("device",devName).put("newDeviceMs",deviceMs);val runs=JSONArray()
            for(spp in budgets) {
                val (color,albedo,normal)=captures.getValue(spp)
                val u0=System.nanoTime();dev.write(bc,color);dev.write(ba,albedo);dev.write(bn,normal);val uploadMs=ms(u0)
                for((variant,quality) in listOf("high" to 6,"balanced" to 5,"cleanAux-high" to 6)) {
                    val clean=variant.startsWith("clean")
                    val f0=System.nanoTime()
                    var pre=0.0
                    val filters=ArrayList<MemorySegment>()
                    if(clean) {
                        val fa=dev.filter(width,height,mapOf("albedo" to ba,"output" to bpa),false,quality)
                        val fn=dev.filter(width,height,mapOf("normal" to bn,"output" to bpn),false,quality)
                        val p0=System.nanoTime();dev.run(fa);dev.run(fn);pre=ms(p0);filters+=fa;filters+=fn
                    }
                    val f=dev.filter(width,height,mapOf("color" to bc,"albedo" to (if(clean)bpa else ba),"normal" to (if(clean)bpn else bn),"output" to bo),true,quality,clean)
                    val commitMs=ms(f0)-pre
                    val first0=System.nanoTime();dev.run(f);val firstMs=ms(first0)
                    val times=(1..5).map { val e0=System.nanoTime();dev.run(f);ms(e0) }.sorted()
                    val d0=System.nanoTime();val out=dev.read(bo,n);val downloadMs=ms(d0)
                    outputs["$devName-$variant-$spp"]=out
                    runs.put(JSONObject().put("spp",spp).put("variant",variant).put("commitMs",commitMs).put("firstExecuteMs",firstMs)
                        .put("executeMsMedian",times[2]).put("executeMsMin",times[0]).put("auxPrefilterMs",pre)
                        .put("uploadMs",uploadMs).put("downloadMs",downloadMs).put("mse",mse(out)).put("psnr",psnr(out)))
                    filters.forEach { dev.release(it) };dev.release(f)
                    println("$devName $variant spp=$spp exec=${times[2]}ms psnr=${psnr(out)}")
                }
            }
            entry.put("runs",runs);devices.put(entry)
            listOf(bc,ba,bn,bo,bpa,bpn).forEach { dev.releaseBuf(it) };dev.close()
        }
        report.put("devices",devices)
        val noisy=JSONArray();for(spp in budgets)noisy.put(JSONObject().put("spp",spp).put("mse",mse(captures.getValue(spp).first)).put("psnr",psnr(captures.getValue(spp).first)))
        report.put("noisy",noisy)
        report.put("metalVsCpuMaxAbsDiff128",maxDiff(outputs.getValue("metal-high-128"),outputs.getValue("cpu-high-128")))

        // Pictures: full frames plus a crop montage. Columns: noisy, OIDN alb+nrm, OIDN cleanAux, reference.
        ReconstructionCapture.save(File(folder,"reference-${referenceSpp}spp.png"),reference,width,height)
        for(spp in budgets) {
            ReconstructionCapture.save(File(folder,"noisy-${spp}spp.png"),captures.getValue(spp).first,width,height)
            ReconstructionCapture.save(File(folder,"oidn-metal-${spp}spp.png"),outputs.getValue("metal-high-$spp"),width,height)
            ReconstructionCapture.save(File(folder,"albedo-${spp}spp.png"),captures.getValue(spp).second,width,height)
        }
        val cx=560;val cy=300;val cw=640;val ch=400;val gap=6
        val cols=4;val m=BufferedImage(cols*cw+(cols-1)*gap,budgets.size*ch+(budgets.size-1)*gap,BufferedImage.TYPE_INT_RGB)
        fun put(p: FloatArray,col: Int,row: Int) { for(y in 0 until ch)for(x in 0 until cw){val i=((cy+y)*width+cx+x)*4
            m.setRGB(col*(cw+gap)+x,row*(ch+gap)+y,((srgb(p[i])*255).toInt() shl 16) or ((srgb(p[i+1])*255).toInt() shl 8) or (srgb(p[i+2])*255).toInt())} }
        budgets.forEachIndexed { row,spp -> put(captures.getValue(spp).first,0,row);put(outputs.getValue("metal-high-$spp"),1,row)
            put(outputs.getValue("metal-cleanAux-high-$spp"),2,row);put(reference,3,row) }
        ImageIO.write(m,"png",File(folder,"crops-montage.png"))
        File(folder,"measurements.json").writeText(report.toString(2));println(report.toString(2))
        println("OIDN SPIKE DONE");exitProcess(0)
    }
}
