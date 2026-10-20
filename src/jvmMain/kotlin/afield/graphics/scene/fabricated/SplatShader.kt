@file:Suppress("MemberVisibilityCanBePrivate")

package afield.graphics.scene.fabricated

import afield.document.context.fromContext
import afield.document.graph.Box
import afield.graphics.render.ComputeMesh
import afield.graphics.render.ComputeModulePointer
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.Target
import afield.graphics.vulkan.buffers.FrameBufferGroup
import afield.graphics.vulkan.buffers.defaultBuffer
import afield.math.linalg.Matrix4f
import afield.math.linalg.Rectanglef
import afield.math.linalg.Vector3f
import afield.ui.output.controlGroup
import afield.ui.output.displayOpen

/**
 * A Gaussian-splat cloud whose splats are rewritten every frame by a few lines of GLSL in a box:
 * the madp splat-shader demo's "insert", on Field's Vulkan splat renderer.
 *
 * The box's compute tab is the insert between two includes:
 *
 * ```glsl
 * #version 450
 * #include "splat/insert"
 *
 * float r = length(pos.xz);
 * pos.y += sin(r * 18.0 - t * 3.5) * 0.045;
 * rgba.rgb = mix(rgba.rgb, vec3(0.08, 0.85, 1.0), 0.5);
 *
 * #include "splat/end"
 * ```
 *
 * and its code hands that module and a cloud to this class:
 *
 * ```kotlin
 * var module = computeModuleHere {}
 * SplatShader(module, ProceduralSplats.torusBraid()) {
 *     autoRotate = 0.32
 * }
 * ```
 *
 * Between the includes, `pos`, `scales`, `rgba` and `quat` are the splat's centre, radii, colour
 * and rotation, yours to change; `index` (which splat) and `t` (seconds since the box ran) are
 * there to read, with `pi` and the helpers `sat`, `rot2`, `hash31` and `noise31`. `splat/end`
 * clamps `rgba` to 0..1 and every scale to at least 1e-4, as the demo does, then builds the
 * splat's covariance.
 *
 * Every frame, the insert runs once per splat on the GPU, writing the cloud the renderer draws;
 * then the renderer sorts *those* splats back to front and draws them. So a splat the insert moves
 * is sorted where it went, which is what keeps a dissolve or a gravity well compositing in the
 * right order. (Edit the splat vertex shader instead, as `gaussianSplatModuleHere` lets you, and
 * the sort still sees the splats where they were uploaded.)
 *
 * The cloud is framed as the demo frames it: centred on its median, scaled so nine splats in ten
 * lie within 1.08 of the origin ([SplatShaderSettings.size] times that), turned about +y at
 * [SplatShaderSettings.autoRotate], and seen from (0, 0.38, 2.65) with a 54° lens.
 *
 * Every SplatShader installs under the same name unless [SplatShaderSettings.name] says otherwise,
 * so launching one replaces the one showing.
 */
