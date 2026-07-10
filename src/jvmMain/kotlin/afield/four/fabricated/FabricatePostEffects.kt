package afield.four.fabricated

import afield.four.Renderer
import afield.four.modules.Mesh
import afield.four.modules.fromContext
import afield.four.targets.RenderToTexture
import afield.four.texture.TextureSampler
import afield.stdlib.fromViews
import org.joml.Vector2f
import java.util.WeakHashMap

/**
 * High-level post-processing helpers for [StandardRenderer.Bundle], layered on top of the ordered
 * [FabricatePostStack]. These are the "make HDR look right" and "make emissive glow" effects that
 * Three.js users expect from an EffectComposer: tone mapping + exposure, and bloom.
 *
 * All passes are built with [StandardRenderer.Bundle.addShaderEffect], so they chain automatically
 * (each samples the previous stack output as `sourceTex`). Build the post stack once, then call
 * `bundle.attachTo(renderer)` so the new passes are synced into the window's frame graph.
 */
enum class FabricateToneMap {
    /** Pass-through (exposure still applies). */
    NONE,
    /** Reinhard `c / (1 + c)` — cheap, desaturates highlights. */
    REINHARD,
    /** Uncharted 2 filmic curve. */
    FILMIC,
    /** ACES filmic approximation (Narkowicz). Three.js `ACESFilmicToneMapping`. */
    ACES,
    /** Minimal AgX — modern, hue-preserving highlight rolloff. */
    AGX
}

private data class ToneConfig(val mode: FabricateToneMap, val exposure: Float)

private val toneConfigs = WeakHashMap<StandardRenderer.Bundle, ToneConfig>()

const val TONEMAP_EFFECT_NAME = "tonemap"
private val BLOOM_EFFECT_NAMES = listOf("bloom-bright", "bloom-blur-h", "bloom-blur-v", "bloom-composite")

/**
 * Set (or replace) the final tone-mapping pass. Tone mapping always runs last in the post stack, so
 * it maps HDR lighting into a displayable range after every other effect. Re-invoking this replaces
 * the previous tone-map pass; [FabricateToneMap.NONE] removes it. Remember to re-attach the frame
 * graph afterwards so the pass is scheduled.
 */
fun StandardRenderer.Bundle.setToneMapping(
    mode: FabricateToneMap = FabricateToneMap.ACES,
    exposure: Float = 1f,
    renderer: Renderer = fromContext()
): FabricatePostEffect? {
    toneConfigs[this] = ToneConfig(mode, exposure)
    postStack.remove(TONEMAP_EFFECT_NAME)
    if (mode == FabricateToneMap.NONE) return null

    val effect = addShaderEffect(TONEMAP_EFFECT_NAME, TONEMAP_FRAGMENT, renderer)
    effect.quad(TONEMAP_EFFECT_NAME)?.push?.let { push ->
        if (push.offsets.containsKey("exposure")) push["exposure"] = exposure
        if (push.offsets.containsKey("toneMode")) push["toneMode"] = mode.ordinal
    }
    return effect
}

/**
 * Add a bloom chain: a bright-pass extract, a separable Gaussian blur, and an additive composite
 * back over the pre-bloom image. Bloom runs on HDR values, so it is inserted before any tone-mapping
 * pass. Calling this again rebuilds the chain. Re-attach the frame graph afterwards.
 *
 * @param threshold luminance above which pixels contribute to bloom.
 * @param intensity how strongly the blurred highlights are added back.
 * @param radius blur step scale in texels; larger spreads the glow wider.
 */
