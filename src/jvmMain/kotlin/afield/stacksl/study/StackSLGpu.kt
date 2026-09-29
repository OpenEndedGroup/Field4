package afield.stacksl.study

import afield.stdlib.renderAll
import afield.stdlib.StudyFrames
import afield.Box
import afield.Runloop
import afield.four.Renderer
import afield.four.modules.Mesh
import afield.four.modules.Module
import afield.four.modules.ModulePointer
import afield.four.modules.ShaderKind
import afield.four.modules.parameters
import afield.four.modules.pointer
import afield.four.targets.RenderToTexture
import afield.stacksl.cpu.CpuRaster
import afield.stdlib.preprocess
import afield.stdlib.stackAutoShaderSources
import afield.stdlib.vec
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32A32_SFLOAT
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * Where a StackSL study's frames come from.
 *
 * Everything a study measures and lays out works on [CpuRaster]s of the program's raw output, so the
 * renderer underneath is a choice: the GPU when a window is running ([StackSLGpuRasterizer]), the CPU
 * reference when it is not ([CpuStackSLRasterizer]) — a [StudyFrames] of a program's text, square.
 * Rows run top to bottom in both, with `uv.y`
 * upwards — `StackSLCpu.FRAG_UV_ORIGIN_IS_TOP_LEFT` has the story of why that is the same thing on
 * both sides.
 */
interface StackSLRasterizer : StudyFrames<String> {
    /** [program]'s raw output at [size] square, once per time in [seconds], handed to [each] in order. */
    fun render(program: String, size: Int, seconds: List<Double>, each: (index: Int, raster: CpuRaster) -> Unit)

    /** A StackSL study frame is square: [width] must equal [height]. */
    override fun render(input: String, width: Int, height: Int, seconds: List<Double>, each: (index: Int, raster: CpuRaster) -> Unit) {
        require(width == height) { "A StackSL study frame is square; asked for $width x $height" }
        render(input, width, seconds, each)
    }
}

/** [StackSLRasterizer.render], collected. */
fun StackSLRasterizer.renderAll(program: String, size: Int, seconds: List<Double>): List<CpuRaster> = renderAll(program, size, size, seconds)

/** The CPU reference: the same GLSL interpreted pixel by pixel. Exact and everywhere, and slow. */
object CpuStackSLRasterizer : StackSLRasterizer {
    override val description = "CPU reference (afield.stacksl.cpu): exact but slow; launch the window box for the GPU"
    override val fast = false

    override fun render(program: String, size: Int, seconds: List<Double>, each: (index: Int, raster: CpuRaster) -> Unit) {
        seconds.forEachIndexed { i, t -> each(i, StackSLStudyShader.renderRaw(program, size, t)) }
    }
}

/**
 * A study's frames from the GPU: the program in a real `afield.four` fragment shader, drawn into an
 * offscreen float target on a running renderer and read back exactly.
 *
 * The shader is [StackSLStudyShader.RAW_FRAGMENT] expanded through Field's own `stack.auto`
 * preprocessing — the path a live box uses — with time in the same push constant, into an
 * `R32G32B32A32_SFLOAT` target, so what comes back is the program's unclamped output, NaN and all,
 * and every measurement means what it means on the CPU. Only transcendentals and hashes can differ in
 * the last bits, which is why the CPU reference stays the thing unit tests assert against.
 *
 * ### How a frame is captured
 *
 * The module is built synchronously on Field's main thread (a `Module` compiles and makes its pipeline
 * in its constructor, so a failure is known at once rather than after a silent blank frame). For each
 * time, the push-constant value is set, one renderer frame is allowed to finish — every frame that
 * starts after it records its draw with the new value — and a readback is queued on the target, which
 * records its copy right after that target's draw. So a capture costs about two frames and never
 * depends on guessing how long a compile takes.
 *
 * ### When it steps aside
 *
 * With no running renderer, a closed one, a window that has stopped drawing (a hidden window submits no
 * frames), or a shader the GPU rejects, it renders on [fallback] and says why in [description]. It
 * never waits on the main thread, because that is the thread that draws.
 *
 * @param rendererOf the renderer to use, looked up on every call — usually the window box's `renderer`
 * @param onMain runs work on the thread that owns the renderer's targets; Field's main thread by default
 */
