package afield.graphics.scene.video

import kotlin.math.floor
import kotlin.math.roundToLong

/** Shared display-time quantizer for rotation and shutters. All state advances on the graphics queue. */
class VideoCarouselClock(val screens: Int) {
    init { require(screens > 0) }

    data class Motion(
        val turnsPerSecond: Double = 0.0,
        val stepped: Boolean = false,
        val stepFrames: Int = 10,
        val sectorLock: Boolean = false,
        val sectorsPerStep: Int = 1,
        val driftTurnsPerSecond: Double = 0.0,
    ) {
        init {
            require(turnsPerSecond.isFinite() && driftTurnsPerSecond.isFinite())
            require(stepFrames in 1..240 && sectorsPerStep in -32..32)
        }
    }

    /** Two linear-light levels, with independent whole-refresh durations and a signed phase offset. */
    data class Flash(val enabled: Boolean = false, val onFrames: Int = 1, val offFrames: Int = 9,
                     val phaseFrames: Int = 0, val onBrightness: Float = 1f, val offBrightness: Float = 0f) {
        init {
            require(onFrames in 0..2400 && offFrames in 0..2400 && onFrames + offFrames > 0)
            require(phaseFrames in -2400..2400)
            require(listOf(onBrightness, offBrightness).all { it.isFinite() && it in 0f..16f })
        }
        val everyFrames get() = onFrames + offFrames
        fun brightness(tick: Long, extraPhase: Long = 0L): Float {
            if (!enabled) return 1f
            return if (Math.floorMod(tick - phaseFrames - extraPhase, everyFrames.toLong()) < onFrames)
                onBrightness else offBrightness
        }
        fun visible(tick: Long): Boolean = brightness(tick) > 0f
    }

    @Volatile var motion = Motion()
    @Volatile var flashes: List<Flash> = List(screens) { Flash() }
        set(value) { require(value.size == screens); field = value.toList() }
    /** Whole headset ticks of additional phase per screen, independent of video delay spacing. */
    @Volatile var flashStaggerFrames = 0
        set(value) { require(value in -2400..2400); field = value }
    @Volatile var tick = 0L
        private set
    @Volatile var refreshHz = 0.0
        private set
    @Volatile var angleDegrees = 0.0
        private set
    private var previousTime = 0L
    private var previousMotion: Motion? = null
    private var previousStep = 0L
    private var phaseTurns = 0.0

    /** Anchor to the existing layout without changing the shared flash phase. Queue thread only. */
    fun setAngle(degrees: Double) {
        require(degrees.isFinite())
        phaseTurns = degrees / 360.0
        angleDegrees = degrees
    }

    /** Repeated renders of one prediction do not advance; missed refreshes advance by their full count. */
    fun advance(displayTime: Long, displayPeriod: Long): Double {
        require(displayTime > 0 && displayPeriod > 0)
        refreshHz = 1e9 / displayPeriod
        val dt = if (previousTime == 0L) 0.0 else ((displayTime - previousTime).coerceAtLeast(0L) / 1e9)
        if (previousTime != 0L) tick += ((displayTime - previousTime).coerceAtLeast(0L).toDouble() / displayPeriod).roundToLong()
        previousTime = displayTime
        val m = motion
        if (m != previousMotion) {
            // Rebase the rotation step grid onto the common shutter grid; parameter edits retain angle.
            previousStep = tick / m.stepFrames
            previousMotion = m
        }
        if (m.stepped || m.sectorLock) {
            val step = tick / m.stepFrames
            val delta = step - previousStep
            val turnsPerStep = if (m.sectorLock) m.sectorsPerStep.toDouble() / screens
                else m.turnsPerSecond * m.stepFrames * displayPeriod / 1e9
            phaseTurns += delta * turnsPerStep
            previousStep = step
        } else phaseTurns += m.turnsPerSecond * dt
        if (m.sectorLock) phaseTurns += m.driftTurnsPerSecond * dt
        phaseTurns -= floor(phaseTurns)
        angleDegrees = phaseTurns * 360.0
        return angleDegrees
    }

    /** A frame shutter; no decoder state, video timestamp or Runloop frame count enters this decision. */
    fun brightness(screen: Int): Float = flashes[screen].brightness(tick, screen.toLong() * flashStaggerFrames)
    fun visible(screen: Int): Boolean = brightness(screen) > 0f
}
