package afield.four.util

import afield.Box
import afield.four.Renderer
import afield.four.modules.fromContext
import org.joml.Math
import org.joml.Vector3f
import org.lwjgl.glfw.GLFW
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty
import kotlin.math.pow

class KeyCamera(
    val renderer: Renderer = fromContext(),
    val camera: Camera = fromContext(),
    val name: String = "__keycamera__"
) {

    // key → (currentAmount, action)
    var actions = mutableMapOf<String, Pair<Double, (Double) -> Unit>>()
    var perActionDecays = mutableMapOf<String, Double>()

    var decay = 0.95
    var movementSpeed = 0.01
    var rotationSpeed = 0.05

    // right-shift double-tap detection
    private var lastRightShiftDownTimeMs: Long = 0L
    private var wasRightShiftDown: Boolean = false
    private var actionsFrozen: Boolean = false
    private var lastTimeNs: Long = System.nanoTime()

    // snapshot for reset
    private val initialAt = Vector3f(camera.at)
    private val initialTarget = Vector3f(camera.target)
    private val initialUp = Vector3f(camera.up)
    private val initialFov = camera.p.fov
    private val initialDecay = decay
    private val initialMovementSpeed = movementSpeed
    private val initialRotationSpeed = rotationSpeed

    init {
        renderer.window.onPoll[name] = sequence {
            while (true) {
                val now = System.nanoTime()
                var dt = (now - lastTimeNs) / 1_000_000_000.0
                lastTimeNs = now
                // clamp to avoid huge jumps
                dt = dt.coerceIn(0.0, 0.5)
                val frameFactor = (dt / (1.0 / 60.0))

                checkKeys()

                // decay & run actions
                actions.entries.retainAll { entry ->
                    var (amount, action) = entry.value
                    val localDecay = perActionDecays.computeIfAbsent(entry.key) { decay }
                    // decay factor scaled by dt so behavior is framerate-independent
                    val decayFactor = localDecay.pow(frameFactor)
                    amount *= decayFactor
                    entry.setValue(amount to action)
                    actions[entry.key] = amount to action
                    action(amount * frameFactor)
                    amount > 0.01
                }

                // keep up ⟂ forward so rotations stay sane
                camera.reorthogonalizeUp()

                yield(true)
            }
        }
    }

    private fun checkKeys() {
        val window = renderer.window

        // handle right-shift double tap before computing general "shift" state
        handleRightShiftDoubleTap()

        val shift = isShiftDown()

        // base movement scale w/ distance
        val baseDistance = camera.distanceToTarget().toDouble()
        val moveStep = baseDistance * movementSpeed

        // ---------- forward / back (UP / DOWN) ----------
        if (window.isKeyPressed(GLFW.GLFW_KEY_UP)) {
            enqueue(if (!shift) "up_target" else "up_at") { amount ->
                val step = moveStep * amount
                if (!shift) {
                    // pan: move both camera and target forward
                    camera.moveLocal(forward = step, moveTarget = true)
                } else {
                    // move camera only (change distance to target)
                    camera.moveLocal(forward = step, moveTarget = false)
                }
            }
        }
        if (window.isKeyPressed(GLFW.GLFW_KEY_DOWN)) {
            enqueue(if (!shift) "down_target" else "down_at") { amount ->
                val step = -moveStep * amount
                if (!shift) {
                    camera.moveLocal(forward = step, moveTarget = true)
                } else {
                    camera.moveLocal(forward = step, moveTarget = false)
                }
            }
        }

        // ---------- orbit left / right (LEFT / RIGHT) ----------
        if (window.isKeyPressed(GLFW.GLFW_KEY_LEFT)) {
            enqueue(if (!shift) "left_target" else "left_at") { amount ->
                val ang = rotationSpeed * amount
                if (!shift) {
                    // rotate TARGET around CAMERA (yaw in place)
                    camera.yaw(
                        angle = ang,
                        aroundTarget = false,
                        rotateUp = false,
                        reorthogonalizeUp = false
                    )
                } else {
                    // rotate CAMERA around TARGET (orbit)
                    camera.yaw(
                        angle = ang,
                        aroundTarget = true,
                        rotateUp = false,
                        reorthogonalizeUp = false
                    )
                }
            }
        }
        if (window.isKeyPressed(GLFW.GLFW_KEY_RIGHT)) {
            enqueue(if (!shift) "right_target" else "right_at") { amount ->
                val ang = rotationSpeed * -amount
                if (!shift) {
                    camera.yaw(
                        angle = ang,
                        aroundTarget = false,
                        rotateUp = false,
                        reorthogonalizeUp = false
                    )
                } else {
                    camera.yaw(
                        angle = ang,
                        aroundTarget = true,
                        rotateUp = false,
                        reorthogonalizeUp = false
                    )
                }
            }
        }

        // ---------- vertical rotate (PAGE UP / PAGE DOWN) ----------
        if (window.isKeyPressed(GLFW.GLFW_KEY_PAGE_UP)) {
            enqueue(if (!shift) "pageup_target" else "pageup_at") { amount ->
                val ang = rotationSpeed * amount
                camera.pitch(
                    angle = ang,
                    aroundTarget = shift,
                    rotateUp = false,
                    reorthogonalizeUp = false
                )
            }
        }
        if (window.isKeyPressed(GLFW.GLFW_KEY_PAGE_DOWN)) {
            enqueue(if (!shift) "pagedown_target" else "pagedown_at") { amount ->
                val ang = rotationSpeed * -amount
                camera.pitch(
                    angle = ang,
                    aroundTarget = shift,
                    rotateUp = false,
                    reorthogonalizeUp = false
                )
            }
        }

        // ---------- zoom ([ and ]) → change camera.p.fov ----------
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
                camera.roll(rotationSpeed * amount)
            }
        }
        if (window.isKeyPressed(GLFW.GLFW_KEY_PERIOD)) {
            enqueue("roll_right") { amount ->
                camera.roll(rotationSpeed * -amount)
            }
        }

        // ---------- reset (R) ----------
        if (window.isKeyPressed(GLFW.GLFW_KEY_R)) {
            resetToInitial()
            actions.clear()
            perActionDecays.clear()
            actionsFrozen = false
        }
    }

    private fun enqueue(key: String, action: (Double) -> Unit) {
        actions[key] = 1.0 to action
        perActionDecays.remove(key)
    }

    private fun isShiftDown(): Boolean {
        val w = renderer.window
        return w.isKeyPressed(GLFW.GLFW_KEY_LEFT_SHIFT) || w.isKeyPressed(GLFW.GLFW_KEY_RIGHT_SHIFT)
    }

    /**
     * Detect double-tap on RIGHT SHIFT (two key-down events within 500ms).
     * Toggles between:
     *  - freezing current actions (perActionDecays[key] = 1.0)
     *  - clearing perActionDecays (back to global decay)
     */
    private fun handleRightShiftDoubleTap() {
        val window = renderer.window
        val rightShiftDown = window.isKeyPressed(GLFW.GLFW_KEY_RIGHT_SHIFT)

        // edge detection: key-down event
        if (rightShiftDown && !wasRightShiftDown) {
            val nowMs = System.nanoTime() / 1_000_000L
            val delta = nowMs - lastRightShiftDownTimeMs

            if (delta in 1..DOUBLE_TAP_WINDOW_MS) {
                toggleActionDecayFreeze()
                // reset to avoid triple-tap being counted as 2 double-taps
                lastRightShiftDownTimeMs = 0L
            } else {
                lastRightShiftDownTimeMs = nowMs
            }
        }

        wasRightShiftDown = rightShiftDown
    }

    private fun toggleActionDecayFreeze() {
        if (!actionsFrozen) {
            // Freeze currently active actions (no decay)
            actions.keys.forEach { key ->
                perActionDecays[key] = 1.0
            }
            actionsFrozen = perActionDecays.size>0
        } else {
            // Restore default behavior
            perActionDecays.clear()
            actionsFrozen = false
        }
    }

    /**
     * FOV-based zoom: shrink/enlarge camera.p.fov.
     *
     * - inward = true  → zoom in  → fov goes down
     * - inward = false → zoom out → fov goes up
     * - fine = true    → smaller step (when Shift is held)
     */
    private fun zoomFov(amount: Double, inward: Boolean, fine: Boolean) {
        val proj = camera.p
        val current = proj.fov

        // radians (JOML Math is double-based)
        val baseStep = Math.toRadians(5.0)                    // ~0.087 rad
        val step = if (fine) baseStep * 0.25 else baseStep    // fine → ~0.0217 rad

        val minFov = Math.toRadians(5.0)                      // 0.087 rad
        val maxFov = Math.toRadians(120.0)                    // 2.094 rad

        val dir = if (inward) -1.0 else 1.0
        val next = current + (dir * step * amount).toFloat()

        proj.fov = next.coerceIn(minFov, maxFov)
    }

    private fun resetToInitial() {
        camera.at.set(initialAt)
        camera.target.set(initialTarget)
        camera.up.set(initialUp)
        camera.p.fov = initialFov

        decay = initialDecay
        movementSpeed = initialMovementSpeed
        rotationSpeed = initialRotationSpeed
    }

    companion object {
        private const val DOUBLE_TAP_WINDOW_MS: Long = 500L
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
        val instance = KeyCamera(
            renderer = renderer,
            camera = camera,
            name = property.name + "__${box.id}"
        )
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
    renderer: Renderer = fromContext(),
    camera: Camera = fromContext()
): KeyCameraDelegateProvider = KeyCameraDelegateProvider(renderer, camera)
