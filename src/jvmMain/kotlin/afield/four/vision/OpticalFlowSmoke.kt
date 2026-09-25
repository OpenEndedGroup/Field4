package afield.four.vision

import afield.Box
import afield.four.Renderer
import afield.four.reconstruction.ReconstructionCapture
import afield.four.util.Options
import afield.four.util.Window
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.system.exitProcess

object OpticalFlowSmoke {
    @JvmStatic fun main(args: Array<String>) {
        Options.enableVR=false;Options.enableHDR=false;Options.enableRayTracing=false;Options.doVulkanSDK()
        val dir=File("build/neural-tools/flow").also { it.mkdirs() }
        val window=Window("Field optical flow");val renderer=Renderer(window);val box=Box("flow-check")
        box.setPropertyRaw("renderer",renderer,doResync=false)
        try { Box.withBoxContextNow(box) {
            OpticalFlowDemo(renderer).use { demo ->
                repeat(12){demo.update(reset=it==0);window.pollEvents();renderer.render()}
                val pixels=ReconstructionCapture.read(renderer,demo.flow.output)
                val errors=mutableListOf<Float>();val costs=mutableListOf<Float>()
                for(y in 16 until demo.flow.flowHeight-16)for(x in 16 until demo.flow.flowWidth-16) {
                    val i=(y*demo.flow.flowWidth+x)*4;errors+=abs(pixels[i]-demo.dx)+abs(pixels[i+1]-demo.dy);costs+=pixels[i+2]
                }
                errors.sort();check(errors[errors.size/2]<1f) { "Flow direction/units wrong: median L1 error ${errors[errors.size/2]}" }
                ReconstructionCapture.save(File(dir,"translation.png"),ReconstructionCapture.read(renderer,demo.output),demo.width*2,demo.height*2)
                val bytes=java.nio.ByteBuffer.allocate(pixels.size*4).order(java.nio.ByteOrder.LITTLE_ENDIAN);bytes.asFloatBuffer().put(pixels);File(dir,"flow.f32").writeBytes(bytes.array())
                val report=JSONObject().put("input",listOf(demo.width,demo.height)).put("grid",demo.flow.grid)
                    .put("expectedDisplacement",listOf(demo.dx,demo.dy)).put("medianL1ErrorPixels",errors[errors.size/2])
                    .put("p95L1ErrorPixels",errors[(errors.size*.95).toInt()]).put("costRange",listOf(costs.min(),costs.max()))
                    .put("allocatedBytes",demo.flow.allocatedBytes)
                repeat(100){demo.update();window.pollEvents();renderer.render()}
                val times=demo.flow.milliseconds.takeLast(100).sorted()
                report.put("pairWallMsIncludingConversionAndFence",JSONObject().put("count",times.size).put("p50",times[50]).put("p95",times[95]))
                report.put("opticalQueueTimestampBits",demo.flow.opticalTimestampBits)
                demo.flow.gpuTimings.forEach { (name,values) -> val sorted=values.takeLast(100).sorted()
                    report.put(name+"GpuMs",if(sorted.isEmpty())JSONObject.NULL else JSONObject().put("count",sorted.size).put("p50",sorted[sorted.size/2]).put("p95",sorted[(sorted.size*.95).toInt()])) }
                demo.deformation=true;demo.dx=0f;demo.dy=0f;demo.displayRange=3f
                val localErrors=mutableListOf<Float>();val constantErrors=mutableListOf<Float>()
                val quadrants=IntArray(4)
                for(time in listOf(0f,.4f,.9f,1.5f)) {
                    demo.update(time,reset=true)
                    val field=ReconstructionCapture.read(renderer,demo.flow.output)
                    check(field.all { it.isFinite() })
                    for(y in 16 until demo.flow.flowHeight-16)for(x in 16 until demo.flow.flowWidth-16) {
                        val i=(y*demo.flow.flowWidth+x)*4
                        val expected=demo.expectedDisplacement((x+.5f)*demo.flow.grid,(y+.5f)*demo.flow.grid,time)
                        val ex=field[i]-expected.x;val ey=field[i+1]-expected.y
                        localErrors+=kotlin.math.sqrt(ex*ex+ey*ey)
                        constantErrors+=kotlin.math.sqrt((12-expected.x)*(12-expected.x)+(-8-expected.y)*(-8-expected.y))
                        if(kotlin.math.abs(field[i])>.2 && kotlin.math.abs(field[i+1])>.2)
                            quadrants[(if(field[i]>0)1 else 0)+(if(field[i+1]>0)2 else 0)]++
                    }
                }
                localErrors.sort();constantErrors.sort()
                val wiggle=JSONObject().put("pairStepSeconds",demo.pairStep).put("samples",localErrors.size)
                    .put("medianEndpointErrorPixels",localErrors[localErrors.size/2]).put("p95EndpointErrorPixels",localErrors[(localErrors.size*.95).toInt()])
                    .put("oldConstantVectorMedianErrorPixels",constantErrors[constantErrors.size/2]).put("directionQuadrants",quadrants.toList())
                report.put("consecutiveFrameWiggle",wiggle);println("WIGGLE METRICS $wiggle")
                check(localErrors[localErrors.size/2]<.5f && localErrors[(localErrors.size*.95).toInt()]<1.5f) { "Wiggle field disagrees with the known local warp: $wiggle" }
                check(quadrants.all { it>localErrors.size/20 }) { "Wiggle field did not change direction" }
                demo.pairStep=0f;demo.update(.5f,reset=true)
                val stationary=ReconstructionCapture.read(renderer,demo.flow.output)
                check(stationary.indices.filter { it%4<2 }.maxOf { abs(stationary[it]) }<.25f) { "Identical frames produced motion" }
                demo.pairStep=1f/30f
                repeat(if("--clip" in args)120 else 32){i ->demo.update(i*demo.pairStep,reset=i==0);window.pollEvents();renderer.render()
                    if("--clip" in args)ReconstructionCapture.save(File(dir,"clip/%04d.png".format(i)),ReconstructionCapture.read(renderer,demo.output),demo.width*2,demo.height*2)}
                ReconstructionCapture.save(File(dir,"advection.png"),ReconstructionCapture.read(renderer,demo.output),demo.width*2,demo.height*2)
                File(dir,"measurements.json").writeText(report.toString(2))
            }
            // A stopped parent renderer must also retire an otherwise still-reachable flow view.
            val unclosed=OpticalFlowDemo(renderer,128,128,name="unclosed-flow-check")
            unclosed.update(reset=true)
        } } finally {renderer.cleanup();window.cleanup()}
        println("OPTICAL FLOW CHECK DONE");exitProcess(0)
    }
}
