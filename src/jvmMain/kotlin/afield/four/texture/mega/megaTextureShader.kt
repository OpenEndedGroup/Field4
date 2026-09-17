package afield.four.texture.mega

import afield.four.util.Resolver

/**
 * The GLSL a box pulls into its own shaders to talk to a [MegaTexture].
 *
 * This is the part of the system that is deliberately *not* hidden in the backend. Generating a
 * whole vertex/fragment pair from Kotlin, which is what the previous implementation did, meant the
 * scene's geometry, its layout and its sampling were all decided somewhere the person looking at
 * the wall could not edit. Here the backend contributes declarations and a handful of functions,
 * and the box owns every line of shader that actually draws anything.
 *
 * Register the map on a box and interpolate the slots:
 *
 * ```
 * shaderTemplate["mega"] = mega.template
 * ```
 * ```glsl
 * #version 450
 * {{ mega.all }}
 * layout(location = 0) in vec2 ftc;
 * layout(location = 1) flat in uint fid;
 * layout(location = 0) out vec4 outColor;
 * void main() { outColor = megaTexture(fid, ftc); }
 * ```
 *
 * The slots, smallest first:
 *
 * - `declarations` — the three bindings and the shape constants. Needed by any stage that touches
 *   the system at all.
 * - `functions` — residency queries, the mean-colour fallback, and `megaSampleResident`. Safe in
 *   any stage.
 * - `fragment` — the derivative-based footprint estimate, `megaRequest` and `megaTexture`.
 *   Fragment stage only: `dFdx` does not exist elsewhere, and a request that is never made is how
 *   a megatexture stays blurry forever.
 * - `all` — `declarations` + `functions` + `fragment`.
 * - `vertex` — `declarations` + `functions`, for a vertex stage that wants `megaAspect` to shape a
 *   quad to its picture.
 *
 * Plus `imageCount`, `levels`, `atlasEdge`, `basePageWidth`, `basePageHeight`, `topPageWidth` and
 * `topPageHeight` as bare numbers, for shader code that wants to do its own arithmetic.
 */
fun megaTextureTemplate(mega: MegaTexture): Map<String, Any> =
    megaTextureTemplate(mega.layout, mega.config)

/**
 * [megaTextureTemplate] from the two objects that actually shape the GLSL.
 *
 * Neither needs a Vulkan device, which is the point: `MegaTextureShaderTest` compiles the real
 * output of this through shaderc and introspects it, so the contract between the shader's bindings
 * and [MegaTexture.bind]'s resolvers is checked on a machine with no GPU.
 */
fun megaTextureTemplate(layout: MegaTextureLayout, config: MegaTextureConfig): Map<String, Any> = mapOf(
    "declarations" to Resolver { megaDeclarationsSource(layout, config) },
    "functions" to Resolver { megaFunctionsSource(layout, config) },
    "fragment" to Resolver { megaFragmentSource(layout, config) },
    "vertex" to Resolver { megaVertexSource(layout, config) },
    "all" to Resolver { megaAllSource(layout, config) },
    "imageCount" to layout.imageCount.toString(),
    "levels" to layout.levels.toString(),
    "basePageWidth" to layout.pageWidth(0).toString(),
    "basePageHeight" to layout.pageHeight(0).toString(),
    "topPageWidth" to layout.topPageWidth.toString(),
    "topPageHeight" to layout.topPageHeight.toString(),
    "atlasEdge" to layout.atlasEdge.toString()
)

/** `declarations` + `functions`: everything a vertex stage may legally use. */
fun megaVertexSource(layout: MegaTextureLayout, config: MegaTextureConfig): String =
    megaDeclarationsSource(layout, config) + "\n" + megaFunctionsSource(layout, config)

/** `declarations` + `functions` + `fragment`: the whole thing, for a fragment stage. */
fun megaAllSource(layout: MegaTextureLayout, config: MegaTextureConfig): String =
    megaVertexSource(layout, config) + "\n" + megaFragmentSource(layout, config)

/**
 * The bindings and the numbers that describe the atlas.
 *
 * `megaPages` and `megaWanted` are both declared `buffer` rather than `readonly`/`writeonly`: the
 * page table is read from both stages, and the feedback buffer is atomically read-modify-written,
 * which `writeonly` would forbid.
 */