class SplatShader(
    /** The compute module holding the insert, usually `computeModuleHere {}`. */
    val module: ComputeModulePointer,
    /** The splats the insert starts from each frame. */
    val splats: SplatSet,
    /** The renderer to draw on; by default the one this box is running in. */
    val renderer: Renderer = fromContext(),
    configure: SplatShaderSettings.() -> Unit = {}
) {
    /** The settings after `configure` ran; the framing, rotation and splat look can change live. */
    val settings = SplatShaderSettings().apply(configure)

    /** The parametric splats, as uploaded: what the insert reads. */
    val source: FrameBufferGroup

    /** The renderer's cloud: what the insert writes, and what is sorted and drawn. */
    val cloud: GaussianSplatRenderer.Cloud

    /** The dispatch in [module] that runs the insert, one invocation per splat. */
    val mesh: ComputeMesh

    /** The [SplatSet.fit] the framing uses. */
    val fit: SplatSet.Fit = splats.fit()

    /** Seconds since this was made: the insert's `t`. Set it to scrub. */
    var t: Double = 0.0

    /** The cloud's current turn about +y, in radians. */
    var angle: Double = 0.0

    /** When false, `t` and the rotation stand still and the insert sees the same `t` each frame. */
    var playing: Boolean = true

    private var lastNanos = 0L

    init {
        require(splats.count > 0) { "a SplatShader needs at least one splat" }
        val count = splats.count
        // Nothing frees a GPU buffer or a cloud once made, so a re-run of the same size takes the
        // last one's back rather than allocating its own.
        val shape = Shape(settings.name, count, settings.sortMode)
        val previous = synchronized(kept) { kept.getOrPut(renderer) { HashMap() }[shape] }
        if (previous != null) {
            source = previous.source
            cloud = previous.cloud
            cloud.beforeSort.clear()
        } else {
            source = defaultBuffer(name = "${settings.name}-source") {
                size = (count * 4).vec4s
                perFrame = false
            }.toBuffer(renderer)
            val (min, max) = splats.bounds()
            cloud = GaussianSplatRenderer.Cloud(
                renderer = renderer,
                camera = afield.graphics.vulkan.util.Camera(renderer),
                table = null,
                // Zeros: opacity 0 hides every splat until the insert has written it.
                packed = GaussianSplatRenderer.PackedSplats(FloatArray(count * SplatSet.STRIDE), count, min, max),
                config = GaussianSplatRenderer.Config(
                    sortMode = settings.sortMode,
                    depthKey = GaussianSplatRenderer.DepthKey.ViewDepth,
                    // the insert can move splats under a still camera
                    sortOnlyWhenViewChanges = false,
                    maxScreenRadius = 256f
                ),
                sourceName = settings.name,
                capacity = count
            )
            synchronized(kept) { kept.getOrPut(renderer) { HashMap() }[shape] = Resources(source, cloud) }
        }
        source.write { put(splats.floats.copyOf(count * SplatSet.STRIDE), padding = false) }

        mesh = ComputeMesh(renderer, module)
        mesh.push.bind("splatsIn") { source }
        mesh.push.bind("splatsOut") { requireNotNull(cloud.splatData.result) }
        mesh.push.bind("splatCount") { count }
        mesh.push.bind("t") { t.toFloat() }
        mesh.x = (count + 63) / 64
        mesh.y = 1
        mesh.z = 1
        module += "${settings.name}-insert" to mesh

        cloud.beforeSort["insert"] = Target { command, _ ->
            tick()
            module.render(command, Rectanglef())
        }
        cloud.bindModel { model() }
        applyLook()
        cloud.install(renderer.renderList, settings.name)
    }

    /** The model matrix this frame: the fit, the size, then the turn about +y. */
    fun model(): Matrix4f {
        val base = if (settings.fit) fit.model(1.08f * settings.size.toFloat())
        else Matrix4f().scale(settings.size.toFloat())
        return Matrix4f().rotateY(angle.toFloat()).mul(base)
    }

    /** Push [settings]' camera and splat look onto the cloud; called once a frame, so edits show. */
    fun applyLook() {
        val camera = cloud.camera
        camera.at = Vector3f(settings.cameraAt)
        camera.target = Vector3f(settings.cameraTarget)
        camera.up = Vector3f(0f, 1f, 0f)
        camera.p.fov = Math.toRadians(settings.fovDegrees)
        camera.p.zNear = 0.01
        camera.p.zFar = 100.0
        cloud.sizeScale = settings.splatSize
        cloud.alphaScale = settings.alphaScale
        cloud.colorGamma = settings.colorGamma
    }

    private fun tick() {
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0.0 else ((now - lastNanos) / 1e9).coerceIn(0.0, 0.25)
        lastNanos = now
        if (playing) {
            t += dt
            // the demo's slider adds speed × 0.01 radians per frame at 60 frames a second
            angle += settings.autoRotate * 0.6 * dt
        }
        applyLook()
    }

    /** Take the cloud off the renderer. Launching another SplatShader with the same name does this too. */
    fun remove() {
        cloud.remove(renderer.renderList, settings.name)
    }

    private data class Shape(val name: String, val count: Int, val sortMode: GaussianSplatRenderer.SortMode)

    private class Resources(val source: FrameBufferGroup, val cloud: GaussianSplatRenderer.Cloud)

    companion object {
        /** The buffers and cloud each shape was last built with, per renderer. */
        private val kept = java.util.WeakHashMap<Renderer, HashMap<Shape, Resources>>()

        private val includes = setOf("insert", "end")

        /** True for the names `#include "splat/<name>"` resolves: `insert` and `end`. */
        fun hasInclude(name: String): Boolean = name.removeSuffix(".glsl") in includes

        /** The packaged source of `#include "splat/<name>"`. */
        fun include(name: String): String {
            val bare = name.removeSuffix(".glsl")
            require(bare in includes) { "Unknown splat shader include: $name (there are ${includes.joinToString()})" }
            return checkNotNull(SplatShader::class.java.getResourceAsStream("/afield/four/splat/$bare.glsl")) {
                "Missing packaged splat shader include: $bare"
            }.bufferedReader().use { it.readText() }
        }

        /**
         * A complete compute shader for [insert] — what a box's compute tab expands to — for
         * building a [SplatShader]'s module outside a box, as the GPU smoke does.
         */
        fun computeSource(insert: String): String =
            "#version 450\n" + include("insert") + "\n" + insert + "\n" + include("end")
    }
}

