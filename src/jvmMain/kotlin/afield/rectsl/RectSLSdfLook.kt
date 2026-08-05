package afield.rectsl

/**
 * What to *do* with a RectSL distance field once you have one.
 *
 * [RectSLGlsl.emitSdf] answers a single question per point — how far to the
 * nearest visible rectangle, at this instant — and its KDoc lists what that
 * buys: outlines and glows at any width, blending between rectangles, boolean
 * ops, per-pixel normals, sub-frame motion blur. Each of those is a handful of
 * GLSL lines over the same `float`, and writing them once per demo is how a
 * capability stays a claim. This file is the vocabulary instead: a look is data,
 * [RectSLSdfShader] turns `(field, look)` into a complete fragment shader, and
 * [RectSLSdfLook.PRESETS] is one worked example of every advertised capability.
 *
 * # What is a look and what is not
 *
 * Two of the capabilities are *not* looks, and the split matters. Smooth-union
 * between rects changes the set itself, so it happens in the emitter
 * ([RectSLSdfOptions.blendRadius]) — a look can only carry the number and hand
 * it over. Boolean subtraction needs a *second* field, so a carving look is only
 * meaningful when the caller supplies one; [RectSLSdfShader.fragment] requires
 * it and says so.
 *
 * # Why the edges are measured in pixels
 *
 * Every softness here is expressed as a multiple of one screen pixel, recovered
 * per-fragment with `fwidth` on the world coordinate. That is the whole point of
 * a distance field over a rasterized quad: the same scene at 512px and at 4096px
 * has one-pixel edges at both, and no geometry was re-tessellated to get there.
 * Widths that describe the *scene* rather than the screen — an outline's
 * thickness, a contour's spacing, a bevel's depth — are in wall units, because
 * those are properties of the drawing and should zoom with it.
 */
