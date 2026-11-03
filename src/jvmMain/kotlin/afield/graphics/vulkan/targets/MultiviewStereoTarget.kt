package afield.graphics.vulkan.targets

import afield.document.context.fromContext
import afield.graphics.render.Mesh
import afield.graphics.render.Module
import afield.graphics.render.ModulePointer
import afield.graphics.render.Parameters
import afield.graphics.render.parameters
import afield.graphics.render.pointer
import afield.graphics.shader.fromViews
import afield.graphics.texture.HasImageView
import afield.graphics.texture.TextureSampler
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.Target
import afield.graphics.vulkan.buffers.BufferParameters
import afield.graphics.vulkan.device.CommandBuffer
import afield.graphics.vulkan.targets.OpenXRHmdTarget.ControllerState
import afield.graphics.vulkan.targets.OpenXRHmdTarget.Hand
import afield.graphics.vulkan.util.Camera
import afield.graphics.vulkan.util.DownResult
import afield.graphics.vulkan.util.MouseEvent
import afield.graphics.vulkan.util.MouseEvents
import afield.math.linalg.*

/** One scene pass into two array layers, with one shared per-frame eye UBO. No packed intermediate. */
class MultiviewStereoTarget(
    val renderer: Renderer = fromContext(),
    val eyeWidth: Int = renderer.window.width / 2,
    val eyeHeight: Int = renderer.window.height,
    val openXR: OpenXRHmdTarget? = null,
) : Target, HasImageView {
    val stereoCamera = StereoCamera(Camera(renderer).apply { p.auto = {} })
    val layered = RenderToTexture(renderer) {
        width = eyeWidth
        height = eyeHeight
        colorAttachments = 1
        colorLayerCount = 2
        multiviewViewMask = 0b11
        colorSampled = true
        colorTransferSrc = true
        useDepth = true
        clearColor = vec(0f, 0f, 0f, 1f)
    }
    override val imageView
        get() = layered.imageView

    private var eyeUniforms: BufferParameters? = null
    private val mockController = ControllerState(active = true, poseValid = true, position = vec(0.0, 0.0, 0.0))
    private var mouseControllerPos: Vector2f? = null
    private var mouseControllerDistance = 1f

    init {
        require(renderer.device.supportsMultiview) { "MultiviewStereoTarget requires Vulkan multiview" }
        renderer.renderList.preTargets["stereo-multiview"] = this
        openXR?.copyLayersToEyes(layered)
    }

    /** Apply the layered pass settings and bind this target's single shared eye buffer. */
    fun configure(p: Parameters) {
        p.run { +layered }
        val previous = p.uboResolver
        p.uboResolver = { requested ->
            if (requested.name == "StereoViews") {
                require(requested.source?.size == 224) { "StereoViews must be 224 bytes" }
                eyeUniforms
                    ?: requested.also {
                        it.perFrame = true
                        it.hosted()
                        eyeUniforms = it
                    }
            } else previous(requested)
        }
    }

    fun registerModule(name: String, factory: () -> ModulePointer) {
        layered += name to factory()
    }

    fun registerModule(name: String, module: ModulePointer) {
        layered += name to module
    }

    @JvmName("plusAssign_pointer")
    operator fun plusAssign(entry: Pair<String, ModulePointer>) = registerModule(entry.first, entry.second)

    fun eyeViewMatrix(eye: Int, out: Matrix4f = Matrix4f()): Matrix4f =
        openXR?.eyeViewMatrix(eye, out) ?: out.set(stereoCamera.eyeViewMatrices()[eye])

    fun eyeProjectionMatrix(eye: Int, out: Matrix4f = Matrix4f()): Matrix4f =
        openXR?.eyeProjectionMatrix(eye, out) ?: out.set(stereoCamera.projectionMatrices()[eye])

    override fun render(command: CommandBuffer, index: Int) {
        mouseControllerPos?.let { updateMockControllerFromScreen(it.x, it.y, mouseControllerDistance) }
        stereoCamera.base.p.resize(eyeWidth, eyeHeight)
        eyeUniforms?.toBuffer(renderer)?.let { buffer ->
            buffer.mapAndWrite_bytes {
                for (eye in 0..1) {
                    val projection = eyeProjectionMatrix(eye)
                    val view = eyeViewMatrix(eye)
                    Matrix4f(projection).mul(view).get(eye * 64, this)
                    val inverse = Matrix4f(view).invert()
                    putFloat(128 + eye * 16, inverse.m00)
                    putFloat(132 + eye * 16, inverse.m01)
                    putFloat(136 + eye * 16, inverse.m02)
                    putFloat(140 + eye * 16, 0f)
                    putFloat(160 + eye * 16, inverse.m10)
                    putFloat(164 + eye * 16, inverse.m11)
                    putFloat(168 + eye * 16, inverse.m12)
                    putFloat(172 + eye * 16, 0f)
                    putFloat(192 + eye * 16, projection.m00)
                    putFloat(196 + eye * 16, projection.m11)
                    putFloat(200 + eye * 16, eyeWidth.toFloat())
                    putFloat(204 + eye * 16, eyeHeight.toFloat())
                }
            }
            buffer.clean(false, null)
        }
        layered.render(command, index)
    }

    /** A read-only desktop view of the actual array image, optionally displaying both layers. */
    fun mirrorToScreen(
        windowIndex: Int = 0,
        name: String = "stereo-mirror",
        stereoPair: () -> Boolean = { false },
    ): ModulePointer {
        val window = renderer.windows[windowIndex]
        val sampler = TextureSampler(renderer, mipLevels = 1, anisotropyEnable = false)
        val module = Module(
            parameters(renderer) {
                this.name = name
                depth = 1
                depthTest = false
                depthWrite = false
                imageFormat = renderer.windowContext(windowIndex).swapChain.surfaceFormat.imageFormat
                vertex = MIRROR_VERTEX
                fragment = MIRROR_FRAGMENT
                textureResolver = { texture ->
                    if (texture.name == "scene") fromViews(listOf(layered), texture.name, sampler) else null
                }
            },
        )
        val pointer = module.pointer()
        val mesh = Mesh(renderer, pointer)
        mesh.resize(3, 0)
        module.meshes[name] = mesh
        module.before["mirror-fit"] = Target { _, _ ->
            val ratio =
                (window.width.toFloat() / window.height.coerceAtLeast(1)) /
                    (eyeWidth.toFloat() / eyeHeight * if (stereoPair()) 2f else 1f)
            mesh.push["mirrorFit"] = Vector4f(
                if (ratio > 1f) ratio else 1f,
                if (ratio < 1f) 1f / ratio else 1f,
                if (stereoPair()) 1f else 0f,
                0f,
            ) // aspect fit and stereo-pair selection
        }
        renderer.renderTargetFor(windowIndex) += name to pointer
        return pointer
    }

    fun controllerState(hand: Hand = Hand.RIGHT, out: ControllerState = ControllerState()): ControllerState {
        val xr = openXR
        if (xr != null) return xr.controllerState(hand, out)

        val src = mockController
        out.active = src.active
        out.poseValid = src.poseValid
        out.position = src.position
        out.rotation = src.rotation
        out.selectPressed = src.selectPressed
        out.triggerValue = src.triggerValue
        out.triggerPressed = src.triggerPressed
        out.squeezeValue = src.squeezeValue
        out.squeezePressed = src.squeezePressed
        out.trackpadX = src.trackpadX
        out.trackpadY = src.trackpadY
        out.trackpadTouched = src.trackpadTouched
        out.trackpadPressed = src.trackpadPressed
        out.thumbstickX = src.thumbstickX
        out.thumbstickY = src.thumbstickY
        out.thumbstickTouched = src.thumbstickTouched
        out.thumbstickPressed = src.thumbstickPressed
        out.aPressed = src.aPressed
        out.bPressed = src.bPressed
        out.menuPressed = src.menuPressed
        return out
    }

    fun updateMockControllerFromCamera(distanceMeters: Float = 1f) {
        val cam = stereoCamera.base
        val origin = cam.at
        val forward = cam.forward
        val dir = Vector3f(forward).normalize()
        mockController.position = origin + dir * distanceMeters
        mockController.rotation = lookAlongZ(dir, cam.up)
        mockController.poseValid = true
    }

    fun updateMockControllerFromRay(origin: Vector3f, direction: Vector3f, distanceMeters: Float = 1f) {
        val nx = direction.normalize(Vector3f())
        mockController.position = origin + nx * distanceMeters
        mockController.rotation = lookAlongZ(nx, Vector3f(0f, 1f, 0f))
        mockController.poseValid = true
    }

    fun enableMouseController(
        mouse: MouseEvents = renderer.window.mouseEvents ?: MouseEvents(renderer),
        name: String = "sbs-multiview-mouse-controller",
        distanceMeters: Float = 1f,
    ): () -> Unit {
        mouseControllerDistance = distanceMeters

        val updateFromScreen: (MouseEvent) -> Unit = { ev ->
            updateMockControllerFromScreen(ev.pos.x, ev.pos.y, distanceMeters)
            mouseControllerPos = Vector2f(ev.pos.x, ev.pos.y)
        }

        mouse.onMove(
            name,
            callback = { ev ->
                updateFromScreen(ev)
                true
            },
        )
        mouse.onDrag(
            name,
            callback = { ev ->
                updateFromScreen(ev)
                true
            },
        )

        mouse.onDown("left", name) { ev ->
            updateFromScreen(ev)
            mockController.selectPressed = true
            mockController.triggerPressed = true
            mockController.triggerValue = 1f
            DownResult.Keep
        }
        mouse.onUp("left", name) { _ ->
            mockController.selectPressed = false
            mockController.triggerPressed = false
            mockController.triggerValue = 0f
            true
        }
        return {
            mouse.remove(name)
            mouseControllerPos = null
            mockController.selectPressed = false
            mockController.triggerPressed = false
            mockController.triggerValue = 0f
        }
    }

    private fun updateMockControllerFromScreen(x: Float, y: Float, distanceMeters: Float) {
        val w = renderer.window.width.toFloat().coerceAtLeast(1f)
        val h = renderer.window.height.toFloat().coerceAtLeast(1f)
        val ndcX = (x / w) * 2f - 1f
        val ndcY = 1f - (y / h) * 2f

        val cam = stereoCamera.base
        val origin = cam.at

        val halfFovRad = (cam.p.fov * 0.5).toFloat() // Projection.fov is radians; see Options.fov
        val tanHalfFov = kotlin.math.tan(halfFovRad)
        val aspect = cam.p.aspect.takeIf { it > 0f } ?: (w / h)

        val scale = 2f
        val dir =
            Vector3f(cam.forward)
                .fma(-ndcX * aspect * tanHalfFov * scale, cam.right)
                .fma(ndcY * tanHalfFov * scale, cam.up)
                .normalize()

        mockController.position = origin + dir * distanceMeters
        mockController.rotation = lookAlongZ(dir, cam.up)
        mockController.poseValid = true
    }

    private fun lookAlongZ(direction: Vector3f, up: Vector3f): Quaternionf {
        val dir = Vector3f(direction).normalize()
        val upNorm = Vector3f(up).normalize()
        return Quaternionf().lookAlong(dir, upNorm)
    }

    companion object {
        /** Binding 7 is reserved for eye data; the same allocation is shared by scene modules. */
        val VIEWS_GLSL =
            """
            #extension GL_EXT_multiview : require
            layout(std140, set=0, binding=7) uniform StereoViews {
                mat4 stereoVpL; // left-eye projection/view
                mat4 stereoVpR; // right-eye projection/view
                vec4 stereoRightL; // left-eye camera right
                vec4 stereoRightR; // right-eye camera right
                vec4 stereoUpL; // left-eye camera up
                vec4 stereoUpR; // right-eye camera up
                vec4 stereoProjectionL; // projection XY and viewport size
                vec4 stereoProjectionR; // projection XY and viewport size
            };
            mat4 stereoViewProjection() { return gl_ViewIndex==0 ? stereoVpL : stereoVpR; }
            vec3 stereoCameraRight() { return (gl_ViewIndex==0 ? stereoRightL : stereoRightR).xyz; }
            vec3 stereoCameraUp() { return (gl_ViewIndex==0 ? stereoUpL : stereoUpR).xyz; }
            vec4 stereoProjection() { return gl_ViewIndex==0 ? stereoProjectionL : stereoProjectionR; }
            """
                .trimIndent()
        private val MIRROR_VERTEX =
            """
            #version 450
            layout(location=0) out vec2 uv;
            void main() {
                vec2 q=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);
                uv=vec2(q.x,1.0-q.y);
                gl_Position=vec4(q*2.0-1.0,0,1);
            }
            """
                .trimIndent()
        private val MIRROR_FRAGMENT =
            """
            #version 450
            layout(binding=0) uniform sampler2DArray scene;
            layout(push_constant) uniform Push {
                vec4 mirrorFit; // aspect fit and stereo pair
            } pc;
            layout(location=0) in vec2 uv;
            layout(location=0) out vec4 color;
            void main() {
                vec2 q=(uv-0.5)*pc.mirrorFit.xy+0.5;
                if(any(lessThan(q,vec2(0))) || any(greaterThan(q,vec2(1)))) { color=vec4(0,0,0,1);return; }
                float layer=0.0;
                if(pc.mirrorFit.z>0.5) { layer=q.x<0.5?0.0:1.0;q.x=fract(q.x*2.0); }
                color=vec4(texture(scene,vec3(q,layer)).rgb,1.0);
            }
            """
                .trimIndent()
    }
}
