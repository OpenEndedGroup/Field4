package afield.four.fabricated

import afield.stdlib.linalg.Vector2f
import org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT

/**
 * Worked multi-resolution passes, as lists of [FabricatePassSpec] ready to hand to a chain.
 *
 * Six recipes and two chains assembled from them: a downsample pyramid, a separable Gaussian blur, a
 * disc aperture blur, an upsample-and-add, a bright pass and a composite, then
 * [FabricatePassLibrary.apertureChain] and [FabricatePassLibrary.bloomPyramid]. Every one returns
 * specs rather than touching the GPU, so a chain can be composed from several of them, planned,
 * printed and asserted on before anything is allocated, and a caller can splice a rung, reorder one
 * or drop one.
 *
 * Four of the fragment shaders live here as string constants; the separable blur reuses the nine-tap
 * kernel the bloom chain already ships rather than keeping a second copy in step with it. All of
 * them follow the post-stack conventions: a fullscreen triangle, `vUv` texel-aligned so `v = 0` is
 * image row 0, the primary input bound as `sourceTex`, and every offset scaled by the source's texel
 * size, so a pass reads its input's texels rather than assuming its own resolution matches.
 */

/**
 * A library of worked multi-resolution passes, expressed as [FabricatePassSpec] fragments.
 *
 * These are the passes a real-time 2D lighting or depth-of-field effect is actually made of, and the
 * reason the layer below them is worth having. Each function returns a list of specs rather than
 * touching the GPU, so a chain can be composed out of several of them, planned, printed and asserted
 * on before anything is allocated — and so a caller can splice, reorder or drop a rung.
 *
 * The shaders all follow the post-stack conventions: a fullscreen triangle, `vUv` texel-aligned so
 * `v = 0` is image row 0 (see `StandardRenderer.fullscreenVertex`, which explains at length what
 * goes wrong when a pass gets that backwards — the image mirrors once *per pass*, so the final
 * orientation depends on the parity of the pass count), the primary input as `sourceTex`, and every
 * offset scaled by `1.0 / textureSize(sourceTex, 0)` so a pass reads its *source's* texels rather
 * than assuming its own resolution matches.
 */
object FabricatePassLibrary {

    /** Hard cap on [aperture] taps, so the disc loop is bounded at compile time. */
    const val MAX_APERTURE_TAPS = 64

    /**
     * A downsample pyramid: [levels] halvings of the source, each a 13-tap filtered reduce.
     *
     * A pyramid is where the whole layer pays for itself. Level 3 of a 1024² pyramid is 128², so a
     * blur kernel of a given *texel* radius there covers eight times the *image* it would at full
     * resolution, at 1/64th the fill rate. That trade is only available if a pass can be smaller than
     * its input, which is the thing [StandardRenderer.Bundle.addShaderEffect] could not express.
     *
     * The filter is the Jimenez 13-tap — four inner taps carrying half the weight and four
     * overlapping 2x2 blocks carrying the rest. A plain 2x2 box reduce is cheaper and looks fine on a
     * still; it aliases visibly the moment the source moves, because a box tap of a bright thin
     * feature flickers on and off as it crosses a texel boundary. This is the standard fix.
     *
     * @param prefix pass names are `$prefix-1`, `$prefix-2`, … one per halving.
     * @param levels how many halvings. A level that would resolve below one texel is still legal;
     *   [FabricatePassSize] clamps at 1x1.
     * @param source the pass (or [FabricatePassChain.CHAIN_INPUT]) the first rung reduces.
     * @param format the format for every rung, or null to inherit.
     */
    fun downsamplePyramid(
        prefix: String = "reduce",
        levels: Int = 3,
        source: String? = null,
        format: Int? = null
    ): List<FabricatePassSpec> {
        require(levels >= 1) { "a pyramid needs at least one level, got $levels" }
        return (1..levels).map { level ->
            FabricatePassSpec(
                name = "$prefix-$level",
                fragment = DOWNSAMPLE_13TAP_FRAGMENT,
                size = FabricatePassSize.Half,
                format = format,
                source = if (level == 1) source else "$prefix-${level - 1}"
            )
        }
    }

