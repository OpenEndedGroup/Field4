package afield.lang.solidsl.study

import afield.graphics.render.Mesh
import afield.graphics.render.Module
import afield.graphics.render.ModulePointer
import afield.graphics.render.parameters
import afield.graphics.render.pointer
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.targets.RenderToTexture
import afield.lang.stacksl.cpu.CpuRaster
import afield.math.linalg.vec
import afield.runtime.lifecycle.Runloop
import afield.study.source.StudyFrames
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32A32_SFLOAT
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * Where a SolidSL study's pictures come from: [SolidFrames] draws a scene from any [SolidView] on the
 * window's GPU, or on the CPU when there is no window, and [SolidLive] keeps the window itself turning
 * the current revision.
 */

/**
 * A SolidSL study's frames: the scene's [SolidStudyShader.fragment] drawn into an offscreen float target
 * on the running renderer above the study box and read back, or [SolidCpuTracer] when there is none.
 *
 * As a [StudyFrames] of the box's source, time is the turntable: a frame at `t` seconds is
 * [SolidView.turntable], so frames at 0, 3, 6 and 9 seconds walk round the solid. [renderViews] takes
 * any views. The GPU path builds one module per scene (a shader the GPU refuses is a failed check,
 * not a CPU fallback: [check] says why) and costs about two renderer frames per view.
 *
 * @param compile the box source's scene, compiled (and cached) — see [SolidStudies.compile]
 */
