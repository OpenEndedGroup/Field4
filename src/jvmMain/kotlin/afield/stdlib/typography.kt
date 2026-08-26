@file:afield.CompletionCategory("Graphics")

package afield.stdlib

import afield.Rect
import afield.graphics.FLine
import java.awt.Font
import java.awt.Shape
import java.awt.font.FontRenderContext
import java.awt.font.TextAttribute
import java.awt.geom.AffineTransform
import java.awt.geom.PathIterator
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Text as geometry: a string becomes contours, and from there everything Field already does to
 * geometry applies to letters.
 *
 * Field can draw, mesh, filter, cluster, contour and evolve geometry, and before this file it could
 * not turn *a string* into any of it. Every `java.awt.Font` in the tree paints a label into a
 * raster — `MeshGallery`, `ImagingGallery`, `montage`'s 5x7 bitmap font — and none of them produces a
 * value you can extrude, march, sample or animate. This file and its bridge companion
 * `typography_field.kt` are that missing layer, and they need no new dependency:
 * `Font.createGlyphVector(...).getOutline()` hands back real quadratic/cubic glyph outlines, and it
 * works headless.
 *
 * # The three values
 *
 *  - [Typeface] — a font at a size, from a logical/system name ([typeface]) or a `.ttf`/`.otf` on
 *    disk ([typefaceFromFile]). Carries the vertical metrics (ascent, descent, leading) a layout
 *    needs.
 *  - [Glyph] — one glyph's outline, cached per typeface and glyph code, in its own frame with its
 *    origin on its own baseline. Both representations live side by side: [Glyph.contours] are
 *    flattened, nesting-classified, winding-normalised polygons for geometry work, and
 *    [Glyph.outline] is the same rings as an [FLine] of *real cubics* for drawing.
 *  - [TextLayout] — glyphs placed on baselines by AWT's own advances and kerning, plus tracking,
 *    multi-line and alignment. Every glyph in it is a [PlacedGlyph] carrying **mutable transform
 *    handles** (offset, rotation, scale, pivot). Those handles are the API, not an afterthought:
 *    they are what makes kinetic typography a two-line box body, and [TextLayout.onCurve] is
 *    nothing but a routine that sets them from an [FLine]'s arc length.
 *
 * # Text space
 *
 * Glyph and layout coordinates are **x right, y down**, origin at the first baseline, units = the
 * typeface's size in points. That is AWT's own glyph frame and it is also the frame Field's 2D
 * outputs use (`Rect`'s y grows downwards), so text handed straight to `Box.lines` or
 * [Box.showText] reads the right way up with no transform. The 3D bridges in `typography_field.kt`
 * flip y at their own boundary, because a mesh wants y up.
 *
 * # The winding convention
 *
 * A glyph's counter — the hole in `o`, `e`, `a`, `B` — is only a hole if something says so, and
 * "whatever the font shipped" is not good enough: `isoContourFLines` in `contours.kt` leaves winding
 * arbitrary and every consumer downstream has to cope. So this file *decides*:
 *
 * > For a ring `p0..pn`, signed area is `½Σ(xᵢyᵢ₊₁ − xᵢ₊₁yᵢ)` in text space. **Outer contours are
 * > positive; holes are negative.** A ring's [GlyphContour.depth] — how many other rings of the same
 * > glyph contain it — is authoritative: even depth is an outer contour, odd depth is a hole. A ring
 * > whose area sign disagrees with its depth is *reversed* on the way out.
 *
 * Both representations obey it, so `contours.filter { it.isHole }` and the subpaths of
 * [Glyph.outline] agree, and `TypographyTest` asserts it on glyphs whose answer is known by eye
 * (`o` has one hole, `l` has none, `%` has two). Depth comes from point-in-polygon containment and
 * the sign comes from the area formula, so the assertion compares two independent computations
 * rather than restating one of them.
 *
 * # AWT and the main run loop
 *
 * `imaging_field.kt:417` explains why `montage` captions with a bitmap table instead of
 * `Graphics2D.drawString`: on macOS, touching the AWT font manager pulls in AppKit, which competes
 * with GLFW for the main run loop, and `Server.main` never initialises the toolkit. This
 * family cannot avoid the font manager — it *is* the font manager — so it contains the hazard in one
 * place, [Typefaces.warm], whose KDoc carries the measured stack trace and the conclusion:
 *
 * > **A JVM that wants glyph outlines and passes `-XstartOnFirstThread` must also pass
 * > `-Djava.awt.headless=true`.** Otherwise `CFontManager.loadFonts` dispatches to the process's first
 * > thread and waits forever for a run loop nobody is pumping.
 *
 * [Typefaces.warm] detects that in four seconds and throws with that sentence in it, rather than
 * hanging the editor. Every jvm-main gallery in the tree already sets the property in the first line
 * of `main`, `jvmTest` does not need it (Gradle's test JVM has no `-XstartOnFirstThread`), and an
 * interactive Field window pumps its run loop through `glfwPollEvents` and so is expected to be
 * fine — expected, not tested; see [Typefaces.warm]. Everything after that one touch is pure
 * computation over cached outlines.
 */

// ==============================================================================================
// Typeface
// ==============================================================================================

/**
 * The render context every measurement in this file goes through.
 *
 * Identity transform, antialiasing on, **fractional metrics on** — the last one matters: with
 * integer metrics AWT rounds every advance to a whole pixel, which at 12pt is a 4% layout error and
 * makes tracking and kerning comparisons meaningless.
 */
internal val typographyFrc: FontRenderContext = FontRenderContext(AffineTransform(), true, true)

/**
 * A font at a size: the thing you ask for glyphs and layouts.
 *
 * Immutable, and cheap to derive from ([atSize], [withKerning]) because the underlying `java.awt.Font`
 * is shared and the glyph cache is keyed by the AWT font, so two `Typeface`s over the same font at
 * the same size share their extracted outlines.
 *
 * @property awtFont the AWT font, exposed — anything AWT can do to a font (attributes,
 *   transforms, `deriveFont`) is available, and [Typeface.of] wraps the result back up.
 */
class Typeface internal constructor(val awtFont: Font) {

    /** Point size, as a `Double` — `awtFont.size` truncates to `Int`. */
    val size: Double = awtFont.size2D.toDouble()

    /** e.g. `Helvetica`. */
    val familyName: String get() = awtFont.family

    /** e.g. `Helvetica-Bold`, which is what a `.ttf` reports and what round-trips through AWT. */
    val fontName: String get() = awtFont.fontName

    /** Distance from the baseline up to the top of most glyphs, positive, in text-space units. */
    val ascent: Double

    /** Distance from the baseline down to the bottom of descenders, positive. */
    val descent: Double

    /** The font's recommended extra space between lines, positive. */
    val leading: Double

    /** `ascent + descent + leading` — the natural baseline-to-baseline distance at this size. */
    val lineHeight: Double get() = ascent + descent + leading

    /** Whether AWT will apply this font's kerning pairs during layout. */
    val kerning: Boolean = awtFont.attributes[TextAttribute.KERNING] == TextAttribute.KERNING_ON

    init {
        Typefaces.warm()
        val m = awtFont.getLineMetrics("Hxpg", typographyFrc)
        ascent = m.ascent.toDouble()
        descent = m.descent.toDouble()
        leading = m.leading.toDouble()
    }

    /** The same font at a different size. */
    fun atSize(size: Number): Typeface = of(awtFont.deriveFont(size.toFloat()))

    /**
     * The same font with AWT's kerning-pair lookup switched on or off.
     *
     * Not every font has a `kern` table, and logical fonts (`SansSerif`, …) resolve to whatever the
     * platform picked, so this can legitimately be a no-op — [TextLayout.advanceWidth] is the authoritative
     * way to find out whether it did anything for a given font and string.
     */
    fun withKerning(on: Boolean): Typeface {
        // KERNING has exactly one legal value; "off" is the attribute's *absence*, not a zero, so
        // the map value has to be null rather than 0 or AWT keeps kerning on.
        val attrs = mapOf<TextAttribute, Any?>(TextAttribute.KERNING to if (on) TextAttribute.KERNING_ON else null)
        return of(awtFont.deriveFont(attrs))
    }

    /** The same font emboldened / italicised through AWT's style bits (synthetic if the family lacks it). */
    fun withStyle(bold: Boolean = false, italic: Boolean = false): Typeface = of(
        awtFont.deriveFont((if (bold) Font.BOLD else 0) or (if (italic) Font.ITALIC else 0))
    )

    /** True when this font has a glyph for every character of [text]. */
    fun canDisplay(text: String): Boolean = awtFont.canDisplayUpTo(text) < 0

    /** One glyph's outline, by character. Convenience over [layoutText] for a single letter. */
    fun glyph(c: Char): Glyph = layoutText(c.toString(), this).glyphs.firstOrNull()?.glyph
        ?: Glyph(0, c.toString(), 0.0, emptyList(), FLine(), Rect(0.0, 0.0, 0.0, 0.0))

    override fun toString(): String = "Typeface($fontName @ ${"%.1f".format(size)}pt)"

    override fun equals(other: Any?): Boolean = other is Typeface && other.awtFont == awtFont

    override fun hashCode(): Int = awtFont.hashCode()

    companion object {
        /** Wrap an AWT font you already have (e.g. from your own `deriveFont`). */
        fun of(font: Font): Typeface = Typeface(font)
    }
}

/**
 * Font loading, the glyph cache, and the one dangerous moment in this family.
 *
 * [warm] is the only place the AWT font manager is initialised, and it is worth reading its KDoc
 * before changing anything here: which *thread* touches it first decides whether it answers at all
 * on macOS. Call [warm] yourself at startup if you want the one-off cost out of the way.
 */
object Typefaces {

    /**
     * How long a helper thread is given before [warm] concludes that AWT is waiting on the thread that
     * called it, and does the work inline instead.
     *
     * Long enough that a cold font-directory walk on a slow disk is not mistaken for a deadlock, short
     * enough that the fallback happens inside a single box execution.
     */
    private const val WARM_TIMEOUT_MS = 4_000L

    @Volatile
    private var warmed = false

    @Volatile
    private var environmentWarmed = false

    private val glyphCache = ConcurrentHashMap<GlyphKey, Glyph>()

    private data class GlyphKey(val font: Font, val glyphCode: Int)

    /**
     * Initialise the AWT font manager once, on a helper thread, and refuse to hang if it will not
     * answer.
     *
     * **Measured 2026-07-30, and the whole reason this function exists.** In a JVM started with
     * `-XstartOnFirstThread` that is not pumping its run loop, the first
     * `Font.createGlyphVector(...)` *never returns*. The sampled stack says exactly why:
     *
     * ```
     * sun.font.CFontManager.loadFonts <- sun.font.SunFontManager.findFont2D <- java.awt.Font.getFont2D
     *   <- sun.font.StandardGlyphVector.<init> <- java.awt.Font.createGlyphVector
     * ```
     *
     * `CFontManager.loadFonts` dispatches to the process's **first** thread and waits. macOS GLFW
     * requires `-XstartOnFirstThread`, so Field passes it to every jvm-main child
     * (`agent_backdoor.kt:856`) — and in a child that opens no window, nothing ever services that
     * dispatch. Running it inline on the caller does not help either; the caller is not that thread.
     *
     * The fix is `java.awt.headless=true`, and it has to be set **before AWT initialises**: headless
     * AWT does not route the font manager through the main thread at all. That is why every jvm-main
     * gallery in the tree starts with `System.setProperty("java.awt.headless", "true")` and why they
     * never saw this, and why setting it from here — after a box has already touched `BufferedImage`
     * or Skiko — is far too late to matter.
     *
     * So this throws rather than blocks, and the message names the fix. `imaging_field.kt:417`'s rule
     * is that a stdlib call every box might make must not be able to take the process down; a hang
     * would, and a diagnosable exception does not. Idempotent and cheap after the first call — the
     * whole family is pure computation over cached outlines from here on.
     *
     * Verified working: `jvmTest` (no `-XstartOnFirstThread`), the jvm-main galleries and
     * `DemoTranscriptExecSmokeTest` (headless set first). An interactive Field window pumps the run
     * loop through `glfwPollEvents`, so it is expected to be fine and is *not* covered by an automated
     * test — if a live box reports this error, the flag is the answer there too.
     */
    fun warm() {
        if (warmed) return
        synchronized(this) {
            if (warmed) return
            // One real glyph outline: that is what loads libfontmanager and the platform's font
            // engine. Deliberately NOT GraphicsEnvironment — the family list is a separate, heavier
            // hazard, and only [available] needs it.
            val probe = { Font(Font.SANS_SERIF, Font.PLAIN, 12).createGlyphVector(typographyFrc, "o").outline }
            if (offThread("afield-typography-init", probe) == null) {
                throw IllegalStateException(stallMessage("the AWT font manager"))
            }
            warmed = true
        }
    }

    private fun stallMessage(what: String) =
        "afield.stdlib typography: $what did not answer within ${WARM_TIMEOUT_MS}ms, so this JVM cannot read " +
            "glyph outlines.\n  stuck at: ${lastWarmStall ?: "unknown"}\n" +
            "  This is the macOS -XstartOnFirstThread trap: CFontManager dispatches to the process's first " +
            "thread and waits, and nothing here is pumping that run loop. Start the JVM with " +
            "-Djava.awt.headless=true (it must be set BEFORE any AWT class initialises — see " +
            "Typefaces.warm and the jvm-main galleries, which all set it in the first line of main)."

    /**
     * Where the last stalled warm attempt was, top frames first.
     *
     * A stall with no stack is a dead end for whoever hits it next, so the helper thread is sampled
     * before it is abandoned. Exposed because it is the evidence behind [warm]'s KDoc, and a platform
     * that stalls somewhere *else* should be able to say so.
     */
    @Volatile
    var lastWarmStall: String? = null
        private set

    /** Run [body] on a fresh daemon thread under [WARM_TIMEOUT_MS]; `null` means it did not finish. */
    private fun <T> offThread(name: String, body: () -> T): T? {
        var thread: Thread? = null
        val exec = Executors.newSingleThreadExecutor { r ->
            Thread(r, name).apply { isDaemon = true; thread = this }
        }
        try {
            return exec.submit(body).get(WARM_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            lastWarmStall = thread?.stackTrace?.take(12)?.joinToString(" <- ") { "${it.className}.${it.methodName}" }
            return null
        } catch (e: java.util.concurrent.ExecutionException) {
            throw IllegalStateException(
                "afield.stdlib typography: the AWT font manager failed to initialise: ${e.cause?.message}",
                e.cause
            )
        } finally {
            // shutdownNow interrupts, but a thread blocked in a native AppKit wait will not notice; it
            // is a daemon, so it cannot keep the JVM alive either way.
            exec.shutdownNow()
        }
    }

    /**
     * A logical or installed font by name.
     *
     * [name] may be a logical family (`SansSerif`, `Serif`, `Monospaced`, `Dialog`) or anything in
     * [available]. AWT silently substitutes for a name it does not know, so pass `strict = true`
     * when you would rather be told.
     */
    fun named(
        name: String = Font.SANS_SERIF,
        size: Number = 64.0,
        bold: Boolean = false,
        italic: Boolean = false,
        kerning: Boolean = true,
        strict: Boolean = false,
    ): Typeface {
        warm()
        if (strict) {
            val known = name in LOGICAL_FAMILIES || available().any { it.equals(name, ignoreCase = true) }
            require(known) { "no installed font family named '$name' (and it is not a logical family)" }
        }
        val style = (if (bold) Font.BOLD else 0) or (if (italic) Font.ITALIC else 0)
        var f = Font(name, style, 12).deriveFont(size.toFloat())
        if (kerning) f = f.deriveFont(mapOf(TextAttribute.KERNING to TextAttribute.KERNING_ON))
        return Typeface(f)
    }

    /**
     * A `.ttf` / `.otf` / `.ttc` from disk, through `Font.createFont(TRUETYPE_FONT, file)`.
     *
     * The font is *not* registered with the graphics environment — it is used directly — so this
     * works headless and leaves no global state behind. `createFont` returns a 1pt font, hence the
     * immediate `deriveFont`.
     */
    fun fromFile(path: String, size: Number = 64.0, kerning: Boolean = true): Typeface {
        warm()
        val file = File(path)
        require(file.isFile) { "no font file at ${file.absolutePath}" }
        val kind = if (file.extension.lowercase() == "pfb") Font.TYPE1_FONT else Font.TRUETYPE_FONT
        var f = Font.createFont(kind, file).deriveFont(size.toFloat())
        if (kerning) f = f.deriveFont(mapOf(TextAttribute.KERNING to TextAttribute.KERNING_ON))
        return Typeface(f)
    }

    /**
     * Every font family the graphics environment can see, sorted.
     *
     * Held apart from [warm]: this is the *only* thing in the family that needs a
     * `GraphicsEnvironment`, which is a second and heavier AWT hazard than the font manager
     * ([warm]'s KDoc has the measurement). Loading a face by name, laying text out and extracting
     * outlines all avoid it entirely, so a live box never pays for it unless it asks for the list
     * — or passes `strict = true` to [named], which is the same question in disguise.
     */
    fun available(): List<String> {
        warm()
        if (!environmentWarmed) {
            synchronized(this) {
                if (!environmentWarmed) {
                    val names = offThread("afield-typography-environment") {
                        java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.size
                    } ?: throw IllegalStateException(
                        stallMessage("GraphicsEnvironment.getLocalGraphicsEnvironment()") +
                            "\n  Note that enumerating installed families needs a graphics environment and " +
                            "loading a face BY NAME does not, so naming the face you want may be enough."
                    )
                    check(names >= 0)
                    environmentWarmed = true
                }
            }
        }
        return java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.sorted()
    }

    /**
     * The first of [candidates] that is actually installed, else [fallback].
     *
     * For docs and galleries that want a *particular* look but must still run on a machine that has
     * never heard of it. Logical families always resolve, so `fallback` genuinely cannot fail.
     */
    fun firstAvailable(vararg candidates: String, size: Number = 64.0, fallback: String = Font.SANS_SERIF): Typeface {
        val have = available().toSet()
        val pick = candidates.firstOrNull { it in have } ?: fallback
        return named(pick, size)
    }

    /** Drop every extracted outline. Only useful if you are churning through fonts from disk. */
    fun clearCache() = glyphCache.clear()

    /** How many glyph outlines are currently cached — the number `TypographyTest` checks caching by. */
    val cachedGlyphCount: Int get() = glyphCache.size

    /**
     * The glyph cache. Keyed by (AWT font, glyph code) and *not* by character, because that is the
     * identity an outline actually has: `ffi` is one glyph, and `A` in two sizes is two.
     *
     * Note the consequence for [Glyph.chars]: the first string to reach a glyph code names it, so a
     * ligature extracted from "waffle" reports the characters that produced it there. A caller who
     * needs its own characters reads [PlacedGlyph.text].
     */
    internal fun cachedGlyph(font: Font, glyphCode: Int, build: () -> Glyph): Glyph =
        glyphCache.computeIfAbsent(GlyphKey(font, glyphCode)) { build() }

    private val LOGICAL_FAMILIES = setOf(
        Font.SANS_SERIF, Font.SERIF, Font.MONOSPACED, Font.DIALOG, Font.DIALOG_INPUT
    )
}

/** A logical or installed font by name — the short form of [Typefaces.named]. */
fun typeface(
    name: String = Font.SANS_SERIF,
    size: Number = 64.0,
    bold: Boolean = false,
    italic: Boolean = false,
): Typeface = Typefaces.named(name, size, bold, italic)

/** A `.ttf`/`.otf` from disk — the short form of [Typefaces.fromFile]. */
fun typefaceFromFile(path: String, size: Number = 64.0): Typeface = Typefaces.fromFile(path, size)

// ==============================================================================================
// Glyph outlines
// ==============================================================================================

/**
 * One closed ring of a glyph outline, flattened to a polygon and classified.
 *
 * The polygon does **not** repeat its first point at the end — the ring closes implicitly, which is
 * what every consumer here (`FloatEarcut`, point-in-polygon, the scanline filler) wants.
 *
 * @property depth how many other rings of the same glyph contain this one. 0 for the bowl of `o`, 1
 *   for its counter. Even is an outer contour, odd is a hole.
 * @property signedArea `½Σ(xᵢyᵢ₊₁ − xᵢ₊₁yᵢ)` in text space; positive for outer rings and negative
 *   for holes, per the convention this file enforces. Its magnitude is the ring's area in square
 *   text-space units.
 */
class GlyphContour internal constructor(val points: List<Vec2>, val depth: Int) {

    val signedArea: Double = run {
        var sum = 0.0
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            sum += a.x.toDouble() * b.y.toDouble() - b.x.toDouble() * a.y.toDouble()
        }
        0.5 * sum
    }

    /** True for the counter of an `o` — an odd nesting depth, and so a negative [signedArea]. */
    val isHole: Boolean get() = depth % 2 == 1

    /** True for the bowl of an `o` — an even nesting depth, and so a positive [signedArea]. */
    val isOuter: Boolean get() = !isHole

    /** Tight bounds in text space. */
    val bounds: Rect = boundsOfPoints(points)

    /** Closed-ring perimeter, in text-space units. */
    val perimeter: Double = run {
        var s = 0.0
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            s += hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble())
        }
        s
    }

    /** This ring as its own closed [FLine] — a polyline, not the original cubics. */
    fun toPolyline(): FLine = fline {
        points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
        if (points.isNotEmpty()) lineTo(points[0].x, points[0].y)
    }

    /** Winding-number test against this ring alone, ignoring nesting. */
    fun contains(p: Vec2): Boolean = pointInRing(points, p.x.toDouble(), p.y.toDouble())

    override fun toString(): String =
        "GlyphContour(${points.size} pts, depth=$depth, ${if (isHole) "hole" else "outer"}, area=${"%.1f".format(signedArea)})"
}