data class RectSLSdfLook(
    /** Short slug; used for asset filenames and as the shader's leading comment. */
    val name: String,
    /** One line saying what this look demonstrates. Rendered into the docs table. */
    val about: String,
    val background: RectSLSdfColor = RectSLSdfColor(0x0b0d12),
    /** Solid interior colour, or null to leave the inside unpainted. */
    val fillColor: RectSLSdfColor? = RectSLSdfColor(0x2f9fe8),
    /** Band centred on the zero set, [outlineWidth] wall units to each side. */
    val outlineColor: RectSLSdfColor? = null,
    val outlineWidth: Double = 0.0,
    /** Exponential falloff outside the shape; [glowFalloff] is per wall unit. */
    val glowColor: RectSLSdfColor? = null,
    val glowFalloff: Double = 6.0,
    /** Iso-distance lines every [contourSpacing] wall units. Zero disables them. */
    val contourColor: RectSLSdfColor = RectSLSdfColor(0x39536e),
    val contourSpacing: Double = 0.0,
    /** Depth in wall units of the rounded bevel lit by [lightDirection]. Zero disables. */
    val reliefDepth: Double = 0.0,
    val lightDirection: Triple<Double, Double, Double> = Triple(-0.45, 0.55, 0.70),
    /** Instants sampled inside one frame. 1 disables motion blur. */
    val motionBlurSamples: Int = 1,
    /** Width of the open shutter, in seconds of scene time. */
    val shutterSeconds: Double = 0.0,
    /** Handed to [RectSLSdfOptions] — the union becomes a smooth minimum of this radius. */
    val blendRadius: Double = 0.0,
    /** True when this look subtracts a second field; [RectSLSdfShader.fragment] then needs one. */
    val carves: Boolean = false,
    /** Antialiasing width, in screen pixels. */
    val edgeSoftnessPixels: Double = 1.2
) {
    init {
        require(name.isNotBlank()) { "a look needs a name" }
        require(motionBlurSamples >= 1) { "motionBlurSamples must be at least 1" }
        require(outlineWidth >= 0.0 && contourSpacing >= 0.0 && reliefDepth >= 0.0) {
            "widths cannot be negative"
        }
        require(motionBlurSamples == 1 || shutterSeconds > 0.0) {
            "motion blur over a zero-width shutter would sample the same instant $motionBlurSamples times"
        }
    }

    /** The emitter options this look implies. Only the union operator is the emitter's business. */
    fun emitOptions(namePrefix: String = "rectsl"): RectSLSdfOptions =
        RectSLSdfOptions(blendRadius = blendRadius, namePrefix = namePrefix)

    companion object {
        /**
         * One preset per advertised capability, in the order the emitter's KDoc
         * lists them. `RectSLSdfLookTest` fails if a capability named in that
         * paragraph has no preset here, which is the same guard that keeps every
         * RectSL operator inside a worked example.
         */
        val PRESETS: List<RectSLSdfLook> = listOf(
            RectSLSdfLook(
                name = "fill",
                about = "The baseline: an antialiased interior, one pixel of edge at any resolution.",
                fillColor = RectSLSdfColor(0x2f9fe8)
            ),
            RectSLSdfLook(
                name = "outline",
                about = "An outline at an arbitrary width, from the same float — no stroke geometry.",
                fillColor = null,
                outlineColor = RectSLSdfColor(0xf2f5f8),
                outlineWidth = 0.035
            ),
            RectSLSdfLook(
                name = "glow",
                about = "Exponential falloff *outside* the shape, which a coverage mask cannot express.",
                fillColor = RectSLSdfColor(0x123048),
                outlineColor = RectSLSdfColor(0x9fe0ff),
                outlineWidth = 0.02,
                glowColor = RectSLSdfColor(0x2aa8ff),
                glowFalloff = 3.2
            ),
            RectSLSdfLook(
                name = "contours",
                about = "Iso-distance lines: direct evidence the field is a distance and not a mask.",
                fillColor = RectSLSdfColor(0x1d3550),
                outlineColor = RectSLSdfColor(0xffd23c),
                outlineWidth = 0.012,
                contourColor = RectSLSdfColor(0x3d5f80),
                contourSpacing = 0.22
            ),
            RectSLSdfLook(
                name = "relief",
                about = "Per-pixel normals from the gradient, lit — a 2D scene with a third dimension.",
                fillColor = RectSLSdfColor(0x5f7fa6),
                reliefDepth = 0.28
            ),
            RectSLSdfLook(
                name = "blend",
                about = "smin between rectangles: the copies of an array fuse as they pass each other.",
                fillColor = RectSLSdfColor(0x7ad0b0),
                reliefDepth = 0.30,
                blendRadius = 0.55
            ),
            RectSLSdfLook(
                name = "carve",
                about = "Boolean subtraction of a second RectSL scene from the first.",
                fillColor = RectSLSdfColor(0xe8734a),
                outlineColor = RectSLSdfColor(0xffe7d5),
                outlineWidth = 0.02,
                carves = true
            ),
            RectSLSdfLook(
                name = "motion-blur",
                about = "24 instants inside one frame — closed-form tracks, so the field knows them all.",
                fillColor = RectSLSdfColor(0xffb347),
                glowColor = RectSLSdfColor(0x40210a),
                glowFalloff = 5.0,
                motionBlurSamples = 24,
                shutterSeconds = 0.14
            )
        )

        /** Look up a preset by [name], or fail with the list of names that exist. */
        fun preset(name: String): RectSLSdfLook =
            PRESETS.firstOrNull { it.name == name }
                ?: error("no RectSL SDF look called '$name'; have ${PRESETS.map { it.name }}")
    }
}

/**
 * A colour written the way RectSL writes one — `RectSLSdfColor(0xff3300)` — so a
 * look reads like the `->fill` it is standing in for.
 */
@JvmInline
value class RectSLSdfColor(val rgb: Int) {
    val r: Double get() = ((rgb shr 16) and 0xff) / 255.0
    val g: Double get() = ((rgb shr 8) and 0xff) / 255.0
    val b: Double get() = (rgb and 0xff) / 255.0

    /** As a GLSL literal, in the linear-ish space the render target expects. */
    fun glsl(): String = "vec3(${fmt(r)}, ${fmt(g)}, ${fmt(b)})"

    private fun fmt(v: Double): String = String.format("%.5f", v)
}

/**
 * Assembles a complete, compilable pair of shaders around an emitted field.
 *
 * The vertex stage is a fullscreen triangle that carries **wall world
 * coordinates**, not UVs, into the fragment stage — `scene(p, t)` is defined in
 * wall units, so handing it anything else would silently rescale every distance
 * in the look. [VERTEX] and [fragment] share one push-constant block by
 * construction, because Vulkan requires the two stages of a pipeline to agree
 * about it.
 */
object RectSLSdfShader {

    /** The push block every shader in this file declares, in both stages. */
    val PUSH_BLOCK: String = """
layout(push_constant) uniform Push {
    float time;
    float wallWidth;
    float wallHeight;
    float probeChannel;
    float probeRange;
} push;
""".trimIndent()

