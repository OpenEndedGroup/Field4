package afield.media.browser

import afield.graphics.vulkan.targets.OpenXRHmdTarget.ControllerState
import afield.graphics.vulkan.targets.OpenXRHmdTarget.HandJoint
import afield.graphics.vulkan.targets.OpenXRHmdTarget.HandTrackingState
import afield.math.linalg.Vector3f
import afield.math.linalg.Quaternionf
import afield.math.linalg.Matrix4f

/** One already tracked ray. OpenXR aim poses point along local -Z. */
data class BrowserVrPointer(
    val id: String,
    val origin: Vector3f,
    val direction: Vector3f,
    val pressed: Boolean,
    val scrollY: Double = 0.0,
    val gripPressed: Boolean = false,
    val orientation: Quaternionf = Quaternionf(),
)

/**
 * Arbitrates two hands against Chromium's single mouse. A press owns the pointer until release,
 * including outside the panel; tracking loss cancels capture. Hover alone cannot steal a drag.
 */
class PuppetBrowserVrInput(val panel: PuppetBrowserPanel) {
    var activePointer: String? = null
        private set
    private var capturedPointer: String? = null
    private var previousPressed = emptySet<String>()
    private val pinching = mutableMapOf<String, Boolean>()

    fun update(pointers: List<BrowserVrPointer>, enabled: Boolean = true): PuppetBrowserPanel.Hit? {
        if (!enabled) {
            panel.releasePointer()
            capturedPointer = null
            activePointer = null
            previousPressed = pointers.filter { it.pressed }.map { it.id }.toSet()
            return null
        }
        val captured = capturedPointer
        if (captured != null && pointers.none { it.id == captured }) {
            panel.releasePointer()
            capturedPointer = null
            activePointer = null
        }
        val hits = pointers.associate { it.id to panel.raycast(it.origin, it.direction) }
        val freshPress = pointers.firstOrNull {
            it.pressed && it.id !in previousPressed && hits[it.id]?.onPanel == true
        }
        val selected = pointers.firstOrNull { it.id == capturedPointer }
            ?: freshPress
            ?: pointers.firstOrNull { it.id == activePointer && hits[it.id]?.onPanel == true }
            ?: pointers.firstOrNull { hits[it.id]?.onPanel == true }
        if (selected?.id != activePointer) panel.releasePointer()
        activePointer = selected?.id
        val hit = selected?.let {
            // Moving onto the panel with a button already held must not synthesize a new click.
            val down = it.pressed && (it.id == capturedPointer || it.id !in previousPressed)
            panel.interact(it.origin, it.direction, down, it.scrollY)
        }
        if (selected == null) panel.releasePointer()
        capturedPointer = if (panel.uv.buttons != 0) selected?.id else null
        previousPressed = pointers.filter { it.pressed }.map { it.id }.toSet()
        return hit
    }

    fun cancel() {
        panel.releasePointer()
        capturedPointer = null
        activePointer = null
        previousPressed = emptySet()
        pinching.clear()
    }

    /** Prefer the runtime's aim pose. Invalid poses never fall back to an untracked mouse. */
    fun controller(id: String, state: ControllerState, elapsedSeconds: Double): BrowserVrPointer? {
        if (!state.active || !state.poseValid) return null
        val axis = when {
            kotlin.math.abs(state.thumbstickY) > 0.12f -> state.thumbstickY
            kotlin.math.abs(state.trackpadY) > 0.12f -> state.trackpadY
            else -> 0f
        }
        val (origin, direction) = PuppetBrowserPanel.controllerRay(state, Vector3f(0f, 0f, -1f))
        return BrowserVrPointer(id, origin, direction, state.selectPressed || state.triggerPressed,
            -axis * 1200.0 * elapsedSeconds.coerceIn(0.0, 0.05),
            gripPressed = state.squeezePressed, orientation = Quaternionf(state.rotation))
    }

    /** Joint-based fallback when the runtime supplies hands without a controller aim action. */
    fun hand(id: String, state: HandTrackingState?): BrowserVrPointer? {
        val wrist = state?.get(HandJoint.WRIST)
        val knuckle = state?.get(HandJoint.INDEX_PROXIMAL)
        val thumb = state?.get(HandJoint.THUMB_TIP)
        val index = state?.get(HandJoint.INDEX_TIP)
        if (state?.isActive != true || wrist?.positionValid != true || knuckle?.positionValid != true ||
            thumb?.positionValid != true || index?.positionValid != true) {
            pinching.remove(id)
            return null
        }
        val direction = Vector3f(knuckle.position).sub(wrist.position)
        if (direction.lengthSquared() < 1e-8f) return null
        val down = state.pinchStrength >= if (pinching[id] == true) 0.55f else 0.8f
        pinching[id] = down
        return BrowserVrPointer(id, Vector3f(knuckle.position), direction.normalize(), down)
    }
}

/** Grip attaches the panel rigidly to the grabbing aim pose, preserving its initial offset. */
class PuppetBrowserVrGrab(private val panel: PuppetBrowserPanel) {
    var owner: String? = null
        private set
    private val offset = Matrix4f()
    private var previousGrips = emptySet<String>()

    fun update(pointers: List<BrowserVrPointer>): Boolean {
        val held = pointers.firstOrNull { it.id == owner && it.gripPressed }
        if (held != null) {
            panel.model.set(pose(held).mul(offset))
        } else {
            owner = null
            val start = pointers.firstOrNull {
                it.gripPressed && it.id !in previousGrips && panel.raycast(it.origin, it.direction)?.onPanel == true
            }
            if (start != null) {
                owner = start.id
                offset.set(pose(start).invert().mul(panel.model))
            }
        }
        previousGrips = pointers.filter { it.gripPressed }.map { it.id }.toSet()
        return owner != null
    }

    fun cancel() {
        owner = null
        previousGrips = emptySet()
    }

    private fun pose(pointer: BrowserVrPointer): Matrix4f =
        Matrix4f().translate(pointer.origin.x, pointer.origin.y, pointer.origin.z).rotate(pointer.orientation)
}