/**
 * One glyph's outline, in its own frame with the origin on its own baseline.
 *
 * Cached per (AWT font, glyph code) by [Typefaces], so a repeated letter costs one hash lookup. Both
 * representations describe the same rings in the same order:
 *
 *  - [contours] — flattened, depth-classified, winding-normalised. What the mesh, SDF, raster and
 *    point-sampling bridges consume.
 *  - [outline] — one `moveTo`-led subpath per contour, with the font's real cubics (quadratics
 *    promoted, because `FLine` has no quadratic node). What you draw.
 *
 * @property glyphCode the font's own glyph id, not a character code — a ligature has one code and
 *   several characters.
 * @property chars the characters that produced this glyph the first time it was extracted.
 * @property advance how far the pen moves after this glyph, in text-space units. Whitespace has an
 *   advance and no contours.
 * @property bounds tight bounds of the outline (an empty rect at the origin for whitespace), which
 *   is the *visual* box — [advance] is generally wider, and italics can overhang it.
 */
class Glyph internal constructor(
    val glyphCode: Int,
    val chars: String,
    val advance: Double,
    val contours: List<GlyphContour>,
    val outline: FLine,
    val bounds: Rect,
) {
    /** True for a space, a tab, or any glyph the font draws nothing for. */
    val isBlank: Boolean get() = contours.isEmpty()

    /** Rings in total. */
    val contourCount: Int get() = contours.size

    /** Rings at odd nesting depth: 1 for `o`, 2 for `%`, 0 for `l`. */
    val holeCount: Int get() = contours.count { it.isHole }

    /** Rings at even nesting depth: 1 for `o`, 2 for `i` (stem and tittle). */
    val outerCount: Int get() = contours.count { it.isOuter }

    /** Ink area: outer rings minus holes. Always >= 0 for a well-formed glyph. */
    val inkArea: Double get() = contours.sumOf { it.signedArea }

    override fun toString(): String =
        "Glyph('${chars}' #$glyphCode, ${contours.size} contours, $holeCount holes, advance=${"%.2f".format(advance)})"
}