    /**
     * Fullscreen triangle, no vertex buffers: three `gl_VertexIndex` corners
     * covering the clip cube, with `v_world` the wall-space point under the
     * fragment. Draw it with `mesh.resize(3, 0, module)`.
     */
    val VERTEX: String = buildString {
        appendLine("#version 450")
        appendLine("layout(location = 0) out vec2 v_world;")
        appendLine()
        appendLine(PUSH_BLOCK)
        appendLine()
        appendLine("void main() {")
        appendLine("    // The standard three-corner fullscreen triangle: 0 -> (0,0), 1 -> (2,0), 2 -> (0,2).")
        appendLine("    vec2 unit = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);")
        appendLine("    vec2 ndc = unit * 2.0 - 1.0;")
        appendLine("    // Exactly WallSpec.worldToClip's mapping, inverted: that is an")
        appendLine("    // ortho(-w/2, w/2, -h/2, h/2), which sends world -h/2 to clip y = -1. Negate")
        appendLine("    // this and an SDF render comes out upside down against the raster render of")
        appendLine("    // the same scene — which is how the side-by-side counterfactual caught it.")
        appendLine("    v_world = ndc * vec2(push.wallWidth, push.wallHeight) * 0.5;")
        appendLine("    gl_Position = vec4(ndc, 0.0, 1.0);")
        appendLine("}")
    }

    /** Helpers the looks share; emitted once, ahead of the field. */
    private val LOOK_HELPERS: String = """
// One screen pixel, measured in wall units, from the world coordinate's own
// screen-space derivative. This is why an outline is a pixel wide at every zoom.
float rectslPixel(vec2 p) {
    return max(fwidth(p.x), fwidth(p.y));
}

// Coverage of the region d < 0, antialiased over `soft` wall units.
float rectslCoverage(float d, float soft) {
    return 1.0 - smoothstep(-soft, soft, d);
}

// Gradient of the field: unit length outside the shape, so it doubles as a normal.
vec2 rectslGradient(float dx0, float dx1, float dy0, float dy1, float e) {
    return vec2(dx1 - dx0, dy1 - dy0) / (2.0 * e);
}
""".trimIndent()

    /**
     * The whole fragment shader for one field and one look.
     *
     * [carve] is the second field a `carves` look subtracts; it must have been
     * emitted with a different [RectSLSdfOptions.namePrefix] so its symbols do
     * not collide, and its globals are merged with [scene]'s so the shared
     * helpers are written exactly once.
     */
    fun fragment(
        scene: RectSLSdfResult,
        look: RectSLSdfLook,
        carve: RectSLSdfResult? = null
    ): String {
        require(!look.carves || carve != null) {
            "look '${look.name}' subtracts a second field, so fragment() needs a `carve` scene"
        }
        require(carve == null || carve.sceneFunctionName != scene.sceneFunctionName) {
            "the carving field must be emitted under a different function name than the scene"
        }
        // Globals are name-keyed precisely so this merge is a no-op for anything
        // both fields need (the box prelude, a shared easing curve).
        val globals = LinkedHashMap<String, String>(scene.globals)
        carve?.globals?.forEach { (name, text) -> globals.putIfAbsent(name, text) }

        return buildString {
            appendLine("#version 450")
            appendLine("// RectSL signed distance field, look '${look.name}'.")
            appendLine("// ${look.about}")
            appendLine("layout(location = 0) in vec2 v_world;")
            appendLine("layout(location = 0) out vec4 outColor;")
            appendLine()
            appendLine(PUSH_BLOCK)
            appendLine()
            globals.values.forEach { appendLine(it); appendLine() }
            appendLine(LOOK_HELPERS)
            appendLine()
            append(scene.sceneGlsl)
            appendLine()
            if (carve != null) {
                appendLine()
                append(carve.sceneGlsl)
                appendLine()
            }
            appendLine()
            appendLine(sceneAccessor(scene, carve, look))
            appendLine()
            append(mainBody(look))
        }
    }

    /**
     * The one place the look reads the field. Everything downstream calls
     * `rectslField(p, t)`, so subtraction is a property of the field rather than
     * something every shading term has to remember to apply.
     */
    private fun sceneAccessor(
        scene: RectSLSdfResult,
        carve: RectSLSdfResult?,
        look: RectSLSdfLook
    ): String = buildString {
        appendLine("float rectslField(vec2 p, float t) {")
        appendLine("    float d = ${scene.sceneFunctionName}(p, t);")
        if (look.carves && carve != null) {
            appendLine("    // A minus B: intersect the scene with the complement of the carving field.")
            appendLine("    float c = ${carve.sceneFunctionName}(p, t);")
            appendLine("    d = max(d, -c);")
        }
        appendLine("    return d;")
        append("}")
    }