fun StandardRenderer.Bundle.addBloom(
    threshold: Float = 1.0f,
    intensity: Float = 0.6f,
    radius: Float = 2.5f,
    renderer: Renderer = fromContext()
): List<FabricatePostEffect> {
    // Rebuild cleanly if bloom already present.
    BLOOM_EFFECT_NAMES.forEach { postStack.remove(it) }

    // Bloom must operate on HDR, so temporarily lift any tone-map pass, add bloom, then restore it.
    val tone = toneConfigs[this]
    val hadTone = postStack[TONEMAP_EFFECT_NAME] != null
    if (hadTone) postStack.remove(TONEMAP_EFFECT_NAME)

    val sceneTex = postStack.output()

    val bright = addShaderEffect("bloom-bright", BLOOM_BRIGHT_FRAGMENT, renderer)
    bright.quad("bloom-bright")?.push?.setIfPresent("threshold", threshold)

    val blurH = addShaderEffect("bloom-blur-h", BLOOM_BLUR_FRAGMENT, renderer)
    blurH.quad("bloom-blur-h")?.push?.let {
        it.setIfPresent("direction", Vector2f(1f, 0f))
        it.setIfPresent("radius", radius)
    }

    val blurV = addShaderEffect("bloom-blur-v", BLOOM_BLUR_FRAGMENT, renderer)
    blurV.quad("bloom-blur-v")?.push?.let {
        it.setIfPresent("direction", Vector2f(0f, 1f))
        it.setIfPresent("radius", radius)
    }

    val composite = addShaderEffect("bloom-composite", BLOOM_COMPOSITE_FRAGMENT, renderer) {
        // Additively combine the blurred highlights (sourceTex) with the pre-bloom scene (sceneTex).
        val previousResolver = textureResolver
        val sceneSampler = TextureSampler(renderer, sceneTex.parameters.mipLevels, false)
        textureResolver = { tex ->
            if (tex.name == "sceneTex") {
                fromViews(listOf(sceneTex.imageViewArray[0]), tex.name, sceneSampler)
            } else {
                previousResolver(tex)
            }
        }
    }
    composite.quad("bloom-composite")?.push?.setIfPresent("intensity", intensity)

    if (hadTone && tone != null) setToneMapping(tone.mode, tone.exposure, renderer)
    return listOf(bright, blurH, blurV, composite)
}

private fun FabricatePostEffect.quad(name: String): Mesh? = module.current.meshes["${name}Quad"]

private fun afield.four.buffers.Structure.setIfPresent(name: String, value: Any) {
    if (offsets.containsKey(name)) set(name, value)
}

// --- Shaders ---

internal const val TONEMAP_FRAGMENT = """
    #version 450
    layout(location = 0) in vec2 vUv;
    layout(set = 0, binding = 0) uniform sampler2D sourceTex;
    layout(push_constant) uniform ToneMapPush {
        float exposure;
        int toneMode;
    } push;
    layout(location = 0) out vec4 outColor;

    vec3 reinhard(vec3 x) { return x / (1.0 + x); }

    vec3 uncharted2Curve(vec3 x) {
        const float A = 0.15, B = 0.50, C = 0.10, D = 0.20, E = 0.02, F = 0.30;
        return ((x * (A * x + C * B) + D * E) / (x * (A * x + B) + D * F)) - E / F;
    }
    vec3 filmic(vec3 x) {
        vec3 curr = uncharted2Curve(x * 2.0);
        vec3 white = uncharted2Curve(vec3(11.2));
        return curr / white;
    }

    vec3 aces(vec3 x) {
        const float a = 2.51, b = 0.03, c = 2.43, d = 0.59, e = 0.14;
        return clamp((x * (a * x + b)) / (x * (c * x + d) + e), 0.0, 1.0);
    }

    vec3 agxContrast(vec3 x) {
        vec3 x2 = x * x;
        vec3 x4 = x2 * x2;
        return 15.5 * x4 * x2 - 40.14 * x4 * x + 31.96 * x4 - 6.868 * x2 * x + 0.4298 * x2 + 0.1191 * x - 0.00232;
    }
    vec3 agx(vec3 val) {
        const mat3 agxMat = mat3(
            0.842479062253094, 0.0423282422610123, 0.0423756549057051,
            0.0784335999999992, 0.878468636469772,  0.0784336,
            0.0792237451477643, 0.0791661274605434, 0.879142973793104);
        const float minEv = -12.47393;
        const float maxEv = 4.026069;
        val = agxMat * val;
        val = clamp(log2(max(val, 1e-10)), minEv, maxEv);
        val = (val - minEv) / (maxEv - minEv);
        return agxContrast(val);
    }
    vec3 agxEotf(vec3 val) {
        const mat3 agxInv = mat3(
             1.19687900512017,   -0.0528968517574562, -0.0529716355144438,
            -0.0980208811401368,  1.15190312990417,   -0.0980434501171241,
            -0.0990297440797205, -0.0989611768448433,  1.15107367264116);
        val = agxInv * val;
        return pow(max(val, 0.0), vec3(2.2));
    }

    void main() {
        vec4 src = texture(sourceTex, vUv);
        vec3 c = max(src.rgb * max(push.exposure, 0.0), vec3(0.0));
        vec3 mapped;
        if (push.toneMode == 1) mapped = reinhard(c);
        else if (push.toneMode == 2) mapped = filmic(c);
        else if (push.toneMode == 3) mapped = aces(c);
        else if (push.toneMode == 4) mapped = agxEotf(agx(c));
        else mapped = c;
        outColor = vec4(mapped, src.a);
    }
"""

