package afield.four.lighting

import afield.Box
import afield.four.Renderer
import afield.four.device.lightingCapabilityReport
import afield.four.fabricated.*
import afield.four.util.Options
import afield.four.util.Window
import afield.stdlib.linalg.Matrix4f
import afield.stdlib.linalg.Vector3f
import org.json.JSONObject
import org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32A32_SFLOAT
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.*
import kotlin.system.exitProcess

/** A reusable live-coding scene. Camera is [bundle.scene.camera]; call animate from the box frame loop. */
class DirectLightingDemo(val renderer: Renderer, width: Int=640,height: Int=360) : AutoCloseable {
    val bundle=StandardRenderer.build(renderer,StandardRenderer.Config(renderWidth=width,renderHeight=height,
        shadowWidth=256,shadowHeight=256,positionFormat=VK_FORMAT_R32G32B32A32_SFLOAT))
    val visibility=OpaqueRayScene(renderer)
    val cube=OpaqueTriangles.box(0.55f,0.75f,0.55f)
    val floor=OpaqueTriangles.box(5f,0.1f,4f)
    val instances=mutableListOf(OpaqueInstance(1,floor,Matrix4f().translation(0f,-0.1f,0f)),
        OpaqueInstance(2,cube,Matrix4f().translation(-1.3f,0.75f,0f)),
        OpaqueInstance(3,cube,Matrix4f().translation(1.1f,0.75f,-0.8f)),
        OpaqueInstance(4,cube,Matrix4f().translation(0.6f,0.75f,1.1f).rotateY(0.5f)))
    val meshes=instances.mapIndexed { index, i -> bundle.registerOpaqueTriangles("object-${i.id}",i.geometry,
        listOf(Vector3f(0.65f),Vector3f(0.8f,0.2f,0.09f),Vector3f(0.1f,0.4f,0.7f),Vector3f(0.7f,0.55f,0.12f))[index],i.transform) }
    val lighting: DirectLighting
    init {
        bundle.scene.camera.at.set(5f,4f,6f);bundle.scene.camera.target.set(0f,0.4f,0f)
        bundle.scene.projection.fov=0.85
        bundle.scene.projection.auto={ bundle.scene.projection.resize(width,height) }
        visibility.commit(instances)
        bundle.attachTo(renderer)
        lighting=bundle.directLighting(visibility).attach()
        lighting.emitter.center.set(-2.2f,4.5f,1.5f)
        lighting.emitter.radiance.set(60f)
        lighting.output.blitToScreen(name="direct-light-demo")
    }
    fun animate(seconds: Double) { lighting.emitter.center.set((sin(seconds)*2.8).toFloat(),4.5f,(cos(seconds)*1.5).toFloat()) }
    fun moveObject(index: Int, transform: Matrix4f) {
        val next=instances.toMutableList();next[index]=next[index].copy(transform=Matrix4f(transform))
        visibility.commit(next) // build first; only then switch the raster transform
        instances.clear();instances.addAll(next);meshes[index].setModel(transform)
    }
    override fun close() {
        renderer.renderList.modules.remove("direct-light-demo")
        lighting.close();visibility.close()
        bundle.frameGraph().passNames().forEach { renderer.renderList.preTargets.remove(it) }
    }
}