    /**
     * A separable Gaussian blur: a horizontal pass then a vertical one, both at the source's size.
     *
     * Reuses the nine-tap kernel the bloom chain already ships ([BLOOM_BLUR_FRAGMENT]) rather than
     * adding a second one to keep in step with it.
     *
     * @param radius kernel step in texels of the *source*. On a downsampled rung this is texels of
     *   the small image, so the same number is a much wider blur in image terms.
     */
    fun separableBlur(
        prefix: String = "blur",
        radius: Float = 2.0f,
        source: String? = null,
        size: FabricatePassSize = FabricatePassSize.MatchSource,
        format: Int? = null
    ): List<FabricatePassSpec> = listOf(
        FabricatePassSpec(
            name = "$prefix-h",
            fragment = BLOOM_BLUR_FRAGMENT,
            size = size,
            format = format,
            source = source,
            push = mapOf("direction" to Vector2f(1f, 0f), "radius" to radius)
        ),
        FabricatePassSpec(
            name = "$prefix-v",
            fragment = BLOOM_BLUR_FRAGMENT,
            size = FabricatePassSize.MatchSource,
            format = format,
            source = "$prefix-h",
            push = mapOf("direction" to Vector2f(0f, 1f), "radius" to radius)
        )
    )

    /**
     * A disc (aperture) blur of [radiusTexels], the shape a real lens makes out of a point of light.
     *
     * This is *not* a Gaussian, and the difference is the whole point of having it. A Gaussian
     * spreads a highlight into a soft falloff; a camera aperture spreads it into a **flat-topped
     * disc** with a hard edge, because every point on the aperture contributes equally. That disc is
     * what reads as "photographic" and a Gaussian never does at any radius.
     *
     * A disc is also not separable, so it costs O(taps) samples per pixel with no way around it —
     * which is exactly why you want it on a downsampled rung, and why the layer this sits on had to
     * learn about resolution first.
     *
     * Taps are placed on a golden-angle spiral with `r = sqrt(t)`, which distributes them uniformly
     * over the disc's *area* (a plain `r = t` crowds the centre and leaves the rim under-sampled,
     * which shows up as a bright core inside every bokeh circle).
     *
     * @param radiusTexels disc radius in texels of the source image.
     * @param taps sample count, capped at [MAX_APERTURE_TAPS].
     */
    fun aperture(
        name: String = "aperture",
        radiusTexels: Float = 8f,
        taps: Int = 32,
        source: String? = null,
        size: FabricatePassSize = FabricatePassSize.MatchSource,
        format: Int? = null
    ): FabricatePassSpec {
        require(radiusTexels > 0f) { "aperture radius must be positive, got $radiusTexels" }
        require(taps in 1..MAX_APERTURE_TAPS) { "aperture taps must be in 1..$MAX_APERTURE_TAPS, got $taps" }
        return FabricatePassSpec(
            name = name,
            fragment = APERTURE_FRAGMENT,
            size = size,
            format = format,
            source = source,
            push = mapOf("radius" to radiusTexels, "taps" to taps)
        )
    }

    /**
     * A tent upsample of [source] added onto [over] at [over]'s resolution, weighted by [weight].
     *
     * This is the rung-joining half of a pyramid: walking back *up* a pyramid adding each level onto
     * the one above accumulates a very wide, very cheap blur whose kernel is far broader than any
     * single pass could afford. The 3x3 tent is deliberately soft — nearest or bilinear upsampling a
     * pyramid level produces blocky steps that survive every later pass.
     */
    fun upsampleAdd(
        name: String,
        source: String,
        over: String,
        weight: Float = 1f,
        radiusTexels: Float = 1f,
        format: Int? = null
    ): FabricatePassSpec = FabricatePassSpec(
        name = name,
        fragment = UPSAMPLE_ADD_FRAGMENT,
        // Sized to the target it is being added over, not to the small source it samples.
        size = FabricatePassSize.MatchSource,
        format = format,
        source = over,
        extraInputs = listOf(FabricatePassInput("lowTex", source)),
        push = mapOf("weight" to weight, "radius" to radiusTexels)
    )