    private fun mainBody(look: RectSLSdfLook): String = buildString {
        val n = look.motionBlurSamples
        appendLine("void main() {")
        appendLine("    vec2 p = v_world;")
        appendLine("    float px = rectslPixel(p);")
        appendLine("    float soft = ${f(look.edgeSoftnessPixels)} * px;")
        appendLine()
        if (n > 1) {
            appendLine("    // Sub-frame motion blur: $n instants across a ${f(look.shutterSeconds)}s shutter.")
            appendLine("    // Nothing was rendered at these times — the tracks are closed-form, so the")
            appendLine("    // field simply answers for them.")
            appendLine("    float d = 1e9;")
            appendLine("    float cov = 0.0;")
            appendLine("    for (int i = 0; i < $n; ++i) {")
            appendLine("        float ti = push.time + (float(i) / ${f((n - 1).toDouble())} - 0.5) * ${f(look.shutterSeconds)};")
            appendLine("        float di = rectslField(p, ti);")
            appendLine("        d = min(d, di);")
            appendLine("        cov += rectslCoverage(di, soft);")
            appendLine("    }")
            appendLine("    cov /= ${f(n.toDouble())};")
        } else {
            appendLine("    float d = rectslField(p, push.time);")
            appendLine("    float cov = rectslCoverage(d, soft);")
        }
        appendLine()
        appendLine("    vec3 c = ${look.background.glsl()};")

        if (look.glowColor != null) {
            appendLine()
            appendLine("    // Glow: falls off with *distance* outside the shape, not with coverage.")
            appendLine("    float glow = exp(-${f(look.glowFalloff)} * max(d, 0.0));")
            appendLine("    c += ${look.glowColor.glsl()} * glow;")
        }
        if (look.fillColor != null) {
            appendLine()
            if (look.reliefDepth > 0.0) {
                appendLine("    // Per-pixel normals: the gradient of the field is the surface normal of a")
                appendLine("    // rounded extrusion of it, so a flat 2D scene picks up a light.")
                appendLine("    float e = max(px, 1e-4);")
                appendLine("    vec2 g = rectslGradient(")
                appendLine("        rectslField(p - vec2(e, 0.0), push.time), rectslField(p + vec2(e, 0.0), push.time),")
                appendLine("        rectslField(p - vec2(0.0, e), push.time), rectslField(p + vec2(0.0, e), push.time), e);")
                appendLine("    g = normalize(g + vec2(1e-9));")
                appendLine("    float k = clamp(-d / ${f(look.reliefDepth)}, 0.0, 1.0);")
                appendLine("    float dome = sqrt(max(1.0 - (1.0 - k) * (1.0 - k), 0.0));")
                appendLine("    vec3 nrm = normalize(vec3(g * (1.0 - dome), max(dome, 1e-3)));")
                val (lx, ly, lz) = look.lightDirection
                appendLine("    vec3 lig = normalize(vec3(${f(lx)}, ${f(ly)}, ${f(lz)}));")
                appendLine("    float diff = 0.25 + 0.75 * max(dot(nrm, lig), 0.0);")
                appendLine("    float spec = pow(max(dot(reflect(-lig, nrm), vec3(0.0, 0.0, 1.0)), 0.0), 24.0);")
                appendLine("    vec3 body = ${look.fillColor.glsl()} * diff + vec3(spec * 0.35);")
            } else {
                appendLine("    vec3 body = ${look.fillColor.glsl()};")
            }
            appendLine("    c = mix(c, body, cov);")
        }
        if (look.contourSpacing > 0.0) {
            appendLine()
            appendLine("    // Iso-distance lines every ${f(look.contourSpacing)} wall units — drawn *after*")
            appendLine("    // the fill so the interior bands survive, which is the half that shows the")
            appendLine("    // field is signed and not a coverage mask.")
            appendLine("    float band = abs(fract(d / ${f(look.contourSpacing)} + 0.5) - 0.5) * ${f(look.contourSpacing)};")
            appendLine("    float line = 1.0 - smoothstep(0.6 * px, 1.6 * px, band);")
            appendLine("    c = mix(c, ${look.contourColor.glsl()}, line * 0.85);")
        }
        if (look.outlineColor != null && look.outlineWidth > 0.0) {
            appendLine()
            appendLine("    // Outline: a band |d| < ${f(look.outlineWidth)}, which needs the distance and")
            appendLine("    // nothing else. No stroke geometry, no width limit.")
            appendLine("    float edge = 1.0 - smoothstep(0.0, soft, abs(d) - ${f(look.outlineWidth)});")
            appendLine("    c = mix(c, ${look.outlineColor.glsl()}, edge);")
        }
        appendLine()
        appendLine("    outColor = vec4(clamp(c, 0.0, 1.0), 1.0);")
        append("}")
    }

