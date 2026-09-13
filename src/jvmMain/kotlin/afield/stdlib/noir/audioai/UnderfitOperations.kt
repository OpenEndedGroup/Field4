@file:afield.CompletionCategory("Audio AI")

package afield.stdlib.noir.audioai

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Stable Audio 3 checkpoint packs supported by the pinned Underfit worker. */
enum class UnderfitModel(val id: String, val maximumDurationSeconds: Double) {
    SMALL_MUSIC("small-music", 120.0),
    SMALL_MUSIC_BASE("small-music-base", 120.0),
    SMALL_SFX("small-sfx", 120.0),
    SMALL_SFX_BASE("small-sfx-base", 120.0),
    MEDIUM("medium", 380.0),
    MEDIUM_BASE("medium-base", 380.0),
}

/** Source-conditioned operation exposed by the Underfit worker. */
enum class UnderfitOperation(val wireName: String) {
    STYLE("generate.style"),
    VARIATIONS("generate.variations"),
}

/** One immutable Underfit LoRA as the worker sees it. */
data class UnderfitLoraCheckpoint(
    /** Path relative to `FIELD_AUDIO_UNDERFIT_LORA_ROOT` on the worker host. */
    val workerPath: String,
    /** Lowercase SHA-256 of the `.safetensors` file. */
    val sha256: String,
    /** LoRA contribution. Zero disables it without changing the request's checkpoint identity. */
    val strength: Double = 1.0,
    /** Lowest denoising sigma at which this LoRA is active. */
    val intervalMin: Double = 0.0,
    /** Highest denoising sigma at which this LoRA is active; lower values skip early structural steps. */
    val intervalMax: Double = 1.0,
    /** Optional Underfit layer-selection expression; blank applies the LoRA to every trained layer. */
    val layerFilter: String = "",
) {
    init {
        val path = Path.of(workerPath)
        require(workerPath.isNotBlank() && !path.isAbsolute) { "workerPath must be relative" }
        require(!path.normalize().startsWith("..")) { "workerPath must stay inside the worker LoRA root" }
        require(workerPath.endsWith(".safetensors", ignoreCase = true)) {
            "Underfit checkpoints must be .safetensors files"
        }
        require(SHA256.matches(sha256)) { "sha256 must contain exactly 64 lowercase hexadecimal digits" }
        require(strength.isFinite() && strength >= 0.0) { "strength must be non-negative and finite" }
        require(intervalMin.isFinite() && intervalMax.isFinite() &&
            intervalMin in 0.0..1.0 && intervalMax in intervalMin..1.0) {
            "LoRA interval must satisfy 0 <= intervalMin <= intervalMax <= 1"
        }
    }

    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")

        /**
         * Hash a local checkpoint and express it relative to the worker's configured LoRA root.
         * This assumes Field and the worker share the same filesystem, which is the ordinary local setup.
         */
        fun fromFile(
            file: Path,
            workerRoot: Path,
            strength: Double = 1.0,
            intervalMin: Double = 0.0,
            intervalMax: Double = 1.0,
            layerFilter: String = "",
        ): UnderfitLoraCheckpoint {
            val root = workerRoot.toAbsolutePath().normalize()
            val checkpoint = file.toAbsolutePath().normalize()
            require(Files.isRegularFile(checkpoint)) { "Underfit checkpoint does not exist: $checkpoint" }
            require(checkpoint.startsWith(root)) { "Underfit checkpoint must be inside $root" }
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(checkpoint).use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val relative = root.relativize(checkpoint).toString().replace('\\', '/')
            return UnderfitLoraCheckpoint(
                workerPath = relative,
                sha256 = digest.digest().joinToString("") { "%02x".format(it) },
                strength = strength,
                intervalMin = intervalMin,
                intervalMax = intervalMax,
                layerFilter = layerFilter,
            )
        }
    }
}