// ==============================================================================================
// PathIterator -> rings.  The heart of the file.
// ==============================================================================================

/**
 * Why this is hand-rolled rather than delegating to `FLinesAndJavaShapes.javaShapeToFLine`.
 *
 * That function (`FLinesAndJavaShapes.kt:2188`) flattens a whole `Shape` into a single `FLine` and
 * gives no handle on the individual rings — no per-contour split, so no nesting depth, and no way to
 * reverse one ring without touching the others. Winding control is the entire point here, so the
 * segments are parsed into per-ring lists first; from there a ring can be flattened, measured,
 * reversed and re-emitted independently, and the polygon and the cubic outline are guaranteed to
 * describe the same curve in the same direction.
 */
private sealed interface RingSeg {
    val to: Vec2
}

private class RingLine(override val to: Vec2) : RingSeg

private class RingCubic(val c1: Vec2, val c2: Vec2, override val to: Vec2) : RingSeg

private class RawRing(var start: Vec2, val segs: MutableList<RingSeg> = mutableListOf())

private fun v2(x: Double, y: Double) = Vec2(x.toFloat(), y.toFloat())

/** Split a glyph outline into rings, promoting quadratics to cubics and closing every ring. */
private fun ringsOf(shape: Shape): List<RawRing> {
    val it = shape.getPathIterator(null)
    val c = DoubleArray(6)
    val out = mutableListOf<RawRing>()
    var cur: RawRing? = null
    var hx = 0.0
    var hy = 0.0
    while (!it.isDone) {
        when (it.currentSegment(c)) {
            PathIterator.SEG_MOVETO -> {
                cur = RawRing(v2(c[0], c[1]))
                out.add(cur)
                hx = c[0]; hy = c[1]
            }

            PathIterator.SEG_LINETO -> {
                cur?.segs?.add(RingLine(v2(c[0], c[1])))
                hx = c[0]; hy = c[1]
            }

            PathIterator.SEG_QUADTO -> {
                // A quadratic (P0, Q, P2) is the cubic (P0, P0 + 2/3(Q-P0), P2 + 2/3(Q-P2), P2).
                // Exact, not an approximation.
                cur?.segs?.add(
                    RingCubic(
                        v2(hx + 2.0 / 3.0 * (c[0] - hx), hy + 2.0 / 3.0 * (c[1] - hy)),
                        v2(c[2] + 2.0 / 3.0 * (c[0] - c[2]), c[3] + 2.0 / 3.0 * (c[1] - c[3])),
                        v2(c[2], c[3])
                    )
                )
                hx = c[2]; hy = c[3]
            }

            PathIterator.SEG_CUBICTO -> {
                cur?.segs?.add(RingCubic(v2(c[0], c[1]), v2(c[2], c[3]), v2(c[4], c[5])))
                hx = c[4]; hy = c[5]
            }

            PathIterator.SEG_CLOSE -> {
                cur?.let { r ->
                    val last = r.segs.lastOrNull()?.to ?: r.start
                    if (abs(last.x - r.start.x) > 1e-5f || abs(last.y - r.start.y) > 1e-5f) {
                        r.segs.add(RingLine(Vec2(r.start)))
                    }
                    hx = r.start.x.toDouble(); hy = r.start.y.toDouble()
                }
            }
        }
        it.next()
    }
    // A glyph outline from AWT is always closed, but a hand-built Shape need not be, and an open
    // ring would make the area formula and the parity fill disagree. Close anything left open.
    out.forEach { r ->
        val last = r.segs.lastOrNull()?.to
        if (last != null && (abs(last.x - r.start.x) > 1e-5f || abs(last.y - r.start.y) > 1e-5f)) {
            r.segs.add(RingLine(Vec2(r.start)))
        }
    }
    return out.filter { it.segs.size >= 2 }
}

