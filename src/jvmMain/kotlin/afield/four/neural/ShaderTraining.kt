package afield.four.neural

import afield.four.Renderer
import afield.four.device.CommandBuffer
import afield.four.device.Fence
import afield.four.reconstruction.ReconstructionCapture
import afield.four.targets.RenderToTexture
import org.json.JSONObject
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK13.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Random
import java.util.concurrent.CompletableFuture

/** Offline image-space distillation of a real Field render target: normalized (x,y,parameter) -> linear RGB.
 * Source shaders and their existing bindings execute in Vulkan; they are never translated to Python.
 * Geometry, camera, textures and other uniforms must stay fixed unless represented by [capture]'s parameter.
 */
object ShaderTraining {
    fun sourceDigest(source: RenderToTexture): String {
        val digest=MessageDigest.getInstance("SHA-256")
        source.modules.toSortedMap().forEach { (key,pointer) ->
            digest.update(key.toByteArray())
            listOf(pointer.current.vi,pointer.current.fi).forEach { digest.update(it.code.bytecode()!!.duplicate()) }
        }
        return digest.digest().joinToString(""){"%02x".format(it)}
    }

    /** Blocking export, called BETWEEN frames. Writes a new directory only; failures leave no valid manifest.
     * Rows are six little-endian float32 values: normalized x,y,t followed by unclamped linear r,g,b.
     * Validation uses different parameter slices, not merely different pixels from the training images.
     */
    fun capture(renderer: Renderer,source: RenderToTexture,directory: File,
        parameterRange: ClosedFloatingPointRange<Float> = -1f..1f,trainingFrames: Int=48,
        validationFrames: Int=13,pixelsPerFrame: Int=2048,seed: Long=4090,
        fixedState: String="Fixed camera, geometry, textures and all other uniforms",
        setParameter: (Float)->Unit): File {
        check(renderer.currentCommandBuffer==null) { "Capture between frames, outside a render pass" }
        require(parameterRange.start.isFinite() && parameterRange.endInclusive.isFinite() && parameterRange.start<parameterRange.endInclusive)
        require(trainingFrames in 2..512 && validationFrames in 2..128 && pixelsPerFrame in 1..65536)
        require(source.colorImageRefs.size==1 && source.colorFormats.single() in listOf(VK_FORMAT_R32G32B32A32_SFLOAT,VK_FORMAT_R16G16B16A16_SFLOAT)) { "Capture needs one linear RGBA16F/RGBA32F attachment" }
        require(source.modules.isNotEmpty() && source.modules.values.all { it.current.valid }) { "The source target needs a valid module" }
        require(!directory.exists() || directory.listFiles().isNullOrEmpty()) { "Choose a new dataset directory: $directory" }
        directory.mkdirs()
        val hash=sourceDigest(source)
        source.modules.toSortedMap().entries.forEachIndexed { i,(_,p) ->
            File(directory,"source-$i.vert").writeText(p.current.vi.source)
            File(directory,"source-$i.frag").writeText(p.current.fi.source)
        }
        val command=CommandBuffer(renderer.commandPool,true,false);val fence=Fence(renderer.device,false)
        val random=Random(seed);val width=source.width;val height=source.height
        val files=JSONObject()
        try {
            for((split,count) in listOf("train" to trainingFrames,"validation" to validationFrames)) {
                val times=mutableListOf<Float>();val file=File(directory,"$split.f32")
                file.outputStream().buffered().use { output ->
                    repeat(count) { frame ->
                        val fraction=if(split=="train")frame.toFloat()/(count-1) else (frame+.381966f)/count
                        val normalized=fraction*2-1
                        val parameter=parameterRange.start+fraction*(parameterRange.endInclusive-parameterRange.start)
                        times+=parameter;setParameter(parameter)
                        check(sourceDigest(source)==hash) { "Source shader changed during capture" }
                        vkResetCommandBuffer(command.vkCommandBuffer,0);command.beginRecording();source.render(command,0);command.endRecording()
                        MemoryStack.stackPush().use { renderer.graphQueue.submit(it.pointers(command.vkCommandBuffer),null,null,null,fence) }
                        fence.fenceWait();fence.reset()
                        val image=ReconstructionCapture.read(renderer,source)
                        check(image.all { it.isFinite() }) { "Source produced NaN/Inf at parameter $parameter" }
                        check((0 until width*height).all { kotlin.math.abs(image[it*4+3]-1)<.001f }) { "The learned RGB model requires opaque output; handle coverage separately" }
                        val bytes=ByteBuffer.allocate(pixelsPerFrame*24).order(ByteOrder.LITTLE_ENDIAN)
                        repeat(pixelsPerFrame) {
                            val x=random.nextInt(width);val y=random.nextInt(height);val index=(y*width+x)*4
                            bytes.putFloat((x+.5f)/width*2-1).putFloat((y+.5f)/height*2-1).putFloat(normalized)
                            repeat(3){c->bytes.putFloat(image[index+c])}
                        }
                        output.write(bytes.array())
                    }
                }
                files.put(split,JSONObject().put("file",file.name).put("rows",count*pixelsPerFrame).put("parameters",times)
                    .put("sha256",MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it)}))
            }
        } finally {fence.cleanup();command.cleanup()}
        File(directory,"dataset.json").writeText(JSONObject().put("schema",1).put("sourceKind","captured-module")
            .put("sourceShaderDigest",hash).put("fixedState",fixedState).put("resolution",listOf(width,height))
            .put("parameterRange",listOf(parameterRange.start,parameterRange.endInclusive)).put("rowFormat","little-endian float32: x,y,t,r,g,b")
            .put("coordinates","pixel centers; x right, y down; x,y,t normalized to [-1,1]")
            .put("color","linear RGB, unclamped, opaque").put("seed",seed).put("splits",files).toString(2)+"\n")
        return directory
    }

    /** Train in a separate CPU process. This never evaluates a shader or mutates GPU state on that worker. */
    fun trainAsync(dataset: File,modelDirectory: File,steps: Int=12000): ShaderTrainingJob {
        require(File(dataset,"dataset.json").isFile)
        require(steps in 1..200000)
        require(!modelDirectory.exists() || modelDirectory.listFiles().isNullOrEmpty()) { "Use a new model directory so a failed job cannot replace working weights" }
        modelDirectory.mkdirs()
        val log=File(modelDirectory,"training.log")
        val process=ProcessBuilder("uv","run","--project",".","--group","docs","python","tools/lighting/train_shader.py",
            "--dataset",dataset.absolutePath,"--out",modelDirectory.absolutePath,"--steps",steps.toString())
            .redirectErrorStream(true).redirectOutput(log).apply { environment()["OPENBLAS_NUM_THREADS"]="1" }.start()
        return ShaderTrainingJob(process,modelDirectory,log)
    }
}

/** Poll [result] from a Field frame; close cancels the child process, including on scene Stop. */
class ShaderTrainingJob internal constructor(private val process: Process,val modelDirectory: File,val log: File): AutoCloseable {
    val result: CompletableFuture<File> = process.onExit().thenApply {
        check(it.exitValue()==0) { "Training failed (${it.exitValue()}): ${log.readText().takeLast(3000)}" }
        check(File(modelDirectory,"weights.json").isFile)
        modelDirectory
    }
    override fun close() { if(process.isAlive){process.descendants().forEach { it.destroy() };process.destroy()} }
}
