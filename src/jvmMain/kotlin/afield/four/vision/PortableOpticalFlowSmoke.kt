package afield.four.vision

import afield.Box
import afield.four.Renderer
import afield.four.reconstruction.ReconstructionCapture
import afield.four.util.Options
import afield.four.util.Window
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.system.exitProcess

/*
FIELD_TEST
id: four.vision.portable-optical-flow
requiresDisplay: true
runner: jvm-main
entrypoint: afield.four.vision.PortableOpticalFlowSmoke
tags: [smoke, four, vision, optical-flow, gpu, image]
timeoutSec: 300
expect:
  exitCode: 0
  stdoutContains:
    - "PORTABLE OPTICAL FLOW CHECK PASSED"
*/
/** Holds [PyramidOpticalFlow] to known synthetic motion, through the production consumer [OpticalFlowDemo].
 *
 * Forces [OpticalFlowBackend.PYRAMID], so an NVIDIA machine tests this same shader path rather than its hardware
 * engine. Every error below is an endpoint error (Euclidean, input pixels) over interior flow samples, 16 texels
 * (64 input pixels at grid 4) away from the border, against the analytical displacement the source shader was
 * built from. Before any flow is trusted, the source pair itself is checked: that the images are textured, that
 * they differ, and that the second really is the first moved by the stated vector.
 *
 * Floors are set a little above what this was measured at on an Apple M-series GPU through MoltenVK
 * (see dev_docs/vision/PORTABLE_OPTICAL_FLOW.md); they are regression floors, not accuracy claims for other hardware.
 * Artifacts: build/portable-optical-flow/{translation,wiggle,advection}.png and measurements.json.
 */
object PortableOpticalFlowSmoke {
    private fun percentile(sorted: List<Float>,q: Double)=sorted[((sorted.size-1)*q).toInt()]
    private fun summary(errors: List<Float>): JSONObject { val s=errors.sorted()
        return JSONObject().put("samples",s.size).put("median",percentile(s,.5)).put("p95",percentile(s,.95))
            .put("max",s.last()).put("fractionOver1px",s.count { it>1f }.toDouble()/s.size) }