    /**
     * A composite: [blurred] blended over [sharp] at [sharp]'s resolution.
     *
     * The pass that puts a multi-resolution chain back together. It runs at the *full* resolution and
     * samples a much smaller texture, which is the point — the expensive filtering happened on the
     * small image and only the cheap interpolation happens here.
     *
     * The two knobs are deliberately separate, because the two effects that want this pass want
     * opposite things. Depth of field **replaces** the sharp image with the blurred one where it
     * applies, so its high-frequency content has to actually go away; bloom **adds** a glow and must
     * leave the sharp image intact. One `blend` knob doing both would mean an additive bloom that
     * softens the picture, or a depth of field that stays sharp underneath — and the second is not
     * visible in a still.
     *
     * @param blend crossfade: 0 keeps [sharp] untouched, 1 replaces it with [blurred].
     * @param gain multiplies [blurred] before either knob sees it.
     * @param add additively laid over the crossfade result — the bloom/glow term.
     */
    fun composite(
        name: String = "composite",
        blurred: String,
        sharp: String,
        blend: Float = 0.5f,
        gain: Float = 1f,
        add: Float = 0f,
        format: Int? = null
    ): FabricatePassSpec = FabricatePassSpec(
        name = name,
        fragment = COMPOSITE_FRAGMENT,
        size = FabricatePassSize.MatchSource,
        format = format,
        source = sharp,
        extraInputs = listOf(FabricatePassInput("blurTex", blurred)),
        push = mapOf("blend" to blend, "gain" to gain, "add" to add)
    )

    /** A soft-knee bright pass, reusing the kernel [addBloom] already ships. */
    fun brightPass(
        name: String = "bright",
        threshold: Float = 1f,
        source: String? = null,
        size: FabricatePassSize = FabricatePassSize.MatchSource,
        format: Int? = VK_FORMAT_R16G16B16A16_SFLOAT
    ): FabricatePassSpec = FabricatePassSpec(
        name = name,
        fragment = BLOOM_BRIGHT_FRAGMENT,
        size = size,
        format = format,
        source = source,
        push = mapOf("threshold" to threshold)
    )

    /**
     * The chain the hand-wired version is made of: source, reduce ½, reduce ¼, two aperture blurs at
     * different radii on the cheap ¼ image, and a full-resolution composite.
     *
     * Six declarations where six boxes used to be. The two apertures deliberately run at the *same*
     * ¼ resolution and different radii, because that is the shape a depth-of-field or a soft-shadow
     * aperture wants: one narrow disc for the near field, one wide one for the far, joined at full
     * resolution.
     *
     * @param nearRadius disc radius for the narrow aperture, in ¼-resolution texels.
     * @param farRadius disc radius for the wide aperture, in ¼-resolution texels.
     * @param blend how much of the aperture result the composite mixes over the sharp source.
     */
    fun apertureChain(
        nearRadius: Float = 4f,
        farRadius: Float = 12f,
        taps: Int = 32,
        blend: Float = 0.7f,
        format: Int? = null
    ): List<FabricatePassSpec> =
        downsamplePyramid(prefix = "reduce", levels = 2, format = format) +
                aperture(
                    name = "aperture-near",
                    radiusTexels = nearRadius,
                    taps = taps,
                    source = "reduce-2",
                    format = format
                ) +
                aperture(
                    name = "aperture-far",
                    radiusTexels = farRadius,
                    taps = taps,
                    source = "aperture-near",
                    format = format
                ) +
                composite(
                    name = "composite",
                    blurred = "aperture-far",
                    sharp = FabricatePassChain.CHAIN_INPUT,
                    blend = blend,
                    format = format
                )