/** Reverse a ring in place-ish: same curve, opposite direction, so its signed area changes sign. */
private fun reverseRing(r: RawRing): RawRing {
    val pts = ArrayList<Vec2>(r.segs.size + 1)
    pts.add(r.start)
    r.segs.forEach { pts.add(it.to) }
    val outSegs = mutableListOf<RingSeg>()
    for (i in r.segs.indices.reversed()) {
        val s = r.segs[i]
        val from = pts[i]
        outSegs.add(
            when (s) {
                is RingLine -> RingLine(Vec2(from))
                is RingCubic -> RingCubic(Vec2(s.c2), Vec2(s.c1), Vec2(from))
            }
        )
    }
    return RawRing(Vec2(pts[pts.lastIndex]), outSegs)
}

/**
 * Flatten a ring to a polygon, subdividing each cubic by its control-polygon length.
 *
 * [flatness] is in text-space units, so the same value gives more samples on a 96pt glyph than a
 * 12pt one, which is what you want. The returned list does not repeat the start point.
 */
private fun flattenRing(r: RawRing, flatness: Double): List<Vec2> {
    val out = ArrayList<Vec2>(r.segs.size * 4)
    var from = r.start
    out.add(Vec2(from))
    for (s in r.segs) {
        when (s) {
            is RingLine -> out.add(Vec2(s.to))
            is RingCubic -> {
                val chord = dist(from, s.c1) + dist(s.c1, s.c2) + dist(s.c2, s.to)
                val n = ceil(chord / max(1e-6, flatness)).toInt().coerceIn(2, 64)
                for (k in 1..n) {
                    val t = k.toDouble() / n
                    out.add(cubicAt(from, s.c1, s.c2, s.to, t))
                }
            }
        }
        from = s.to
    }
    // Drop consecutive duplicates and the repeated closing point.
    val cleaned = ArrayList<Vec2>(out.size)
    for (p in out) {
        val last = cleaned.lastOrNull()
        if (last == null || abs(last.x - p.x) > 1e-5f || abs(last.y - p.y) > 1e-5f) cleaned.add(p)
    }
    while (cleaned.size >= 2) {
        val a = cleaned[0]
        val b = cleaned[cleaned.lastIndex]
        if (abs(a.x - b.x) <= 1e-5f && abs(a.y - b.y) <= 1e-5f) cleaned.removeAt(cleaned.lastIndex) else break
    }
    return cleaned
}

