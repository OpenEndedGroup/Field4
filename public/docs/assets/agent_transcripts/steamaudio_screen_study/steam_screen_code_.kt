import afield.audio.spatial.steamaudio.Material
import afield.examples.steamaudio.SteamAudioScreenStudy
import java.util.Locale

val scene = SteamAudioScreenStudy
val listener = scene.listenerPosition
val source = scene.sourcePosition
val material = Material.WOOD
val metre = 80.0
val player = AudioVisualizerOptions(width = 500, waveformHeight = 65)

fun draw(offset: Float) {
    val take = scene.render(offset, material)
    val centre = (listener.x + source.x) / 2f + offset
    val z = scene.SCREEN_DEPTH * metre
    val transmission = take.transmission.joinToString(" / ") { "%.3f".format(Locale.ROOT, it) }
    val ray = fline {
        moveTo(listener.x * metre, listener.z * metre)
        lineTo(source.x * metre, source.z * metre)
        color = if (take.occlusion > 0.5f) vec(0.32, 0.82, 0.75, 1.0) else vec(0.93, 0.64, 0.30, 1.0)
        thickness = 2.0
    }
    val screen = fline {
        moveTo((centre - scene.SCREEN_HALF_WIDTH) * metre, z)
        lineTo((centre + scene.SCREEN_HALF_WIDTH) * metre, z)
        color = vec(0.68, 0.73, 0.84, 1.0)
        thickness = 7.0
    }
    val markers =
        [listener, source].map { point ->
            fline {
                circle(point.x * metre, point.z * metre, 7.0)
                filled = true
                color = vec(0.83, 0.90, 1.0, 1.0)
            }
        }
    lines(
        [ray, screen] + markers,
        w = 420.0,
        h = 245.0,
        fitToSize = true,
        framing = LinesFraming.Fixed(-220.0, -265.0, 575.0, 290.0),
    )
    val visibility = "%.2f".format(Locale.ROOT, take.occlusion)
    markdown("direct path", "**Occlusion $visibility** · transmission low / mid / high: $transmission", width = 500)
    displayAudioBytes(take.wav(), channel = "screen take", label = "Current screen position", options = player)
    println("Screen take ready: occlusion $visibility")
}

displayAudioBytes(
    scene.render(scene.CLEAR_OFFSET, material).wav(),
    channel = "clear reference",
    label = "Clear path",
    options = player,
)
slider("Screen offset (m)", min = -2.4, max = 2.4, step = 0.05, initialValue = 0.0) {
    draw(it.toFloat())
}
draw(0f)
displayOpen("controls")
displayOpen("lines")
displayClose("clear reference")
displayClose("screen take")