    /**
     * A fragment shader that does not draw the field but *reports* it.
     *
     * One of `scene(p, t)`, `p.x` or `p.y` — selected by the `probeChannel` push
     * constant — is written into the RGB channels as a 24-bit fixed-point number,
     * with alpha pinned to 1. That is the whole differential: read the framebuffer
     * back and you have what the *compiled shader* thinks, at world positions the
     * shader itself reported, ready to compare against the CPU sampler. Nothing is
     * assumed about how pixels map to the wall, which is the one thing a
     * hand-written mapping always gets wrong.
     *
     * The two coordinate channels are encoded over the **wall's own size**, not
     * over `probeRange`: a caller narrowing the distance range to resolve it
     * finely must not thereby saturate the coordinates and quietly destroy the
     * mapping it was about to fit. The first run of this probe did exactly that —
     * `probeRange = 1` against a 9-unit wall clamped every `p.x` outside the
     * middle ninth, and the differential reported 1.4 wall units of disagreement
     * that were entirely its own.
     *
     * Alpha is left at 1 deliberately: the readback goes through a PNG, and a PNG
     * with a non-opaque alpha invites a decoder to premultiply the colour.
     */
    fun probeFragment(scene: RectSLSdfResult): String = buildString {
        appendLine("#version 450")
        appendLine("// RectSL SDF probe: reports the field rather than drawing it.")
        appendLine("layout(location = 0) in vec2 v_world;")
        appendLine("layout(location = 0) out vec4 outColor;")
        appendLine()
        appendLine(PUSH_BLOCK)
        appendLine()
        scene.globals.values.forEach { appendLine(it); appendLine() }
        append(scene.sceneGlsl)
        appendLine()
        appendLine()
        appendLine(
            """
            // 24-bit fixed point across three UNORM8 channels. The readback path
            // copies R8G8B8A8_UNORM bytes straight out and PNG-encodes them, so
            // every channel survives as exactly k/255.
            vec3 rectslPack24(float v01) {
                float u = clamp(v01, 0.0, 1.0);
                float scaled = floor(u * 16777215.0 + 0.5);
                float hi = floor(scaled / 65536.0);
                float mid = floor((scaled - hi * 65536.0) / 256.0);
                float lo = scaled - hi * 65536.0 - mid * 256.0;
                return vec3(hi, mid, lo) / 255.0;
            }
            """.trimIndent()
        )
        appendLine()
        appendLine("void main() {")
        appendLine("    vec2 p = v_world;")
        appendLine("    float v;")
        appendLine("    float range;")
        appendLine("    if (push.probeChannel < 0.5) {")
        appendLine("        v = ${scene.sceneFunctionName}(p, push.time);")
        appendLine("        range = push.probeRange;")
        appendLine("    } else if (push.probeChannel < 1.5) {")
        appendLine("        v = p.x;")
        appendLine("        range = push.wallWidth;   // |p.x| <= wallWidth/2, so never clamped")
        appendLine("    } else {")
        appendLine("        v = p.y;")
        appendLine("        range = push.wallHeight;")
        appendLine("    }")
        appendLine("    float u = clamp(v / range, -1.0, 1.0) * 0.5 + 0.5;")
        appendLine("    outColor = vec4(rectslPack24(u), 1.0);")
        append("}")
    }

    /** The inverse of `rectslPack24`, for the CPU side of the probe. */
    fun unpack24(r: Int, g: Int, b: Int, range: Double): Double {
        val scaled = (r * 65536 + g * 256 + b).toDouble()
        val u = scaled / 16777215.0
        return (u * 2.0 - 1.0) * range
    }

    /** The width of one 24-bit step at [range] — the probe's own quantisation floor. */
    fun quantisation(range: Double): Double = 2.0 * range / 16777215.0

    private fun f(v: Double): String {
        val s = String.format("%.6f", v)
        return if (s.contains('.')) s else "$s.0"
    }
}
