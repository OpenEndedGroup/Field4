package afield.media.reconstruction

import afield.document.graph.Box
import afield.graphics.render.Mesh
import afield.graphics.render.Module
import afield.graphics.render.parameters
import afield.graphics.render.pointer
import afield.graphics.shader.fromViews
import afield.graphics.texture.TextureSampler
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.Target
import afield.graphics.vulkan.device.CommandBuffer
import afield.graphics.vulkan.targets.RenderToTexture
import afield.graphics.vulkan.util.Options
import afield.graphics.vulkan.util.Window
import org.json.JSONObject
import org.lwjgl.vulkan.VK13.*
import java.io.File
import kotlin.math.abs
import kotlin.system.exitProcess

/** Compare real NGX history fed pixel motion with the same motion encoded as Field UV velocity.
 * An untagged UV control must disagree, so equality cannot pass by ignoring motion on both paths.
 * Also exercises rejection of a source independently registered after view construction.
 * Run explicitly through `run-jvm-main`; this requires the optional Linux/NVIDIA NGX adapter. */
object ReconstructionMotionGpuSmoke {
    @JvmStatic fun main(args: Array<String>) {
        Options.enableVR=false;Options.enableHDR=false;Options.enableRayTracing=false
        val folder=File(args.firstOrNull() ?: "build/reconstruction/motion-convention").also { it.mkdirs() }
        val window=Window("Field reconstruction motion conventions")
        val renderer=Renderer(window)
        val box=Box("reconstruction-motion-check")
        try { Box.withBoxContextNow(box) {
            val size=ReconstructionSize(640,360)
            ReconstructionDemo(renderer,ReconstructionMode.DLAA,size).use { demo ->
                demo.animate=false;demo.cameraMotion=false
                val converted=object : RenderToTexture(renderer,{
                    width=size.width;height=size.height;useDepth=false;format=VK_FORMAT_R32G32_SFLOAT
                    colorTransferSrc=true;debugName="current-minus-previous UV motion"
                }) {
                    var recordings=0
                    override fun render(command: CommandBuffer,index: Int) { recordings++;super.render(command,index) }
                }
                val sampler=TextureSampler(renderer,1,false,VK_FILTER_NEAREST)
                val module=Module(parameters(renderer) {
                    converted.apply();depthTest=false;depthWrite=false;blend=false
                    vertex="""
                        #version 460
                        void main() {
                            vec2 p=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);
                            gl_Position=vec4(p*2.-1.,0.,1.);
                        }
                    """.trimIndent()
                    fragment="""
                        #version 460
                        layout(binding=0) uniform sampler2D pixelMotion;
                        layout(location=0) out vec2 motion;
                        void main() {
                            motion=-texelFetch(pixelMotion,ivec2(gl_FragCoord.xy),0).xy
                                /vec2(textureSize(pixelMotion,0));
                        }
                    """.trimIndent()
                    textureResolver={ request -> fromViews(listOf(demo.motion),request.name,sampler) }
                })
                check(module.valid) { "Motion conversion shader did not compile" }
                Mesh(renderer,module.pointer()).also { mesh ->
                    mesh.resize(3,0,module.pointer());module.meshes["fullscreen"]=mesh
                }
                converted.modules["convert"]=module.pointer()
                DlssReconstruction(renderer,size,size,ReconstructionMode.DLAA).use { tagged ->
                    DlssReconstruction(renderer,size,size,ReconstructionMode.DLAA).use { untagged ->
                        renderer.renderList.preTargets["accidental-second-source"]=converted
                        val rejected=runCatching { tagged.validateSourceScheduling(converted) }.exceptionOrNull()
                        check(rejected is IllegalStateException && rejected.message.orEmpty().contains("preTargets")) {
                            "An independently scheduled source was not rejected: $rejected"
                        }
                        renderer.renderList.preTargets.remove("accidental-second-source")
                        var frame=0L
                        renderer.renderList.preTargets["compare-motion"]=Target { command,index ->
                            val observation=ReconstructionFrame(frame,ReconstructionJitter.halton(frame,size,size))
                            tagged.evaluate(command,index,converted,
                                ReconstructionInputs(demo.color,demo.depth,converted,
                                    motionCurrentMinusPrevious=true,motionInPixels=false),observation)
                            untagged.evaluate(command,ReconstructionInputs(demo.color,demo.depth,converted),observation)
                            frame++
                        }
                        try {
                            repeat(48) { i ->
                                demo.horizontalShiftPixels=i*4f
                                window.pollEvents();renderer.render()
                            }
                            check(converted.recordings==frame.toInt()) { "Source rendered more than once per evaluation" }
                            val reference=ReconstructionCapture.read(renderer,demo.output)
                            val correct=ReconstructionCapture.read(renderer,tagged.output)
                            val wrong=ReconstructionCapture.read(renderer,untagged.output)
                            check(listOf(reference,correct,wrong).all { pixels -> pixels.all { it.isFinite() } && pixels.max()>.25f })
                            fun error(pixels: FloatArray): Double = reference.indices.filter { it%4!=3 }
                                .map { abs(reference[it]-pixels[it]).toDouble() }.average()
                            val correctError=error(correct);val wrongError=error(wrong)
                            val report=JSONObject().put("taggedUvMeanAbsoluteError",correctError)
                                .put("untaggedUvMeanAbsoluteError",wrongError).put("frames",frame)
                                .put("sourceRecordings",converted.recordings).put("duplicateSourceRejected",true)
                                .put("sdkRevision",NgxSupport.SDK_REVISION)
                            File(folder,"measurements.json").writeText(report.toString(2))
                            for((name,pixels) in listOf("pixel-reference" to reference,"tagged-uv" to correct,"untagged-uv" to wrong))
                                ReconstructionCapture.save(File(folder,"$name.png"),pixels,size.width,size.height)
                            check(correctError<.002 && correctError<wrongError*.25) {
                                "Tagged UV motion did not reproduce pixel-motion history: $report"
                            }
                            println(report.toString(2))
                        } finally { renderer.renderList.preTargets.remove("compare-motion") }
                    }
                }
                renderer.cleanup+=Runnable {
                    module.cleanup();sampler.cleanup()
                    converted.imageViewArray.forEach { it.imageView.cleanup() }
                    converted.colorImageRefs.forEach { it?.cleanup() }
                }
            }
        } } finally { renderer.cleanup();window.cleanup() }
        println("RECONSTRUCTION MOTION CHECK DONE")
        exitProcess(0)
    }
}
