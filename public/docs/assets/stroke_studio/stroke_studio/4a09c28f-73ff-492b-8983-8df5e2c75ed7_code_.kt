// Connect a new box here to inherit the renderer, textures, frame data and controls.
import java.nio.ByteBuffer
import java.nio.ByteOrder

val value = strokeSummary.getValue("stroke-summary-value-0")
val extent = strokeSummary.getValue("stroke-summary-extent-0")
val size = strokeResolution
val settings = strokeSettings.get()

frames {
    val meanVariance = value.readPixelsAsync()
    val extrema = extent.readPixelsAsync()
    val v = ByteBuffer.wrap(meanVariance.await()).order(ByteOrder.LITTLE_ENDIAN)
    val e = ByteBuffer.wrap(extrema.await()).order(ByteOrder.LITTLE_ENDIAN)
    markdown(
        "GPU summary",
        """
        Analysis **${size.analysisWidth} × ${size.analysisHeight}**
        Paint **${size.paintWidth} × ${size.paintHeight}**
        Velocity mix **${settings.velocityMix}**

        | Luminance | Value |
        | --- | --- |
        | Min | ${e.getFloat(4).f6} |
        | Mean | ${v.getFloat(4).f6} |
        | Max | ${e.getFloat(8).f6} |
        | Variance | ${v.getFloat(8).f6} |""".trimIndent(),
    )
    displayOpen("GPU summary")
}
