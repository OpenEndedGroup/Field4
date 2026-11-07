package afield.graphics.scene.reading

import afield.graphics.render.*
import afield.graphics.text.slug.*
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.Target
import afield.graphics.vulkan.buffers.*
import afield.graphics.vulkan.targets.MultiviewStereoTarget
import afield.math.linalg.*
import kotlin.math.*
import org.lwjgl.vulkan.VK10.*

data class DiagramShaderSet(
    val strokeVertex: String = ReadingShaders.source("stroke_vertex"),
    val strokeFragment: String = ReadingShaders.source("stroke_fragment"),
    val pointVertex: String = ReadingShaders.source("point_vertex"),
    val pointFragment: String = ReadingShaders.source("point_fragment"),
    val textVertex: String = ReadingShaders.source("slug_vertex"),
    val textFragment: String = ReadingShaders.source("slug_fragment"),
    val blurVertex: String = ReadingShaders.source("fullscreen_vertex"),
    val blurFragment: String = ReadingShaders.source("blur_fragment"),
    val compositeVertex: String = ReadingShaders.source("fullscreen_vertex"),
    val compositeFragment: String = ReadingShaders.source("composite_fragment"),
)

object ReadingShaders {
    /** Inject the exact StereoViews ABI used by Field's multiview target. */
    fun source(name: String) =
        requireNotNull(javaClass.getResource("/afield/reading/$name.glsl"))
            .readText()
            .replace("// VIEWS", MultiviewStereoTarget.VIEWS_GLSL)
}

/**
 * Ordinary vertex/fragment draws for small CPU-driven diagrams. No task/mesh extension is needed.
 * CPU simulation/uploads happen once, in the first module's pre-draw callback after the target
 * acquires its frame slot. The same predicted eye buffer then drives strokes, markers and Slug.
 */
