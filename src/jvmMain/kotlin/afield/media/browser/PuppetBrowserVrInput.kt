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
    val scrollX: Double = 0.0,
)

/**
 * Arbitrates two hands against Chromium's single mouse. A press owns the pointer until release,
 * including outside the panel; tracking loss cancels capture. Hover alone cannot steal a drag.
 */
class PuppetBrowserVrInput(val panel: PuppetBrowserPanel) {
    /** Resting cursor smoothing time in seconds; zero bypasses the filter. Large moves catch up faster. */
    var smoothingSeconds: Double = 0.0
        set(value) {
            require(value.isFinite() && value >= 0.0)
            field = value
        }
    internal var clockNanos: () -> Long = System::nanoTime
    private var filteredHit: PuppetBrowserPanel.Hit? = null
    private var filteredAt = 0L
    var activePointer: String? = null
        private set
    private var capturedPointer: String? = null
    private var previousPressed = emptySet<String>()
    private val pinching = mutableMapOf<String, Boolean>()

    fun update(pointers: List<BrowserVrPointer>, enabled: Boolean = true): PuppetBrowserPanel.Hit? {
        if (!enabled) {
            filteredHit = null
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
        if (selected?.id != activePointer) {
            panel.releasePointer()
            filteredHit = null
        }
        activePointer = selected?.id
        val hit = selected?.let {
            // Moving onto the panel with a button already held must not synthesize a new click.
            val down = it.pressed && (it.id == capturedPointer || it.id !in previousPressed)
            val target = hits[it.id]?.let(::smoothTarget)
            if (target == null) filteredHit = null
            panel.interactHit(target, down, it.scrollY, scrollX = it.scrollX)
        }
        if (selected == null) {
            panel.releasePointer()
            filteredHit = null
        }
        capturedPointer = if (panel.uv.buttons != 0) selected?.id else null
        previousPressed = pointers.filter { it.pressed }.map { it.id }.toSet()
        return hit
    }

    private fun smoothTarget(hit: PuppetBrowserPanel.Hit): PuppetBrowserPanel.Hit {
        val now = clockNanos()
        val previous = filteredHit
        val seconds = (now - filteredAt) / 1e9
        filteredAt = now
        val next = if (smoothingSeconds == 0.0 || previous == null || seconds <= 0.0 || seconds > 0.25) hit else {
            val du = hit.u - previous.u
            val dv = hit.v - previous.v
            val pixels = kotlin.math.hypot(du * panel.uv.cssWidth, dv * panel.uv.cssHeight)
            val tau = smoothingSeconds / (1.0 + pixels / 24.0)
            val alpha = -kotlin.math.expm1(-seconds / tau)
            val u = previous.u + du * alpha
            val v = previous.v + dv * alpha
            val world = panel.model.transformPosition(Vector3f(
                ((u - 0.5) * 2 * panel.halfWidth).toFloat(),
                ((0.5 - v) * 2 * panel.halfHeight).toFloat(), 0f))
            PuppetBrowserPanel.Hit(world, u, v, hit.onPanel)
        }
        filteredHit = next
        return next
    }

    fun cancel() {
        filteredHit = null
        panel.releasePointer()
        capturedPointer = null
        activePointer = null
        // A recenter/cancel must not turn a still-held trigger into a fresh click.
        pinching.clear()
    }

    /** Prefer the runtime's aim pose. Invalid poses never fall back to an untracked mouse. */
    fun controller(id: String, state: ControllerState, elapsedSeconds: Double): BrowserVrPointer? {
        if (!state.active || !state.poseValid) return null
        fun axis(stick: Float, pad: Float): Double {
            val raw = if (kotlin.math.abs(stick) > 0.12f) stick else pad
            val magnitude = ((kotlin.math.abs(raw) - 0.12) / 0.88).coerceIn(0.0, 1.0)
            return kotlin.math.sign(raw.toDouble()) * magnitude * magnitude
        }
        val seconds = elapsedSeconds.coerceIn(0.0, 0.05)
        val (origin, direction) = PuppetBrowserPanel.controllerRay(state, Vector3f(0f, 0f, -1f))
        return BrowserVrPointer(id, origin, direction, state.selectPressed || state.triggerPressed || state.aPressed,
            -axis(state.thumbstickY, state.trackpadY) * 1200.0 * seconds,
            gripPressed = state.squeezePressed, orientation = Quaternionf(state.rotation),
            scrollX = axis(state.thumbstickX, state.trackpadX) * 1200.0 * seconds)
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

/** One grip moves the panel; two grips resize around its centre and translate with the hands. */
class PuppetBrowserVrGrab(
    private val panel: PuppetBrowserPanel,
    private val minimumWidthMeters: Float = 0.4f,
    private val maximumWidthMeters: Float = 8f,
) {
    var owner: String? = null
        private set
    private val offset = Matrix4f()
    private var previousGrips = emptySet<String>()
    private data class Resize(
        val first: String, val second: String, val midpoint: Vector3f,
        val distance: Float, val width: Float, val model: Matrix4f,
    )
    private var resize: Resize? = null

    init {
        require(minimumWidthMeters > 0f && maximumWidthMeters >= minimumWidthMeters)
    }

    fun update(pointers: List<BrowserVrPointer>, enabled: Boolean = true): Boolean {
        if (!enabled) {
            owner = null
            resize = null
            previousGrips = pointers.filter { it.gripPressed }.map { it.id }.toSet()
            return false
        }
        resize?.let { gesture ->
            val first = pointers.firstOrNull { it.id == gesture.first && it.gripPressed }
            val second = pointers.firstOrNull { it.id == gesture.second && it.gripPressed }
            if (first != null && second != null) {
                val midpoint = Vector3f(first.origin).add(second.origin).mul(0.5f)
                val scale = (first.origin.distance(second.origin) / gesture.distance)
                    .coerceIn(minimumWidthMeters / gesture.width, maximumWidthMeters / gesture.width)
                val centre = gesture.model.getTranslation(Vector3f())
                val translated = Vector3f(midpoint).sub(gesture.midpoint).add(centre)
                panel.model.identity().translate(translated.x, translated.y, translated.z).scale(scale)
                    .translate(-centre.x, -centre.y, -centre.z).mul(gesture.model)
            } else {
                // Releasing either hand resumes a one-handed grab without jumping. Tracking
                // loss follows the same rule; with neither hand tracked the panel stays put.
                resize = null
                val remaining = first ?: second
                owner = remaining?.id
                if (remaining != null) offset.set(pose(remaining).invert().mul(panel.model))
            }
            previousGrips = pointers.filter { it.gripPressed }.map { it.id }.toSet()
            return owner != null
        }
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
        val first = pointers.firstOrNull { it.id == owner && it.gripPressed }
        val second = pointers.firstOrNull {
            it.id != owner && it.gripPressed && it.id !in previousGrips &&
                panel.raycast(it.origin, it.direction)?.onPanel == true
        }
        if (first != null && second != null && first.origin.distance(second.origin) >= 0.08f) {
            resize = Resize(first.id, second.id, Vector3f(first.origin).add(second.origin).mul(0.5f),
                first.origin.distance(second.origin),
                panel.model.transformDirection(Vector3f(panel.halfWidth * 2, 0f, 0f)).length(),
                Matrix4f(panel.model))
        }
        previousGrips = pointers.filter { it.gripPressed }.map { it.id }.toSet()
        return owner != null
    }

    fun cancel() {
        owner = null
        resize = null
        previousGrips = emptySet()
    }

    private fun pose(pointer: BrowserVrPointer): Matrix4f =
        Matrix4f().translate(pointer.origin.x, pointer.origin.y, pointer.origin.z).rotate(pointer.orientation)
}
