package afield.four.lighting

import afield.Box
import afield.four.Renderer
import afield.four.util.Options
import afield.four.util.Window
import afield.stdlib.linalg.Vector3f
import org.json.JSONObject
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.*
import kotlin.system.exitProcess

/** Full-resolution, linear-light quality checks and actual GPU documentation captures.
 * Separate from the small estimator acceptance suite so its statistical tests stay inexpensive.
 */
object DirectLightingQualitySmoke {
    @JvmStatic fun main(args: Array<String>) {
        Options.enableVR=false; Options.enableHDR=false; Options.doVulkanSDK()
        val directory=File("build/lighting-experiment/quality").also { it.mkdirs() }
        val window=Window("Field · direct lighting")
        val renderer=Renderer(window)
        val box=Box("lighting-quality")
        box.setPropertyRaw("renderer",renderer,doResync=false)
        box.setPropertyRaw("window",window,doResync=false)
        try {
            Box.withBoxContextNow(box) {
                DirectLightingDemo(renderer,1280,720).use { demo ->
                    val light=demo.lighting
                    fun pump(frames: Int=1) { repeat(frames) { window.pollEvents();renderer.render() } }
                    fun save(name: String,pixels: FloatArray) = save(directory,name,pixels,light.width,light.height)
                    light.samples=64
                    light.mode=DirectLighting.Mode.UNSHADOWED;pump();save("unshadowed",light.readLinear())
                    light.mode=DirectLighting.Mode.RAY_QUERY;pump();save("shadow",light.readLinear())
                    demo.animate(2.0);pump();save("moved-light",light.readLinear())
                    for(view in listOf(DirectLighting.View.NORMALS,DirectLighting.View.VISIBILITY)) {
                        light.view=view;pump();save(view.name.lowercase(),light.readLinear())
                    }
                    light.view=DirectLighting.View.RADIANCE
                    val settings=RtxdiSettings({ demo.bundle.scene.camera.modelView() },{ demo.bundle.scene.projection.evaluateUniform() })
                    fun emitters(time: Double=0.0)=List(16) { i ->
                        val a=i*2.0*PI/16+time
                        IdentifiedAreaLight(100+i,AreaEmitter(Vector3f((cos(a)*3+sin(time)*1.5).toFloat(),4.5f,(sin(a)*2).toFloat()),
                            Vector3f(0.6f,0f,0f),Vector3f(0f,0f,0.6f),Vector3f(180f/16)))
                    }
                    settings.commitLights(emitters())
                    light.enableRtxdi(RtxdiProgram.load(),settings).getOrThrow()
                    light.mode=DirectLighting.Mode.RTXDI_REFERENCE;light.samples=256
                    val reference=FloatArray(light.width*light.height*4)
                    repeat(16) {
                        pump();val sample=light.readLinear()
                        for(i in reference.indices)reference[i]+=sample[i]/16f
                    }
                    save("rtxdi-reference",reference)
                    fun mse(value: FloatArray): Double {
                        var error=0.0;var count=0
                        for(i in value.indices step 4)if(reference[i+3]>=0)for(c in 0..2) {
                            error+=(value[i+c]-reference[i+c]).toDouble().pow(2);count++
                        }
                        return error/count
                    }
                    fun mean(value: FloatArray): Double {
                        var total=0.0;var count=0
                        for(i in value.indices step 4)if(reference[i+3]>=0)for(c in 0..2) { total+=value[i+c];count++ }
                        return total/count
                    }
                    light.mode=DirectLighting.Mode.RTXDI;light.samples=16
                    settings.temporal=false;settings.spatialSamples=0;pump();save("rtxdi-initial",light.readLinear())
                    settings.temporal=true;settings.spatialSamples=8;settings.denoisePasses=3;pump(32)
                    val raw=light.readLinear();val reconstructed=light.readPresentedLinear()
                    check(reconstructed.all { it.isFinite() })
                    save("rtxdi-full",raw);save("rtxdi-reconstructed",reconstructed)
                    check(mse(reconstructed)<mse(raw)*0.3) { "Reconstruction does not materially reduce pixel error" }
                    check(abs(mean(reconstructed)-mean(reference))/mean(reference)<0.02) { "Reconstruction changes scene energy by over 2%" }
                    val average=FloatArray(reference.size)
                    repeat(64) {
                        pump();val sample=light.readLinear()
                        for(i in average.indices)average[i]+=sample[i]/64f
                    }
                    check(mse(average)<mse(raw)*0.35) { "Raw time average does not converge toward the reference" }
                    val report=JSONObject().put("width",light.width).put("height",light.height)
                        .put("referenceSamples",4096).put("candidates",light.samples).put("spatialNeighbors",8)
                        .put("denoisePasses",3).put("rawMse",mse(raw)).put("reconstructedMse",mse(reconstructed))
                        .put("raw64FrameMeanMse",mse(average)).put("referenceMean",mean(reference))
                        .put("reconstructedMean",mean(reconstructed))
                    // Diagnostics and the raw view bypass reconstruction exactly.
                    for(view in listOf(DirectLighting.View.RAW_RADIANCE,DirectLighting.View.HISTORY_REJECTION,
                        DirectLighting.View.LIGHT_ID,DirectLighting.View.RESERVOIR_M,DirectLighting.View.RESERVOIR_WEIGHT,DirectLighting.View.MOTION)) {
                        light.view=view;pump()
                        check(light.readLinear().contentEquals(light.readPresentedLinear())) { "Filter altered $view" }
                        save("rtxdi-${view.name.lowercase().replace('_','-')}",light.readLinear())
                    }
                    light.view=DirectLighting.View.RADIANCE
                    settings.denoisePasses=0;pump()
                    check(light.readLinear().contentEquals(light.readPresentedLinear()))
                    settings.denoisePasses=3
                    // Filtering must not blur the source term of a fractional effect blend.
                    settings.temporal=false;settings.spatialSamples=0;settings.animateSeed=false
                    light.effectWeight=1f;pump();val full=light.readPresentedLinear()
                    light.effectWeight=0f;pump();val source=light.readPresentedLinear()
                    check(source.contentEquals(light.readLinear()))
                    light.effectWeight=0.35f;pump();val partial=light.readPresentedLinear()
                    check(partial.indices.filter { it%4!=3 }.all { i -> abs(partial[i]-(source[i]*0.65f+full[i]*0.35f))<1e-5 })
                    light.effectWeight=Float.MIN_VALUE;pump()
                    val tiny=light.readPresentedLinear()
                    check(tiny.all { it.isFinite() })
                    check(tiny.indices.filter { it%4!=3 }.all { i -> abs(tiny[i]-source[i])<1e-6 })
                    light.effectWeight=1f;settings.temporal=true;settings.spatialSamples=8;settings.animateSeed=true
                    // No retained image history: the very first frame after deleting lights is dark.
                    settings.commitLights(emptyList());pump()
                    val dark=light.readPresentedLinear()
                    check((dark.indices step 4).filter { dark[it+3]>=0 }.all { i -> (0..2).all { dark[i+it]==0f } })
                    settings.commitLights(emitters());settings.resetHistory("restore scene");pump(32)
                    light.clearRtxdiTimings();pump(96)
                    val timings=JSONObject()
                    light.rtxdiTimings.forEach { (stage,all) ->
                        val values=all.sorted()
                        if(values.isNotEmpty())timings.put(stage,JSONObject().put("count",values.size)
                            .put("p50",values[values.size/2]).put("p95",values[(values.size*0.95).toInt()]))
                    }
                    report.put("gpuMilliseconds",timings).put("workingBytes",light.workingBytes)
                    if("--clip" in args) {
                        File(directory,"clip").mkdirs()
                        repeat(180) { frame ->
                            settings.commitLights(emitters(frame/30.0))
                            pump();save("clip/%04d".format(frame),light.readPresentedLinear())
                        }
                    }
                    File(directory,"measurements.json").writeText(report.toString(2))
                    println(report.toString(2))
                }
            }
            println("DIRECT LIGHTING QUALITY CHECK DONE")
        } finally { renderer.cleanup();window.cleanup() }
        exitProcess(0)
    }

    private fun save(directory: File,name: String,pixels: FloatArray,width: Int,height: Int) {
        val image=BufferedImage(width,height,BufferedImage.TYPE_INT_RGB)
        for(y in 0 until height)for(x in 0 until width) {
            fun channel(c: Int): Int {
                val value=pixels[(y*width+x)*4+c].toDouble().coerceAtLeast(0.0)
                return (255*(value/(1+value)).pow(1/2.2)).roundToInt().coerceIn(0,255)
            }
            image.setRGB(x,y,(channel(0) shl 16) or (channel(1) shl 8) or channel(2))
        }
        ImageIO.write(image,"png",File(directory,"$name.png"))
    }
}