class SolidFrames(
    private val rendererOf: () -> Renderer?,
    private val compile: (String) -> SolidScene,
    private val frameTimeoutMs: Long = 4000,
) : StudyFrames<String> {

    @Volatile override var description: String = "GPU (no frames drawn yet)"
        private set

    override val fast: Boolean get() = rendererOf()?.isClosed == false

    private class Unavailable(message: String) : RuntimeException(message)

    private val lock = Any()
    private val targets = LinkedHashMap<Pair<Int, Int>, RenderToTexture>()
    private val builtFrom = HashMap<Pair<Int, Int>, String>()
    private var targetsFor: Renderer? = null
    @Volatile private var viewPush = floatArrayOf(0f, 0f, 1.6f, 0f)
    @Volatile private var framePush = floatArrayOf(1f, 1f, 0f, 0f)

    override fun render(input: String, width: Int, height: Int, seconds: List<Double>, each: (index: Int, raster: CpuRaster) -> Unit) =
        renderViews(compile(input), width, height, seconds.map(SolidView::turntable), each)

    /** [scene] from each of [views] at [width] by [height], handed to [each] in order. */
    fun renderViews(scene: SolidScene, width: Int, height: Int, views: List<SolidView>, each: (index: Int, raster: CpuRaster) -> Unit) {
        val renderer = rendererOf()
        val reason = when {
            renderer == null -> "no window: make the study with Make a SolidSL Study, or put it beneath a renderer"
            renderer.isClosed -> "the window's renderer is closed"
            Runloop.isMainThread -> "called on the main thread, which is the one that draws"
            else -> null
        }
        if (reason != null) return cpu(scene, width, height, views, 0, reason, each)
        var done = 0
        try {
            synchronized(lock) { gpu(renderer!!, scene, width, height, views) { i, r -> each(i, r); done = i + 1 } }
            description = "GPU (${renderer!!.physicalDevice.deviceName})"
        } catch (e: Unavailable) {
            cpu(scene, width, height, views, done, "GPU: ${e.message}", each)
        }
    }

    private fun cpu(scene: SolidScene, width: Int, height: Int, views: List<SolidView>, from: Int, reason: String, each: (Int, CpuRaster) -> Unit) {
        description = "CPU reference ($reason)" + if (scene.shade.isNotBlank()) "; the scene's own shade() is not drawn on the CPU, the default shading is" else ""
        views.drop(from).forEachIndexed { i, v -> each(from + i, SolidCpuTracer.render(scene, width, height, v)) }
    }

    /**
     * Whether the GPU builds [scene]'s shader: null when it does or there is no window to ask, else the
     * compiler's message. With no window the GLSL is only parsed.
     */
    fun check(scene: SolidScene): String? {
        val fragment = SolidStudyShader.fragment(scene)
        runCatching { afield.lang.stacksl.cpu.parseGlslShader(fragment) }.exceptionOrNull()?.let { e ->
            if (scene.shade.isNotBlank()) return "the shade() GLSL does not parse: ${e.message}"
        }
        val renderer = rendererOf()?.takeIf { !it.isClosed && !Runloop.isMainThread } ?: return null
        return try {
            synchronized(lock) { build(renderer, 16, 16, fragment) }
            null
        } catch (e: Unavailable) {
            e.message
        }
    }

    private fun build(renderer: Renderer, width: Int, height: Int, fragment: String): RenderToTexture {
        var target: RenderToTexture? = null
        var failure: String? = null
        onMain {
            try {
                val key = width to height
                val t = targetFor(renderer, key)
                if (builtFrom[key] == fragment && t.modules[MODULE]?.current?.valid == true) { target = t; return@onMain }
                var error: String? = null
                val built = Module(parameters(renderer) {
                    name = MODULE
                    vertex = SolidStudyShader.VERTEX
                    this.fragment = fragment
                    blend = false
                    depthTest = false
                    +t
                    errorHandler = { message, _ -> error = message }
                }).pointer()
                if (!built.current.valid) throw Unavailable("the GPU refused the shader: ${error ?: "unknown error"}")
                val mesh = Mesh(renderer, built)
                mesh.resize(3, 0, built)
                built.current.meshes["fullscreen"] = mesh
                mesh.push.bind("view") { vec(viewPush[0], viewPush[1], viewPush[2], viewPush[3]) }
                mesh.push.bind("frame") { vec(framePush[0], framePush[1], framePush[2], framePush[3]) }
                val previous = t.modules[MODULE]
                t += MODULE to built
                previous?.takeIf { it !== built }?.let { old -> renderer.cleanup.add(Runnable { dispose(old) }) }
                builtFrom[key] = fragment
                target = t
            } catch (e: Unavailable) {
                failure = e.message
            } catch (e: Exception) {
                failure = "the GPU refused the shader: ${e.message}"
            }
        }
        failure?.let { throw Unavailable(it) }
        return target!!
    }

    private fun gpu(renderer: Renderer, scene: SolidScene, width: Int, height: Int, views: List<SolidView>, each: (Int, CpuRaster) -> Unit) {
        val t = build(renderer, width, height, SolidStudyShader.fragment(scene))
        onMain { renderer.renderList.preTargets[TARGET] = t }
        try {
            views.forEachIndexed { i, v ->
                val (view, frame) = SolidStudyShader.push(v, width, height)
                viewPush = view; framePush = frame
                awaitFrame(renderer); awaitFrame(renderer)
                val bytes = try {
                    t.readPixelsAsync().get(frameTimeoutMs, TimeUnit.MILLISECONDS)
                } catch (e: java.util.concurrent.TimeoutException) {
                    throw Unavailable("the window stopped drawing (hidden or minimised?)")
                }
                each(i, toRaster(bytes, width, height))
            }
        } finally {
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

    private fun targetFor(renderer: Renderer, key: Pair<Int, Int>): RenderToTexture {
        if (targetsFor !== renderer) { targets.clear(); builtFrom.clear(); targetsFor = renderer }
        targets[key]?.let { return it }
        if (targets.size >= 4) {
            val oldest = targets.keys.first()
            targets.remove(oldest)?.let { old -> renderer.cleanup.add(Runnable { disposeTarget(old) }) }
            builtFrom.remove(oldest)
        }
        return RenderToTexture(renderer) {
            width = key.first
            height = key.second
            format = VK_FORMAT_R32G32B32A32_SFLOAT
            useDepth = false
            colorSampled = true
            colorTransferSrc = true
            clearColor = vec(0f, 0f, 0f, 0f)
            debugName = "solidsl-study-${key.first}x${key.second}"
        }.also { targets[key] = it }
    }

    private fun toRaster(bytes: ByteArray, width: Int, height: Int): CpuRaster {
        val floats = FloatArray(width * height * 4)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        require(buffer.remaining() >= floats.size) { "readback held ${buffer.remaining()} floats, expected ${floats.size}" }
        buffer.get(floats)
        return CpuRaster(width, height, floats)
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

    private companion object {
        const val MODULE = "solidsl-study-module"
        const val TARGET = "solidsl-study-target"
    }
}

/**
 * The window above a SolidSL study box, turning the current revision: [show] puts a scene in it on the
 * [SolidView.turntable] (time since it was shown), [hide] takes it out. A shader the GPU refuses leaves
 * the previous scene drawing.
 */
class SolidLive internal constructor(private val rendererOf: () -> Renderer?, private val id: String) {
    private val name = "solidsl-live-$id"
    private var renderer: Renderer? = null
    private var pointer: ModulePointer? = null
    private var shown: String? = null
    private val started = System.nanoTime()

    /** Put [scene] in the window; null when it is drawing, else why not. */
    fun show(scene: SolidScene): String? = onMain {
        val r = rendererOf()?.takeIf { !it.isClosed } ?: return@onMain "no window above this box"
        if (r !== renderer) { hideNow(); renderer = r }
        val fragment = SolidStudyShader.fragment(scene)
        if (shown == fragment && pointer?.current?.valid == true && r.renderList.modules[name] === pointer) return@onMain null
        var error: String? = null
        val fresh = Module(parameters(r) {
            this.name = this@SolidLive.name
            vertex = SolidStudyShader.VERTEX
            this.fragment = fragment
            blend = false
            depthTest = false
            errorHandler = { message, _ -> error = message }
        }).pointer()
        if (!fresh.current.valid) {
            r.cleanup.add(Runnable { dispose(fresh) })
            return@onMain "the GPU refused the shader: ${error ?: "unknown error"}"
        }
        val mesh = Mesh(r, fresh)
        mesh.resize(3, 0, fresh)
        fresh.current.meshes["fullscreen"] = mesh
        fun now() = SolidStudyShader.push(SolidView.turntable((System.nanoTime() - started) / 1e9 / 3.0),
            r.window.width, r.window.height)
        mesh.push.bind("view") { now().first.let { vec(it[0], it[1], it[2], it[3]) } }
        mesh.push.bind("frame") { now().second.let { vec(it[0], it[1], it[2], it[3]) } }
        val previous = pointer
        r.renderList.modules[name] = fresh
        pointer = fresh
        shown = fragment
        previous?.let { old -> r.cleanup.add(Runnable { dispose(old) }) }
        null
    }

    /** Stop drawing in the window. */
    fun hide() { if (pointer != null) onMain { hideNow() } }

    private fun hideNow() {
        val r = renderer; val old = pointer
        if (r != null && old != null) {
            if (r.renderList.modules[name] === old) r.renderList.modules.remove(name)
            if (!r.isClosed) r.cleanup.add(Runnable { dispose(old) })
        }
        pointer = null; shown = null
    }
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

private fun <T> onMain(block: () -> T): T =
    if (Runloop.isMainThread) block() else runBlocking { withContext(Runloop.runLoop.main) { block() } }