internal const val BLOOM_BRIGHT_FRAGMENT = """
    #version 450
    layout(location = 0) in vec2 vUv;
    layout(set = 0, binding = 0) uniform sampler2D sourceTex;
    layout(push_constant) uniform BloomBrightPush {
        float threshold;
    } push;
    layout(location = 0) out vec4 outColor;

    void main() {
        vec3 c = texture(sourceTex, vUv).rgb;
        float luma = dot(c, vec3(0.2126, 0.7152, 0.0722));
        float knee = max(push.threshold, 1e-4);
        // Soft-knee bright pass: fade in contribution around the threshold.
        float contribution = clamp((luma - knee) / max(knee, 1e-4), 0.0, 1.0);
        outColor = vec4(c * contribution, 1.0);
    }
"""

internal const val BLOOM_BLUR_FRAGMENT = """
    #version 450
    layout(location = 0) in vec2 vUv;
    layout(set = 0, binding = 0) uniform sampler2D sourceTex;
    layout(push_constant) uniform BloomBlurPush {
        vec2 direction;
        float radius;
    } push;
    layout(location = 0) out vec4 outColor;

    void main() {
        vec2 texel = 1.0 / vec2(textureSize(sourceTex, 0));
        vec2 step = push.direction * texel * max(push.radius, 0.0);
        // 9-tap Gaussian.
        float w0 = 0.227027;
        float w1 = 0.1945946;
        float w2 = 0.1216216;
        float w3 = 0.054054;
        float w4 = 0.016216;
        vec3 result = texture(sourceTex, vUv).rgb * w0;
        result += texture(sourceTex, vUv + step * 1.0).rgb * w1;
        result += texture(sourceTex, vUv - step * 1.0).rgb * w1;
        result += texture(sourceTex, vUv + step * 2.0).rgb * w2;
        result += texture(sourceTex, vUv - step * 2.0).rgb * w2;
        result += texture(sourceTex, vUv + step * 3.0).rgb * w3;
        result += texture(sourceTex, vUv - step * 3.0).rgb * w3;
        result += texture(sourceTex, vUv + step * 4.0).rgb * w4;
        result += texture(sourceTex, vUv - step * 4.0).rgb * w4;
        outColor = vec4(result, 1.0);
    }
"""

internal const val BLOOM_COMPOSITE_FRAGMENT = """
    #version 450
    layout(location = 0) in vec2 vUv;
    layout(set = 0, binding = 0) uniform sampler2D sourceTex; // blurred highlights
    layout(set = 0, binding = 1) uniform sampler2D sceneTex;  // pre-bloom scene
    layout(push_constant) uniform BloomCompositePush {
        float intensity;
    } push;
    layout(location = 0) out vec4 outColor;

    void main() {
        vec4 scene = texture(sceneTex, vUv);
        vec3 bloom = texture(sourceTex, vUv).rgb;
        outColor = vec4(scene.rgb + bloom * max(push.intensity, 0.0), scene.a);
    }
"""