fun megaDeclarationsSource(layout: MegaTextureLayout, config: MegaTextureConfig): String {
    return """
// --- megaTexture: bindings and shape (generated from MegaTexture '${config.name}') ---
layout(set = ${config.descriptorSet}, binding = ${config.atlasBinding}) uniform sampler2DArray ${config.atlasName};

layout(set = ${config.descriptorSet}, binding = ${config.pageTableBinding}, std430) buffer ${config.pageTableName} {
    uvec4 megaPages[];
};

layout(set = ${config.descriptorSet}, binding = ${config.feedbackBinding}, std430) buffer ${config.feedbackName} {
    uint megaWanted[];
};

const uint  megaImageCount   = ${layout.imageCount}u;
const int   megaLevels       = ${layout.levels};
const float megaAtlasEdge    = ${layout.atlasEdge}.0;

// The page ladder, as the two ends of it. Pages are not square in general — for a film set they
// are the shape of a frame — and they are not a shift apart either, because halving 798 twice
// gives 200 and not 199.5. So the top page is the source size and every level below it is that
// halved and rounded up, which is what `megaLevelForPixels` inverts.
const float megaTopPageWidth  = ${layout.topPageWidth}.0;
const float megaTopPageHeight = ${layout.topPageHeight}.0;
const float megaBasePageWidth = ${layout.pageWidth(0)}.0;
const uint  megaResidentBit  = ${"0x%xu".format(MegaTextureLayout.RESIDENT_BIT)};
const int   megaLevelShift   = ${MegaTextureLayout.LEVEL_SHIFT};
const uint  megaLayerMask    = ${"0x%xu".format(MegaTextureLayout.MAX_LAYER)};
const int   megaFadeShift    = ${MegaTextureLayout.FADE_SHIFT};

// Two entries per image: the page being shown, and the page it is fading in over. The second is
// the whole reason the stride is not 1 — a crossfade needs the *address* of the outgoing page, and
// the CPU is the only thing that knows which atlas slot that was.
const uint  megaEntryStride  = ${MegaTextureLayout.ENTRY_STRIDE_WORDS / MegaTextureLayout.ENTRY_WORDS}u;
""".trimIndent()
}

/**
 * Everything that only reads the page table, so it is legal in any stage.
 *
 * The page-table entry is unpacked here and nowhere else — the layout is
 * `x = pageX | pageY<<16`, `y = layer | level<<12 | residentBit`, `z = mean colour`,
 * `w = usedWidth | usedHeight<<16` — and `MegaTexture.writeEntry` is the only thing that writes
 * it. Two places, one format.
 *
 * Two details worth the lines they cost. `megaSampleResident` insets its coordinates by half a
 * texel and clamps to the page's *used* rectangle, which is why no page needs a border and no
 * neighbour ever bleeds in. And the mean colour is converted from sRGB, because the atlas is an
 * `_SRGB` format whose samples arrive linearised — without this, an image waiting for its first
 * page would be visibly brighter than the same image once it arrived.
 */