    /**
     * A multi-resolution bloom: bright pass, a pyramid, then the pyramid walked back up with
     * [upsampleAdd], then composited additively.
     *
     * The distinction from [addBloom] is resolution, not shader code. `addBloom` blurs at full
     * resolution with a nine-tap separable kernel, so its glow is as wide as nine full-resolution
     * texels and no wider. This runs the same class of filter on rungs down to 1/2^levels, so the
     * accumulated kernel is hundreds of texels across while costing a fraction of a single
     * full-resolution blur — the thing `addBloom` could not do because its passes could not shrink.
     *
     * Every rung is half-float by default: a bright pass over an 8-bit target clips the highlights it
     * exists to isolate, which is a per-pass format decision and therefore also new here.
     */
    fun bloomPyramid(
        levels: Int = 4,
        threshold: Float = 1f,
        intensity: Float = 0.7f,
        format: Int? = VK_FORMAT_R16G16B16A16_SFLOAT
    ): List<FabricatePassSpec> {
        require(levels >= 1) { "a bloom pyramid needs at least one level, got $levels" }
        val bright = brightPass(name = "bloom-bright", threshold = threshold, format = format)
        val pyramid = downsamplePyramid(prefix = "bloom-down", levels = levels, source = "bloom-bright", format = format)
        // Walk back up: bloom-up-k is bloom-down-(levels-k) with the level below added onto it.
        val up = (1 until levels).map { step ->
            val over = "bloom-down-${levels - step}"
            val below = if (step == 1) "bloom-down-$levels" else "bloom-up-${step - 1}"
            upsampleAdd(name = "bloom-up-$step", source = below, over = over, format = format)
        }
        val glow = if (levels == 1) "bloom-down-1" else "bloom-up-${levels - 1}"
        val join = composite(
            name = "bloom-composite",
            blurred = glow,
            sharp = FabricatePassChain.CHAIN_INPUT,
            blend = 0f,
            gain = intensity,
            add = 1f,
            format = null
        )
        return listOf(bright) + pyramid + up + join
    }

    // --- Shaders ---

    /**
     * Jimenez 13-tap downsample. Four inner taps at ±1 source texel carry half the weight; four
     * overlapping 2x2 blocks on the ±2 ring carry an eighth each.
     */
    const val DOWNSAMPLE_13TAP_FRAGMENT = """
        #version 450
        layout(location = 0) in vec2 vUv;
        layout(set = 0, binding = 0) uniform sampler2D sourceTex;
        layout(location = 0) out vec4 outColor;

        void main() {
            vec2 t = 1.0 / vec2(textureSize(sourceTex, 0));
            vec3 a = texture(sourceTex, vUv + t * vec2(-2.0, -2.0)).rgb;
            vec3 b = texture(sourceTex, vUv + t * vec2( 0.0, -2.0)).rgb;
            vec3 c = texture(sourceTex, vUv + t * vec2( 2.0, -2.0)).rgb;
            vec3 d = texture(sourceTex, vUv + t * vec2(-2.0,  0.0)).rgb;
            vec3 e = texture(sourceTex, vUv                       ).rgb;
            vec3 f = texture(sourceTex, vUv + t * vec2( 2.0,  0.0)).rgb;
            vec3 g = texture(sourceTex, vUv + t * vec2(-2.0,  2.0)).rgb;
            vec3 h = texture(sourceTex, vUv + t * vec2( 0.0,  2.0)).rgb;
            vec3 i = texture(sourceTex, vUv + t * vec2( 2.0,  2.0)).rgb;
            vec3 j = texture(sourceTex, vUv + t * vec2(-1.0, -1.0)).rgb;
            vec3 k = texture(sourceTex, vUv + t * vec2( 1.0, -1.0)).rgb;
            vec3 l = texture(sourceTex, vUv + t * vec2(-1.0,  1.0)).rgb;
            vec3 m = texture(sourceTex, vUv + t * vec2( 1.0,  1.0)).rgb;

            vec3 result = (j + k + l + m) * 0.5 * 0.25;
            result += (a + b + d + e) * 0.125 * 0.25;
            result += (b + c + e + f) * 0.125 * 0.25;
            result += (d + e + g + h) * 0.125 * 0.25;
            result += (e + f + h + i) * 0.125 * 0.25;
            outColor = vec4(result, 1.0);
        }
    """