private fun dist(a: Vec2, b: Vec2) = hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble())

private fun cubicAt(p0: Vec2, c1: Vec2, c2: Vec2, p1: Vec2, t: Double): Vec2 {
    val u = 1.0 - t
    val a = u * u * u
    val b = 3.0 * u * u * t
    val c = 3.0 * u * t * t
    val d = t * t * t
    return v2(
        a * p0.x + b * c1.x + c * c2.x + d * p1.x,
        a * p0.y + b * c1.y + c * c2.y + d * p1.y
    )
}

private fun ringArea(points: List<Vec2>): Double {
    var sum = 0.0
    for (i in points.indices) {
        val a = points[i]
        val b = points[(i + 1) % points.size]
        sum += a.x.toDouble() * b.y.toDouble() - b.x.toDouble() * a.y.toDouble()
    }
    return 0.5 * sum
}

internal fun pointInRing(points: List<Vec2>, px: Double, py: Double): Boolean {
    var inside = false
    var j = points.size - 1
    for (i in points.indices) {
        val yi = points[i].y.toDouble()
        val yj = points[j].y.toDouble()
        if ((yi > py) != (yj > py)) {
            val xi = points[i].x.toDouble()
            val xj = points[j].x.toDouble()
            if (px < (xj - xi) * (py - yi) / (yj - yi) + xi) inside = !inside
        }
        j = i
    }
    return inside
}

internal fun boundsOfPoints(points: List<Vec2>): Rect {
    if (points.isEmpty()) return Rect(0.0, 0.0, 0.0, 0.0)
    var minX = Double.MAX_VALUE
    var minY = Double.MAX_VALUE
    var maxX = -Double.MAX_VALUE
    var maxY = -Double.MAX_VALUE
    points.forEach {
        minX = min(minX, it.x.toDouble()); maxX = max(maxX, it.x.toDouble())
        minY = min(minY, it.y.toDouble()); maxY = max(maxY, it.y.toDouble())
    }
    return Rect(minX, minY, maxX - minX, maxY - minY)
}

/** Emit a ring as one `moveTo`-led, explicitly-closed subpath into [into]. */
private fun emitRing(into: FLine, r: RawRing) {
    into.moveTo(r.start.x, r.start.y)
    r.segs.forEach { s ->
        when (s) {
            is RingLine -> into.lineTo(s.to.x, s.to.y)
            is RingCubic -> into.cubicTo(s.c1.x, s.c1.y, s.c2.x, s.c2.y, s.to.x, s.to.y)
        }
    }
    // Deliberately no Close node: `javaShapeToFLine` does not emit one either (it converts SEG_CLOSE
    // into the closing lineTo, which `ringsOf` has already added), and FLine.tessellate's ring
    // sanitiser drops the duplicate closing point either way. One representation, no special case.
}

/**
 * Extract, classify and winding-normalise one glyph's rings.
 *
 * The three steps that make the convention true:
 *  1. flatten every ring, so nesting can be tested and area measured;
 *  2. depth = how many other rings contain this ring's first vertex (point-in-polygon, so a ring
 *    sharing a bounding box with a sibling is still classified correctly);
 *  3. if `sign(area) != expected(depth)`, reverse the *cubic* ring and re-flatten, so the polygon
 *    and the outline never disagree.
 */
private fun extractGlyph(shape: Shape, glyphCode: Int, chars: String, advance: Double, flatness: Double): Glyph {
    val rings = ringsOf(shape)
    if (rings.isEmpty()) {
        return Glyph(glyphCode, chars, advance, emptyList(), FLine(), Rect(0.0, 0.0, 0.0, 0.0))
    }
    val flat = rings.map { flattenRing(it, flatness) }

    val depth = IntArray(rings.size)
    for (i in rings.indices) {
        val probe = flat[i].firstOrNull() ?: continue
        var d = 0
        for (j in rings.indices) {
            if (i == j || flat[j].size < 3) continue
            if (pointInRing(flat[j], probe.x.toDouble(), probe.y.toDouble())) d++
        }
        depth[i] = d
    }

    val outline = FLine()
    val contours = ArrayList<GlyphContour>(rings.size)
    for (i in rings.indices) {
        var ring = rings[i]
        var pts = flat[i]
        if (pts.size < 3) continue
        val wantPositive = depth[i] % 2 == 0
        if ((ringArea(pts) > 0.0) != wantPositive) {
            ring = reverseRing(ring)
            pts = flattenRing(ring, flatness)
        }
        emitRing(outline, ring)
        contours.add(GlyphContour(pts, depth[i]))
    }

    val allPoints = contours.flatMap { it.points }
    return Glyph(glyphCode, chars, advance, contours, outline, boundsOfPoints(allPoints))
}

// ==============================================================================================
// Layout
// ==============================================================================================

/** Where a line's glyphs sit relative to the widest line of the layout. */
enum class TextAlign { LEFT, CENTER, RIGHT }