fun megaFunctionsSource(layout: MegaTextureLayout, config: MegaTextureConfig): String {
    return """
// --- megaTexture: page table queries ---

/** The entry for the page this image is showing. */
uvec4 megaEntry(uint id) { return megaPages[id * megaEntryStride]; }

/** The entry for the page it is fading in over; not resident when there is nothing to fade from. */
uvec4 megaOutgoingEntry(uint id) { return megaPages[id * megaEntryStride + 1u]; }

/** Highest level currently resident for this image, or -1 if it has none yet. */
int megaResidentLevel(uint id) {
    if (id >= megaImageCount) return -1;
    uint word = megaEntry(id).y;
    return ((word & megaResidentBit) != 0u) ? int((word >> megaLevelShift) & 0xfu) : -1;
}

/** Width / height of the image as currently known; 1.0 before anything has been decoded. */
float megaAspect(uint id) {
    if (id >= megaImageCount) return 1.0;
    uvec4 entry = megaEntry(id);
    vec2 used = vec2(float(entry.w & 0xffffu), float(entry.w >> 16));
    return (used.x > 0.0 && used.y > 0.0) ? used.x / used.y : 1.0;
}

vec3 megaSrgbToLinear(vec3 c) {
    return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), c));
}

/** The image's mean colour — what it draws as while it is still loading. */
vec4 megaAverageColor(uint id) {
    if (id >= megaImageCount) return vec4(0.5, 0.5, 0.5, 1.0);
    uint packed = megaEntry(id).z;
    vec3 srgb = vec3(
        float(packed & 255u),
        float((packed >> 8) & 255u),
        float((packed >> 16) & 255u)
    ) / 255.0;
    return vec4(megaSrgbToLinear(srgb), float((packed >> 24) & 255u) / 255.0);
}

/** Sample the page an entry names. The caller has already checked that it is resident. */
vec4 megaSampleEntry(uvec4 entry, vec2 uv) {
    vec2 origin = vec2(float(entry.x & 0xffffu), float(entry.x >> 16));
    vec2 used   = vec2(float(entry.w & 0xffffu), float(entry.w >> 16));
    float layer = float(entry.y & megaLayerMask);
    vec2 texel = clamp(uv, vec2(0.0), vec2(1.0)) * max(used - vec2(1.0), vec2(0.0)) + vec2(0.5);
    return textureLod(${config.atlasName}, vec3((origin + texel) / megaAtlasEdge, layer), 0.0);
}

/**
 * Sample whatever page this image currently has, without asking for a better one.
 *
 * Two taps rather than one while a page is fading in, and one everywhere else — the early-out is
 * on a value the CPU controls, so with `crossfadeFrames = 0` this costs a compare and nothing
 * more. What it fades *from* is the previous page if there was one and the mean colour if there
 * was not, which means the first page an image ever gets arrives as a dissolve out of its own
 * average rather than as a rectangle snapping from flat colour to photograph.
 */
vec4 megaSampleResident(uint id, vec2 uv) {
    if (id >= megaImageCount) return vec4(1.0, 0.0, 1.0, 1.0);
    uvec4 entry = megaEntry(id);
    if ((entry.y & megaResidentBit) == 0u) return megaAverageColor(id);
    vec4 arrived = megaSampleEntry(entry, uv);
    float fade = float((entry.y >> megaFadeShift) & 255u) * (1.0 / 255.0);
    if (fade >= 1.0) return arrived;
    uvec4 outgoing = megaOutgoingEntry(id);
    vec4 leaving = ((outgoing.y & megaResidentBit) != 0u)
        ? megaSampleEntry(outgoing, uv)
        : megaAverageColor(id);
    return mix(leaving, arrived, fade);
}
""".trimIndent()
}

/**
 * The feedback half: work out what resolution this fragment needs and say so.
 *
 * [megaScreenPixels] is the only place the scene's scale is ever measured, and it is measured from
 * `dFdx`/`dFdy` of the image's own UVs — so it is correct for a quad, a sphere, a distorted mesh
 * or a ray march, with no agreement needed between the shader and the CPU. When the derivatives
 * come out as zero (a degenerate triangle, a point-sized quad) it asks for the coarsest level
 * rather than dividing by zero, which is also the cheapest thing to be wrong about.
 *
 * It must be called in uniform control flow, like any derivative: put `megaTexture` on the main
 * path and branch on its result, not the other way round.
 */
@Suppress("UNUSED_PARAMETER")
fun megaFragmentSource(layout: MegaTextureLayout, config: MegaTextureConfig): String = """
// --- megaTexture: feedback (fragment stage only — uses derivatives) ---

/** How many pixels across the whole image is being drawn at, from its UV derivatives. */
float megaScreenPixels(vec2 uv) {
    vec2 ddx = dFdx(uv);
    vec2 ddy = dFdy(uv);
    float perPixel = max(max(abs(ddx.x), abs(ddx.y)), max(abs(ddy.x), abs(ddy.y)));
    return (perPixel > 0.0) ? 1.0 / perPixel : megaTopPageWidth;
}

/**
 * The smallest level whose page is at least this many pixels across.
 *
 * Counted down from the top rather than up from the base, because that is how the ladder is
 * built: the top page is the source width, and level `n` below it is that width over `2^n`. So
 * the number of halvings that still leaves enough width is `floor(log2(top / wanted))`, and the
 * level is that many steps below the top.
 */
int megaLevelForPixels(float pixelsAcross) {
    float halvings = floor(log2(megaTopPageWidth / max(pixelsAcross, 1.0)));
    return clamp(megaLevels - 1 - int(halvings), 0, megaLevels - 1);
}

/** Tell the CPU this image wants this level. Coarser requests never undo finer ones. */
void megaRequest(uint id, int level) {
    if (id >= megaImageCount) return;
    atomicMax(megaWanted[id], uint(clamp(level, 0, megaLevels - 1)) + 1u);
}

/** Request the level this fragment needs, and sample the best one that has arrived. */
vec4 megaTexture(uint id, vec2 uv) {
    megaRequest(id, megaLevelForPixels(megaScreenPixels(uv)));
    return megaSampleResident(id, uv);
}
""".trimIndent()