/*
FIELD_TEST
id: four.direct-lighting.smoke
requiresDisplay: true
runner: jvm-main
entrypoint: afield.four.lighting.DirectLightingSmoke
tags: [smoke, four, lighting, ray-query, gpu]
timeoutSec: 420
expect:
  exitCode: 0
  stdoutContains:
    - "DIRECT LIGHTING CHECK DONE"
*/
object DirectLightingSmoke {
    @JvmStatic fun main(args: Array<String>) {
        Options.enableVR=false
        if("--fallback" in args)Options.enableRayTracing=false
        Options.doVulkanSDK()
        val dir=File("build/lighting-experiment/${if("--fallback" in args) "fallback" else "gpu"}").also { it.mkdirs() }
        val window=Window("Field · direct lighting laboratory")
        val renderer=Renderer(window)
        val box=Box("direct-lighting-check")
        box.setPropertyRaw("renderer",renderer,doResync=false)
        box.setPropertyRaw("window",window,doResync=false)
        var success=false
        try {
            File(dir,"capabilities.json").writeText(renderer.device.lightingCapabilityReport().toString(2))
            println(renderer.device.lightingCapabilityReport().toString(2))
            Box.withBoxContextNow(box) {
                DirectLightingDemo(renderer,256,192).use { demo ->
                    val light=demo.lighting
                    fun pump(n: Int=4) { repeat(n) { window.pollEvents();renderer.render() } }
                    fun read(): FloatArray { pump();return light.readLinear().also { check(it.all { v -> v.isFinite() })
                        check(it.indices.filter { i -> i%4!=3 }.all { i -> it[i]>=0f }) } }
                    light.mode=DirectLighting.Mode.SOURCE
                    val source=read();save(dir,"source",source,light.width,light.height)
                    light.mode=DirectLighting.Mode.UNSHADOWED;light.effectWeight=0f
                    val bypass=read();check(error(source,bypass)<1e-6) { "Effect zero did not preserve source" }
                    light.effectWeight=1f;light.samples=32
                    val direct=read();save(dir,"unshadowed",direct,light.width,light.height)
                    check(direct.filterIndexed { i,_ -> i%4!=3 }.max()>0.1f) { "No lit geometry" }
                    // Independent seeded ensembles at low and high sample budgets; report uncertainty.
                    fun ensemble(budget: Int, base: Int): List<Double> {
                        light.samples=budget
                        return List(12) { i -> light.seed=base+i; val pixels=read()
                            pixels.indices.filter { it%4!=3 }.sumOf { pixels[it].toDouble() }/(pixels.size/4*3) }
                    }
                    val low=ensemble(8,100);val reference=ensemble(256,1000)
                    fun variance(v: List<Double>): Double = v.sumOf { (it-v.average()).pow(2) }/(v.size-1)
                    val uncertainty=sqrt(variance(low)/low.size+variance(reference)/reference.size)
                    println("Lighting ensemble low=$low reference=$reference standardError=$uncertainty")
                    check(abs(low.average()-reference.average())<=6*uncertainty+1e-6) { "Estimator disagreement: low=${low.average()} reference=${reference.average()} SE=$uncertainty" }
                    light.samples=32;light.seed=1
                    val report=JSONObject().put("ensemble",JSONObject().put("lowMean",low.average())
                        .put("referenceMean",reference.average()).put("standardError",uncertainty)).put("width",light.width).put("height",light.height).put("samples",light.samples)
                        .put("workingBytes",light.workingBytes)
                    if(demo.visibility.available) {
                        light.mode=DirectLighting.Mode.RAY_QUERY
                        val shadow=read();save(dir,"shadow",shadow,light.width,light.height)
                        val shadowDifference=error(direct,shadow)
                        check(shadowDifference>0.005) { "No shadow difference: $shadowDifference" }
                        check(shadow.indices.filter { it%4!=3 }.all { shadow[it]<=direct[it]+1e-5 }) { "Visibility added energy" }
                        val before=demo.visibility.blasBuilds
                        light.emitter.center.x=2.7f
                        val moved=read();save(dir,"moved-light",moved,light.width,light.height)
                        check(error(shadow,moved)>0.01) { "Moving light did not change illumination" }
                        check(demo.visibility.blasBuilds==before) { "Light edit rebuilt static geometry" }
                        // An enormous opaque slab BEYOND the emitter must not occlude finite segments.
                        val beyond=OpaqueInstance(10,OpaqueTriangles.box(100f,0.2f,100f),Matrix4f().translation(0f,9f,0f))
                        demo.visibility.commit(demo.instances+ beyond)
                        val finite=read();check(error(finite,moved)<1e-6) { "Occluder beyond emitter cast a shadow" }
                        demo.visibility.commit(demo.instances)
                        demo.moveObject(1,Matrix4f().translation(-2.5f,0.75f,1f))
                        val movedInstance=read();save(dir,"moved-instance",movedInstance,light.width,light.height)
                        checkChanged(moved,movedInstance,"Instance transform")
                        check(demo.visibility.blasBuilds==before+1) { "Transform-only edit rebuilt BLAS" }
                        demo.bundle.scene.camera.at.x-=3f
                        val camera=read();save(dir,"camera-cut",camera,light.width,light.height)
                        checkChanged(camera,movedInstance,"Camera cut")
                        light.mode=DirectLighting.Mode.SOURCE;read();light.mode=DirectLighting.Mode.RAY_QUERY
                        check(error(camera,read())<1e-6) { "Disable/re-enable retained stale state" }
                        val failed=light.reload(DirectLighting.shader(true)+"\ninvalid shader")
                        check(failed.isFailure);check(error(camera,read())<1e-6) { "Failed reload lost working pipeline" }
                        check(light.reload(DirectLighting.shader(true).replace("float epsilon; float weight;", "float epsilon; float weight; vec4 incompatible;")).isFailure)
                        check(error(camera,read())<1e-6)
                        check(light.reload(DirectLighting.shader(true).replace("local_size_x=8", "local_size_x=4")).isFailure)
                        check(light.reload(DirectLighting.shader(true)).isSuccess)
                        check(error(camera,read())<1e-6)
                        report.put("shadowMeanAbsoluteDifference",shadowDifference).put("blasBuilds",demo.visibility.blasBuilds).put("tlasBuilds",demo.visibility.tlasBuilds)
                    } else {
                        check(runCatching { light.mode=DirectLighting.Mode.RAY_QUERY }.isFailure)
                        println("Ray-query runtime unavailable/disabled: source and direct fallback passed")
                    }
                    for(view in listOf(DirectLighting.View.NORMALS,DirectLighting.View.DEPTH,DirectLighting.View.VISIBILITY,DirectLighting.View.MOTION)) {
                        light.view=view;save(dir,view.name.lowercase(),read(),light.width,light.height)
                    }
                    light.view=DirectLighting.View.RADIANCE
                    light.emitter.radiance.set(0f)
                    val dark=read()
                    save(dir,"dark",dark,light.width,light.height)
                    check((dark.indices step 4).filter { dark[it+3]>=0f }.all { i -> (0..2).all { dark[i+it]<1e-6f } }) { "Dark scene retained energy" }
                    light.emitter.radiance.set(60f)
                    pump(16)
                    val start=light.gpuMilliseconds.size
                    pump(100)
                    val times=light.gpuMilliseconds.drop(start).sorted()
                    check(times.all { it>=0 && it.isFinite() })
                    if(times.isNotEmpty())
                    report.put("visibilityShadingGpuMs",JSONObject().put("count",times.size).put("p50",times[times.size/2])
                        .put("p95",times[(times.size*0.95).toInt()]).put("min",times.first()).put("max",times.last()))
                    File(dir,"measurements.json").writeText(report.toString(2));println(report.toString(2))
                    if("--clip" in args) {
                        val clip=File(dir,"clip").also { it.mkdirs() }
                        demo.bundle.scene.camera.at.set(5f,4f,6f)
                        demo.moveObject(1,Matrix4f().translation(-1.3f,0.75f,0f))
                        light.samples=16
                        repeat(120) { frame ->
                            demo.animate(frame/30.0)
                            save(clip,"%04d".format(frame),read(),light.width,light.height)
                        }
                    }
                    if("--keep" in args) {
                        var frame=0
                        while(!window.shouldClose()) { demo.animate(frame++/60.0);pump(1) }
                    }
                }
            }
            success=true;println("DIRECT LIGHTING CHECK DONE")
        } finally { renderer.cleanup();window.cleanup() }
        exitProcess(if(success)0 else 1)
    }
    private fun checkChanged(a: FloatArray,b: FloatArray,label: String) {
        val changed=(a.indices step 4).count { i -> (0..2).any { abs(a[i+it]-b[i+it])>0.01f } }
        check(changed>64) { "$label changed only $changed pixels; mean difference=${error(a,b)}" }
    }
    private fun error(a: FloatArray,b: FloatArray): Double = a.indices.filter { it%4!=3 }.sumOf { abs(a[it]-b[it]).toDouble() }/(a.size/4*3)
    private fun save(dir: File,name: String,data: FloatArray,width: Int,height: Int) {
        val image=BufferedImage(width,height,BufferedImage.TYPE_INT_RGB)
        for(y in 0 until height)for(x in 0 until width) {
            fun code(c: Int): Int { val v=data[(y*width+x)*4+c].toDouble().coerceAtLeast(0.0);return (255*(v/(1+v)).pow(1/2.2)).roundToInt().coerceIn(0,255) }
            image.setRGB(x,y,(code(0) shl 16) or (code(1) shl 8) or code(2))
        }
        ImageIO.write(image,"png",File(dir,"$name.png"))
    }
}