/**
 * One glyph placed on a baseline, plus the handles that make it kinetic.
 *
 * [origin] is where layout put it and never changes. Everything else is yours to move: this is the
 * object a `Box.animate { }` writes to frame by frame, and the reason [TextLayout.toFLines] and the
 * geometry bridges all re-read the transform rather than baking it.
 *
 * The transform is `translate(origin + offset) · translate(pivot) · rotate · scale · translate(-pivot)`,
 * so [pivot] is in the glyph's own coordinates and defaults to the centre of its visual bounds —
 * which means a bare `rotation = 0.3` spins each letter about itself rather than about its baseline
 * corner, and looks like what you meant.
 *
 * @property charIndex index into the laid-out string of the first character this glyph renders.
 */
class PlacedGlyph internal constructor(
    val glyph: Glyph,
    val origin: Vec2,
    val lineIndex: Int,
    val charIndex: Int,
) {
    /** Extra translation, in text-space units, applied after layout. */
    var offset: Vec2 = Vec2(0f, 0f)

    /** Rotation about [pivot], radians, positive clockwise on screen (because y is down). */
    var rotation: Double = 0.0

    /** Scale about [pivot]. Negative components mirror, and the geometry bridges cope. */
    var scale: Vec2 = Vec2(1f, 1f)

    /** Rotation/scale centre in the glyph's own coordinates; `null` means the visual-bounds centre. */
    var pivot: Vec2? = null

    /** Per-glyph colour override for [TextLayout.toFLines]; `null` means take the caller's colour. */
    var color: Vec4? = null

    /** The characters this placement renders — one, or several for a ligature. */
    val text: String get() = glyph.chars

    /** The resolved pivot, in glyph coordinates. */
    val resolvedPivot: Vec2
        get() = pivot ?: if (glyph.isBlank) Vec2((glyph.advance / 2).toFloat(), 0f)
        else Vec2(glyph.bounds.cx.toFloat(), glyph.bounds.cy.toFloat())

    /** The affine this placement's geometry goes through. A fresh instance each call. */
    fun transform(): AffineTransform {
        val t = AffineTransform()
        t.translate(origin.x + offset.x.toDouble(), origin.y + offset.y.toDouble())
        if (rotation != 0.0 || scale.x != 1f || scale.y != 1f) {
            val p = resolvedPivot
            t.translate(p.x.toDouble(), p.y.toDouble())
            if (rotation != 0.0) t.rotate(rotation)
            if (scale.x != 1f || scale.y != 1f) t.scale(scale.x.toDouble(), scale.y.toDouble())
            t.translate(-p.x.toDouble(), -p.y.toDouble())
        }
        return t
    }

    /** True when nothing has been asked of the handles, so the transform is a pure translation. */
    val isUntransformed: Boolean
        get() = rotation == 0.0 && scale.x == 1f && scale.y == 1f && offset.x == 0f && offset.y == 0f

    internal fun copyPlacement(): PlacedGlyph {
        val p = PlacedGlyph(glyph, Vec2(origin), lineIndex, charIndex)
        p.offset = Vec2(offset)
        p.rotation = rotation
        p.scale = Vec2(scale)
        p.pivot = pivot?.let { Vec2(it) }
        p.color = color?.let { Vec4(it) }
        return p
    }

    override fun toString(): String = "PlacedGlyph('${text}' @ ${"%.1f".format(origin.x)},${"%.1f".format(origin.y)})"
}

/** One baseline's worth of a layout. */
class TextLine internal constructor(
    val index: Int,
    val text: String,
    val baselineY: Double,
    val advanceWidth: Double,
    val glyphs: List<PlacedGlyph>,
)

/**
 * A laid-out string: glyphs on baselines, with handles.
 *
 * Produced by [layoutText] / [Typeface.layout]. Immutable in its structure and mutable in its
 * handles, which is the split that matters — a layout is computed once and animated many times.
 */
class TextLayout internal constructor(
    val text: String,
    val typeface: Typeface,
    val glyphs: List<PlacedGlyph>,
    val lines: List<TextLine>,
    val tracking: Double,
    val lineHeight: Double,
    val align: TextAlign,
) {
    /** Positive, from the typeface. */
    val ascent: Double get() = typeface.ascent

    /** Positive, from the typeface. */
    val descent: Double get() = typeface.descent

    /** The widest line's advance width. Tracking is included; the trailing gap is not. */
    val advanceWidth: Double = lines.maxOfOrNull { it.advanceWidth } ?: 0.0

    /** Baseline-to-baseline of the first and last line, plus one line's ascent and descent. */
    val layoutHeight: Double
        get() = if (lines.isEmpty()) 0.0 else (lines.last().baselineY - lines.first().baselineY) + ascent + descent

    /** The em box: the rectangle layout reserved, ignoring what the glyphs actually inked. */
    val layoutBounds: Rect
        get() = Rect(0.0, -ascent, advanceWidth, layoutHeight)

    /** Tight bounds of the *inked* geometry with every handle applied. Empty for whitespace-only text. */
    val bounds: Rect
        get() {
            var minX = Double.MAX_VALUE
            var minY = Double.MAX_VALUE
            var maxX = -Double.MAX_VALUE
            var maxY = -Double.MAX_VALUE
            var any = false
            glyphs.forEach { g ->
                if (g.glyph.isBlank) return@forEach
                val t = g.transform()
                g.glyph.contours.forEach { ring ->
                    ring.points.forEach { p ->
                        val d = t.transform(java.awt.geom.Point2D.Double(p.x.toDouble(), p.y.toDouble()), null)
                        minX = min(minX, d.x); maxX = max(maxX, d.x)
                        minY = min(minY, d.y); maxY = max(maxY, d.y)
                        any = true
                    }
                }
            }
            return if (!any) Rect(0.0, 0.0, 0.0, 0.0) else Rect(minX, minY, maxX - minX, maxY - minY)
        }

    /** Every glyph that inks something. Whitespace is in [glyphs] and not here. */
    val inkedGlyphs: List<PlacedGlyph> get() = glyphs.filter { !it.glyph.isBlank }

    /** Total contour count across the layout — outer rings plus holes. */
    val contourCount: Int get() = glyphs.sumOf { it.glyph.contourCount }

    /** Total hole count across the layout. */
    val holeCount: Int get() = glyphs.sumOf { it.glyph.holeCount }

    /**
     * Set the handles glyph by glyph, and hand the layout back for chaining.
     *
     * The kinetic-typography entry point:
     * ```
     * layout.eachGlyph { i -> rotation = 0.25 * sin(t + i * 0.6) }
     * ```
     */
    fun eachGlyph(block: PlacedGlyph.(Int) -> Unit): TextLayout {
        glyphs.forEachIndexed { i, g -> g.block(i) }
        return this
    }

    /** Reset every handle to its identity. */
    fun resetHandles(): TextLayout = eachGlyph {
        offset = Vec2(0f, 0f); rotation = 0.0; scale = Vec2(1f, 1f); pivot = null; color = null
    }

    /**
     * A deep copy: same structure, independent handles.
     *
     * Needed whenever two things animate the same string differently, and used by [onCurve] so that
     * bending text along a path does not destroy the layout you bent.
     */
    fun copyLayout(): TextLayout {
        val copies = glyphs.map { it.copyPlacement() }
        var k = 0
        val newLines = lines.map { line ->
            val take = copies.subList(k, k + line.glyphs.size).toList()
            k += line.glyphs.size
            TextLine(line.index, line.text, line.baselineY, line.advanceWidth, take)
        }
        return TextLayout(text, typeface, copies, newLines, tracking, lineHeight, align)
    }

    /** How far along a baseline this glyph's advance box is centred — the parameter [onCurve] uses. */
    internal fun advanceCentreOf(g: PlacedGlyph): Double = g.origin.x + g.glyph.advance / 2.0

    override fun toString(): String =
        "TextLayout(${text.lines().size} line(s), ${glyphs.size} glyphs, ${"%.1f".format(advanceWidth)} wide, $typeface)"
}

