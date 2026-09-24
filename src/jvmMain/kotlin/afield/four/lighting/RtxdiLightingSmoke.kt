package afield.four.lighting

import afield.Box
import afield.four.device.lightingCapabilityReport
import afield.four.Renderer
import afield.four.util.Options
import afield.four.util.Window
import afield.stdlib.linalg.Matrix4f
import afield.stdlib.linalg.Vector3f
import org.json.JSONObject
import java.io.File
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.math.*
import kotlin.system.exitProcess

/** Optional GPU acceptance check: build the pinned shaders before run-jvm-main. */
object RtxdiLightingSmoke {
    @JvmStatic fun main(args: Array<String>) {
        val program=RtxdiProgram.load()
        Options.enableVR=false;Options.doVulkanSDK()
        val dir=File("build/lighting-experiment/rtxdi-gpu").also { it.mkdirs() }
        val window=Window("Field · RTXDI laboratory");val renderer=Renderer(window)
        val box=Box("rtxdi-check");box.setPropertyRaw("renderer",renderer,doResync=false);box.setPropertyRaw("window",window,doResync=false)
        try {
            Box.withBoxContextNow(box) {
                DirectLightingDemo(renderer,256,192).use { demo ->
                    val light=demo.lighting
                    val settings=RtxdiSettings({ demo.bundle.scene.camera.modelView() },{ demo.bundle.scene.projection.evaluateUniform() })
                    fun emitters(n: Int)=List(n) { i ->
                        val a=i*2*PI/n
                        IdentifiedAreaLight(100+i,AreaEmitter(Vector3f((cos(a)*3).toFloat(),4.5f,(sin(a)*2).toFloat()),
                            Vector3f(0.6f,0f,0f),Vector3f(0f,0f,0.6f),Vector3f(150f/n,100f/n,70f/n))) }
                    val original=emitters(16);settings.commitLights(original)
                    light.enableRtxdi(program,settings).getOrThrow();light.samples=4
                    fun pump(n: Int=1) { repeat(n) { window.pollEvents();renderer.render() } }
                    fun read(): FloatArray { pump();return light.readLinear().also { data ->
                        check(data.all { it.isFinite() }) { "Non-finite RTXDI output" }
                        check(data.indices.filter { it%4!=3 }.all { data[it]>=0 && data[it]<10000 }) { "Negative or explosive lighting" }
                    } }
                    fun mean(data: FloatArray): Double = (data.indices step 4).filter { data[it+3]>=0 }.map { i ->
                        0.2126*data[i]+0.7152*data[i+1]+0.0722*data[i+2] }.average()
                    fun variance(data: List<Double>)=data.sumOf { (it-data.average()).pow(2) }/(data.size-1)
                    fun ensemble(mode: DirectLighting.Mode,temporal: Boolean,spatial: Int,budget: Int): List<Double> {
                        light.mode=mode;settings.temporal=temporal;settings.spatialSamples=spatial;light.samples=budget
                        return List(16) { trial -> settings.resetHistory("independent ensemble $trial");pump(if(temporal)24 else 1);mean(read()) }
                    }
                    val reference=ensemble(DirectLighting.Mode.RTXDI_REFERENCE,false,0,256)
                    save(dir,"reference",read(),light.width,light.height)
                    val report=JSONObject().put("gpu",renderer.device.lightingCapabilityReport()).put("program",program.manifest)
                        .put("width",light.width).put("height",light.height).put("lights",original.size).put("workingBytes",light.workingBytes)
                    val ensembles=JSONObject().put("reference",JSONObject().put("mean",reference.average()).put("variance",variance(reference)))
                    for((label,t,s) in listOf(Triple("initial",false,0),Triple("temporal",true,0),Triple("spatial",false,4),Triple("full",true,4))) {
                        val values=ensemble(DirectLighting.Mode.RTXDI,t,s,4)
                        val se=sqrt(variance(values)/values.size+variance(reference)/reference.size)
                        val delta=abs(values.average()-reference.average())
                        println("RTXDI $label mean=${values.average()} reference=${reference.average()} SE=$se delta=$delta")
                        ensembles.put(label,JSONObject().put("mean",values.average()).put("variance",variance(values)).put("standardError",se).put("difference",delta))
                        // Independent scene-mean ensembles; 6 SE plus 0.1% covers FP/UV quantization.
                        check(delta<=6*se+0.001*reference.average()) { "$label estimator differs from reference: delta=$delta SE=$se" }
                        save(dir,label,read(),light.width,light.height)
                    }
                    report.put("ensembles",ensembles)
                    light.view=DirectLighting.View.HISTORY_REJECTION
                    val history=read();save(dir,"history",history,light.width,light.height)
                    check(mean(history)<0.5) { "Static scene rejects most history: ${mean(history)}" }
                    demo.bundle.scene.camera.at.x+=0.02f
                    val movingHistory=read();check(mean(movingHistory)<0.98) { "Smooth camera has no valid reprojection" }
                    demo.bundle.scene.camera.at.x-=3f
                    read();check(!light.rtxdiHistoryValid) { "Camera cut retained history" }
                    demo.bundle.scene.camera.at.set(5f,4f,6f);settings.resetHistory("restore camera");pump(24)
                    light.view=DirectLighting.View.RADIANCE
                    // Stable IDs must survive ordering edits without global history invalidation.
                    val resets=light.rtxdiResetCount
                    settings.commitLights(original.reversed());read()
                    check(light.rtxdiResetCount==resets && light.rtxdiHistoryValid)
                    settings.commitLights(original.drop(8));pump(16)
                    light.view=DirectLighting.View.LIGHT_ID
                    val ids=read()
                    val allowed=(108..115).map { id -> floatArrayOf((id*71%251)/250f,(id*137%251)/250f,(id*197%251)/250f) }+listOf(floatArrayOf(0f,0f,0f))
                    check((ids.indices step 4).filter { ids[it+3]>=0 }.all { offset -> allowed.any { rgb -> (0..2).all { abs(ids[offset+it]-rgb[it])<1e-6f } } }) { "Deleted light ID reused" }
                    light.view=DirectLighting.View.RADIANCE
                    settings.commitLights(emptyList());val dark=read()
                    check(mean(dark)<1e-7) { "Deleted lights left stale illumination" }
                    settings.commitLights(original)
                    val before=demo.visibility.blasBuilds
                    demo.moveObject(1,Matrix4f().translation(-2.2f,0.75f,1f));read()
                    check(!light.rtxdiHistoryValid && demo.visibility.blasBuilds==before)
                    demo.moveObject(1,Matrix4f().translation(-1.3f,0.75f,0f));pump(16)
                    for(view in listOf(DirectLighting.View.LIGHT_ID,DirectLighting.View.RESERVOIR_M,DirectLighting.View.RESERVOIR_WEIGHT,DirectLighting.View.MOTION)) {
                        light.view=view;save(dir,view.name.lowercase(),read(),light.width,light.height)
                    }
                    light.view=DirectLighting.View.RADIANCE
                    light.mode=DirectLighting.Mode.SOURCE;val source=read()
                    light.mode=DirectLighting.Mode.RTXDI;light.effectWeight=0f;val bypass=read()
                    check(source.indices.filter { it%4!=3 }.all { abs(source[it]-bypass[it])<1e-6 }) { "Bypass changed source" }
                    light.effectWeight=1f;light.reloadRtxdi(program).getOrThrow();read();check(!light.rtxdiHistoryValid)
                    val sweeps=mutableListOf<JSONObject>()
                    for(n in listOf(1,16,128))for(budget in listOf(1,8)) {
                        settings.commitLights(emitters(n));light.samples=budget;pump(24)
                        light.clearRtxdiTimings();pump(96)
                        val timing=JSONObject()
                        light.rtxdiTimings.forEach { (stage,all) -> val values=all.sorted()
                            if(values.isNotEmpty())timing.put(stage,JSONObject().put("count",values.size).put("p50",values[values.size/2])
                                .put("p95",values[(values.size*0.95).toInt()]).put("min",values.first()).put("max",values.last())) }
                        sweeps+=JSONObject().put("lights",n).put("candidates",budget).put("gpuMs",timing)
                    }
                    report.put("sweeps",sweeps).put("historyRejectionStatic",mean(history)).put("historyRejectionMovingCamera",mean(movingHistory))
                        .put("historyResets",light.rtxdiResetCount)
                    File(dir,"measurements.json").writeText(report.toString(2))
                    if("--clip" in args) {
                        val clip=File(dir,"clip").also { it.mkdirs() };light.samples=4
                        repeat(120) { frame ->
                            settings.commitLights(emitters(16).map { l -> l.copy(emitter=l.emitter.copy(center=Vector3f(l.emitter.center).add(sin(frame/20.0).toFloat(),0f,0f))) })
                            save(clip,"%04d".format(frame),read(),light.width,light.height)
                        }
                    }
                    // Pathological emitters and black materials must remain finite and dark when appropriate.
                    settings.commitLights(listOf(original.first().copy(emitter=original.first().emitter.copy(
                        edgeU=Vector3f(0.006f,0f,0f),edgeV=Vector3f(0f,0f,0.006f),radiance=Vector3f(50000f)))))
                    pump(8);read()
                    demo.meshes.forEach { it.gBuffer.mesh.push["baseColor"]=afield.stdlib.linalg.Vector4f(0f,0f,0f,1f) }
                    settings.resetHistory("black materials");check(mean(read())<1e-7)
                    println(report.toString(2))
                }
            }
            // Recreate at a non-tiled size; old GPU allocations retire after their last use.
            Box.withBoxContextNow(box) {
                DirectLightingDemo(renderer,173,119).use { resized ->
                    val settings=RtxdiSettings({ resized.bundle.scene.camera.modelView() },{ resized.bundle.scene.projection.evaluateUniform() })
                    settings.commitLights(listOf(IdentifiedAreaLight(1,resized.lighting.emitter)))
                    resized.lighting.enableRtxdi(program,settings).getOrThrow();resized.lighting.mode=DirectLighting.Mode.RTXDI
                    repeat(16) { window.pollEvents();renderer.render() }
                    check(resized.lighting.readLinear().all { it.isFinite() })
                    org.lwjgl.glfw.GLFW.glfwSetWindowSize(window.windowHandle,800,600)
                    repeat(8) { window.pollEvents();renderer.render() }
                    check(resized.lighting.readLinear().any { it>0.1f })
                }
            }
            println("RTXDI LIGHTING CHECK DONE")
        } finally { renderer.cleanup();window.cleanup() }
        exitProcess(0)
    }
    private fun save(dir: File,name: String,data: FloatArray,width: Int,height: Int) {
        val image=BufferedImage(width,height,BufferedImage.TYPE_INT_RGB)
        for(y in 0 until height)for(x in 0 until width) {
            fun code(c: Int): Int { val v=data[(y*width+x)*4+c].toDouble().coerceAtLeast(0.0);return (255*(v/(1+v)).pow(1/2.2)).roundToInt().coerceIn(0,255) }
            image.setRGB(x,y,(code(0) shl 16) or (code(1) shl 8) or code(2))
        }
        ImageIO.write(image,"png",File(dir,"$name.png"))
    }
}