class StackSLGpuRasterizer(
    private val rendererOf: () -> Renderer?,
    private val onMain: (() -> Unit) -> Unit = ::onRunloopMain,
    private val fallback: StackSLRasterizer = CpuStackSLRasterizer,
    private val frameTimeoutMs: Long = 4000
) : StackSLRasterizer {

    @Volatile override var description: String = "GPU (no frames rendered yet)"
        private set

    override val fast: Boolean get() = rendererOf()?.isClosed == false

    private val lock = Any()
    private val targets = LinkedHashMap<Int, RenderToTexture>()
    // The program each target's module was built from, so asking again for the same program — a
    // viewer refresh, several calls about one revision — reuses the pipeline instead of rebuilding it.
    private val builtFrom = HashMap<Int, String>()
    private var targetsFor: Renderer? = null
    private val templateBox = Box("stacksl-study-gpu")
    @Volatile private var time = 0f

    private class Unavailable(message: String) : RuntimeException(message)

    companion object {
        private const val MODULE = "stacksl-study-module"
        private const val MESH = "fullscreen"
        private const val TARGET = "stacksl-study-target"
        private const val MAX_TARGETS = 4

        private fun onRunloopMain(block: () -> Unit) {
            if (Runloop.isMainThread) block()
            else runBlocking { withContext(Runloop.runLoop.main) { block() } }
        }
    }

    override fun render(program: String, size: Int, seconds: List<Double>, each: (index: Int, raster: CpuRaster) -> Unit) {
        val renderer = rendererOf()
        val reason = when {
            renderer == null -> "no renderer: launch the window box"
            renderer.isClosed -> "the window's renderer is closed"
            Runloop.isMainThread -> "called on the main thread, which is the one that draws"
            else -> null
        }
        if (reason != null) {
            description = "CPU reference ($reason)"
            return fallback.render(program, size, seconds, each)
        }
        var done = 0
        try {
            synchronized(lock) {
                gpuRender(renderer!!, program, size, seconds) { i, raster -> each(i, raster); done = i + 1 }
            }
            description = "GPU (${renderer!!.physicalDevice.deviceName})"
        } catch (e: Unavailable) {
            description = "CPU reference (GPU: ${e.message})"
            fallback.render(program, size, seconds.drop(done)) { i, raster -> each(done + i, raster) }
        }
    }

    private fun gpuRender(renderer: Renderer, program: String, size: Int, seconds: List<Double>, each: (Int, CpuRaster) -> Unit) {
        var target: RenderToTexture? = null
        var failure: String? = null
        onMain {
            try {
                val t = targetFor(renderer, size)
                if (builtFrom[size] == program && t.modules[MODULE]?.current?.valid == true) {
                    renderer.renderList.preTargets[TARGET] = t
                    target = t
                    return@onMain
                }
                templateBox.stackAutoShaderSources["program"] = program
                val fragment = preprocess(templateBox, StackSLStudyShader.RAW_FRAGMENT, ShaderKind.FRAGMENT_SHADER)
                var error: String? = null
                val built = Module(parameters(renderer) {
                    name = MODULE
                    vertex = StackSLStudyShader.VERTEX
                    this.fragment = fragment
                    blend = false
                    depthTest = false
                    t.apply()
                    errorHandler = { message, _ -> error = message }
                }).pointer()
                if (!built.current.valid) throw Unavailable("the GPU shader did not build: ${error ?: "unknown error"}")
                val mesh = Mesh(renderer, built)
                mesh.resize(3, 0, built)
                built.current.meshes[MESH] = mesh
                mesh.push.bind("params") { vec(time, 0f, 0f, 0f) }
                val previous = t.modules[MODULE]
                t += MODULE to built
                // A command buffer in flight may still use the old pipeline: retire it through the
                // renderer's deferred queue, which runs once those frames have completed.
                previous?.takeIf { it !== built }?.let { old -> renderer.cleanup.add(Runnable { dispose(old) }) }
                renderer.renderList.preTargets[TARGET] = t
                builtFrom[size] = program
                target = t
            } catch (e: Unavailable) {
                failure = e.message
            } catch (e: Exception) {
                failure = "the GPU shader did not build: ${e.message}"
            }
        }
        failure?.let { throw Unavailable(it) }
        val t = target!!
        try {
            seconds.forEachIndexed { i, s ->
                time = s.toFloat()
                awaitFrame(renderer)
                val bytes = try {
                    t.readPixelsAsync().get(frameTimeoutMs, TimeUnit.MILLISECONDS)
                } catch (e: java.util.concurrent.TimeoutException) {
                    throw Unavailable("the window stopped drawing (hidden or minimised?)")
                }
                each(i, toRaster(bytes, size))
            }
        } finally {
            // The module stays built (and idle) for the next request; only the drawing stops.
            onMain { renderer.renderList.preTargets.remove(TARGET) }
        }
    }

    private fun awaitFrame(renderer: Renderer) {
        val start = renderer.frameCounter
        val deadline = System.nanoTime() + frameTimeoutMs * 1_000_000
        while (renderer.frameCounter <= start) {
            if (renderer.isClosed) throw Unavailable("the window's renderer closed")
            if (System.nanoTime() > deadline) throw Unavailable("the window stopped drawing (hidden or minimised?)")
            Thread.sleep(1)
        }
    }

    private fun targetFor(renderer: Renderer, size: Int): RenderToTexture {
        if (targetsFor !== renderer) {
            targets.clear() // the old renderer's targets died with it
            builtFrom.clear()
            targetsFor = renderer
        }
        targets[size]?.let { return it }
        if (targets.size >= MAX_TARGETS) {
            val oldest = targets.keys.first()
            targets.remove(oldest)?.let { old -> renderer.cleanup.add(Runnable { disposeTarget(old) }) }
            builtFrom.remove(oldest)
        }
        val created = RenderToTexture(renderer) {
            width = size
            height = size
            format = VK_FORMAT_R32G32B32A32_SFLOAT
            useDepth = false
            colorSampled = true
            colorTransferSrc = true
            clearColor = vec(0f, 0f, 0f, 0f)
            debugName = "stacksl-study-$size"
        }
        targets[size] = created
        return created
    }

    private fun toRaster(bytes: ByteArray, size: Int): CpuRaster {
        val floats = FloatArray(size * size * 4)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        require(buffer.remaining() >= floats.size) { "readback held ${buffer.remaining()} floats, expected ${floats.size}" }
        buffer.get(floats)
        return CpuRaster(size, size, floats)
    }

    private fun dispose(module: ModulePointer) {
        runCatching {
            module.current.meshes.values.toSet().forEach { mesh ->
                (mesh.v.values + mesh.vn.values).toSet().forEach { it.cleanup() }
                mesh.t?.cleanup()
            }
            module.current.meshes.clear()
            module.current.cleanup()
        }
    }

    private fun disposeTarget(rtt: RenderToTexture) {
        runCatching {
            rtt.modules.values.forEach(::dispose)
            rtt.modules.clear()
            rtt.defaultSampler?.cleanup()
            rtt.imageViewArray.map { it.imageView }.distinctBy { it.vkImageView }.forEach { it.cleanup() }
            rtt.colorImageRefs.filterNotNull().distinctBy { it.vkImage }.forEach { it.cleanup() }
            rtt.commandBuffers.forEach { it.cleanup() }
            rtt.fences.forEach { it.cleanup() }
        }
    }
}
