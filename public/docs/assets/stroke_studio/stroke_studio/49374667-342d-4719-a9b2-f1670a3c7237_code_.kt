import afield.math.linalg.Vector4f

// Dimensions are allocation-time values. Start a fresh renderer after changing them.
data class StrokeResolution(
    val analysisWidth: Int = 1792,
    val analysisHeight: Int = 1120,
    val paintWidth: Int = 3840,
    val paintHeight: Int = 2400,
) {
    init {
        require(minOf(analysisWidth, analysisHeight, paintWidth, paintHeight) > 0)
        require(analysisWidth.toLong() * paintHeight == paintWidth.toLong() * analysisHeight)
    }
}

data class StrokeFrame(
    val camera: Vector4f, // eye xyz, vertical half-FOV tangent
    val time: Float,
    val deltaSeconds: Float,
    val resolution: StrokeResolution,
)

data class StrokeSettings(
    val view: Int = 0,
    val sceneSpeed: Float = 0.42f,
    val orbitSpeed: Float = 0.055f,
    val length: Float = 1.0f,
    val width: Float = 1.0f,
    val scale: Float = 1.2f,
    val attack: Float = 0.18f,
    val release: Float = 8.0f,
    val interruptRelease: Float = 0.85f,
    val rate: Float = 3.4f,
    val frontGuide: Float = 0.74f,
    val spill: Float = 0.92f,
    val percentile: Float = 0.73f,
    val feedback: Float = 1.0f,
    val edgeLitMix: Float = 0.18f,
    val pigmentFraction: Float = 0.55f,
    val residual: Float = 0.032f,
    val weight: Float = 1.0f,
    val displacement: Float = 0.0f,
    val velocityMix: Float = 0.0f,
    val varianceTarget: Float = 0.0f,
    val varianceThickness: Float = 0.0f,
    val varianceReference: Float = 0.015f,
    val greenBlur: Float = 0.42f,
    val greenGain: Float = 4.0f,
    val greenReconstruct: Float = 0.28f,
    val greenBlue: Float = 0.0f,
    val paintMix: Float = 0.46f,
    val paintCrossover: Float = 0.48f,
)
