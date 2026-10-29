package afield.media.video.diffusion

import org.json.JSONObject
import java.io.File

/**
 * Settings for the optional streaming-diffusion backend: where it is installed, the prompt and
 * strength, the seed, frame rate and the temporal options.
 *
 * [StreamDiffusionConfig] validates its ranges on construction and is handed to a
 * [StreamDiffusionView]. [StreamDiffusionPresets] holds named prompts (ink, charcoal, wire, and white-on-black variants);
 * change prompt, strength, seed or noise advection on a running view with `view.reset(...)`.
 */

/** Optional installed backend. No Python/CUDA dependency is loaded by ordinary Field startup. */
data class StreamDiffusionConfig(
    /** The StreamDiffusionV2 installation, holding the `configs`, `ckpts` and `wan_models` it needs. */
    val root: File,
    /** The interpreter the worker runs in; it needs the backend's own Torch and CUDA environment. */
    val python: File = File(root, ".venv/bin/python"),
    /** Field's worker script, by default relative to the directory Field itself was started in. */
    val worker: File = File("optional/streamdiffusion/worker.py"),
    /** What the model is asked to draw. [StreamDiffusionPresets] holds the ones the study used. */
    val prompt: String = StreamDiffusionPresets.INK,
    /** How far the result may leave the input, 0.2 to 0.9; it sets the worker's noise scale and its
     * denoising steps, so low values keep the drawing and high ones redraw it. */
    val strength: Double = 0.60,
    /** The worker's sampling seed, applied when it starts and again on every reset. */
    val seed: Int = 0,
    /** Frames per second to capture and to present, 1 to 60, whatever rate the renderer runs at. */
    val fps: Int = 16,
    /** Asks for TensorRT. Whether one was built is [StreamDiffusionSession.tensorRtActive]. */
    val useTensorRT: Boolean = true,
    /** Moves the text encoder to the CPU once the prompt is encoded, freeing its GPU memory; later
     * prompt changes are then encoded there. */
    val offloadTextEncoder: Boolean = true,
    /** A GPU-only identity/invert mode useful when composing a new RTT graph. */
    val backend: String = "diffusion",
    /** How long the session waits for the worker to load its weights and report ready. */
    val startupTimeoutSeconds: Long = 240,
    /** The view's starting [StreamDiffusionView.displayReprojection], which needs a motion provider. */
    val displayReprojection: Boolean = false,
    /** The view's starting [StreamDiffusionView.historyBlend], which needs a motion provider. */
    val historyBlend: Double = 0.0,
    /** How much of the latent noise the worker carries along the motion field, 0 to 1; with no
     * correspondence for a frame it draws fresh noise instead. */
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
    /** One fine ink stroke per input line, on pale paper: the closest of these to the source. */
    const val FINE_INK = "A faithful black ink sketch of these exact rotating lines and curves on pale paper. Each input line becomes a fine ink stroke with subtle hand drawn texture. Preserve the exact positions, silhouettes, crossings, curve shapes, and motion of every line. Clean pale background, no extra objects or lines."
    /** The same restraint in charcoal: a narrow rough stroke per line, pale paper, little grain. */
    const val FINE_CHARCOAL = "A faithful charcoal drawing of these exact rotating lines and curves on pale paper. Each input line becomes a narrow rough black charcoal stroke. Preserve the exact positions, silhouettes, crossings, curve shapes, and motion of every line. Clean pale background, restrained charcoal grain, no extra objects or lines."
    /** Barbed steel wire following each line, barbs along it, pale background. */
    const val WIRE = "Thin realistic dark steel barbed wire precisely following these exact rotating lines and curves against a plain pale background. Small sharp barbs spaced along each wire. Preserve the exact wire centerlines, positions, silhouettes, crossings, curve shapes, and motion of every input line. No extra wires or objects."
    /** The default: wet India ink, with pools, splatter and bleeding edges around the curves. */
    const val INK = "An expressive black India ink drawing of the rotating lines and curves on pale paper. Wet black ink strokes with ragged brush edges, bold pools of ink, explosive ink splatter, scattered droplets, spatters and bleeding feathered edges around the curves. Energetic gestural ink marks. Keep the input curves as the dominant composition with their positions, crossings and motion recognizable. Abstract ink mark making, no objects or text."
    /** Thick velvety charcoal: crumbly edges, loose dust and blended smudges around the strokes. */
    const val CHARCOAL = "An expressive charcoal drawing of the rotating lines and curves on pale textured paper. Thick velvety black charcoal strokes with crumbly edges, loose charcoal dust, broad smoky smudges and finger-blended shading around the strokes. Rich granular charcoal texture, scattered charcoal crumbs. Keep the input curves as the dominant composition with their positions, crossings and motion recognizable. Abstract charcoal mark making, no objects or text."
    /** The wire prompt for a dark source: white-painted wire and barbs on matte black. */
    const val WHITE_WIRE = "Thin realistic white-painted steel barbed wire precisely following these exact rotating lines and curves against a plain matte black background. Small sharp white barbs spaced along each white wire. Preserve the exact wire centerlines, positions, silhouettes, crossings, curve shapes, and motion of every input line. No extra wires or objects."
    /** Opaque white ink on black paper, for white lines on a dark source. */
    const val WHITE_INK = "An expressive opaque white ink drawing of the rotating lines and curves on matte black paper. Wet white ink strokes with ragged brush edges, bold pools of white ink, explosive white ink splatter, scattered white droplets, spatters and bleeding feathered edges around the curves. Energetic gestural white ink marks. Keep the input curves as the dominant composition with their positions, crossings and motion recognizable. Abstract white ink mark making on black paper, no objects or text."
    /** White charcoal on black paper, the dark-source companion to the charcoal prompt. */
    const val WHITE_CHARCOAL = "An expressive white charcoal drawing of the rotating lines and curves on matte black textured paper. Thick velvety white charcoal strokes with crumbly edges, loose white charcoal dust, broad smoky white smudges and finger-blended white shading around the strokes. Rich granular white charcoal texture, scattered white charcoal crumbs. Keep the input curves as the dominant composition with their positions, crossings and motion recognizable. Abstract white charcoal mark making on black paper, no objects or text."
}