    @JvmStatic fun main(args: Array<String>) {
        Options.enableVR=false;Options.enableHDR=false;Options.enableRayTracing=false;Options.doVulkanSDK()
        val dir=File("build/portable-optical-flow").also { it.mkdirs() }
        val window=Window("Field portable optical flow");val renderer=Renderer(window);val box=Box("portable-flow-check")
        box.setPropertyRaw("renderer",renderer,doResync=false)
        val report=JSONObject()
        val failures=mutableListOf<String>()
        fun expect(ok: Boolean,message: ()->String) { if(!ok)failures+=message() }
        try { Box.withBoxContextNow(box) {
            report.put("device",renderer.device.physicalDevice.vkPhysicalDeviceProperties.deviceNameString())
            report.put("autoBackend",OpticalFlowBackend.AUTO.resolve(renderer).name)
            OpticalFlowDemo(renderer,backend=OpticalFlowBackend.PYRAMID).use { demo ->
                val flow=demo.flow
                check(flow is PyramidOpticalFlow && flow.backend==OpticalFlowBackend.PYRAMID)
                check(flow.flowWidth==(demo.width+3)/4 && flow.flowHeight==(demo.height+3)/4) { "Output dimensions break the grid contract" }
                report.put("input",listOf(demo.width,demo.height)).put("grid",flow.grid).put("levels",flow.levels)
                    .put("radius",flow.radius).put("iterations",flow.iterations).put("allocatedBytes",flow.allocatedBytes)

                // Premise: the pair is textured, differs, and the second is exactly the first moved by (dx, dy).
                demo.update(reset=true)
                val a=ReconstructionCapture.read(renderer,demo.pair.imageViewArray[0]);val b=ReconstructionCapture.read(renderer,demo.pair.imageViewArray[1])
                fun grey(p: FloatArray,x: Int,y: Int)=((y*demo.width+x)*4).let { .2126f*p[it]+.7152f*p[it+1]+.0722f*p[it+2] }
                var sum=0.0;var sumSq=0.0;var unmoved=0.0;var moved=0.0;var n=0
                for(y in 64 until demo.height-64)for(x in 64 until demo.width-64) {
                    val g=grey(a,x,y);sum+=g;sumSq+=g*g;n++
                    unmoved+=abs(grey(b,x,y)-g)
                    moved+=abs(grey(b,x+demo.dx.toInt(),y+demo.dy.toInt())-g)
                }
                val premise=JSONObject().put("greyStd",sqrt(sumSq/n-(sum/n)*(sum/n))).put("meanAbsDifferenceUnmoved",unmoved/n)
                    .put("meanAbsDifferenceAfterKnownShift",moved/n)
                report.put("premise",premise);println("PREMISE $premise")
                check(premise.getDouble("greyStd")>.05 && unmoved/n>.02) { "Source pair is too flat or too similar to test flow: $premise" }
                check(moved/n<1e-3) { "Second image is not the first moved by (${demo.dx}, ${demo.dy}): $premise" }

                fun errors(time: Float): List<Float> {
                    val field=ReconstructionCapture.read(renderer,flow.output)
                    check(field.all { it.isFinite() }) { "Non-finite flow" }
                    val out=ArrayList<Float>()
                    for(y in 16 until flow.flowHeight-16)for(x in 16 until flow.flowWidth-16) {
                        val i=(y*flow.flowWidth+x)*4
                        val e=demo.expectedDisplacement((x+.5f)*flow.grid,(y+.5f)*flow.grid,time)
                        out+=sqrt((field[i]-e.x)*(field[i]-e.x)+(field[i+1]-e.y)*(field[i+1]-e.y))
                    }
                    return out
                }

                // Known translation (+12, -8), through the demo's frame loop.
                repeat(12){demo.update(reset=it==0);window.pollEvents();renderer.render()}
                val translation=summary(errors(0f));report.put("translation",translation);println("TRANSLATION $translation")
                // Cost: the three leftmost columns see content that entered from outside the first image (dx = 12 px),
                // so no correct match exists there. Their residual should stand clear of the interior's.
                run {
                    val field=ReconstructionCapture.read(renderer,flow.output);val revealed=ArrayList<Float>();val interior=ArrayList<Float>()
                    for(y in 16 until flow.flowHeight-16)for(x in 0 until flow.flowWidth-16){val z=field[(y*flow.flowWidth+x)*4+2]
                        if(x<3)revealed+=z else if(x>=16)interior+=z}
                    val cost=JSONObject().put("units","mean absolute window residual, grey levels 0-255")
                        .put("revealedColumns",summary(revealed)).put("interior",summary(interior))
                    report.put("translationCost",cost);println("COST $cost")
                    expect(percentile(revealed.sorted(),.5)>maxOf(2f,2*percentile(interior.sorted(),.95))) { "Cost does not flag revealed content: $cost" }
                }
                ReconstructionCapture.save(File(dir,"translation.png"),ReconstructionCapture.read(renderer,demo.output),demo.width*2,demo.height*2)
                expect(translation.getDouble("median")<TRANSLATION_MEDIAN && translation.getDouble("p95")<TRANSLATION_P95) { "Translation: $translation" }

                // A displacement beyond one window at full resolution: only the pyramid can recover it.
                demo.dx=40f;demo.dy=-24f;demo.update(reset=true)
                val large=summary(errors(0f));report.put("largeTranslation40x24",large);println("LARGE $large")
                expect(large.getDouble("median")<LARGE_MEDIAN && large.getDouble("p95")<LARGE_P95 &&
                    large.getDouble("fractionOver1px")<LARGE_OUTLIER_FRACTION) { "Large translation: $large" }

                // Other grids, on the same pair, with the class used directly.
                demo.dx=12f;demo.dy=-8f;demo.update(reset=true)
                val grids=JSONObject()
                for(g in listOf(2,8)) PyramidOpticalFlow(renderer,demo.pair.imageViewArray[0],demo.pair.imageViewArray[1],grid=g).use { other ->
                    other.evaluate(true)
                    check(other.flowWidth==(demo.width+g-1)/g && other.flowHeight==(demo.height+g-1)/g)
                    val field=ReconstructionCapture.read(renderer,other.output);val margin=64/g;val e=ArrayList<Float>()
                    for(y in margin until other.flowHeight-margin)for(x in margin until other.flowWidth-margin){val i=(y*other.flowWidth+x)*4
                        e+=sqrt((field[i]-12)*(field[i]-12)+(field[i+1]+8)*(field[i+1]+8))}
                    grids.put("grid$g",summary(e))
                    expect(percentile(e.sorted(),.5)<TRANSLATION_MEDIAN && percentile(e.sorted(),.95)<TRANSLATION_P95) { "Grid $g translation: ${summary(e)}" }
                }
                report.put("translationOtherGrids",grids);println("GRIDS $grids")

                // Timing: 100 warmed pairs.
                repeat(100){demo.update();window.pollEvents();renderer.render()}
                val times=flow.milliseconds.takeLast(100).sorted()
                report.put("pairWallMsIncludingFence",JSONObject().put("count",times.size).put("p50",percentile(times.map{it.toFloat()},.5)).put("p95",percentile(times.map{it.toFloat()},.95)))
                flow.gpuTimings.forEach { (name,values) -> val s=values.takeLast(100).sorted()
                    report.put("gpuMs_"+name,if(s.isEmpty())JSONObject.NULL else JSONObject().put("count",s.size).put("p50",s[s.size/2]).put("p95",s[((s.size-1)*.95).toInt()])) }

                // The page's consecutive-frame wiggle: a local warp, no global offset, at four phases.
                demo.deformation=true;demo.dx=0f;demo.dy=0f;demo.displayRange=3f
                val local=ArrayList<Float>();val zeroField=ArrayList<Float>();val quadrants=IntArray(4)
                for(time in listOf(0f,.4f,.9f,1.5f)) {
                    demo.update(time,reset=true);local+=errors(time)
                    for(y in 16 until flow.flowHeight-16)for(x in 16 until flow.flowWidth-16)
                        zeroField+=demo.expectedDisplacement((x+.5f)*flow.grid,(y+.5f)*flow.grid,time).let { sqrt(it.x*it.x+it.y*it.y) }
                    val field=ReconstructionCapture.read(renderer,flow.output)
                    for(y in 16 until flow.flowHeight-16)for(x in 16 until flow.flowWidth-16){val i=(y*flow.flowWidth+x)*4
                        if(abs(field[i])>.2 && abs(field[i+1])>.2)quadrants[(if(field[i]>0)1 else 0)+(if(field[i+1]>0)2 else 0)]++}
                }
                // What a zero field would score: the premise that the wiggle is motion the floors can tell apart.
                val zero=summary(zeroField);check(zero.getDouble("median")>5*WIGGLE_MEDIAN) { "Wiggle too small to test: $zero" }
                val wiggleCost=ArrayList<Float>();ReconstructionCapture.read(renderer,flow.output).let { f ->
                    for(y in 16 until flow.flowHeight-16)for(x in 16 until flow.flowWidth-16)wiggleCost+=f[(y*flow.flowWidth+x)*4+2] }
                val wiggle=summary(local).put("zeroFieldError",zero).put("phases",4).put("costLastPhase",summary(wiggleCost)).put("pairStepSeconds",demo.pairStep).put("directionQuadrants",quadrants.toList())
                report.put("consecutiveFrameWiggle",wiggle);println("WIGGLE $wiggle")
                expect(wiggle.getDouble("median")<WIGGLE_MEDIAN && wiggle.getDouble("p95")<WIGGLE_P95) { "Wiggle: $wiggle" }
                expect(quadrants.all { it>local.size/20 }) { "Wiggle field did not change direction: ${quadrants.toList()}" }
                repeat(8){i->demo.update(1.5f+i*demo.pairStep);window.pollEvents();renderer.render()}
                ReconstructionCapture.save(File(dir,"wiggle.png"),ReconstructionCapture.read(renderer,demo.output),demo.width*2,demo.height*2)

                // Identical frames: no motion anywhere, borders included.
                demo.pairStep=0f;demo.update(.5f,reset=true)
                val still=ReconstructionCapture.read(renderer,flow.output)
                val stillLengths=(0 until flow.flowWidth*flow.flowHeight).map { sqrt(still[it*4]*still[it*4]+still[it*4+1]*still[it*4+1]) }
                val stationary=summary(stillLengths);report.put("identicalFrames",stationary);println("IDENTICAL $stationary")
                expect(stationary.getDouble("max")<IDENTICAL_MAX) { "Identical frames produced motion: $stationary" }

                // Feedback advection driven by the measured field, as the starting point shows it.
                demo.pairStep=1f/30f
                repeat(if("--clip" in args)120 else 32){i ->demo.update(i*demo.pairStep,reset=i==0);window.pollEvents();renderer.render()
                    if("--clip" in args)ReconstructionCapture.save(File(dir,"clip/%04d.png".format(i)),ReconstructionCapture.read(renderer,demo.output),demo.width*2,demo.height*2)}
                ReconstructionCapture.save(File(dir,"advection.png"),ReconstructionCapture.read(renderer,demo.output),demo.width*2,demo.height*2)
            }
            // A stopped parent renderer must also retire an otherwise still-reachable flow view.
            OpticalFlowDemo(renderer,128,128,name="unclosed-portable-flow-check",backend=OpticalFlowBackend.PYRAMID).update(reset=true)
        } } finally {renderer.cleanup();window.cleanup()}
        report.put("failures",failures)
        File(dir,"measurements.json").writeText(report.toString(2))
        if(failures.isNotEmpty()) { failures.forEach { System.err.println("FAIL $it") };exitProcess(1) }
        println("PORTABLE OPTICAL FLOW CHECK PASSED");exitProcess(0)
    }
    // Regression floors in input pixels. Measured 2026-09-26, Apple M1 Max / MoltenVK, 960 × 540, grid 4, defaults:
    // translation median 6.7e-6 / p95 1.7e-5 (21,424 samples); 40 × 24 median 6.9e-6 / p95 1.6e-5 with 1.0% of
    // samples over 1 px; wiggle median 0.0802 / p95 0.2745 (85,696 samples, 4 phases); identical frames max 0.
    private const val TRANSLATION_MEDIAN=.005f
    private const val TRANSLATION_P95=.01f
    private const val LARGE_MEDIAN=.005f
    private const val LARGE_P95=.01f
    private const val LARGE_OUTLIER_FRACTION=.02
    private const val WIGGLE_MEDIAN=.09f
    private const val WIGGLE_P95=.30f
    private const val IDENTICAL_MAX=.01f
}
