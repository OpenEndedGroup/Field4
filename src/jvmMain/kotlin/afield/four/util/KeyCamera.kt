package afield.four.util

import afield.Box
import afield.four.Renderer
import afield.four.modules.fromContext
import afield.stdlib.quat
import afield.stdlib.rem
import afield.stdlib.times
import org.joml.Math
import org.joml.Vector3f
import org.joml.minus
import org.joml.plus
import org.lwjgl.glfw.GLFW
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

class KeyCamera(
    val renderer: Renderer = fromContext(), val camera: Camera = fromContext(), val name: String = "__keycamera__"
) {

    // key → (currentAmount, action)
    var actions = mutableMapOf<String, Pair<Double, (Double) -> Unit>>()

    var decay = 0.95
    var movementSpeed = 0.01
    var rotationSpeed = 0.05

    // snapshot for reset
    private val initialAt = Vector3f(camera.at)
    private val initialTarget = Vector3f(camera.target)
    private val initialUp = Vector3f(camera.up)
    private val initialFov = camera.p.fov           // 👈 new
    private val initialDecay = decay
    private val initialMovementSpeed = movementSpeed
    private val initialRotationSpeed = rotationSpeed

    init {
        renderer.window.onPoll[name] = sequence {
            while (true) {
                checkKeys()

                // decay & run actions
                actions.entries.retainAll { entry ->
                    var (amount, action) = entry.value
                    amount *= decay
                    entry.setValue(amount to action)
                    actions[entry.key] = Pair(amount, action)
                    action(amount)
                    amount > 0.01
                }

                // keep up ⟂ forward so rotations stay sane
                reorthogonalizeUp()

                yield(true)
            }
        }
    }

    private fun checkKeys() {
        val window = renderer.window
        val shift = isShiftDown()

        // base movement scale w/ distance
        val baseDistance = camera.at.distance(camera.target)
        val moveStep = baseDistance * movementSpeed

        // ---------- forward / back (UP / DOWN) ----------
        if (window.isKeyPressed(GLFW.GLFW_KEY_UP)) {
            enqueue(if (!shift) "up_target" else "up_at") { amount ->
                val delta = camera.forward.times(moveStep * amount)
                if (!shift) {
                    // pan: move both
                    camera.at.add(delta)
                    camera.target.add(delta)
                } else {
                    // move camera
                    camera.at.add(delta)
                }
            }
        }
        if (window.isKeyPressed(GLFW.GLFW_KEY_DOWN)) {
            enqueue(if (!shift) "down_target" else "down_at") { amount ->
                val delta = camera.forward.times(moveStep * -amount)
                if (!shift) {
                    camera.at.add(delta)
                    camera.target.add(delta)
                } else {
                    camera.at.add(delta)
                }
            }
        }

        // ---------- orbit left / right (LEFT / RIGHT) ----------
        if (window.isKeyPressed(GLFW.GLFW_KEY_LEFT)) {
            enqueue(if (!shift) "left_target" else "left_at") { amount ->
                val ang = rotationSpeed * amount
                if (!shift) {
                    // rotate TARGET around CAMERA
                    camera.target %= (camera.target - camera.at) * quat(angle = ang, axis = camera.up) + camera.at
                } else {
                    // rotate CAMERA around TARGET
                    camera.at %= (camera.at - camera.target) * quat(angle = ang, axis = camera.up) + camera.target
                }
            }
        }
        if (window.isKeyPressed(GLFW.GLFW_KEY_RIGHT)) {
            enqueue(if (!shift) "right_target" else "right_at") { amount ->
                val ang = rotationSpeed * -amount
                if (!shift) {
                    camera.target %= (camera.target - camera.at) * quat(angle = ang, axis = camera.up) + camera.at
                } else {
                    camera.at %= (camera.at - camera.target) * quat(angle = ang, axis = camera.up) + camera.target
                }
            }
        }

        // ---------- vertical rotate (PAGE UP / PAGE DOWN) ----------
        if (window.isKeyPressed(GLFW.GLFW_KEY_PAGE_UP)) {
            enqueue(if (!shift) "pageup_target" else "pageup_at") { amount ->
                val forward = Vector3f(camera.target).sub(camera.at)
                if (forward.lengthSquared() > 1e-12f) {
                    val right = forward.cross(Vector3f(camera.up)).normalize()
                    val ang = rotationSpeed * amount
                    if (shift) {
                        // rotate CAMERA around TARGET
                        camera.at %= (camera.at - camera.target) * quat(angle = ang, axis = right) + camera.target
                    } else {
                        // rotate TARGET around CAMERA
                        camera.target %= (camera.target - camera.at) * quat(angle = ang, axis = right) + camera.at
                    }
                }
            }
        }
        if (window.isKeyPressed(GLFW.GLFW_KEY_PAGE_DOWN)) {
            enqueue(if (!shift) "pagedown_target" else "pagedown_at") { amount ->
                val forward = Vector3f(camera.target).sub(camera.at)
                if (forward.lengthSquared() > 1e-12f) {
                    val right = forward.cross(Vector3f(camera.up)).normalize()
                    val ang = rotationSpeed * -amount
                    if (shift) {
                        // rotate CAMERA around TARGET
                        camera.at %= (camera.at - camera.target) * quat(angle = ang, axis = right) + camera.target
                    } else {
                        // rotate TARGET around CAMERA
                        camera.target %= (camera.target - camera.at) * quat(angle = ang, axis = right) + camera.at
                    }
                }
            }
        }


        // ---------- zoom ([ and ]) → NOW: change camera.p.fov ----------
        if (window.isKeyPressed(GLFW.GLFW_KEY_LEFT_BRACKET)) {
            // zoom in → smaller fov
            enqueue("zoom_fov_in") { amount ->
                zoomFov(amount, inward = true, fine = shift)
            }
        }
        if (window.isKeyPressed(GLFW.GLFW_KEY_RIGHT_BRACKET)) {
            // zoom out → bigger fov
            enqueue("zoom_fov_out") { amount ->
                zoomFov(amount, inward = false, fine = shift)
            }
        }

        // ---------- roll ( , and . ) ----------
        if (window.isKeyPressed(GLFW.GLFW_KEY_COMMA)) {
            enqueue("roll_left") { amount ->
                rollAroundView(rotationSpeed * amount)
            }
        }
        if (window.isKeyPressed(GLFW.GLFW_KEY_PERIOD)) {
            enqueue("roll_right") { amount ->
                rollAroundView(rotationSpeed * -amount)
            }
        }

        // ---------- reset (R) ----------
        if (window.isKeyPressed(GLFW.GLFW_KEY_R)) {
            resetToInitial()
            actions.clear()
        }
    }

    private fun enqueue(key: String, action: (Double) -> Unit) {
        actions[key] = 1.0 to action
    }

    private fun isShiftDown(): Boolean {
        val w = renderer.window
        return w.isKeyPressed(GLFW.GLFW_KEY_LEFT_SHIFT) || w.isKeyPressed(GLFW.GLFW_KEY_RIGHT_SHIFT)
    }

    /**
     * FOV-based zoom: shrink/enlarge camera.p.fov.
     *
     * - inward = true  → zoom in  → fov goes down
     * - inward = false → zoom out → fov goes up
     * - fine = true    → smaller step (when Shift is held)
     *
     * Tries to auto-detect if projection is in degrees or radians.
     */
    private fun zoomFov(amount: Double, inward: Boolean, fine: Boolean) {
        val proj = camera.p
        val current = proj.fov

        // radians
        val baseStep = Math.toRadians(5.0)          // ~0.087 rad
        val step = if (fine) baseStep * 0.25f else baseStep   // fine → ~0.0217 rad

        val minFov = Math.toRadians(5.0)            // 0.087 rad
        val maxFov = Math.toRadians(120.0)          // 2.094 rad

        val dir = if (inward) -1f else 1f
        val next = current + dir * step * amount

        proj.fov = next.coerceIn(minFov, maxFov)
    }


    private fun rollAroundView(angle: Double) {
        // view dir = target - at
        val forward = Vector3f(camera.target).sub(camera.at)
        if (forward.lengthSquared() < 1e-12f) return
        forward.normalize()

        val q = quat(angle = angle, axis = forward)
        camera.up %= camera.up * q
    }

    private fun reorthogonalizeUp() {
        val forward = Vector3f(camera.target).sub(camera.at)
        if (forward.lengthSquared() < 1e-12f) {
            return
        }
        forward.normalize()

        val up = Vector3f(camera.up)
        val proj = up.dot(forward)
        up.fma(-proj, forward)

        if (up.lengthSquared() < 1e-6f) {
            if (kotlin.math.abs(forward.y) > 0.9f) {
                up.set(1f, 0f, 0f)
            } else {
                up.set(0f, 1f, 0f)
                val p2 = up.dot(forward)
                up.fma(-p2, forward)
            }
        }

        up.normalize()
        camera.up.set(up)
    }

    private fun resetToInitial() {
        camera.at.set(initialAt)
        camera.target.set(initialTarget)
        camera.up.set(initialUp)
        camera.p.fov = initialFov      // 👈 restore fov

        decay = initialDecay
        movementSpeed = initialMovementSpeed
        rotationSpeed = initialRotationSpeed
    }
}

/**
 * Delegate provider that creates a KeyCamera whose name equals the property name.
 */
class KeyCameraDelegateProvider(
    private val renderer: Renderer = fromContext(),
    private val camera: Camera = fromContext(),
    private val box: Box = fromContext()
) {
    operator fun provideDelegate(thisRef: Any?, property: KProperty<*>): ReadWriteProperty<Any?, KeyCamera> {
        val instance = KeyCamera(renderer = renderer, camera = camera, name = property.name + "__${box.id}")
        return object : ReadWriteProperty<Any?, KeyCamera> {
            private var value: KeyCamera = instance
            override fun getValue(thisRef: Any?, property: KProperty<*>): KeyCamera = value
            override fun setValue(thisRef: Any?, property: KProperty<*>, value: KeyCamera) {
                this.value = value
            }
        }
    }
}

/**
 * Factory function to create a delegate for KeyCamera using the property's name.
 */
fun keyCamera(
    renderer: Renderer = fromContext(), camera: Camera = fromContext()
): KeyCameraDelegateProvider = KeyCameraDelegateProvider(renderer, camera)
