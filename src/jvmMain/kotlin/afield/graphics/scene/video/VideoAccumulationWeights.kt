package afield.graphics.scene.video

import kotlin.math.pow

/** CPU scalar normalization keeps long exposures from overflowing or losing small weights in FP16. */
class VideoAccumulationWeights {
    var mass = 0.0
        private set

    fun clear() { mass = 0.0 }

    /** Returns the incoming sample's fraction of a decaying weighted mean. Time is wall-clock seconds. */
    fun add(elapsedSeconds: Double, halfLifeSeconds: Double, incomingWeight: Double): Float {
        require(elapsedSeconds.isFinite() && elapsedSeconds >= 0)
        require(halfLifeSeconds.isFinite() && halfLifeSeconds >= 0)
        require(incomingWeight.isFinite() && incomingWeight >= 0)
        val carried = if (halfLifeSeconds == 0.0) 0.0 else mass * .5.pow(elapsedSeconds / halfLifeSeconds)
        mass = carried + incomingWeight
        return if (mass > 0) (incomingWeight / mass).toFloat() else 0f
    }
}