/**
 * Lay a string out: AWT advances and kerning, plus tracking, line breaks and alignment.
 *
 * Positions come from `Font.layoutGlyphVector`, so ligatures, bidi-neutral shaping and the font's
 * own kerning pairs are AWT's business rather than this file's. What this file adds is what AWT does
 * not do: `\n` handling, [TextAlign], letter-spacing, and the [PlacedGlyph] handles.
 *
 * @param tracking extra advance after every glyph, in text-space units — so it scales with the
 *   typeface's size the same way the letters do. `tracking = 0.05 * typeface.size` is a readable
 *   loose setting. The gap *after* the last glyph of a line is not counted in [TextLayout.advanceWidth].
 * @param lineSpacing multiplier on the typeface's natural [Typeface.lineHeight].
 * @param flatness polygon subdivision tolerance for [Glyph.contours], in text-space units. The
 *   default is a 400th of the size, which is finer than any raster you are likely to make and still
 *   only a few hundred points per glyph.
 */
fun layoutText(
    text: String,
    typeface: Typeface,
    tracking: Double = 0.0,
    lineSpacing: Double = 1.0,
    align: TextAlign = TextAlign.LEFT,
    flatness: Double = typeface.size / 400.0,
): TextLayout {
    Typefaces.warm()
    val font = typeface.awtFont
    val lineHeight = typeface.lineHeight * lineSpacing
    val rawLines = text.split("\n")

    // Pass one: glyphs and per-line widths, all left-aligned at x = 0.
    class Pending(val lineText: String, val placed: MutableList<PlacedGlyph>, val width: Double)

    val pending = mutableListOf<Pending>()
    var charBase = 0
    rawLines.forEachIndexed { li, lineText ->
        val placed = mutableListOf<PlacedGlyph>()
        var width = 0.0
        if (lineText.isNotEmpty()) {
            val chars = lineText.toCharArray()
            val gv = font.layoutGlyphVector(typographyFrc, chars, 0, chars.size, Font.LAYOUT_LEFT_TO_RIGHT)
            val n = gv.numGlyphs
            // getGlyphCharIndex, one at a time: the bulk getGlyphCharIndices takes a range and an
            // output array, and there is nothing to gain from it at a glyph or two per letter.
            val charIndices = IntArray(n) { gv.getGlyphCharIndex(it) }
            for (i in 0 until n) {
                val code = gv.getGlyphCode(i)
                val pos = gv.getGlyphPosition(i)
                val advance = gv.getGlyphMetrics(i).advanceX.toDouble()
                val ci = charIndices.getOrElse(i) { i }
                val nextCi = charIndices.getOrElse(i + 1) { lineText.length }
                val src = lineText.substring(
                    ci.coerceIn(0, lineText.length),
                    max(ci + 1, nextCi).coerceIn(0, lineText.length)
                )
                val g = Typefaces.cachedGlyph(font, code) {
                    // getGlyphOutline(i, x, y) places the glyph's origin at (x, y), so passing the
                    // negated layout position lifts it back into its own frame — which is what makes
                    // the cache sharable between every string that uses this glyph.
                    val shape = gv.getGlyphOutline(i, (-pos.x).toFloat(), (-pos.y).toFloat())
                    extractGlyph(shape, code, src, advance, flatness)
                }
                placed.add(
                    PlacedGlyph(g, v2(pos.x + i * tracking, 0.0), li, charBase + ci)
                )
            }
            // The pen after the last glyph, minus the trailing tracking gap nobody asked for.
            width = gv.getGlyphPosition(n).x + max(0, n - 1) * tracking
        }
        pending.add(Pending(lineText, placed, width))
        charBase += lineText.length + 1
    }

    // Pass two: alignment shift and baseline y, which need the widest line.
    val widest = pending.maxOfOrNull { it.width } ?: 0.0
    val allGlyphs = mutableListOf<PlacedGlyph>()
    val outLines = mutableListOf<TextLine>()
    pending.forEachIndexed { li, p ->
        val shift = when (align) {
            TextAlign.LEFT -> 0.0
            TextAlign.CENTER -> (widest - p.width) / 2.0
            TextAlign.RIGHT -> widest - p.width
        }
        val baselineY = li * lineHeight
        val moved = p.placed.map { g ->
            PlacedGlyph(g.glyph, v2(g.origin.x + shift, baselineY), li, g.charIndex)
        }
        allGlyphs.addAll(moved)
        outLines.add(TextLine(li, p.lineText, baselineY, p.width, moved))
    }

    return TextLayout(text, typeface, allGlyphs, outLines, tracking, lineHeight, align)
}

/** [layoutText] with the typeface first — reads better when you keep a typeface around. */
fun Typeface.layout(
    text: String,
    tracking: Double = 0.0,
    lineSpacing: Double = 1.0,
    align: TextAlign = TextAlign.LEFT,
): TextLayout = layoutText(text, this, tracking, lineSpacing, align)

// ==============================================================================================
// Text on a curve
// ==============================================================================================

/**
 * Bend a layout onto an [FLine] by arc length.
 *
 * Each glyph's advance-box centre is looked up at distance `startOffset + centreX` along [path] with
 * `FLine.cursor()`'s arc-length table (`FLinesAndJavaShapes.Cursor`, which is the same machinery
 * `sampleByDistance` and `byDashing` use, so spacing is even in *distance* and not in curve
 * parameter). The glyph is then rotated to the tangent there and pushed along the normal by its own
 * baseline y, so a multi-line layout follows the curve in bands rather than collapsing onto it.
 *
 * Returns a **copy** — the layout you passed in is untouched, so you can bend the same string along
 * several paths, or animate `startOffset` without re-laying-out.
 *
 * @param startOffset where the first baseline unit lands, in distance along [path].
 * @param align `LEFT` starts at [startOffset]; `CENTER` and `RIGHT` add the slack between the path's
 *   length and the layout's width, so text centres or ends on the path.
 * @param flip rotate every glyph by π, for text on the inside of a circle.
 * @param clampToPath when true (the default) a glyph past either end of the path is placed at the
 *   end; when false it is extrapolated along the end tangent, which keeps letters marching off the
 *   end of an open curve instead of piling up on the last point.
 */