    /**
     * Disc (aperture) blur. Taps on a golden-angle spiral with `r = sqrt(t)` so they are uniform over
     * the disc's area, weighted equally so the result is flat-topped with a hard rim — the bokeh
     * shape, not a Gaussian.
     */
    val APERTURE_FRAGMENT = """
        #version 450
        layout(location = 0) in vec2 vUv;
        layout(set = 0, binding = 0) uniform sampler2D sourceTex;
        layout(push_constant) uniform AperturePush {
            float radius;
            int taps;
        } push;
        layout(location = 0) out vec4 outColor;

        const float GOLDEN_ANGLE = 2.39996323;
        const int MAX_TAPS = $MAX_APERTURE_TAPS;

        void main() {
            vec2 texel = 1.0 / vec2(textureSize(sourceTex, 0));
            int taps = clamp(push.taps, 1, MAX_TAPS);
            float radius = max(push.radius, 0.0);
            vec3 sum = vec3(0.0);
            for (int i = 0; i < MAX_TAPS; ++i) {
                if (i >= taps) break;
                float t = (float(i) + 0.5) / float(taps);
                float r = sqrt(t) * radius;
                float a = float(i) * GOLDEN_ANGLE;
                sum += texture(sourceTex, vUv + vec2(cos(a), sin(a)) * r * texel).rgb;
            }
            outColor = vec4(sum / float(taps), 1.0);
        }
    """

    /**
     * 3x3 tent upsample of `lowTex` added onto `sourceTex`. Offsets are in `lowTex` texels, so the
     * tent is a genuine reconstruction filter for the small image rather than a blur of the big one.
     */
    const val UPSAMPLE_ADD_FRAGMENT = """
        #version 450
        layout(location = 0) in vec2 vUv;
        layout(set = 0, binding = 0) uniform sampler2D sourceTex;
        layout(set = 0, binding = 1) uniform sampler2D lowTex;
        layout(push_constant) uniform UpsampleAddPush {
            float weight;
            float radius;
        } push;
        layout(location = 0) out vec4 outColor;

        void main() {
            vec2 t = (1.0 / vec2(textureSize(lowTex, 0))) * max(push.radius, 0.0);
            vec3 low = texture(lowTex, vUv + t * vec2(-1.0, -1.0)).rgb * 1.0;
            low += texture(lowTex, vUv + t * vec2( 0.0, -1.0)).rgb * 2.0;
            low += texture(lowTex, vUv + t * vec2( 1.0, -1.0)).rgb * 1.0;
            low += texture(lowTex, vUv + t * vec2(-1.0,  0.0)).rgb * 2.0;
            low += texture(lowTex, vUv                      ).rgb * 4.0;
            low += texture(lowTex, vUv + t * vec2( 1.0,  0.0)).rgb * 2.0;
            low += texture(lowTex, vUv + t * vec2(-1.0,  1.0)).rgb * 1.0;
            low += texture(lowTex, vUv + t * vec2( 0.0,  1.0)).rgb * 2.0;
            low += texture(lowTex, vUv + t * vec2( 1.0,  1.0)).rgb * 1.0;
            low /= 16.0;
            vec4 here = texture(sourceTex, vUv);
            outColor = vec4(here.rgb + low * max(push.weight, 0.0), here.a);
        }
    """

    /**
     * Join `blurTex` onto `sourceTex` at `sourceTex`'s resolution: crossfade by `blend`, then lay
     * `add` of the blurred image over the top. Depth of field is `blend` near one; bloom is `blend`
     * zero and `add` positive.
     */
    const val COMPOSITE_FRAGMENT = """
        #version 450
        layout(location = 0) in vec2 vUv;
        layout(set = 0, binding = 0) uniform sampler2D sourceTex;
        layout(set = 0, binding = 1) uniform sampler2D blurTex;
        layout(push_constant) uniform CompositePush {
            float blend;
            float gain;
            float add;
        } push;
        layout(location = 0) out vec4 outColor;

        void main() {
            vec4 sharp = texture(sourceTex, vUv);
            vec3 blurred = texture(blurTex, vUv).rgb * max(push.gain, 0.0);
            vec3 crossfaded = mix(sharp.rgb, blurred, clamp(push.blend, 0.0, 1.0));
            outColor = vec4(crossfaded + blurred * max(push.add, 0.0), sharp.a);
        }
    """
}
