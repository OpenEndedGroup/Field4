package afield.graphics.scene.video

import afield.animation.keyframes.KeyframeScope
import java.nio.file.Files

/** A bounded, wall-time wave of look recalls. New recalls replace pending work; playback never waits. */
class StereoVideoLookRecall(
    private val pool: () -> StereoVideoLookPool,
    private val speed: (Double) -> Unit,
    private val now: () -> Long = System::nanoTime,
) {
    private data class Step(val at: Long, val screen: Int, val key: KeyframeScope.Key, val owner: StereoVideoLookPool)
    private val pending = ArrayDeque<Step>()
    var staggerSeconds = 0.0
        set(value) { require(value.isFinite() && value in 0.0..30.0); field = value }
    val pendingCount get() = pending.size
    var status = "Ready"
        private set
    private var activeName = ""

    /** Selected screens are sorted in ring order; shared speed changes once, at the start. */
    fun recall(name: String, screens: Collection<Int> = pool().targetScreens,
               withSpeed: Boolean = pool().restoreSpeed, delaySeconds: Double = staggerSeconds): Boolean {
        require(delaySeconds.isFinite() && delaySeconds in 0.0..30.0)
        val owner = pool()
        val path = owner.path(name)
        if (!Files.isRegularFile(path)) { status = "No saved look: ${name.trim()}"; return false }
        require(screens.isNotEmpty() || withSpeed) { "Choose target screens, or enable playback speed recall" }
        val key = owner.scope.loadKey(path) // one immutable snapshot for the whole wave
        val savedSpeed = if (withSpeed) (key["playback.speed"] as? Number)?.toDouble()
            ?: error("This key has no saved playback speed") else null
        owner.targetScreens = screens.toSet()
        owner.restoreSpeed = withSpeed
        owner.selectedName = name.trim()
        pending.clear()
        activeName = name.trim()
        if (savedSpeed != null) speed(savedSpeed)
        val started = now()
        screens.distinct().sorted().forEachIndexed { i, screen ->
            pending.addLast(Step(started + (i * delaySeconds * 1e9).toLong(), screen, key, owner))
        }
        status = "Recalled $activeName; playback speed ${if (withSpeed) "restored" else "kept"}"
        tick()
        return true
    }

    /** Run at display/runloop cadence. At most one pending event per selected screen exists. */
    fun tick() {
        val time = now()
        while (pending.firstOrNull()?.let { it.at <= time } == true) {
            val step = pending.removeFirst()
            if (pool() !== step.owner) { cancel(); return }
            val targets = step.owner.targetScreens
            val speedOption = step.owner.restoreSpeed
            try {
                step.owner.targetScreens = setOf(step.screen)
                step.owner.restoreSpeed = false // a wave has one global speed change, never one per screen
                step.key()
                status = "$activeName → screen ${step.screen + 1}; ${pending.size} pending"
            } catch (failure: Exception) {
                pending.clear()
                status = "Recall stopped: ${failure.message ?: failure.javaClass.simpleName}"
            } finally {
                step.owner.targetScreens = targets
                step.owner.restoreSpeed = speedOption
            }
        }
    }

    fun cancel() {
        pending.clear()
        status = "Pending recall cancelled; already-applied screens retain their look"
    }
}
