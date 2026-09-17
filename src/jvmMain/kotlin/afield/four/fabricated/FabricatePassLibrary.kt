package afield.four.fabricated

import afield.stdlib.linalg.Vector2f
import afield.stdlib.linalg.Vector3f
import org.lwjgl.vulkan.VK10.VK_FORMAT_R16_SFLOAT
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
     * Largest [jumpFloodDistance] resolution whose texel coordinates a half-float holds exactly.
     *
     * A jump flood does not carry distances, it carries the *coordinate* of the nearest seed, and a
     * coordinate that rounds is a seed that moves. Half-float has an 11-bit significand, so every
     * integer up to 2048 is exact and 2049 is not — and the failure is not a crash or a visibly
     * broken image, it is a distance field that is subtly wrong in the far field of a large one.
     * Above this, ask for `VK_FORMAT_R32G32B32A32_SFLOAT`.
     */
    const val MAX_HALF_EXACT_RESOLUTION = 2048

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

    /**
     * A **Stam-style stable fluid**: a velocity field and a dye field that persist across frames, with
     * a splat you paint into either of them.
     *
     * ### Why this is the chain that needed [FabricatePassSpec.persistent]
     *
     * Everything else in this file is a function of one frame's input. A fluid is not. `velocity` this
     * frame is `velocity` last frame advected by itself, pushed by whatever you painted, and then made
     * divergence-free — and no ordering of passes *within* a frame produces last frame's velocity. So
     * `$prefix-velocity` and `$prefix-dye` are persistent passes: ping-pong targets whose previous
     * frame the chain reads. `$prefix-advect` binds `$prefix-velocity`'s previous frame as its primary
     * input, which is a reference pointing *forwards* in the pass list, and is exactly as legal as any
     * other read of a value that already exists.
     *
     * ### The method, and where each pass comes from
     *
     * Stam's *Stable Fluids* (1999) is stable because it never integrates the advection term forward.
     * Instead each pass runs the operator splitting: advect, add force, project.
     *
     * | pass | what it does | why it is separate |
     * | --- | --- | --- |
     * | `advect` | semi-Lagrangian backtrace of velocity by itself | reads *back* along the flow, so it is unconditionally stable at any timestep — the whole point of the method |
     * | `curl` | ∇×v of the advected field | vorticity confinement needs the curl before the force that uses it |
     * | `force` | vorticity confinement + your splat | the only pass that reads a push constant you turn per frame |
     * | `divergence` | ∇·v of the forced field | the right-hand side of the pressure equation, and one channel, so it is `R16_SFLOAT` |
     * | `pressure-0` | zero | a Jacobi solve needs an initial iterate, and a pass that writes zero is cheaper than a clear the frame graph would have to special-case |
     * | `pressure-1…N` | one Jacobi iteration each | a Jacobi sweep *is* a pass chain: each iterate reads only the one before it and the fixed divergence |
     * | `velocity` | subtract ∇pressure — **persistent** | the projection, and the state |
     * | `dye` | advect dye by the new velocity + your mass splat — **persistent** | what you look at, and the second state field |
     *
     * Semi-Lagrangian advection is the one pass here that *wants* the `VK_FILTER_LINEAR` sampler
     * `addShaderEffect` builds: the backtrace lands between texels and bilinear interpolation is the
     * reconstruction. That is the opposite of [jumpFloodDistance], which cannot use it at all, and the
     * two together are why a per-pass filter mode is worth having eventually and why neither of these
     * needed it.
     *
     * ### Painting into it
     *
     * The `force` and `dye` passes share a splat: a Gaussian blob at `splatCenter` (in UV) of radius
     * `splatRadius`, adding `splatVelocity` to the velocity field and `splatColor * splatMass` to the
     * dye. Nothing here reads a mouse — retune them from a box with
     * `FabricateBuiltChain.setPush("$prefix-force", "splatCenter", Vector2f(u, v))` and friends, once
     * per frame, and set `splatMass`/`splatVelocity` to zero on the frames you are not painting. The
     * two passes take the *same* names, so one loop can drive both.
     *
     * ### Reading it out
     *
     * `FabricateBuiltChain.stateOf("$prefix-velocity")` is the ping-pong target; its `latestImageView`
     * samples the field from another module, and the ordinary `RenderToTexture` readback pulls it to
     * the CPU. `latestImageView` and not `thisImageView` — outside the target's own render, "this" is
     * the half about to be overwritten.
     *
     * @param prefix every pass name is `$prefix-…`; the two state fields are `$prefix-velocity` and
     *   `$prefix-dye`.
     * @param resolution the square simulation grid. A fluid does not want the window's resolution:
     *   256² is a lively fluid, and the pressure solve is where the cost is.
     * @param iterations Jacobi iterations on the pressure equation, each one a pass. Below about 15 the
     *   field visibly fails to be divergence-free — dye piles up and thins out where it should not —
     *   and above about 40 nothing changes.
     * @param timestep advection timestep. Stable at any value; large ones smear.
     * @param velocityDissipation per-frame multiplier on velocity, below 1 for a fluid that settles.
     * @param dyeDissipation per-frame multiplier on dye, below 1 for a fluid that fades.
     * @param vorticity vorticity confinement strength. Zero drops the `curl` pass entirely. This is
     *   what puts the curl back that a first-order advection scheme numerically dissipates, and it is
     *   the difference between a smear and something that reads as fluid.
     * @param format the velocity and dye fields' format. Half-float is the floor: velocity is signed
     *   and routinely outside 0..1, so an 8-bit field clamps the flow to one octant and quantises it.
     */
    fun stableFluid(
        prefix: String = "fluid",
        resolution: Int = 256,
        iterations: Int = 20,
        timestep: Float = 1f / 60f,
        velocityDissipation: Float = 0.999f,
        dyeDissipation: Float = 0.997f,
        vorticity: Float = 12f,
        format: Int = VK_FORMAT_R16G16B16A16_SFLOAT
    ): List<FabricatePassSpec> {
        require(resolution >= 4) { "a fluid grid needs at least 4 texels a side, got $resolution" }
        require(iterations >= 1) { "a pressure solve needs at least one iteration, got $iterations" }
        require(timestep > 0f) { "timestep must be positive, got $timestep" }

        val size = FabricatePassSize.Absolute(resolution, resolution)
        val scalar = VK_FORMAT_R16_SFLOAT
        val velocity = "$prefix-velocity"
        val splat = mapOf<String, Any>(
            "splatCenter" to Vector2f(0.5f, 0.5f),
            "splatVelocity" to Vector2f(0f, 0f),
            "splatColor" to Vector3f(1f, 1f, 1f),
            "splatRadius" to 0.05f,
            "splatMass" to 0f
        )

        val passes = ArrayList<FabricatePassSpec>()

        // Velocity advected by itself, read from last frame. This is the forward reference: the pass
        // that writes `velocity` is the second to last in the list, and its previous frame is what
        // this frame starts from.
        passes += FabricatePassSpec(
            name = "$prefix-advect",
            fragment = FLUID_ADVECT_VELOCITY_FRAGMENT,
            size = size,
            format = format,
            source = velocity,
            sourcePreviousFrame = true,
            push = mapOf("timestep" to timestep, "dissipation" to velocityDissipation)
        )

        if (vorticity > 0f) {
            passes += FabricatePassSpec(
                name = "$prefix-curl",
                fragment = FLUID_CURL_FRAGMENT,
                size = size,
                format = scalar,
                source = "$prefix-advect"
            )
        }

        passes += FabricatePassSpec(
            name = "$prefix-force",
            fragment = fluidForceFragment(vorticity > 0f),
            size = size,
            format = format,
            source = "$prefix-advect",
            extraInputs = if (vorticity > 0f) listOf(FabricatePassInput("curlTex", "$prefix-curl")) else emptyList(),
            push = splat + mapOf("timestep" to timestep, "vorticity" to vorticity)
        )

        passes += FabricatePassSpec(
            name = "$prefix-divergence",
            fragment = FLUID_DIVERGENCE_FRAGMENT,
            size = size,
            format = scalar,
            source = "$prefix-force"
        )

        passes += FabricatePassSpec(
            name = "$prefix-pressure-0",
            fragment = FLUID_ZERO_FRAGMENT,
            size = size,
            format = scalar,
            source = "$prefix-divergence"
        )
        (1..iterations).forEach { iteration ->
            passes += FabricatePassSpec(
                name = "$prefix-pressure-$iteration",
                fragment = FLUID_JACOBI_FRAGMENT,
                size = size,
                format = scalar,
                source = "$prefix-pressure-${iteration - 1}",
                extraInputs = listOf(FabricatePassInput("divergenceTex", "$prefix-divergence"))
            )
        }

        passes += FabricatePassSpec(
            name = velocity,
            fragment = FLUID_PROJECT_FRAGMENT,
            size = size,
            format = format,
            source = "$prefix-force",
            extraInputs = listOf(FabricatePassInput("pressureTex", "$prefix-pressure-$iterations")),
            persistent = true
        )

        passes += FabricatePassSpec(
            name = "$prefix-dye",
            fragment = FLUID_ADVECT_DYE_FRAGMENT,
            size = size,
            format = format,
            // Its own previous frame is the dye being transported; this frame's velocity transports it.
            source = "$prefix-dye",
            sourcePreviousFrame = true,
            extraInputs = listOf(FabricatePassInput("velocityTex", velocity)),
            push = splat + mapOf("timestep" to timestep, "dissipation" to dyeDissipation),
            persistent = true
        )

        return passes
    }

    /**
     * A **jump flood** distance transform: a mask in, a distance field out, in `1 + log2(resolution)`
     * passes.
     *
     * ### What it computes, and why it is not a blur
     *
     * Every pass so far in this file spreads energy. This one propagates *identity*. Each texel
     * carries the coordinate of the nearest seed it knows about, and each pass offers its eight
     * neighbours at distance `stride` the chance to improve that answer, with `stride` halving every
     * pass — `N/2, N/4, … 1`. After `log2(N)` passes, every texel holds the coordinate of (very nearly)
     * the nearest seed in the whole image, so the distance to it is a subtraction. That is an
     * O(N² log N) approximation of something exact algorithms need a sweep for, and it is the reason a
     * GPU can produce a signed distance field of a shape you are *painting* at frame rate.
     *
     * "Very nearly" is honest: the jump flood is an approximation, and its error is concentrated in a
     * handful of texels where three or more Voronoi cells meet. [plusOne] repeats the final `stride =
     * 1` pass, which is the standard JFA+1 fix and removes most of them for the cost of one pass.
     *
     * ### The two things it does differently from every other pass here
     *
     * - **It reads with `texelFetch`, not `texture`.** A bilinear tap between two seed coordinates is
     *   the average of two positions, which is a position no seed is at — so a filtered read does not
     *   blur the answer slightly, it invents one. `addShaderEffect` builds its samplers
     *   `VK_FILTER_LINEAR` and nothing in a pass spec changes that, so the shaders sidestep the
     *   sampler entirely rather than depend on a filter mode they cannot set.
     * - **It indexes by `gl_FragCoord`, not `vUv`.** For a pass the same size as its source those
     *   agree exactly (see `StandardRenderer.fullscreenVertex` on why `vUv` is texel-aligned), and
     *   integer texel arithmetic is what a jump has to be — a stride expressed in UV would land
     *   between texels at odd resolutions.
     *
     * ### Signed, from one flood
     *
     * With [signed] the seed predicate changes from "every interior texel" to "every interior texel
     * with an exterior neighbour" — the boundary. One flood then gives the distance to the nearest
     * *edge*, and the sign comes from the mask itself, which the final pass samples. The alternative
     * is two floods and a subtraction, for twice the passes and the same answer.
     *
     * @param prefix pass names are `$prefix-seed`, `$prefix-flood-1`… and `$prefix-distance`.
     * @param resolution the square field's resolution, which also fixes the pass count. Must be a
     *   power of two: the stride sequence is exactly the halvings of it, and a non-power-of-two would
     *   leave the largest stride unable to reach the far corner.
     * @param source the pass (or [FabricatePassChain.CHAIN_INPUT]) holding the mask. Sampled with
     *   filtering, so it may be any resolution.
     * @param threshold mask values at or above this count as inside.
     * @param channel which channel of [source] is the mask — 0=r, 1=g, 2=b, 3=a.
     * @param signed seed the boundary and sign the result by the mask: negative inside, positive out.
     * @param plusOne append the extra `stride = 1` pass (JFA+1).
     * @param scale multiplies the distance on the way out. The default leaves it in texels of the
     *   field, which is the unit worth reading back; `1f / resolution` puts it in UV.
     * @param format the seed and flood passes' format. The default is exact for coordinates up to
     *   [MAX_HALF_EXACT_RESOLUTION].
     */
    fun jumpFloodDistance(
        prefix: String = "jfa",
        resolution: Int = 512,
        source: String = FabricatePassChain.CHAIN_INPUT,
        threshold: Float = 0.5f,
        channel: Int = 3,
        signed: Boolean = false,
        plusOne: Boolean = true,
        scale: Float = 1f,
        format: Int = VK_FORMAT_R16G16B16A16_SFLOAT
    ): List<FabricatePassSpec> {
        require(resolution >= 2 && resolution and (resolution - 1) == 0) {
            "jump flood resolution must be a power of two of at least 2, got $resolution"
        }
        require(channel in 0..3) { "mask channel must be 0..3 (r, g, b, a), got $channel" }
        require(format != VK_FORMAT_R16G16B16A16_SFLOAT || resolution <= MAX_HALF_EXACT_RESOLUTION) {
            "a $resolution² jump flood cannot hold its seed coordinates exactly in half-float " +
                    "(exact to $MAX_HALF_EXACT_RESOLUTION); pass a 32-bit float format"
        }

        val size = FabricatePassSize.Absolute(resolution, resolution)
        val seed = FabricatePassSpec(
            name = "$prefix-seed",
            fragment = JUMP_FLOOD_SEED_FRAGMENT,
            size = size,
            format = format,
            source = source,
            push = mapOf(
                "threshold" to threshold,
                "channel" to channel,
                "boundary" to if (signed) 1 else 0
            )
        )

        // N/2, N/4, … 1: exactly the halvings, which is why the resolution has to be a power of two.
        val strides = generateSequence(resolution / 2) { if (it > 1) it / 2 else null }.toList()
        val floods = strides.mapIndexed { index, stride ->
            FabricatePassSpec(
                name = "$prefix-flood-${index + 1}",
                fragment = JUMP_FLOOD_FRAGMENT,
                size = size,
                format = format,
                source = if (index == 0) "$prefix-seed" else "$prefix-flood-$index",
                push = mapOf("stride" to stride)
            )
        } + if (!plusOne) emptyList() else listOf(
            FabricatePassSpec(
                name = "$prefix-flood-plus1",
                fragment = JUMP_FLOOD_FRAGMENT,
                size = size,
                format = format,
                source = "$prefix-flood-${strides.size}",
                push = mapOf("stride" to 1)
            )
        )

        val distance = FabricatePassSpec(
            name = "$prefix-distance",
            fragment = JUMP_FLOOD_DISTANCE_FRAGMENT,
            size = size,
            format = format,
            source = floods.last().name,
            extraInputs = listOf(FabricatePassInput("maskTex", source)),
            push = mapOf(
                "scale" to scale,
                "threshold" to threshold,
                "channel" to channel,
                "signedField" to if (signed) 1 else 0
            )
        )

        return listOf(seed) + floods + distance
    }

    /**
     * Bands and an isoline over a distance field, so a chain that ends in one shows something.
     *
     * A distance field is not a picture — its interesting values are outside 0..1 and its sign carries
     * half the information — so looking at one raw tells you approximately nothing. Contours do: even
     * spacing means the field is a distance, kinks along a ridge are where the Voronoi cells meet, and
     * a band that pinches shows a seed the flood never reached.
     *
     * @param source the [jumpFloodDistance] output, whose `r` is the (possibly signed) distance.
     * @param spacing band width, in whatever unit the distance pass scaled to.
     * @param inside colour for the negative side of a signed field.
     * @param outside colour for the positive side.
     */
    fun distanceBands(
        name: String = "distance-bands",
        source: String? = null,
        spacing: Float = 16f,
        inside: Vector3f = Vector3f(0.15f, 0.45f, 0.95f),
        outside: Vector3f = Vector3f(0.95f, 0.55f, 0.15f),
        format: Int? = null
    ): FabricatePassSpec {
        require(spacing > 0f) { "band spacing must be positive, got $spacing" }
        return FabricatePassSpec(
            name = name,
            fragment = DISTANCE_BANDS_FRAGMENT,
            size = FabricatePassSize.MatchSource,
            format = format,
            source = source,
            push = mapOf("spacing" to spacing, "inside" to inside, "outside" to outside)
        )
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

    /**
     * Shared GLSL for the fluid passes: the texel step, the boundary mask, and the splat.
     *
     * `boundaryScale` is the no-slip wall. Multiplying velocity by zero in the outermost texel ring
     * is crude next to Stam's `set_bnd` reflection, and it is what a fullscreen pass can do without a
     * second geometry pass — but it has to be *somewhere*, because with pure clamp-to-edge sampling
     * the advection pass transports velocity through the wall, the pressure solve has no boundary
     * condition to satisfy, and the fluid slowly pumps itself into a corner. That drift takes a few
     * hundred frames to become visible, which is exactly long enough to blame something else.
     */
    private const val FLUID_COMMON = """
        vec2 texelStep(sampler2D tex) {
            return 1.0 / vec2(textureSize(tex, 0));
        }

        float boundaryScale(vec2 uv, vec2 t) {
            vec2 inner = step(t, uv) * step(t, vec2(1.0) - uv);
            return inner.x * inner.y;
        }

        float splatWeight(vec2 uv, vec2 center, float radius) {
            vec2 d = uv - center;
            float r = max(radius, 1e-5);
            return exp(-dot(d, d) / (r * r));
        }
    """

    /**
     * Semi-Lagrangian advection of the velocity field by itself.
     *
     * The backtrace — sample where this parcel *came from*, rather than push it where it is going —
     * is what makes the method unconditionally stable, and is the whole reason it is called stable
     * fluids. A forward-Euler advection of the same field blows up above a CFL limit that a painted
     * splat trivially exceeds.
     *
     * This is also the pass that wants the bilinear sampler `addShaderEffect` builds: the backtrace
     * lands between texels and the interpolation *is* the reconstruction. It is first-order, so it
     * dissipates vorticity, which is what the confinement force in [FLUID_FORCE_FRAGMENT] puts back.
     */
    val FLUID_ADVECT_VELOCITY_FRAGMENT = """
        #version 450
        layout(location = 0) in vec2 vUv;
        layout(set = 0, binding = 0) uniform sampler2D sourceTex;
        layout(push_constant) uniform AdvectPush {
            float timestep;
            float dissipation;
        } push;
        layout(location = 0) out vec4 outColor;

        $FLUID_COMMON

        void main() {
            vec2 t = texelStep(sourceTex);
            vec2 v = texture(sourceTex, vUv).xy;
            // The timestep is in grid cells, so the displacement is scaled by the texel step: the
            // same timestep then means the same motion at any resolution.
            vec2 from = vUv - v * push.timestep * t;
            vec2 advected = texture(sourceTex, clamp(from, t * 0.5, vec2(1.0) - t * 0.5)).xy;
            advected *= push.dissipation * boundaryScale(vUv, t);
            outColor = vec4(advected, 0.0, 1.0);
        }
    """

    /** `∇×v` of the velocity field, one channel, for the vorticity confinement force. */
    val FLUID_CURL_FRAGMENT = """
        #version 450
        layout(location = 0) in vec2 vUv;
        layout(set = 0, binding = 0) uniform sampler2D sourceTex;
        layout(location = 0) out float outCurl;

        $FLUID_COMMON

        void main() {
            vec2 t = texelStep(sourceTex);
            float right = texture(sourceTex, vUv + vec2(t.x, 0.0)).y;
            float left  = texture(sourceTex, vUv - vec2(t.x, 0.0)).y;
            float up    = texture(sourceTex, vUv + vec2(0.0, t.y)).x;
            float down  = texture(sourceTex, vUv - vec2(0.0, t.y)).x;
            outCurl = 0.5 * ((right - left) - (up - down));
        }
    """

    /**
     * The force stage: vorticity confinement, then your splat.
     *
     * Confinement finds the direction the curl is *increasing* in, turns it ninety degrees, and pushes
     * along it — putting energy back into the swirls that the first-order advection above numerically
     * dissipated. Without it a stable fluid is a smear that fades; with it the same solver produces
     * eddies that persist and shed. It is not a more accurate solver, it is a correction for a known
     * error in a cheap one, and it is the single change that makes this read as fluid.
     *
     * The confinement term is compiled *in or out* rather than branched on, which is why this is a
     * function and not a constant. A `sampler2D curlTex` that survives into the SPIR-V has to be
     * bound to something, and a `vorticity = 0` chain has no `curl` pass to bind — so gating the term
     * on a push constant at runtime would leave a declared-but-unbindable sampler and fail in
     * descriptor setup rather than producing an unforced fluid.
     */
    fun fluidForceFragment(vorticity: Boolean): String = """
        #version 450
        layout(location = 0) in vec2 vUv;
        layout(set = 0, binding = 0) uniform sampler2D sourceTex;
        ${if (vorticity) "layout(set = 0, binding = 1) uniform sampler2D curlTex;" else ""}
        layout(push_constant) uniform ForcePush {
            vec2 splatCenter;
            vec2 splatVelocity;
            vec3 splatColor;
            float splatRadius;
            float splatMass;
            float timestep;
            float vorticity;
        } push;
        layout(location = 0) out vec4 outColor;

        $FLUID_COMMON

        void main() {
            vec2 t = texelStep(sourceTex);
            vec2 v = texture(sourceTex, vUv).xy;
        ${
        if (!vorticity) "" else """
            {
                float right = texture(curlTex, vUv + vec2(t.x, 0.0)).r;
                float left  = texture(curlTex, vUv - vec2(t.x, 0.0)).r;
                float up    = texture(curlTex, vUv + vec2(0.0, t.y)).r;
                float down  = texture(curlTex, vUv - vec2(0.0, t.y)).r;
                float here  = texture(curlTex, vUv).r;

                // grad|omega|, normalised, then rotated a quarter turn and scaled by omega.
                vec2 gradient = 0.5 * vec2(abs(right) - abs(left), abs(up) - abs(down));
                gradient /= max(length(gradient), 1e-5);
                v += vec2(gradient.y, -gradient.x) * here * max(push.vorticity, 0.0) * push.timestep;
            }
        """
    }
            v += push.splatVelocity * splatWeight(vUv, push.splatCenter, push.splatRadius) * push.timestep;
            outColor = vec4(v * boundaryScale(vUv, t), 0.0, 1.0);
        }
    """

    /** `∇·v`, the right-hand side of the pressure equation. One channel, hence its own format. */
    val FLUID_DIVERGENCE_FRAGMENT = """
        #version 450
        layout(location = 0) in vec2 vUv;
        layout(set = 0, binding = 0) uniform sampler2D sourceTex;
        layout(location = 0) out float outDivergence;

        $FLUID_COMMON

        void main() {
            vec2 t = texelStep(sourceTex);
            float right = texture(sourceTex, vUv + vec2(t.x, 0.0)).x;
            float left  = texture(sourceTex, vUv - vec2(t.x, 0.0)).x;
            float up    = texture(sourceTex, vUv + vec2(0.0, t.y)).y;
            float down  = texture(sourceTex, vUv - vec2(0.0, t.y)).y;
            outDivergence = 0.5 * ((right - left) + (up - down));
        }
    """

    /**
     * Writes zero.
     *
     * The initial iterate of the Jacobi solve. A pass that does nothing but clear looks like waste
     * until you notice the alternative: the chain has no way to say "clear this target and stop", and
     * a `clearFirst` on the first pressure pass would clear the target it is about to read from when
     * slot sharing hands it one it has seen before. One fullscreen write of a constant is the
     * unambiguous version.
     */
    const val FLUID_ZERO_FRAGMENT = """
        #version 450
        layout(location = 0) in vec2 vUv;
        layout(set = 0, binding = 0) uniform sampler2D sourceTex;
        layout(location = 0) out float outValue;

        void main() {
            outValue = 0.0;
        }
    """

    /**
     * One Jacobi iteration of `∇²p = ∇·v`.
     *
     * A Jacobi sweep is a pass chain in the most literal sense: iterate *k* reads iterate *k-1* and
     * the fixed divergence, and nothing else. That is why [stableFluid] can express a twenty-iteration
     * pressure solve as twenty declarations, and why it is the part of a fluid that most obviously
     * wanted this layer — the hand-wired version is twenty boxes or a loop nobody can insert a probe
     * into.
     *
     * Jacobi and not Gauss-Seidel or multigrid because Jacobi reads only the previous iterate, which
     * is the only thing a fragment shader can do without reading the target it is writing.
     */
    const val FLUID_JACOBI_FRAGMENT = """
        #version 450
        layout(location = 0) in vec2 vUv;
        layout(set = 0, binding = 0) uniform sampler2D sourceTex;
        layout(set = 0, binding = 1) uniform sampler2D divergenceTex;
        layout(location = 0) out float outPressure;

        void main() {
            vec2 t = 1.0 / vec2(textureSize(sourceTex, 0));
            float right = texture(sourceTex, vUv + vec2(t.x, 0.0)).r;
            float left  = texture(sourceTex, vUv - vec2(t.x, 0.0)).r;
            float up    = texture(sourceTex, vUv + vec2(0.0, t.y)).r;
            float down  = texture(sourceTex, vUv - vec2(0.0, t.y)).r;
            float divergence = texture(divergenceTex, vUv).r;
            outPressure = (left + right + down + up - divergence) * 0.25;
        }
    """

    /**
     * The projection: subtract `∇p` from the velocity field, leaving it (approximately)
     * divergence-free. The pass that writes the fluid's persistent velocity state.
     *
     * "Approximately" is the pressure solve's iteration count. The visible symptom of too few is not
     * instability — this method does not blow up — it is dye that thins and piles up, because a
     * velocity field with residual divergence is one that creates and destroys mass.
     */
    val FLUID_PROJECT_FRAGMENT = """
        #version 450
        layout(location = 0) in vec2 vUv;
        layout(set = 0, binding = 0) uniform sampler2D sourceTex;
        layout(set = 0, binding = 1) uniform sampler2D pressureTex;
        layout(location = 0) out vec4 outColor;

        $FLUID_COMMON

        void main() {
            vec2 t = texelStep(sourceTex);
            float right = texture(pressureTex, vUv + vec2(t.x, 0.0)).r;
            float left  = texture(pressureTex, vUv - vec2(t.x, 0.0)).r;
            float up    = texture(pressureTex, vUv + vec2(0.0, t.y)).r;
            float down  = texture(pressureTex, vUv - vec2(0.0, t.y)).r;
            vec2 v = texture(sourceTex, vUv).xy - 0.5 * vec2(right - left, up - down);
            outColor = vec4(v * boundaryScale(vUv, t), 0.0, 1.0);
        }
    """

    /**
     * Advects the dye by *this* frame's projected velocity and adds the mass splat — the fluid's other
     * persistent field, and the one you actually look at.
     *
     * `sourceTex` is this pass's own previous frame; `velocityTex` is the same-frame velocity. Both
     * references in one pass, and the reason the planner had to learn the difference: one is a value
     * from before the chain started running and the other is a pass three steps back.
     *
     * Advecting by the *projected* velocity rather than by the forced one is not a detail. Dye
     * transported by a field with divergence in it does not conserve, and the error accumulates in one
     * direction — the picture slowly brightens or washes out over a few thousand frames.
     */
    val FLUID_ADVECT_DYE_FRAGMENT = """
        #version 450
        layout(location = 0) in vec2 vUv;
        layout(set = 0, binding = 0) uniform sampler2D sourceTex;
        layout(set = 0, binding = 1) uniform sampler2D velocityTex;
        layout(push_constant) uniform DyePush {
            vec2 splatCenter;
            vec2 splatVelocity;
            vec3 splatColor;
            float splatRadius;
            float splatMass;
            float timestep;
            float dissipation;
        } push;
        layout(location = 0) out vec4 outColor;

        $FLUID_COMMON

        void main() {
            vec2 t = texelStep(sourceTex);
            vec2 v = texture(velocityTex, vUv).xy;
            vec2 from = vUv - v * push.timestep * t;
            vec3 dye = texture(sourceTex, clamp(from, t * 0.5, vec2(1.0) - t * 0.5)).rgb;
            dye *= push.dissipation;
            dye += push.splatColor * push.splatMass * splatWeight(vUv, push.splatCenter, push.splatRadius);
            outColor = vec4(dye, 1.0);
        }
    """

    /**
     * Jump flood seeding. Writes the texel's own centre as a seed coordinate where the mask says so,
     * and a `z = 0` "no seed" sentinel where it does not.
     *
     * `boundary` switches the predicate from the whole interior to just the interior texels that have
     * an exterior 4-neighbour, which is what makes one flood produce a *signed* field: the zero level
     * set then sits at the mask's edge rather than at its outside.
     *
     * The mask is read with `texture`, so it may be at any resolution; the seed coordinate it writes
     * is in this pass's own texels.
     */
    const val JUMP_FLOOD_SEED_FRAGMENT = """
        #version 450
        layout(location = 0) in vec2 vUv;
        layout(set = 0, binding = 0) uniform sampler2D sourceTex;
        layout(push_constant) uniform SeedPush {
            float threshold;
            int channel;
            int boundary;
        } push;
        layout(location = 0) out vec4 outColor;

        float maskAt(vec2 uv) {
            vec4 c = texture(sourceTex, clamp(uv, vec2(0.0), vec2(1.0)));
            float v = push.channel == 0 ? c.r : push.channel == 1 ? c.g : push.channel == 2 ? c.b : c.a;
            return v >= push.threshold ? 1.0 : 0.0;
        }

        void main() {
            ivec2 p = ivec2(gl_FragCoord.xy);
            vec2 t = 1.0 / vec2(textureSize(sourceTex, 0));
            float here = maskAt(vUv);
            bool seed = here > 0.5;
            if (push.boundary != 0 && seed) {
                float ring = maskAt(vUv + vec2( t.x, 0.0))
                           * maskAt(vUv + vec2(-t.x, 0.0))
                           * maskAt(vUv + vec2(0.0,  t.y))
                           * maskAt(vUv + vec2(0.0, -t.y));
                seed = ring < 0.5;
            }
            outColor = seed ? vec4(vec2(p) + 0.5, 1.0, here) : vec4(0.0, 0.0, 0.0, here);
        }
    """

    /**
     * One jump flood round: keep whichever of the nine candidates at `±stride` carries the seed
     * closest to *this* texel.
     *
     * `texelFetch`, not `texture`, and deliberately — a bilinear tap between two seed coordinates
     * averages two positions into a third that no seed occupies, so a filtered read here does not
     * soften the result, it fabricates seeds. See [jumpFloodDistance].
     *
     * `stride`, not `step`: `step` is a GLSL built-in.
     */
    const val JUMP_FLOOD_FRAGMENT = """
        #version 450
        layout(location = 0) in vec2 vUv;
        layout(set = 0, binding = 0) uniform sampler2D sourceTex;
        layout(push_constant) uniform FloodPush {
            int stride;
        } push;
        layout(location = 0) out vec4 outColor;

        void main() {
            ivec2 size = textureSize(sourceTex, 0);
            ivec2 p = ivec2(gl_FragCoord.xy);
            vec2 here = vec2(p) + 0.5;
            int stride = max(push.stride, 1);

            // A finite ceiling, not 1.0/0.0: a literal division by zero is a compile error on some
            // shaderc configurations, and an inf here would survive into the distance pass.
            const float NO_SEED = 3.0e38;
            vec4 best = texelFetch(sourceTex, p, 0);
            float bestDistance = best.z > 0.5 ? dot(best.xy - here, best.xy - here) : NO_SEED;

            for (int dy = -1; dy <= 1; ++dy) {
                for (int dx = -1; dx <= 1; ++dx) {
                    ivec2 q = p + ivec2(dx, dy) * stride;
                    if (q.x < 0 || q.y < 0 || q.x >= size.x || q.y >= size.y) continue;
                    vec4 candidate = texelFetch(sourceTex, q, 0);
                    if (candidate.z < 0.5) continue;
                    vec2 d = candidate.xy - here;
                    float dd = dot(d, d);
                    if (dd < bestDistance) {
                        bestDistance = dd;
                        best = candidate;
                    }
                }
            }
            outColor = best;
        }
    """

    /**
     * Turns a flooded seed field into a distance: `r` is the distance, `gb` the seed coordinate it
     * came from, `a` the mask.
     *
     * A texel the flood never reached — a field with no seeds at all, or one seed the strides could
     * not span — gets `size.x + size.y` rather than an arbitrary large constant, because a value over
     * 65504 is `inf` in the half-float target and an `inf` propagates into every later pass.
     *
     * `maskTex` is sampled with filtering at `vUv`, so the mask may be a different resolution from the
     * field; the sign has to come from the mask and not from the flood, because the flood only knows
     * where the nearest seed is, not which side of it this texel is on.
     */
    const val JUMP_FLOOD_DISTANCE_FRAGMENT = """
        #version 450
        layout(location = 0) in vec2 vUv;
        layout(set = 0, binding = 0) uniform sampler2D sourceTex;
        layout(set = 0, binding = 1) uniform sampler2D maskTex;
        layout(push_constant) uniform DistancePush {
            float scale;
            float threshold;
            int channel;
            int signedField;
        } push;
        layout(location = 0) out vec4 outColor;

        void main() {
            ivec2 size = textureSize(sourceTex, 0);
            ivec2 p = ivec2(gl_FragCoord.xy);
            vec4 seed = texelFetch(sourceTex, p, 0);
            vec2 here = vec2(p) + 0.5;

            float unreached = float(size.x + size.y);
            float distance = seed.z > 0.5 ? length(seed.xy - here) : unreached;

            vec4 m = texture(maskTex, vUv);
            float mv = push.channel == 0 ? m.r : push.channel == 1 ? m.g : push.channel == 2 ? m.b : m.a;
            float inside = mv >= push.threshold ? 1.0 : 0.0;
            if (push.signedField != 0 && inside > 0.5) distance = -distance;

            outColor = vec4(distance * push.scale, seed.xy, inside);
        }
    """

    /**
     * Even bands and a dark isoline over a signed or unsigned distance field, with the two sides
     * coloured differently.
     *
     * Even spacing is the check: a field whose bands crowd or stretch is not a distance.
     */
    const val DISTANCE_BANDS_FRAGMENT = """
        #version 450
        layout(location = 0) in vec2 vUv;
        layout(set = 0, binding = 0) uniform sampler2D sourceTex;
        layout(push_constant) uniform BandsPush {
            float spacing;
            vec3 inside;
            vec3 outside;
        } push;
        layout(location = 0) out vec4 outColor;

        void main() {
            float d = texture(sourceTex, vUv).r;
            float spacing = max(push.spacing, 1e-4);
            vec3 tint = d < 0.0 ? push.inside : push.outside;

            // fract() of the band index, shaped so each band fades out towards its far edge.
            float band = fract(abs(d) / spacing);
            vec3 color = tint * (0.35 + 0.65 * (1.0 - band));

            // The zero level set, one band-width wide however steep the field is there.
            float edge = 1.0 - smoothstep(0.0, spacing * 0.15, abs(d));
            color = mix(color, vec3(0.05), edge);

            outColor = vec4(color, 1.0);
        }
    """

    // --- Reprojection: passes that read last frame somewhere *else* ---------------------------

    /** Hard cap on [cameraMotionBlur] taps, so the streak loop is bounded at compile time. */
    const val MAX_MOTION_BLUR_TAPS = 33

    /** Highest [temporalDebug] mode — availability. */
    const val MOTION_DISPLAY_MAX_MODE = 3

    /**
     * The GLSL every [FabricatePassSpec.frameHistory] pass shares: clip/UV conversion and the
     * reprojection lookup.
     *
     * Paste it into a fragment shader after the push block. It declares no uniforms and no push
     * constants of its own — it is four pure functions over values the caller passes in — because a
     * push block cannot be assembled from string fragments without the offsets becoming somebody's
     * problem. What the caller has to declare, in this order and **first** in the block, is:
     *
     * ```glsl
     * layout(push_constant) uniform WhateverPush {
     *     mat4 reprojection;    // bound by addPassChain
     *     vec4 historyParams;   // bound by addPassChain
     *     // ... then the pass's own constants
     * } push;
     * ```
     *
     * ### The two conventions this encodes, both of which are easy to get wrong
     *
     * 1. **The Y flip.** `StandardRenderer.fullscreenVertex` emits `vUv = vec2(pos.x, -pos.y) * 0.5
     *    + 0.5`, so `v = 0` is image row 0 and NDC `y` runs the *other* way from `v`. The clip-to-UV
     *    scale is therefore `* vec2(0.5, -0.5) + 0.5` and not `* 0.5 + 0.5`. This is the same
     *    expression the g-buffer already uses to write its motion vectors, and it is written
     *    identically on purpose: a chain whose reprojection disagreed with the g-buffer's motion
     *    vectors by a sign in `y` would produce an image that is stable when the camera pans and
     *    smears when it tilts.
     * 2. **Availability is two tests, not one.** `historyParams.z` is the CPU's answer — is there a
     *    previous frame at all (see [afield.four.targets.FrameHistory]). It says nothing about *this
     *    pixel*, which may have reprojected to somewhere that was off the edge of the previous
     *    frame. Both have to hold, and the second is per-pixel, which is why it cannot live on the
     *    CPU side.
     *
     * ### The depth honesty
     *
     * `fieldHistoryUv` reprojects at an **assumed NDC depth**, because a post pass has a colour
     * buffer and no depth buffer. That makes it exact for a camera that rotated or changed its field
     * of view — every ray is the same ray, differently indexed — and approximate for a camera that
     * translated, with the error growing with the spread of real depth around the assumed plane. It
     * is the honest first scope rather than a shortcut: the alternative is to thread the g-buffer's
     * depth attachment into the chain as a named sampler, which is a bigger change than this one. A
     * pass that *does* have depth should compose `reprojection` with its own unprojection rather
     * than call `fieldHistoryUv`.
     */
    const val FRAME_HISTORY_GLSL = """
        // Clip position to post-stack UV. The vec2(0.5, -0.5) is the Y flip; see FRAME_HISTORY_GLSL.
        vec2 fieldUvFromClip(vec4 clip) {
            return (clip.xy / max(abs(clip.w), 1e-5)) * vec2(0.5, -0.5) + vec2(0.5);
        }

        // Post-stack UV and an assumed NDC depth back to a clip position.
        vec4 fieldClipFromUv(vec2 uv, float ndcDepth) {
            return vec4((uv - vec2(0.5)) / vec2(0.5, -0.5), ndcDepth, 1.0);
        }

        // Where `uv` was in the previous frame, at an assumed NDC depth.
        vec2 fieldHistoryUv(mat4 reprojection, vec2 uv, float ndcDepth) {
            return fieldUvFromClip(reprojection * fieldClipFromUv(uv, ndcDepth));
        }

        // Both halves: the CPU's validity rule, and whether this pixel was on screen last frame.
        bool fieldHistoryValid(vec4 historyParams, vec2 historyUv) {
            return historyParams.z > 0.5 &&
                historyUv.x > 0.0 && historyUv.x < 1.0 &&
                historyUv.y > 0.0 && historyUv.y < 1.0;
        }
    """

    /**
     * A TAA-style temporal accumulation: blend this frame into a reprojected history, with a
     * rejection rule.
     *
     * This is the pass the whole frame-history layer exists for, and it is the exact composition of
     * the two declarations: [FabricatePassSpec.persistent] to own the accumulation buffer,
     * `previousFrame = true` to read it, and [FabricatePassSpec.frameHistory] to know *which texel*
     * of it to read. Take away the third and you have a pass that accumulates correctly while the
     * camera is still and smears while it moves — which is what a temporal chain could do before,
     * and why the layer needed a third declaration rather than a better shader.
     *
     * ### The rejection rule, and why it is a neighbourhood and not a threshold
     *
     * Reprojection answers "where was this pixel", not "was it the same thing". A pixel that
     * reprojects onto a surface that has since been occluded, revealed, or simply moved past finds
     * history that is a perfectly good picture of something else, and blending it in is a ghost.
     *
     * So the history is clamped to the **colour range of a 3×3 neighbourhood of the current frame**
     * before it is blended — the standard neighbourhood clamp. A plain "reject if the colours differ
     * by more than t" threshold cannot work: on a noisy or a finely detailed image every pixel
     * differs by more than any useful t, so the effect switches itself off exactly where it would
     * have helped most. The clamp instead keeps the *component* of history that is consistent with
     * what is in front of it, which degrades to "no accumulation" on a disocclusion and to "full
     * accumulation" on a still surface without a knob in between.
     *
     * [rejection] dilates the clamp box about the neighbourhood mean. At 0 the clamp is the raw
     * neighbourhood min/max and ghosting is least; above 0 more history survives, and so do more of
     * its ghosts.
     *
     * @param name the pass name; also the accumulation buffer's, since they are the same target.
     * @param source the pass supplying this frame's image, or null for the pass before.
     * @param blend weight given to the reprojected history when it is valid. 0.9 is about ten frames
     *   of effective history; 1.0 would never take a new sample and is rejected.
     * @param rejection clamp-box dilation, in units of the neighbourhood's own range.
     * @param ndcDepth the NDC depth reprojection assumes; see [FRAME_HISTORY_GLSL].
     * @param resolution the accumulation buffer's size. A persistent pass must state its own size
     *   ([FabricatePassChain.plan] enforces that) and it must match the image it accumulates, or the
     *   reprojected UV addresses the right fraction of the wrong picture.
     * @param format the accumulation buffer's format. Half-float by default and deliberately: an
     *   8-bit accumulation buffer quantises at every blend, and at a blend of 0.9 a converging
     *   increment is under one code, so the image stops converging and sits a code short forever.
     */
    fun temporalAccumulate(
        name: String = "taa",
        source: String? = null,
        blend: Float = 0.9f,
        rejection: Float = 0f,
        ndcDepth: Float = 0.5f,
        resolution: Pair<Int, Int>,
        format: Int = VK_FORMAT_R16G16B16A16_SFLOAT
    ): List<FabricatePassSpec> {
        require(blend in 0f..0.999f) {
            "temporal blend must be in 0..0.999, got $blend — at 1.0 the pass never takes a new " +
                    "sample and the accumulation buffer freezes at whatever it first held"
        }
        require(rejection >= 0f) { "rejection dilation must not be negative, got $rejection" }
        val (width, height) = resolution
        require(width > 0 && height > 0) {
            "temporal accumulation needs a positive size, got ${width}x$height"
        }
        return listOf(
            FabricatePassSpec(
                name = name,
                fragment = TEMPORAL_ACCUMULATE_FRAGMENT,
                size = FabricatePassSize.Absolute(width, height),
                format = format,
                source = source,
                // Itself, last frame — the forward reference persistence already allows.
                extraInputs = listOf(FabricatePassInput("historyTex", name, previousFrame = true)),
                push = mapOf("blend" to blend, "rejection" to rejection, "ndcDepth" to ndcDepth),
                persistent = true,
                frameHistory = true
            )
        )
    }

    /**
     * Camera motion blur: smear each pixel along the direction it came from.
     *
     * The second consumer, and deliberately a pass that declares [FabricatePassSpec.frameHistory]
     * and **not** [FabricatePassSpec.persistent]. It has no history buffer and reads no previous
     * frame; it only wants to know which way the camera went, and takes every sample from the
     * current image. That the two declarations are independent is a claim about the API rather than
     * about this effect, which is why the API needed a second consumer to make it.
     *
     * Not to be confused with the standard renderer's `PASS_MOTION_BLUR`, which is the better
     * effect: that one reads the g-buffer's per-pixel motion vectors, so it blurs a moving *object*
     * in front of a still camera, which this cannot. This is the camera-only case, available to any
     * chain over any image — including one with no geometry behind it at all.
     *
     * @param strength fraction of the frame's motion to smear over. 1.0 is a full open shutter.
     * @param taps samples along the streak. Odd values keep a centre sample.
     */
    fun cameraMotionBlur(
        name: String = "camera-motion-blur",
        source: String? = null,
        strength: Float = 1f,
        taps: Int = 9,
        ndcDepth: Float = 0.5f,
        format: Int? = null
    ): List<FabricatePassSpec> {
        require(strength >= 0f) { "motion blur strength must not be negative, got $strength" }
        require(taps in 1..MAX_MOTION_BLUR_TAPS) {
            "motion blur taps must be in 1..$MAX_MOTION_BLUR_TAPS, got $taps"
        }
        return listOf(
            FabricatePassSpec(
                name = name,
                fragment = cameraMotionBlurFragment(taps),
                size = FabricatePassSize.MatchSource,
                format = format,
                source = source,
                push = mapOf("strength" to strength, "ndcDepth" to ndcDepth),
                frameHistory = true
            )
        )
    }

    /**
     * The debug view, in four modes — and it is not garnish.
     *
     * Reprojection is the case where "it renders" and "it is right" are almost unrelated. A
     * reprojection with a sign error in `y`, a half-texel offset, or an availability flag stuck on
     * produces an image that a person looking at it will call fine, and a temporal pass built on it
     * will look *better* than the correct one on a still frame. The only way a human sees which it
     * is, is to look at the correspondence directly, so this ships with the layer rather than after
     * it — the same four modes the hand-built prototype had.
     *
     * - **0 — beauty.** The source, untouched. The baseline to flip against.
     * - **1 — backward motion.** `previousUv - currentUv`, the direction this pixel came *from*,
     *   scaled by [motionScale] and shown as red/green about a grey zero. A still camera is flat
     *   grey; pan and the frame goes uniformly one colour. The *sign* is what this mode is for.
     * - **2 — previous UV.** The reprojected coordinate itself as red/green: a smooth corner-to-corner
     *   ramp when it is right. This is the mode that shows a half-texel error, which mode 1 cannot —
     *   a constant offset inside a difference is invisible, and inside an absolute coordinate it is
     *   a visible colour shift.
     * - **3 — availability.** Green where `fieldHistoryValid` holds, red where it does not. Entirely
     *   green on a still camera, growing a red band on the edge the camera moves *away* from — the
     *   pixels that were off screen last frame. Entirely red is history the CPU rule rejected;
     *   entirely green *while the camera moves* is an availability flag that is not being written.
     *
     * @param mode 0..[MOTION_DISPLAY_MAX_MODE] as above. A push constant, so it is a number a person
     *   turns while watching rather than a rebuild.
     */
    fun temporalDebug(
        name: String = "motion-display",
        source: String? = null,
        mode: Int = 0,
        motionScale: Float = 8f,
        ndcDepth: Float = 0.5f,
        format: Int? = null
    ): List<FabricatePassSpec> {
        require(mode in 0..MOTION_DISPLAY_MAX_MODE) {
            "motion display mode must be in 0..$MOTION_DISPLAY_MAX_MODE, got $mode"
        }
        require(motionScale > 0f) { "motion scale must be positive, got $motionScale" }
        return listOf(
            FabricatePassSpec(
                name = name,
                fragment = TEMPORAL_DEBUG_FRAGMENT,
                size = FabricatePassSize.MatchSource,
                format = format,
                source = source,
                push = mapOf("mode" to mode, "motionScale" to motionScale, "ndcDepth" to ndcDepth),
                frameHistory = true
            )
        )
    }

    /**
     * Neighbourhood-clamped temporal accumulation against a reprojected history.
     *
     * Reads `historyTex` at the *reprojected* UV rather than at `vUv`, which is the whole point, and
     * writes the blend into its own persistent target. A pixel failing either half of
     * `fieldHistoryValid` takes the current frame whole — the correct restart, rather than a fade
     * towards it.
     */
    val TEMPORAL_ACCUMULATE_FRAGMENT = """
        #version 450
        layout(location = 0) in vec2 vUv;
        layout(set = 0, binding = 0) uniform sampler2D sourceTex;
        layout(set = 0, binding = 1) uniform sampler2D historyTex;
        layout(push_constant) uniform TemporalPush {
            mat4 reprojection;
            vec4 historyParams;
            float blend;
            float rejection;
            float ndcDepth;
        } push;
        layout(location = 0) out vec4 outColor;

$FRAME_HISTORY_GLSL

        void main() {
            vec4 here = texture(sourceTex, vUv);
            vec2 hUv = fieldHistoryUv(push.reprojection, vUv, push.ndcDepth);

            if (!fieldHistoryValid(push.historyParams, hUv)) {
                outColor = here;
                return;
            }

            // The 3x3 neighbourhood of the *current* frame, in this pass's own texels.
            vec2 t = 1.0 / vec2(textureSize(sourceTex, 0));
            vec3 lo = here.rgb;
            vec3 hi = here.rgb;
            for (int dy = -1; dy <= 1; ++dy) {
                for (int dx = -1; dx <= 1; ++dx) {
                    vec3 c = texture(sourceTex, vUv + t * vec2(dx, dy)).rgb;
                    lo = min(lo, c);
                    hi = max(hi, c);
                }
            }

            // Dilate the clamp box. At rejection = 0 this is the raw neighbourhood min/max.
            vec3 pad = (hi - lo) * 0.5 * max(push.rejection, 0.0);
            lo -= pad;
            hi += pad;

            vec3 history = clamp(texture(historyTex, hUv).rgb, lo, hi);
            outColor = vec4(mix(here.rgb, history, clamp(push.blend, 0.0, 0.999)), here.a);
        }
    """

    /**
     * The debug view's four modes, sharing one shader because they share one reprojection.
     *
     * One shader and one `mode` push constant rather than four passes: what a person does with this
     * is flip between modes on the *same* frame, and four passes would be four reprojections that
     * could disagree.
     */
    val TEMPORAL_DEBUG_FRAGMENT = """
        #version 450
        layout(location = 0) in vec2 vUv;
        layout(set = 0, binding = 0) uniform sampler2D sourceTex;
        layout(push_constant) uniform DisplayPush {
            mat4 reprojection;
            vec4 historyParams;
            int mode;
            float motionScale;
            float ndcDepth;
        } push;
        layout(location = 0) out vec4 outColor;

$FRAME_HISTORY_GLSL

        void main() {
            vec2 hUv = fieldHistoryUv(push.reprojection, vUv, push.ndcDepth);
            bool valid = fieldHistoryValid(push.historyParams, hUv);

            if (push.mode == 1) {
                // Backward motion: where it came FROM, minus where it is. Grey is zero.
                vec2 motion = (hUv - vUv) * push.motionScale;
                outColor = vec4(clamp(motion * 0.5 + 0.5, 0.0, 1.0), 0.5, 1.0);
            } else if (push.mode == 2) {
                // The reprojected coordinate itself, so a constant offset is visible.
                outColor = vec4(clamp(hUv, 0.0, 1.0), 0.0, 1.0);
            } else if (push.mode == 3) {
                outColor = valid ? vec4(0.1, 0.85, 0.2, 1.0) : vec4(0.9, 0.15, 0.1, 1.0);
            } else {
                outColor = texture(sourceTex, vUv);
            }
        }
    """

    /**
     * Camera motion blur over [taps] samples along the backward-motion direction.
     *
     * The tap count is baked into the source rather than pushed, so the loop is bounded at compile
     * time and the shader carries no dynamic trip count. Whether it unrolls is the compiler's
     * business; the constant being constant is what lets it do any.
     */
    fun cameraMotionBlurFragment(taps: Int): String {
        require(taps in 1..MAX_MOTION_BLUR_TAPS) { "taps must be in 1..$MAX_MOTION_BLUR_TAPS, got $taps" }
        return """
        #version 450
        layout(location = 0) in vec2 vUv;
        layout(set = 0, binding = 0) uniform sampler2D sourceTex;
        layout(push_constant) uniform BlurPush {
            mat4 reprojection;
            vec4 historyParams;
            float strength;
            float ndcDepth;
        } push;
        layout(location = 0) out vec4 outColor;

        const int TAPS = $taps;

$FRAME_HISTORY_GLSL

        void main() {
            vec2 hUv = fieldHistoryUv(push.reprojection, vUv, push.ndcDepth);
            // Unlike the accumulating pass this does NOT test availability: the streak is taken
            // entirely from the current image, and `reprojection` is only being asked which way the
            // camera went. With no previous frame the reprojection is the identity, so the streak
            // has zero length and the pass is a no-op — handled by arithmetic rather than a branch.
            vec2 stride = (hUv - vUv) * max(push.strength, 0.0) / float(max(TAPS - 1, 1));

            vec4 total = vec4(0.0);
            for (int i = 0; i < TAPS; ++i) {
                // Centred on vUv so a symmetric shutter does not shift the image.
                vec2 at = vUv + stride * float(i - (TAPS - 1) / 2);
                total += texture(sourceTex, clamp(at, vec2(0.0), vec2(1.0)));
            }
            outColor = total / float(TAPS);
        }
        """
    }
}