/** What a [SplatShader] can be told; see each property. */
class SplatShaderSettings {
    /** The renderer module name. Two SplatShaders with one name replace each other. */
    var name: String = "splat-shader"

    /** Turn about +y, in the demo's slider units: 0.32 is its default, 0 stands still. */
    var autoRotate: Double = 0.32

    /** The demo's size slider: a multiplier on the fitted size. */
    var size: Double = 1.0

    /** Frame the cloud by its median and 90th-percentile radius; false draws it as it is. */
    var fit: Boolean = true

    /** Multiplies every splat's screen size. */
    var splatSize: Float = 1f

    /** Multiplies every splat's opacity. */
    var alphaScale: Float = 1f

    /** See [GaussianSplatRenderer.Config.colorGamma]; 2.2 is right for Field's window. */
    var colorGamma: Float = 2.2f

    var cameraAt: Vector3f = Vector3f(0f, 0.38f, 2.65f)
    var cameraTarget: Vector3f = Vector3f(0f, 0f, 0f)

    /** Vertical field of view. */
    var fovDegrees: Double = 54.0

    /** [GaussianSplatRenderer.SortMode.ExactRadix] by default: the keys are view-depth float bits. */
    var sortMode: GaussianSplatRenderer.SortMode = GaussianSplatRenderer.SortMode.ExactRadix
}

/**
 * The demo's viewer toolbar for [shader], in this box's `controls` output: the auto-rotate
 * speed, the size, a splat-size multiplier, play/pause for `t` and the turn, and a button that
 * starts `t` again from zero. The panel opens, as the demo's toolbar is always in view.
 */
fun Box.showControls(shader: SplatShader, channel: String = "controls") {
    val s = shader.settings
    controlGroup(channel) {
        slider("turn", min = 0.0, max = 1.5, step = 0.01, initialValue = s.autoRotate) { s.autoRotate = it }
        slider("size", min = 0.35, max = 2.5, step = 0.01, initialValue = s.size) { s.size = it }
        slider("splat size", min = 0.25, max = 4.0, step = 0.01, initialValue = s.splatSize.toDouble()) { s.splatSize = it.toFloat() }
        checkbox("play", initialValue = shader.playing) { shader.playing = it }
        button("t = 0") { down -> if (down) shader.t = 0.0 }
    }
    displayOpen(channel)
}
