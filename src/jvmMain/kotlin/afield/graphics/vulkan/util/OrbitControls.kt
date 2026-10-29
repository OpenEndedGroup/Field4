package afield.graphics.vulkan.util

/**
 * Mouse orbit controls for a [Camera] in a Vulkan window: rotate, pan and dolly around the camera's target, after Three.js's OrbitControls.
 */

import afield.document.graph.Box
import afield.graphics.vulkan.Renderer
import afield.document.context.fromContext
import afield.math.linalg.Quaternionf
import afield.math.linalg.Vector3f
import org.lwjgl.glfw.GLFW
import kotlin.math.*

/**
 * Mouse-driven orbit controls inspired by Three.js OrbitControls.
 *
 * - Left-drag: rotate
 * - Middle-drag or CTRL/ALT+Left: dolly (zoom)
 * - Right-drag or SHIFT+Left: pan
 * - Scroll: dolly (zoom)
 */
class OrbitControls(
    /** The renderer whose window supplies the mouse events, the key state for the drag modifiers and the poll loop that damping runs in. */
    val renderer: Renderer = fromContext(),
    /** The camera these controls move. Its `at`, `target` and `up` are written on every [update]. */
    val camera: Camera = fromContext(),
    mouseEvents: MouseEvents? = renderer.window.mouseEvents,
    /** The name the mouse handlers and the poll-loop entry are registered under, so a second set of controls on the same window does not displace the first. */
    val name: String = "__orbitcontrols__"
) {
    /**
     * The mouse stream these controls listen on: the window's own [MouseEvents] when the constructor
     * was given one, otherwise a private stream named `<name>__mouse__`.
     */
    val mouse: MouseEvents = mouseEvents ?: MouseEvents(renderer, name = "${name}__mouse__")

    /** Whether drags and scrolls move the camera at all. While false the handlers return early and damping stops advancing, so the camera holds its pose. */
    var enabled = true

    /** Whether a plain left-drag orbits the camera around the camera's target. */
    var enableRotate = true

    /** Whether a right-drag, or shift and left-drag, slides camera and target together. */
    var enablePan = true

    /** Whether the scroll wheel, a middle-drag and ctrl (or alt) with a left-drag change the distance to the target. */
    var enableZoom = true

    /** Radians of orbit per pixel dragged, as a multiple of a full turn across the window: at 1.0, dragging the window's own height turns 360° about either axis. */
    var rotateSpeed = 1.0

    /** How sharply scrolling and dollying change the distance to the target: an event of `d` units multiplies it by `0.95` raised to `zoomSpeed * d / 100`, inwards or outwards according to the sign. */
    var zoomSpeed = 1.0

    /** Pan distance per pixel dragged, as a multiple of what the camera can see: at 1.0, dragging the window's own height moves the target by the full visible height at the target's distance, so the scene keeps pace with the pointer. */
    var panSpeed = 1.0

    /**
     * Whether a drag or scroll leaves momentum behind. While it is set, [update] keeps being called
     * from the window's poll loop after the mouse stops, each call applying and then shrinking
     * what is left, until the pending angles are under a millionth of a radian and the pending pan
     * under a thousandth of a world unit.
     */
    var enableDamping = false

    /** The fraction of the pending orbit, pan and zoom that each damped [update] discards: 0.1 keeps nine tenths of the momentum per frame, 1.0 spends it all in one frame, and 0.0 never settles. Read only while [enableDamping] is set. */
    var dampingFactor = 0.1

    /** The closest the camera may come to its target; the distance is clamped into this range by every [update], not only by the drag that crossed it. */
    var minDistance = 0.001

    /** The farthest the camera may sit from its target, infinite by default; clamped like [minDistance]. */
    var maxDistance = Double.POSITIVE_INFINITY

    /** The smallest angle, in radians, between [upAxis] and the direction from target to camera: 0 is directly overhead, and the camera is held a millionth of a radian inside the limit so the pole itself stays unreachable. */
    var minPolarAngle = 0.0

    /** The largest such angle, `PI` (directly underneath) by default; held just inside the limit like [minPolarAngle], which is what keeps the picture from flipping over at the pole. */
    var maxPolarAngle = PI

    /**
     * Which plane a pan moves the target in. Set, the drag moves it in the plane of the screen,
     * along the camera's own up; clear, it moves in the plane that [upAxis] is normal to, so a
     * vertical drag travels along the ground rather than lifting the target off it.
     */
    var screenSpacePanning = true

    /**
     * The axis the camera orbits about and the one the polar limits are measured from: the camera's
     * `up` as it stood when the controls were built, normalized. Every [update] writes it back into
     * the camera, so the horizon stays level however far the orbit has travelled.
     */
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

    /** Records the camera's present position, target, up and [upAxis] as the pose [reset] returns to. The constructor records it once, so a reset with no save behind it returns the camera to where it stood when the controls were built. */
    fun saveState() {
        initialAt = Vector3f(camera.at)
        initialTarget = Vector3f(camera.target)
        initialUp = Vector3f(camera.up)
        initialUpAxis = Vector3f(upAxis)
    }

    /** Puts the camera back to the pose [saveState] recorded and drops whatever orbit, pan or zoom was still pending. */
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
        mouse.onDown("left", name) { ev : MouseEvent ->
            DownResult.Keep
        }

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

    /**
     * Applies the pending orbit, pan and zoom to the camera, clamping the distance and the polar
     * angle on the way, and returns whether damping has left anything worth another call — which is
     * what keeps the poll loop going while the camera coasts to a stop. The drag and scroll handlers
     * call it themselves; with nothing pending it still clamps, so it also brings a camera that
     * other code has moved back inside the limits.
     */
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
        var phi = acos((offset.y / radius).coerceIn(-1.0, 1.0))

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
