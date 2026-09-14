package afield.four.audio.steamaudio

/**
 * A measured set of quality settings, so the trade-offs do not have to be rediscovered.
 *
 * Every number in the presets below came from a measurement in this repository rather than from
 * a guess: `./gradlew binauralQuality` for the binaural settings, `./gradlew caveDemo` for the
 * per-block reflection cost, `./gradlew geometryBenchmark` for the geometry and simulation cost.
 *
 * ### Costs, measured
 *
 * Per-block cost of reflection plus binaural decode, one source, 512-sample blocks at 48 kHz,
 * 3-second reverb, on a 10-core Apple Silicon machine. The block budget is 10667 us.
 *
 * | configuration | median | p95 | % of budget | sources per core at p95 |
 * |---|---|---|---|---|
 * | `CONVOLUTION`, order 2 | 648 us | 1227 us | 11.5% | ~8 |
 * | `CONVOLUTION`, order 1 | 281 us | 559 us | 5.2% | ~19 |
 * | `PARAMETRIC`, order 1 | 67 us | 101 us | 0.9% | ~100 |
 *
 * Those are per *source*, and a [ReflectionMixer] lets many sources share one decode, so the
 * real ceiling is higher than the table suggests. `runReflections` is a separate budget on the
 * simulation thread: 10-15 ms at 4096 rays and 16 bounces, 32 ms at 8192/32, 54 ms at
 * 16384/32, and essentially independent of triangle count.
 *
 * ### Using a preset
 *
 * ```
 * val quality = QualityPreset.HIGHEST
 *
 * val simulator = Simulator(context, SimulationSettings(
 *     flags = SimulationFlags.DIRECT or SimulationFlags.REFLECTIONS,
 *     reflectionType = quality.reflectionType,
 *     maxOrder = quality.ambisonicOrder,
 *     maxNumRays = quality.numRays,
 *     maxDuration = quality.reverbDuration,
 *     samplingRate = audioSettings.samplingRate,
 *     frameSize = audioSettings.frameSize,
 * ))
 *
 * val reflection = ReflectionEffect(
 *     context, audioSettings,
 *     type = quality.reflectionType,
 *     duration = quality.reverbDuration,
 *     ambisonicOrder = quality.ambisonicOrder,
 * )
 *
 * val decode = AmbisonicsDecodeEffect(
 *     context, audioSettings, hrtf, maxOrder = quality.ambisonicOrder,
 * )
 *
 * val binauralParams = BinauralEffectParams(
 *     arena, hrtf, interpolation = quality.hrtfInterpolation,
 * )
 * ```
 */
public data class QualityPreset(
    /** Which reflection algorithm to render with. */
    public val reflectionType: ReflectionEffectType,
    /** Ambisonic order for the reflected and pathed buses. */
    public val ambisonicOrder: Int,
    /** HRTF interpolation for the direct path. */
    public val hrtfInterpolation: HrtfInterpolation,
    /** Reverb length to simulate and render, in seconds. */
    public val reverbDuration: Float,
    /** Rays cast per reflection simulation. */
    public val numRays: Int,
    /** Bounces per ray. */
    public val numBounces: Int,
    /** Diffuse samples per bounce. */
    public val numDiffuseSamples: Int,
    /** A one-line description, for logging which preset is active. */
    public val description: String,
) {
    /** Ambisonic channel count for [ambisonicOrder]. */
    public val ambisonicChannelCount: Int
        get() = SteamAudio.ambisonicChannelCount(ambisonicOrder)

    override fun toString(): String =
        "QualityPreset($description: $reflectionType, order $ambisonicOrder " +
            "($ambisonicChannelCount ch), ${reverbDuration}s, $numRays rays x $numBounces bounces)"

    public companion object {
        /**
         * Highest quality: full convolution at Ambisonic order 2.
         *
         * **Why convolution.** Measured, [ReflectionEffectType.CONVOLUTION] is both more
         * accurate *and cheaper* than [ReflectionEffectType.HYBRID] at every reverb duration
         * from 1 to 10 seconds -- hybrid pays for the convolution and the feedback network
         * both. [ReflectionEffectType.PARAMETRIC] is around five times cheaper again but loses
         * early-reflection detail and can glitch audibly on a foreground source, because its
         * network is re-driven by reverb times that step at the simulation rate instead of
         * cross-fading.
         *
         * **Why order 2, with a caveat.** Under convolution the whole impulse response stays in
         * the Ambisonic domain, and raising the order improves the reflected field's directional
         * spread consistently across every arrival window measured (+6% early, +21% mid, +49%
         * late, +17% tail). Be aware of how strong that evidence is: the improvement is
         * consistent in *sign* but comparable in magnitude to the run-to-run variance of a
         * stochastic ray tracer, and under `HYBRID` -- where the parametric tail cannot be
         * order-dependent -- order 1 and order 2 were not ABX-able. Order 2 also costs 2.3x per
         * block. It is the right choice when quality is the priority; it is not a free win.
         */
        public val HIGHEST: QualityPreset = QualityPreset(
            reflectionType = ReflectionEffectType.CONVOLUTION,
            ambisonicOrder = 2,
            hrtfInterpolation = HrtfInterpolation.BILINEAR,
            reverbDuration = 3.0f,
            numRays = 8192,
            numBounces = 32,
            numDiffuseSamples = 1024,
            description = "highest",
        )

        /**
         * Convolution at order 1: most of the quality for less than half the per-block cost.
         *
         * 5.2% of a 512-sample block budget against 11.5%. Order 1 was not ABX-able against
         * order 2 in a diffuse cave under hybrid reflections, so this is a defensible default
         * for a scene whose reverb is mostly diffuse.
         */
        public val BALANCED: QualityPreset = HIGHEST.copy(
            ambisonicOrder = 1,
            numRays = 4096,
            numBounces = 16,
            description = "balanced",
        )

        /**
         * Parametric reflections: for distant or background sources, and for platforms where
         * the per-block budget is tight.
         *
         * Around 0.9% of a block per source. Expect the early reflection detail to go, and
         * expect audible stepping if the source is in the foreground and moving.
         */
        public val ECONOMY: QualityPreset = QualityPreset(
            reflectionType = ReflectionEffectType.PARAMETRIC,
            ambisonicOrder = 1,
            hrtfInterpolation = HrtfInterpolation.BILINEAR,
            reverbDuration = 2.0f,
            numRays = 2048,
            numBounces = 16,
            numDiffuseSamples = 512,
            description = "economy",
        )
    }
}