class StereoDiagramRenderer(
    val renderer: Renderer,
    val stereo: MultiviewStereoTarget,
    val scene: ReadingScene,
    shaders: DiagramShaderSet = DiagramShaderSet(),
    val maxSegments: Int = 8192,
    val maxPoints: Int = 1024,
    sceneScale: Float = 1f,
) : AutoCloseable {
    private val ownedTarget = stereo.layered
    val compositor = DiagramCompositor(renderer, stereo, shaders, sceneScale)
    private val segments =
        defaultBuffer(name = "reading-segments") {
                size = maxSegments.toLong() * 128
                perFrame = true
                hosted()
                addressable()
            }
            .toBuffer(renderer)
    private val points =
        defaultBuffer(name = "reading-points") {
                size = maxPoints.toLong() * 48
                perFrame = true
                hosted()
                addressable()
            }
            .toBuffer(renderer)

    private fun module(name: String, vertex: String, fragment: String): ModulePointer =
        Module(
                parameters(renderer) {
                    this.name = name
                    stereo.configure(this)
                    +compositor.sharp
                    this.vertex = vertex
                    this.fragment = fragment
                    primitiveType = triangles
                    depthTest = false
                    depthWrite = false
                    blend = true
                    srcColor = VK_BLEND_FACTOR_SRC_ALPHA
                    dstColor = VK_BLEND_FACTOR_ONE
                    srcAlpha = VK_BLEND_FACTOR_ONE
                    dstAlpha = VK_BLEND_FACTOR_ONE
                },
            )
            .pointer()

    val strokeModule = module("reading-strokes", shaders.strokeVertex, shaders.strokeFragment)
    val pointModule = module("reading-markers", shaders.pointVertex, shaders.pointFragment)
    val textModule = module("reading-slug", shaders.textVertex, shaders.textFragment)
    private val strokeMesh = Mesh(renderer, strokeModule)
    private val pointMesh = Mesh(renderer, pointModule)
    private val slugData =
        scene.shapes.values.distinct().associateWith { SlugBuffer(renderer, it.slug) }
    private val wordMeshes = List(scene.maximumLabels) { Mesh(renderer, textModule) }
    val model = Matrix4f()
    val origin = Vector3f()
    var distance = 2.35f
    var followReader = true
    var turnHalfLife = 2f
    var maximumTurnRate = .25f
    var minimumStrokePixels = .65f
    var minimumPointPixels = 1.25f
    var labelColor = Vector4f(1f)
    var frameCount = 0L
        private set

    private var yaw = 0f
    private var recenterPending = true
    private var previousNs = 0L
    private var lastFrame = -1L
    private var closed = false
    var beforeUpdate: () -> Unit = {}

    init {
        require(maxSegments > 0 && maxPoints > 0)
        strokeModule += "centrelines" to strokeMesh
        pointModule += "markers" to pointMesh
        for ((i, mesh) in wordMeshes.withIndex()) {
            // Dependency collection visits zero-vertex meshes too. Seed every gallery slot,
            // including labels that only become visible in another example or a later cue.
            mesh.resize(0, 0)
            mesh.push["model"] = Matrix4f()
            mesh.push["slugData"] = slugData.values.first()
            mesh.push["viewport"] =
                Vector2f(compositor.sharp.width.toFloat(), compositor.sharp.height.toFloat())
            mesh.push["tint"] = Vector4f(0f)
            textModule += "word-$i" to mesh
        }
        val overlays = LinkedHashMap(ownedTarget.modules)
        ownedTarget.modules.clear()
        compositor.sharp += "reading-strokes" to strokeModule
        compositor.sharp += "reading-markers" to pointModule
        compositor.sharp += "reading-slug" to textModule
        ownedTarget += "reading-composite" to compositor.module
        ownedTarget.modules.putAll(overlays) // Field's browser stays sharp and above the scene.
        strokeModule.current.before["reading-frame"] = Target { _, _ -> updateFrame() }
        strokeMesh.resize(0, 0)
        pointMesh.resize(0, 0)
        // Address/model suppliers remain live when a module is rebuilt.
        strokeMesh.push.bindPushConstant("model", { model }) {
            error("Missing diagram push-constant member")
        }
        strokeMesh.push.bindPushConstant("diagramData", { segments }) {
            error("Missing diagram push-constant member")
        }
        strokeMesh.push.bindPushConstant("options", { viewportOptions() }) {
            error("Missing diagram push-constant member")
        }
        pointMesh.push.bindPushConstant("model", { model }) {
            error("Missing diagram push-constant member")
        }
        pointMesh.push.bindPushConstant("diagramData", { points }) {
            error("Missing diagram push-constant member")
        }
        pointMesh.push.bindPushConstant("options", { viewportOptions() }) {
            error("Missing diagram push-constant member")
        }
    }

    private fun viewportOptions() =
        Vector4f(
            compositor.sharp.width.toFloat(),
            compositor.sharp.height.toFloat(),
            minimumStrokePixels,
            minimumPointPixels,
        )

    fun recenter() {
        recenterPending = true
    }

    private fun updateFrame() {
        if (closed || lastFrame == renderer.frameCounter) return
        lastFrame = renderer.frameCounter
        beforeUpdate()
        val now = System.nanoTime()
        val dt = if (previousNs == 0L) 0.0 else ((now - previousNs) / 1e9).coerceAtLeast(0.0)
        previousNs = now
        val left = stereo.eyeViewMatrix(0).invert()
        val right = stereo.eyeViewMatrix(1).invert()
        val head =
            Vector3f(left.m30 + right.m30, left.m31 + right.m31, left.m32 + right.m32).mul(.5f)
        if (recenterPending) {
            val forward = Vector3f(-left.m20 - right.m20, 0f, -left.m22 - right.m22)
            if (forward.lengthSquared() < 1e-10f) forward.set(0f, 0f, -1f) else forward.normalize()
            origin.set(head).fma(distance, forward).add(0f, -.1f, 0f)
            yaw = atan2(-forward.x, -forward.z)
            recenterPending = false
        }
        if (followReader) {
            val desired = atan2(head.x - origin.x, head.z - origin.z)
            val delta = atan2(sin(desired - yaw), cos(desired - yaw))
            val motionDt = min(dt, .1)
            val amount = delta * (1 - exp(-motionDt / max(turnHalfLife.toDouble(), .01)))
            yaw +=
                amount.coerceIn(-maximumTurnRate * motionDt, maximumTurnRate * motionDt).toFloat()
        }
        model.identity().translation(origin).rotateY(yaw)
        scene.update(dt)
        check(scene.geometry.segments.size <= maxSegments) {
            "Diagram has ${scene.geometry.segments.size} segments; capacity $maxSegments"
        }
        check(scene.geometry.points.size <= maxPoints) {
            "Diagram has ${scene.geometry.points.size} markers; capacity $maxPoints"
        }
        segments.mapAndWrite_bytes {
            clear()
            scene.geometry.writeSegments(this)
        }
        points.mapAndWrite_bytes {
            clear()
            scene.geometry.writePoints(this)
        }
        strokeMesh.resize(scene.geometry.segments.size * 6, 0)
        pointMesh.resize(scene.geometry.points.size * 6, 0)
        for ((i, mesh) in wordMeshes.withIndex()) {
            val word = scene.labels.getOrNull(i)
            if (word == null) {
                mesh.resize(0, 0)
                continue
            }
            mesh.resize(6, 0)
            mesh.push["model"] =
                Matrix4f(model).translate(word.position).rotateZ(word.rotation).scale(word.scale)
            mesh.push["slugData"] = slugData.getValue(word.shape)
            mesh.push["viewport"] =
                Vector2f(compositor.sharp.width.toFloat(), compositor.sharp.height.toFloat())
            mesh.push["tint"] = Vector4f(labelColor).mul(word.color).apply { w *= word.opacity }
        }
        frameCount++
    }

    /**
     * Changing appearance keeps all text atlases and physics state; geometry-only buffers stay
     * bound.
     */
    fun reload(shaders: DiagramShaderSet) {
        fun reload(module: ModulePointer, vertex: String, fragment: String) {
            check(
                module.reload(module.current.parameters.copy(vertex = vertex, fragment = fragment)),
            ) {
                "Shader reload failed: ${module.current.parameters.name}"
            }
        }
        reload(strokeModule, shaders.strokeVertex, shaders.strokeFragment)
        reload(pointModule, shaders.pointVertex, shaders.pointFragment)
        reload(textModule, shaders.textVertex, shaders.textFragment)
        compositor.reload(shaders)
    }

    override fun close() {
        if (closed) return
        closed = true
        ownedTarget.modules.remove("reading-composite", compositor.module)
        compositor.sharp.modules.clear()
        compositor.close()
        strokeModule.current.before.remove("reading-frame")
        slugData.values.forEach { it.close() }
        // Use Field's retirement queue: previous frames may still be reading these resources.
        renderer.cleanup.add {
            segments.buffers.forEach { it.cleanup() }
            points.buffers.forEach { it.cleanup() }
            strokeModule.current.cleanup()
            pointModule.current.cleanup()
            textModule.current.cleanup()
        }
    }
}
