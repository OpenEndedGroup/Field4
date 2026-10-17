package afield.media.video.diffusion

import org.json.JSONObject
import java.io.File

/** Optional installed backend. No Python/CUDA dependency is loaded by ordinary Field startup. */
data class StreamDiffusionConfig(
    val root: File,
    val python: File = File(root, ".venv/bin/python"),
    val worker: File = File("optional/streamdiffusion/worker.py"),
    val prompt: String = StreamDiffusionPresets.INK,
    val strength: Double = 0.60,
    val seed: Int = 0,
    val fps: Int = 16,
    val useTensorRT: Boolean = true,
    val offloadTextEncoder: Boolean = true,
    /** A GPU-only identity/invert mode useful when composing a new RTT graph. */
    val backend: String = "diffusion",
    val startupTimeoutSeconds: Long = 240,
    val displayReprojection: Boolean = false,
    val historyBlend: Double = 0.0,
    val noiseAdvection: Double = 0.0
) {
    init {
        require(strength in 0.2..0.9 && strength.isFinite())
        require(fps in 1..60 && startupTimeoutSeconds > 0)
        require(backend in setOf("diffusion", "identity", "invert"))
        require(prompt.isNotBlank())
        require(historyBlend.isFinite() && historyBlend in 0.0..1.0)
        require(noiseAdvection.isFinite() && noiseAdvection in 0.0..1.0)
    }
    internal fun settings() = JSONObject().put("prompt", prompt).put("strength", strength)
        .put("seed", seed).put("use_tensorrt", useTensorRT).put("offload_text_encoder", offloadTextEncoder)
        .put("backend", backend).put("noise_advection", noiseAdvection)
}

/** Presets match the expressive study; edit the prompt/strength live through view.reset(...). */
object StreamDiffusionPresets {
    const val FINE_INK = "A faithful black ink sketch of these exact rotating lines and curves on pale paper. Each input line becomes a fine ink stroke with subtle hand drawn texture. Preserve the exact positions, silhouettes, crossings, curve shapes, and motion of every line. Clean pale background, no extra objects or lines."
    const val FINE_CHARCOAL = "A faithful charcoal drawing of these exact rotating lines and curves on pale paper. Each input line becomes a narrow rough black charcoal stroke. Preserve the exact positions, silhouettes, crossings, curve shapes, and motion of every line. Clean pale background, restrained charcoal grain, no extra objects or lines."
    const val WIRE = "Thin realistic dark steel barbed wire precisely following these exact rotating lines and curves against a plain pale background. Small sharp barbs spaced along each wire. Preserve the exact wire centerlines, positions, silhouettes, crossings, curve shapes, and motion of every input line. No extra wires or objects."
    const val INK = "An expressive black India ink drawing of the rotating lines and curves on pale paper. Wet black ink strokes with ragged brush edges, bold pools of ink, explosive ink splatter, scattered droplets, spatters and bleeding feathered edges around the curves. Energetic gestural ink marks. Keep the input curves as the dominant composition with their positions, crossings and motion recognizable. Abstract ink mark making, no objects or text."
    const val CHARCOAL = "An expressive charcoal drawing of the rotating lines and curves on pale textured paper. Thick velvety black charcoal strokes with crumbly edges, loose charcoal dust, broad smoky smudges and finger-blended shading around the strokes. Rich granular charcoal texture, scattered charcoal crumbs. Keep the input curves as the dominant composition with their positions, crossings and motion recognizable. Abstract charcoal mark making, no objects or text."
    const val WHITE_WIRE = "Thin realistic white-painted steel barbed wire precisely following these exact rotating lines and curves against a plain matte black background. Small sharp white barbs spaced along each white wire. Preserve the exact wire centerlines, positions, silhouettes, crossings, curve shapes, and motion of every input line. No extra wires or objects."
    const val WHITE_INK = "An expressive opaque white ink drawing of the rotating lines and curves on matte black paper. Wet white ink strokes with ragged brush edges, bold pools of white ink, explosive white ink splatter, scattered white droplets, spatters and bleeding feathered edges around the curves. Energetic gestural white ink marks. Keep the input curves as the dominant composition with their positions, crossings and motion recognizable. Abstract white ink mark making on black paper, no objects or text."
    const val WHITE_CHARCOAL = "An expressive white charcoal drawing of the rotating lines and curves on matte black textured paper. Thick velvety white charcoal strokes with crumbly edges, loose white charcoal dust, broad smoky white smudges and finger-blended white shading around the strokes. Rich granular white charcoal texture, scattered white charcoal crumbs. Keep the input curves as the dominant composition with their positions, crossings and motion recognizable. Abstract white charcoal mark making on black paper, no objects or text."
}
