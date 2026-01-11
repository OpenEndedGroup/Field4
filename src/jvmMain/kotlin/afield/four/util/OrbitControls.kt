package afield.four.util

import afield.Box
import afield.four.Renderer
import afield.four.modules.fromContext
import org.joml.Quaternionf
import org.joml.Vector3f
import org.lwjgl.glfw.GLFW
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/**
 * Mouse-driven orbit controls inspired by Three.js OrbitControls.
 *
 * - Left-drag: rotate
 * - Middle-drag or CTRL/ALT+Left: dolly (zoom)
 * - Right-drag or SHIFT+Left: pan
 * - Scroll: dolly (zoom)
 */
class OrbitControls(
    val renderer: Renderer = fromContext(),
    val camera: Camera = fromContext(),
    mouseEvents: MouseEvents? = null,
    val name: String = "__orbitcontrols__"
) {
    val mouse: MouseEvents = mouseEvents ?: MouseEvents(renderer, name = "${name}__mouse__")

    var enabled = true

    var enableRotate = true
    var enablePan = true
    var enableZoom = true

    var rotateSpeed = 1.0
    var zoomSpeed = 1.0
    var panSpeed = 1.0

    var enableDamping = false
    var dampingFactor = 0.1

    var minDistance = 0.001
    var maxDistance = Double.POSITIVE_INFINITY

    var minPolarAngle = 0.0
    var maxPolarAngle = PI

    var screenSpacePanning = true

    val upAxis = Vector3f(camera.up).normalize()

    private var sphericalDeltaTheta = 0.0
    private var sphericalDeltaPhi = 0.0
    private var scale = 1.0
    private val panOffset = Vector3f()
    private var needsUpdate = false

    private var initialAt = Vector3f(camera.at)
    private var initialTarget = Vector3f(camera.target)
    private var initialUp = Vector3f(camera.up)
    private var initialUpAxis = Vector3f(upAxis)

    init {
        installMouseHandlers()
        renderer.window.onPoll[name] = sequence {
            while (true) {
                if (enabled && enableDamping && needsUpdate) {
                    needsUpdate = update()
                }
                yield(true)
            }
        }
    }

    fun saveState() {
        initialAt = Vector3f(camera.at)
        initialTarget = Vector3f(camera.target)
        initialUp = Vector3f(camera.up)
        initialUpAxis = Vector3f(upAxis)
    }

    fun reset() {
        camera.at.set(initialAt)
        camera.target.set(initialTarget)
        camera.up.set(initialUp)
        upAxis.set(initialUpAxis)
        sphericalDeltaTheta = 0.0
        sphericalDeltaPhi = 0.0
        scale = 1.0
        panOffset.set(0f, 0f, 0f)
        needsUpdate = false
    }

    private fun installMouseHandlers() {
        mouse.onDrag(name) { ev ->
            if (!enabled) return@onDrag true
            handleDrag(ev)
            if (enableDamping) {
                needsUpdate = true
            } else {
                update()
                needsUpdate = false
            }
            true
        }

        mouse.onScroll(name) { ev ->
            if (!enabled) return@onScroll true
            handleScroll(ev)
            if (enableDamping) {
                needsUpdate = true
            } else {
                update()
                needsUpdate = false
            }
            true
        }
    }

    private fun handleDrag(ev: MouseEvent) {
        val action = resolveAction(ev) ?: return
        val dx = ev.delta.x.toDouble()
        val dy = ev.delta.y.toDouble()

        when (action) {
            Action.ROTATE -> if (enableRotate) rotate(dx, dy)
            Action.PAN -> if (enablePan) pan(dx, dy)
            Action.DOLLY -> if (enableZoom) dolly(dy)
        }
    }

    private fun handleScroll(ev: MouseEvent) {
        if (!enableZoom) return
        val dy = ev.scroll.y.toDouble()
        if (dy == 0.0) return
        val zoomScale = getZoomScale(dy)
        if (dy > 0.0) {
            dollyIn(zoomScale)
        } else if (dy < 0.0) {
            dollyOut(zoomScale)
        }
    }

    private fun rotate(deltaX: Double, deltaY: Double) {
        val height = renderer.window.height.toDouble().coerceAtLeast(1.0)
        val angleX = 2.0 * PI * deltaX / height * rotateSpeed
        val angleY = 2.0 * PI * deltaY / height * rotateSpeed
        sphericalDeltaTheta -= angleX
        sphericalDeltaPhi -= angleY
    }

    private fun pan(deltaX: Double, deltaY: Double) {
        val height = renderer.window.height.toDouble().coerceAtLeast(1.0)
        val distance = camera.distanceToTarget().toDouble().coerceAtLeast(1e-6)
        val fov = camera.p.fov
        val targetDistance = distance * tan(0.5 * fov)
        val panX = 2.0 * deltaX * targetDistance / height * panSpeed
        val panY = 2.0 * deltaY * targetDistance / height * panSpeed

        val right = camera.right
        val up = if (screenSpacePanning) {
            Vector3f(camera.up).normalize()
        } else {
            Vector3f(upAxis).cross(right, Vector3f()).normalize()
        }

        panOffset.fma((-panX).toFloat(), right)
        panOffset.fma((panY).toFloat(), up)
    }

    private fun dolly(deltaY: Double) {
        if (deltaY == 0.0) return
        val zoomScale = getZoomScale(deltaY)
        if (deltaY > 0.0) {
            dollyOut(zoomScale)
        } else if (deltaY < 0.0) {
            dollyIn(zoomScale)
        }
    }

    private fun dollyIn(dollyScale: Double) {
        scale *= dollyScale
    }

    private fun dollyOut(dollyScale: Double) {
        scale /= dollyScale
    }

    private fun getZoomScale(delta: Double): Double {
        val normalized = abs(delta) * 0.01
        return 0.95.pow(zoomSpeed * normalized)
    }

    fun update(): Boolean {
        val offset = Vector3f(camera.at).sub(camera.target)
        if (offset.lengthSquared() < 1e-12f) {
            panOffset.set(0f, 0f, 0f)
            sphericalDeltaTheta = 0.0
            sphericalDeltaPhi = 0.0
            scale = 1.0
            return false
        }

        val up = Vector3f(upAxis).normalize()
        val quat = Quaternionf().rotateTo(up, WORLD_UP)
        val quatInverse = Quaternionf(quat).invert()

        quat.transform(offset)

        var radius = offset.length().toDouble()
        if (radius < 1e-12) return false

        var theta = atan2(offset.x.toDouble(), offset.z.toDouble())
        var phi = acos((offset.y / radius).toDouble().coerceIn(-1.0, 1.0))

        theta += sphericalDeltaTheta
        phi += sphericalDeltaPhi

        val minPhi = minPolarAngle.coerceAtLeast(0.0)
        val maxPhi = maxPolarAngle.coerceAtMost(PI)
        val eps = 1e-6
        phi = phi.coerceIn(minPhi + eps, maxPhi - eps)

        radius = (radius * scale).coerceIn(minDistance, maxDistance)

        val sinPhi = sin(phi)
        offset.set(
            (radius * sinPhi * sin(theta)).toFloat(),
            (radius * cos(phi)).toFloat(),
            (radius * sinPhi * cos(theta)).toFloat()
        )

        quatInverse.transform(offset)

        camera.target.add(panOffset)
        camera.at.set(camera.target).add(offset)
        camera.up.set(upAxis)

        val damp = if (enableDamping) (1.0 - dampingFactor).coerceIn(0.0, 1.0) else 0.0
        if (enableDamping) {
            sphericalDeltaTheta *= damp
            sphericalDeltaPhi *= damp
            panOffset.mul(damp.toFloat())
        } else {
            sphericalDeltaTheta = 0.0
            sphericalDeltaPhi = 0.0
            panOffset.set(0f, 0f, 0f)
        }
        scale = 1.0

        return enableDamping && (
            abs(sphericalDeltaTheta) > 1e-6 ||
                abs(sphericalDeltaPhi) > 1e-6 ||
                panOffset.lengthSquared() > 1e-6f
            )
    }

    private fun resolveAction(ev: MouseEvent): Action? {
        val left = ev.isDown("left")
        val middle = ev.isDown("middle")
        val right = ev.isDown("right")

        val shift = isShiftDown()
        val ctrl = isCtrlDown()
        val alt = isAltDown()

        return when {
            left && (ctrl || alt) -> Action.DOLLY
            left && shift -> Action.PAN
            left -> Action.ROTATE
            middle -> Action.DOLLY
            right -> Action.PAN
            else -> null
        }
    }

    private fun isShiftDown(): Boolean =
        renderer.window.isKeyPressed(GLFW.GLFW_KEY_LEFT_SHIFT) ||
            renderer.window.isKeyPressed(GLFW.GLFW_KEY_RIGHT_SHIFT)

    private fun isCtrlDown(): Boolean =
        renderer.window.isKeyPressed(GLFW.GLFW_KEY_LEFT_CONTROL) ||
            renderer.window.isKeyPressed(GLFW.GLFW_KEY_RIGHT_CONTROL)

    private fun isAltDown(): Boolean =
        renderer.window.isKeyPressed(GLFW.GLFW_KEY_LEFT_ALT) ||
            renderer.window.isKeyPressed(GLFW.GLFW_KEY_RIGHT_ALT)

    private enum class Action { ROTATE, PAN, DOLLY }

    companion object {
        private val WORLD_UP = Vector3f(0f, 1f, 0f)
    }
}

/**
 * Delegate provider that creates OrbitControls whose name equals the property name.
 */
class OrbitControlsDelegateProvider(
    private val renderer: Renderer = fromContext(),
    private val camera: Camera = fromContext(),
    private val box: Box = fromContext()
) {
    operator fun provideDelegate(thisRef: Any?, property: kotlin.reflect.KProperty<*>): kotlin.properties.ReadWriteProperty<Any?, OrbitControls> {
        val baseName = property.name + "__${box.id}"
        val mouse = MouseEvents(renderer, name = "${baseName}__mouse__")
        val instance = OrbitControls(
            renderer = renderer,
            camera = camera,
            mouseEvents = mouse,
            name = baseName
        )
        return object : kotlin.properties.ReadWriteProperty<Any?, OrbitControls> {
            private var value: OrbitControls = instance
            override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): OrbitControls = value
            override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: OrbitControls) {
                this.value = value
            }
        }
    }
}

/**
 * Factory function to create a delegate for OrbitControls using the property's name.
 */
fun orbitControls(
    renderer: Renderer = fromContext(),
    camera: Camera = fromContext()
): OrbitControlsDelegateProvider = OrbitControlsDelegateProvider(renderer, camera)