/** Stable Audio 3 sampling controls honored by the pinned Underfit worker. */
data class UnderfitGenerationControls(
    val steps: Int = 8,
    val cfg: Double = 1.0,
    val initNoiseLevel: Double = 0.65,
    val samplerType: String? = null,
    val cfgIntervalMin: Double = 0.0,
    val cfgIntervalMax: Double = 1.0,
    val cfgRescale: Double = 0.0,
    val cfgNormThreshold: Double = 0.0,
    val durationPaddingSeconds: Double = 6.0,
    val apgScale: Double = 1.0,
    val chunkedDecode: Boolean? = null,
) {
    init {
        require(steps in 1..1000) { "steps must be between 1 and 1000" }
        require(cfg.isFinite() && cfg >= 0.0) { "cfg must be non-negative and finite" }
        require(initNoiseLevel.isFinite() && initNoiseLevel in 0.0..1.0) {
            "initNoiseLevel must be between zero and one"
        }
        require(samplerType == null || samplerType.isNotBlank()) { "samplerType must not be blank" }
        require(cfgIntervalMin.isFinite() && cfgIntervalMax.isFinite() &&
            cfgIntervalMin in 0.0..1.0 && cfgIntervalMax in cfgIntervalMin..1.0) {
            "CFG interval must satisfy 0 <= cfgIntervalMin <= cfgIntervalMax <= 1"
        }
        require(cfgRescale.isFinite() && cfgRescale >= 0.0) {
            "cfgRescale must be non-negative and finite"
        }
        require(cfgNormThreshold.isFinite() && cfgNormThreshold >= 0.0) {
            "cfgNormThreshold must be non-negative and finite"
        }
        require(durationPaddingSeconds.isFinite() && durationPaddingSeconds >= 0.0) {
            "durationPaddingSeconds must be non-negative and finite"
        }
        require(apgScale.isFinite() && apgScale >= 0.0) { "apgScale must be non-negative and finite" }
    }
}

/**
 * Build a source-conditioned Stable Audio 3 request using base weights or checksummed Underfit LoRAs.
 *
 * The source region is sent as Stable Audio's init audio. [UnderfitGenerationControls.initNoiseLevel]
 * controls how far generation may move from it: zero preserves it and one approaches text-only noise.
 */
fun underfitCandidates(
    source: AssetRegion,
    prompt: String = "",
    negativePrompt: String? = null,
    durationSeconds: Double = source.durationSeconds,
    checkpoints: List<UnderfitLoraCheckpoint> = emptyList(),
    model: UnderfitModel = UnderfitModel.SMALL_MUSIC,
    operation: UnderfitOperation = UnderfitOperation.STYLE,
    seeds: LongRange = 0L until 8L,
    requestedBy: String? = null,
    controls: UnderfitGenerationControls = UnderfitGenerationControls(),
    forceRegenerate: Boolean = false,
): ModelOperation {
    require(source.durationSeconds > 0.0) { "source duration must be positive" }
    require(durationSeconds.isFinite() && durationSeconds > 0.0) { "durationSeconds must be positive and finite" }
    require(durationSeconds <= model.maximumDurationSeconds) {
        "${model.id} supports at most ${model.maximumDurationSeconds.toInt()} seconds"
    }
    val seedList = seeds.toList()
    require(seedList.isNotEmpty()) { "seeds must not be empty" }
    return ModelOperation(
        operation = operation.wireName,
        inputs = listOf(source),
        parameters = buildJsonObject {
            put("duration_seconds", durationSeconds)
            put("prompt", prompt)
            negativePrompt?.let { put("negative_prompt", it) }
            put("model_name", model.id)
            put("steps", controls.steps)
            put("cfg", controls.cfg)
            put("init_noise_level", controls.initNoiseLevel)
            controls.samplerType?.let { put("sampler_type", it) }
            put("cfg_interval_min", controls.cfgIntervalMin)
            put("cfg_interval_max", controls.cfgIntervalMax)
            put("cfg_rescale", controls.cfgRescale)
            put("cfg_norm_threshold", controls.cfgNormThreshold)
            put("duration_padding_seconds", controls.durationPaddingSeconds)
            put("apg_scale", controls.apgScale)
            controls.chunkedDecode?.let { put("chunked_decode", it) }
            putJsonArray("lora_checkpoints") {
                checkpoints.forEach { checkpoint ->
                    add(buildJsonObject {
                        put("path", checkpoint.workerPath)
                        put("sha256", checkpoint.sha256)
                        put("strength", checkpoint.strength)
                        put("interval_min", checkpoint.intervalMin)
                        put("interval_max", checkpoint.intervalMax)
                        if (checkpoint.layerFilter.isNotBlank()) {
                            put("layer_filter", checkpoint.layerFilter)
                        }
                    })
                }
            }
        },
        seeds = seedList,
        candidateCount = seedList.size,
        backendPreference = "underfit.stable_audio_3",
        requestedBy = requestedBy,
        forceRegenerate = forceRegenerate,
    )
}
