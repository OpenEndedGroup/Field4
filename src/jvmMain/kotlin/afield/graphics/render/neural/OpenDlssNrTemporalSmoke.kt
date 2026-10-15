@file:afield.runtime.completion.CompletionCategory("Internal")
package afield.graphics.render.neural

import afield.graphics.vulkan.Renderer
import afield.media.reconstruction.ReconstructionDemo
import afield.media.reconstruction.ReconstructionSize
import afield.graphics.vulkan.util.Options
import afield.graphics.vulkan.util.Window
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.math.abs

/** Real moving raster geometry/camera, supplied per-object motion and depth. Writes a consecutive
 * source / independent-frame NR / temporal NR sequence, plus reset and finite-output assertions. */
internal object OpenDlssNrTemporalSmoke {
    fun main(args: Array<String>) {
        require(args.isNotEmpty());val output=Path.of(args[0]);output.createDirectories()
        Options.enableHDR=false
        val window=Window("Field NR temporal verification");val renderer=Renderer(window)
        try {
            val size=ReconstructionSize(256,144)
            ReconstructionDemo(renderer,mode=null,outputSize=size,attach=false).use {scene ->
                OpenDlssNrPass(renderer,size.width,size.height).use {single ->
                    OpenDlssNrPass(renderer,size.width,size.height).use {temporal ->
                        temporal.controls=OpenDlssNrControls(temporal=true)
                        val inputs=OpenDlssNrInputs(scene.color,OpenDlssNrColorEncoding.LINEAR_LDR,scene.motion,scene.depth,
                            motionCurrentMinusPrevious=false,motionInPixels=true)
                        var meanDifference=0.0
                        for(i in 0 until 48) {
                            val cut=i==24
                            if(cut)scene.time+=4f
                            OpenDlssNrDiagnostics.submit(renderer) {cmd ->
                                scene.render(cmd,0)
                                single.evaluate(cmd,inputs,OpenDlssNrFrame(i.toLong(),reset=cut))
                                temporal.evaluate(cmd,inputs,OpenDlssNrFrame(i.toLong(),reset=cut))
                            }
                            val a=OpenDlssNrDiagnostics.pixels(renderer,single.output)
                            val b=OpenDlssNrDiagnostics.pixels(renderer,temporal.output)
                            check(a.all {it.isFinite()} && b.all {it.isFinite()})
                            if(i==0 || cut)check(a.contentEquals(b)) {"Reset retained neural history at frame $i"}
                            else meanDifference+=a.indices.sumOf {abs(a[it]-b[it]).toDouble()}/a.size
                            val source=OpenDlssNrDiagnostics.pixels(renderer,scene.output)
                            val row=FloatArray(size.width*3*size.height*4)
                            for(y in 0 until size.height)for((j,pixels) in listOf(source,a,b).withIndex()) {
                                pixels.copyInto(row,(y*size.width*3+j*size.width)*4,y*size.width*4,(y+1)*size.width*4)
                            }
                            OpenDlssNrDiagnostics.png(row,size.width*3,size.height,output.resolve("frame-%03d.png".format(i)))
                        }
                        check(meanDifference>1e-5) {"Temporal path did not affect moving-scene output"}
                        check(temporal.resetCount==2) {"Unexpected temporal resets: ${temporal.resetCount}"}
                        val report="PASS NR temporal: 48 moving geometry/camera frames, supplied pixel motion, first/cut equality, finite output, two resets; mean difference=${meanDifference/46}; gpuMilliseconds=${temporal.gpuMilliseconds}\n"
                        output.resolve("temporal.txt").writeText(report);println(report)
                    }
                }
            }
        } finally {renderer.cleanup();window.cleanup()}
    }
}