fun TextLayout.onCurve(
    path: FLine,
    startOffset: Double = 0.0,
    align: TextAlign = this.align,
    flip: Boolean = false,
    clampToPath: Boolean = true,
): TextLayout {
    val out = copyLayout()
    if (path.nodes.isEmpty()) return out
    val cursor = path.cursor()
    val length = cursor.lengthD().toDouble()
    if (length <= 0.0) return out

    val slack = length - advanceWidth
    val base = startOffset + when (align) {
        TextAlign.LEFT -> 0.0
        TextAlign.CENTER -> slack / 2.0
        TextAlign.RIGHT -> slack
    }

    var lastTangent = Vec2(1f, 0f)
    out.glyphs.forEach { g ->
        val centre = g.origin.x + g.glyph.advance / 2.0
        val want = base + centre
        val d = want.coerceIn(0.0, length)
        val p = cursor.setD(d.toFloat()).position()
        val t = cursor.tangent()
        val tan = if (t != null && (abs(t.x) > 1e-7f || abs(t.y) > 1e-7f)) {
            val l = hypot(t.x.toDouble(), t.y.toDouble())
            Vec2((t.x / l).toFloat(), (t.y / l).toFloat())
        } else lastTangent
        lastTangent = tan

        // The overshoot a non-clamping caller asked for: keep walking along the end tangent.
        val over = if (clampToPath) 0.0 else (want - d)

        var angle = atan2(tan.y.toDouble(), tan.x.toDouble())
        if (flip) angle += Math.PI

        // Local +y maps to rotate(angle) * (0,1) = (-sin, cos) = the left normal, so the glyph's own
        // baseline y is already handled by the rotation; what needs adding here is the *line's*
        // baseline offset, because that is part of `origin` and not of the glyph.
        val nx = -sin(angle)
        val ny = cos(angle)
        val pivot = Vec2((g.glyph.advance / 2.0).toFloat(), 0f)
        val targetX = p.x + nx * g.origin.y + tan.x * over
        val targetY = p.y + ny * g.origin.y + tan.y * over

        g.pivot = pivot
        g.rotation = angle
        g.offset = v2(
            targetX - g.origin.x - pivot.x,
            targetY - g.origin.y - pivot.y
        )
    }
    return out
}

/**
 * A circle as an [FLine], clockwise from the top — the path [onCurve] wants for a roundel.
 *
 * In text space (y down) `clockwise = true` reads left-to-right along the top of the circle, which
 * is the usual setting for a badge or a seal; `false` plus `flip = true` reads along the bottom.
 */
fun textCircle(radius: Number, centre: Vec2 = Vec2(0f, 0f), clockwise: Boolean = true, segments: Int = 96): FLine {
    val r = radius.toDouble()
    val dir = if (clockwise) 1.0 else -1.0
    return fline {
        for (i in 0..segments) {
            val a = -Math.PI / 2 + dir * 2.0 * Math.PI * i / segments
            val x = centre.x + r * cos(a)
            val y = centre.y + r * sin(a)
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
    }
}

/**
 * A cubic arch through three points — a quick path for [onCurve] when you do not want to draw one.
 *
 * Sags/rises by [height] at its midpoint; negative arches upward in text space.
 */
fun textArc(width: Number, height: Number, from: Vec2 = Vec2(0f, 0f)): FLine {
    val w = width.toDouble()
    val h = height.toDouble()
    return fline {
        moveTo(from.x, from.y)
        cubicTo(
            from.x + w / 3.0, from.y + h * 4.0 / 3.0,
            from.x + w * 2.0 / 3.0, from.y + h * 4.0 / 3.0,
            from.x + w, from.y.toDouble()
        )
    }
}

/** Total arc length of a path, the number [onCurve]'s `startOffset` is measured in. */
fun FLine.arcLength(): Double = if (nodes.isEmpty()) 0.0 else cursor().lengthD().toDouble()

// ==============================================================================================
// Small conveniences
// ==============================================================================================

/**
 * The classic single-line word-wrap, in text-space units.
 *
 * Wraps on spaces only (no hyphenation, no CJK line-breaking) and measures with the real layout, so
 * the result is correct for the tracking and kerning you are going to use. Existing `\n` are kept.
 */
fun wrapText(text: String, typeface: Typeface, maxWidth: Double, tracking: Double = 0.0): String {
    require(maxWidth > 0) { "maxWidth must be positive, got $maxWidth" }
    fun widthOf(s: String) = if (s.isEmpty()) 0.0 else layoutText(s, typeface, tracking).advanceWidth
    val out = StringBuilder()
    text.split("\n").forEachIndexed { li, para ->
        if (li > 0) out.append('\n')
        var line = StringBuilder()
        para.split(" ").filter { it.isNotEmpty() }.forEach { word ->
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (widthOf(candidate) <= maxWidth || line.isEmpty()) {
                line = StringBuilder(candidate)
            } else {
                out.append(line).append('\n')
                line = StringBuilder(word)
            }
        }
        out.append(line)
    }
    return out.toString()
}

/** Advance width of a string without building a layout you keep. */
fun textWidth(text: String, typeface: Typeface, tracking: Double = 0.0): Double =
    layoutText(text, typeface, tracking).advanceWidth

/**
 * Even samples along a ring, by arc length — the primitive the point-cloud bridge is built from.
 *
 * Kept here rather than in `typography_field.kt` because [GlyphContour] owns its own resampling and
 * because it is the right way to compare two rings' shapes at equal spacing.
 */
fun GlyphContour.resample(spacing: Double): List<Vec2> {
    require(spacing > 0) { "spacing must be positive, got $spacing" }
    if (points.size < 2) return points.map { Vec2(it) }
    val out = mutableListOf<Vec2>()
    var carry = 0.0
    for (i in points.indices) {
        val a = points[i]
        val b = points[(i + 1) % points.size]
        val seg = dist(a, b)
        if (seg <= 1e-9) continue
        var s = carry
        while (s < seg) {
            val t = s / seg
            out.add(v2(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t))
            s += spacing
        }
        carry = s - seg
    }
    return out
}

/** Straight-line distance from [p] to this ring's boundary. Always non-negative. */
fun GlyphContour.distanceTo(p: Vec2): Double {
    var best = Double.MAX_VALUE
    for (i in points.indices) {
        val a = points[i]
        val b = points[(i + 1) % points.size]
        best = min(best, pointSegmentDistance(p.x.toDouble(), p.y.toDouble(), a, b))
    }
    return if (best == Double.MAX_VALUE) 0.0 else sqrt(best)
}

/** Squared distance from (px, py) to segment ab. Squared, because the callers sort before rooting. */
internal fun pointSegmentDistance(px: Double, py: Double, a: Vec2, b: Vec2): Double {
    val ax = a.x.toDouble()
    val ay = a.y.toDouble()
    val vx = b.x.toDouble() - ax
    val vy = b.y.toDouble() - ay
    val wx = px - ax
    val wy = py - ay
    val vv = vx * vx + vy * vy
    val t = if (vv <= 1e-18) 0.0 else ((wx * vx + wy * vy) / vv).coerceIn(0.0, 1.0)
    val dx = wx - t * vx
    val dy = wy - t * vy
    return dx * dx + dy * dy
}
