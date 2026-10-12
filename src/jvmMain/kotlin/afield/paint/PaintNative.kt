@file:afield.CompletionCategory("Internal")
package afield.paint

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_DOUBLE
import java.lang.foreign.ValueLayout.JAVA_FLOAT
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle
import java.lang.ref.Cleaner
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * Where `native/field-paint` is. Searched in order: `-Dafield.paint.library`,
 * `AFIELD_PAINT_LIBRARY`, then `build/field-paint/release` in the working directory.
 */
object PaintLibrary {
    private val os = System.getProperty("os.name").lowercase()

    val fileName: String
        get() = when {
            os.contains("win") -> "field_paint_jvm.dll"
            os.contains("mac") || os.contains("darwin") -> "libfield_paint_jvm.dylib"
            else -> "libfield_paint_jvm.so"
        }

    val searchedPaths: List<Path>
        get() = listOfNotNull(
            System.getProperty("afield.paint.library")?.takeIf { it.isNotBlank() },
            System.getenv("AFIELD_PAINT_LIBRARY")?.takeIf { it.isNotBlank() },
        ).map(Path::of) +
            // listOf, not a bare `+ Path`: Path is Iterable<Path>, so that appends its name components.
            listOf(Path.of("build", "field-paint", "release", fileName).toAbsolutePath())

    /** Not cached: building it during a live session is picked up by the next box run. */
    val resolvedPath: Path? get() = searchedPaths.firstOrNull { it.exists() }

    val isAvailable: Boolean get() = resolvedPath != null

    const val HOW_TO_BUILD = "Build it with `./gradlew buildPaintNative` (needs cargo, and the network once)."

    fun describe(): String = resolvedPath?.let { "paint wrapper at $it (engine ${PaintNative.engine()})" }
        ?: "paint wrapper not built. $HOW_TO_BUILD"
}

/**
 * The FFM plumbing under [PaintCanvas]: one downcall per `field_paint_*` entry point in
 * `native/field-paint/src/lib.rs`, whose comments are the contract. A negative status (or a null
 * handle) means the wrapper caught an error or a panic; [error] fetches its message.
 */
internal object PaintNative {
    val cleaner: Cleaner = Cleaner.create()

    /** Holds only the handle, never the owner, so the owner can be collected. */
    class Free(private val handle: MemorySegment, private val free: (MemorySegment) -> Unit) : Runnable {
        override fun run() = free(handle)
    }

    private val linker = Linker.nativeLinker()

    private val lookup: SymbolLookup by lazy {
        val path = PaintLibrary.resolvedPath ?: throw UnsatisfiedLinkError(
            "paint wrapper is not built. ${PaintLibrary.HOW_TO_BUILD}\nLooked at: " +
                PaintLibrary.searchedPaths.joinToString(", "))
        SymbolLookup.libraryLookup(path, Arena.global())
    }

    private fun handle(name: String, d: FunctionDescriptor): MethodHandle = linker.downcallHandle(
        lookup.find(name).orElseThrow { UnsatisfiedLinkError("${PaintLibrary.fileName} has no $name; rebuild it with ./gradlew buildPaintNative") }, d)

    private fun fn(name: String, ret: java.lang.foreign.MemoryLayout?, vararg args: java.lang.foreign.MemoryLayout) = lazy {
        handle(name, if (ret == null) FunctionDescriptor.ofVoid(*args) else FunctionDescriptor.of(ret, *args))
    }

    private val F = JAVA_FLOAT
    private val I = JAVA_INT
    private val A = ADDRESS
    private val L = JAVA_LONG

    private val lastErrorH by fn("field_paint_last_error", A)
    private val engineH by fn("field_paint_engine", I)
    private val canvasNewH by fn("field_paint_canvas_new", A, I, F, I, L, F, F, F, F)
    private val canvasFreeH by fn("field_paint_canvas_free", null, A)
    private val canvasSizeH by fn("field_paint_canvas_size", null, A, A, A)
    private val canvasCopyH by fn("field_paint_canvas_copy", A, A)
    private val brushNewH by fn("field_paint_brush_new", A, I, F, F, L)
    private val brushFreeH by fn("field_paint_brush_free", null, A)
    private val brushLoadH by fn("field_paint_brush_load", I, A, F, F, F, F, F, F, F, F)
    private val brushLoadKmH by fn("field_paint_brush_load_km", I, A, F, F, F, F, F, F, F, F)
    private val brushFullnessH by fn("field_paint_brush_fullness", F, A)
    private val dragH by fn("field_paint_drag", I, A, A, A, I, F, F, F, F, F, I, A, I)
    private val touchH by fn("field_paint_touch", I, A, A, F, F, F, F, F, F, F)
    private val glazeH by fn("field_paint_glaze", I, A, A, A, I, F, F, F, F, F, F)
    private val workH by fn("field_paint_work", I, A, A, A, I, F, I, F, F, F, F, F, F, F, F, F, F, F, F, F, I, F, I, F, L)
    private val outlineH by fn("field_paint_outline", I, A, A, A, I, I, I, F, F, F, L, A, A)
    private val freeFloatsH by fn("field_paint_free_floats", null, A, L)
    private val waitH by fn("field_paint_wait", I, A, F)
    private val dryH by fn("field_paint_dry", I, A)
    private val primeH by fn("field_paint_prime", I, A, F, F, F, F, F, F, F, L)
    private val crackH by fn("field_paint_crack", I, A, I, L)
    private val softnessH by fn("field_paint_pencil_softness", F, A)
    private val drawLineH by fn("field_paint_draw_line", F, A, I, F, F, A, I, A, I, F, I, I, L)
    private val sketchLineH by fn("field_paint_sketch_line", F, A, I, F, F, A, I, F, I, F, F, L)
    private val hatchH by fn("field_paint_hatch", F, A, I, F, F, A, A, I, F, F, F, F, L)
    private val eraseH by fn("field_paint_erase", I, A, A, A, I, F, F)
    private val fixH by fn("field_paint_fix_drawing", I, A)
    private val stippleH by fn("field_paint_stipple", I, A, A, A, I, F, F, F, F, F, F, F, F, F, F, F, F, I, L)
    private val tubesH by fn("field_paint_tubes", A)
    private val freeStringH by fn("field_paint_free_string", null, A)
    private val mixTubesH by fn("field_paint_mix_tubes", I, A, A, I, F, A)
    private val mixSpectralH by fn("field_paint_mix_spectral", I, A, A, I, A)
    private val renderH by fn("field_paint_render_argb", I, A, F, F, A, I)
    private val renderWetH by fn("field_paint_render_wet_argb", I, A, A, I)
    private val statsH by fn("field_paint_stats", I, A, A)
    private val saveH by fn("field_paint_save_png", I, A, A)
    private val saveStateH by fn("field_paint_save_state", I, A, A)
    private val loadStateH by fn("field_paint_load_state", A, A)

    // ---- upstream's later tools (tools.rs) ----
    private val D = JAVA_DOUBLE
    private val canvasNewV2H by fn("field_paint_canvas_new_v2", A, I, F, I, L, F, F, F, F, I)
    private val canvasEngineH by fn("field_paint_canvas_engine", I, A)
    private val strokeNoiseH by fn("field_paint_stroke_noise", I, A, I)
    private val groundFinishH by fn("field_paint_ground_finish", I, A, F)
    private val brushNewV2H by fn("field_paint_brush_new_v2", A, I, F, L, I, A)
    private val brushConfigH by fn("field_paint_brush_config", I, A, A)
    private val brushLoadV2H by fn("field_paint_brush_load_v2", I, A, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, L)
    private val brushWipeH by fn("field_paint_brush_wipe", I, A, F)
    private val brushCarriedH by fn("field_paint_brush_carried_mm3", I, A, A, A)
    private val brushPaintH by fn("field_paint_brush_paint", I, A, A, A)
    private val spatterH by fn("field_paint_spatter", I, A, A, F, F, F, F, F, F, L)
    private val knifeNewH by fn("field_paint_knife_new", A, F)
    private val knifeFreeH by fn("field_paint_knife_free", null, A)
    private val knifeLoadH by fn("field_paint_knife_load", I, A, F, F, F, F, F, F, F, F, I, F, F, F)
    private val knifeWipeH by fn("field_paint_knife_wipe", I, A)
    private val knifeFullnessH by fn("field_paint_knife_fullness", F, A)
    private val knifePaintH by fn("field_paint_knife_paint", I, A, A, A)
    private val knifeDragH by fn("field_paint_knife_drag", I, A, A, A, I, F, F, F, I, F)
    private val ragNewH by fn("field_paint_rag_new", A, F, L)
    private val ragFreeH by fn("field_paint_rag_free", null, A)
    private val ragDipH by fn("field_paint_rag_dip", I, A, A, F)
    private val ragRefoldH by fn("field_paint_rag_refold", I, A, A)
    private val ragWipeH by fn("field_paint_rag_wipe", D, A, A, A, I, A, I, L)
    private val ragStateH by fn("field_paint_rag_state", I, A, A, A)
    private val renderLitH by fn("field_paint_render_lit_argb", I, A, F, F, F, I, A, I)
    private val statsV2H by fn("field_paint_stats_v2", I, A, A)
    private val freeBytesH by fn("field_paint_free_bytes", null, A, L)
    private val brushStateH by fn("field_paint_brush_state", I, A, A, A)
    private val brushFromStateH by fn("field_paint_brush_from_state", A, A, L)
    private val knifeStateH by fn("field_paint_knife_state", I, A, A, A)
    private val knifeFromStateH by fn("field_paint_knife_from_state", A, A, L)
    private val ragStateBytesH by fn("field_paint_rag_state_bytes", I, A, A, A)
    private val ragFromStateH by fn("field_paint_rag_from_state", A, A, L)

    // ---- water (water.rs) ----
    private val canvasNewPaperH by fn("field_paint_canvas_new_paper", A, I, F, A, L, F, I)
    private val mmPerUnitH by fn("field_paint_canvas_mm_per_unit", F, A)
    private val paperH by fn("field_paint_paper", I, A, A)
    private val tiltH by fn("field_paint_tilt", I, A, F, F, L)
    private val evaporationH by fn("field_paint_evaporation", I, A, F, L)
    private val waitSecondsH by fn("field_paint_wait_seconds", I, A, F)
    private val dispersionH by fn("field_paint_dispersion", I, A, F, L)
    private val marangoniH by fn("field_paint_marangoni", I, A, F, L)
    private val wbrushNewH by fn("field_paint_wbrush_new", A, A, F, F)
    private val wbrushFreeH by fn("field_paint_wbrush_free", null, A)
    private val wbrushLoadH by fn("field_paint_wbrush_load", I, A, A, A, A, I, F, F)
    private val wbrushBlotH by fn("field_paint_wbrush_blot", I, A, F)
    private val wbrushStateH by fn("field_paint_wbrush_state", I, A, A)
    private val wbrushStrokeH by fn("field_paint_wbrush_stroke", I, A, A, A, I, L, A)
    private val wbrushStateBytesH by fn("field_paint_wbrush_state_bytes", I, A, A, A)
    private val wbrushFromStateH by fn("field_paint_wbrush_from_state", A, A, L)
    private val waterDropH by fn("field_paint_water_drop", I, A, F, F, F, A, A, A, I, L)
    private val waterWashH by fn("field_paint_water_wash", I, A, A, A, I, F, F, A, A, A, I, L)
    private val waterStatsH by fn("field_paint_water_stats", I, A, A, I)
    private val waterSpeciesH by fn("field_paint_water_species", A, A)
    private val waterMapH by fn("field_paint_water_map", I, A, I, A, I)
    private val aqPigmentH by fn("field_paint_aq_pigment", I, A, A)
    private val aqPigmentNamesH by fn("field_paint_aq_pigment_names", A)
    private val aqPigmentMakeH by fn("field_paint_aq_pigment_make", I, F, F, F, F, F, F, F, F, F, A)

    // ---- the segmented brush (dynamic.rs) ----
    private val dbrushNewH by fn("field_paint_dbrush_new", A, A, I, F, A, I, I, L)
    private val dbrushFreeH by fn("field_paint_dbrush_free", null, A)
    private val dbrushSetH by fn("field_paint_dbrush_set", I, A, F, F, F, F, F, F)
    private val dbrushInfoH by fn("field_paint_dbrush_info", I, A, A)
    private val dbrushLoadH by fn("field_paint_dbrush_load", I, A, F, F, F, F, F, F, F, F, F, F, F, F, F, F, F, L)
    private val dbrushLoadWaterH by fn("field_paint_dbrush_load_water", I, A, A, A, A, A, I, F, F)
    private val dbrushStrokeH by fn("field_paint_dbrush_stroke", I, A, A, A, I, L, A)
    private val dbrushHairsH by fn("field_paint_dbrush_hairs", I, A, A, A, A)
    private val contactMapH by fn("field_paint_contact_map", I, A, A, I)
    private val dbrushStateH by fn("field_paint_dbrush_state", I, A, A, A)
    private val dbrushFromStateH by fn("field_paint_dbrush_from_state", A, A, L)

    private fun error(what: String): Nothing {
        val msg = (lastErrorH.invokeExact() as MemorySegment).reinterpret(Long.MAX_VALUE).getString(0)
        throw IllegalStateException("paint: $what failed: $msg")
    }

    private fun check(status: Int, what: String) { if (status < 0) error(what) }
    private fun check(drawn: Float, what: String): Double = if (drawn < 0f) error(what) else drawn.toDouble()
    private fun nonNull(p: MemorySegment, what: String): MemorySegment = if (p == MemorySegment.NULL) error(what) else p

    /** Rings packed as the wrapper's regions want them: all points, then each ring's size. */
    private fun Arena.rings(rings: List<FloatArray>?): Triple<MemorySegment, MemorySegment, Int> {
        if (rings.isNullOrEmpty()) return Triple(MemorySegment.NULL, MemorySegment.NULL, 0)
        val xy = FloatArray(rings.sumOf { it.size })
        var at = 0
        for (r in rings) { r.copyInto(xy, at); at += r.size }
        return Triple(allocateFrom(F, *xy), allocateFrom(I, *IntArray(rings.size) { rings[it].size / 2 }), rings.size)
    }

    fun engine(): Int = engineH.invokeExact() as Int

    fun canvasNew(w: Int, aspect: Float, style: Int, seed: Long, r: Float, g: Float, b: Float, sizeMm: Float): MemorySegment =
        nonNull(canvasNewH.invokeExact(w, aspect, style, seed, r, g, b, sizeMm) as MemorySegment, "creating a canvas")

    fun canvasFree(c: MemorySegment) { canvasFreeH.invokeExact(c) }

    fun canvasCopy(c: MemorySegment): MemorySegment = nonNull(canvasCopyH.invokeExact(c) as MemorySegment, "copying the canvas")

    fun canvasSize(c: MemorySegment): Pair<Int, Int> = Arena.ofConfined().use { a ->
        val w = a.allocate(I)
        val h = a.allocate(I)
        canvasSizeH.invokeExact(c, w, h)
        w.get(I, 0) to h.get(I, 0)
    }

    fun brushNew(kind: Int, width: Float, point: Float, seed: Long): MemorySegment =
        nonNull(brushNewH.invokeExact(kind, width, point, seed) as MemorySegment, "creating a brush")

    fun brushFree(b: MemorySegment) { brushFreeH.invokeExact(b) }

    fun brushLoad(b: MemorySegment, r: Float, g: Float, bl: Float, hiding: Float, stiff: Float, drying: Float, amount: Float, wipe: Float) =
        check(brushLoadH.invokeExact(b, r, g, bl, hiding, stiff, drying, amount, wipe) as Int, "loading the brush")

    fun brushLoadKm(b: MemorySegment, r: Float, g: Float, bl: Float, scatter: Float, stiff: Float, drying: Float, amount: Float, wipe: Float) =
        check(brushLoadKmH.invokeExact(b, r, g, bl, scatter, stiff, drying, amount, wipe) as Int, "loading the brush")

    fun brushFullness(b: MemorySegment): Float = brushFullnessH.invokeExact(b) as Float

    fun drag(c: MemorySegment, b: MemorySegment, xy: FloatArray, p0: Float, p1: Float, shake: Float,
             attack: Float, release: Float, along: Boolean, swell: List<Double>) = Arena.ofConfined().use { a ->
        val sw = if (swell.isEmpty()) MemorySegment.NULL else a.allocateFrom(F, *FloatArray(swell.size) { swell[it].toFloat() })
        check(dragH.invokeExact(c, b, a.allocateFrom(F, *xy), xy.size / 2, p0, p1, shake, attack, release,
            if (along) 1 else 0, sw, swell.size) as Int, "a stroke")
    }

    fun touch(c: MemorySegment, b: MemorySegment, x: Float, y: Float, p: Float, dx: Float, dy: Float, twist: Float, angle: Float) =
        check(touchH.invokeExact(c, b, x, y, p, dx, dy, twist, angle) as Int, "a touch")

    fun glaze(c: MemorySegment, rings: List<FloatArray>?, feather: Float, r: Float, g: Float, b: Float, hiding: Float, coats: Float) =
        Arena.ofConfined().use { a ->
            val (xy, sizes, n) = a.rings(rings)
            check(glazeH.invokeExact(c, xy, sizes, n, feather, r, g, b, hiding, coats) as Int, "a glaze")
        }

    fun work(c: MemorySegment, rings: List<FloatArray>, feather: Float, preset: Int, r: Float, g: Float, b: Float,
             hiding: Float, stiff: Float, angle: Float, angleJitter: Float, len0: Float, len1: Float, coverage: Float,
             p0: Float, p1: Float, cross: Float, order: Int, sweep: Float, clip: Int, width: Float, seed: Long) =
        Arena.ofConfined().use { a ->
            val (xy, sizes, n) = a.rings(rings)
            check(workH.invokeExact(c, xy, sizes, n, feather, preset, r, g, b, hiding, stiff, angle, angleJitter,
                len0, len1, coverage, p0, p1, cross, order, sweep, clip, width, seed) as Int, "working a region")
        }

    /** The outline's strokes, each as interleaved canvas-unit x, y. */
    fun outline(c: MemorySegment, b: MemorySegment?, xy: FloatArray, closed: Boolean, character: Int, amount: Float,
                pressure: Float, size: Float, seed: Long): List<FloatArray> = Arena.ofConfined().use { a ->
        val out = a.allocate(A)
        val len = a.allocate(L)
        check(outlineH.invokeExact(c, b ?: MemorySegment.NULL, a.allocateFrom(F, *xy), xy.size / 2, if (closed) 1 else 0,
            character, amount, pressure, size, seed, out, len) as Int, "an outline")
        val n = len.get(L, 0)
        val buf = out.get(A, 0).reinterpret(n * 4)
        try {
            val v = buf.toArray(F)
            val count = v[0].toInt()
            var at = 1 + count
            List(count) { i ->
                val k = 2 * v[1 + i].toInt()
                v.copyOfRange(at, at + k).also { at += k }
            }
        } finally {
            freeFloatsH.invokeExact(buf, n)
        }
    }

    fun waitMinutes(c: MemorySegment, minutes: Float) = check(waitH.invokeExact(c, minutes) as Int, "wait")

    fun dry(c: MemorySegment) = check(dryH.invokeExact(c) as Int, "dry")

    fun prime(c: MemorySegment, r: Float, g: Float, b: Float, hiding: Float, um: Float, stiff: Float, texture: Float, seed: Long) =
        check(primeH.invokeExact(c, r, g, b, hiding, um, stiff, texture, seed) as Int, "priming")

    fun crack(c: MemorySegment, kind: Int, seed: Long) = check(crackH.invokeExact(c, kind, seed) as Int, "craquelure")

    fun pencilSoftness(grade: String): Double = Arena.ofConfined().use { a ->
        val s = softnessH.invokeExact(a.allocateFrom(grade)) as Float
        require(!s.isNaN()) { "\"$grade\" is not a pencil grade; try 9H .. H, F, HB, B .. 9B" }
        s.toDouble()
    }

    private fun Arena.floats(v: List<Double>): MemorySegment = allocateFrom(F, *FloatArray(v.size) { v[it].toFloat() })

    fun drawLine(c: MemorySegment, p: Pencil, xy: FloatArray, profile: List<Double>, tremor: Float, smooth: Boolean, ruler: Boolean, seed: Long): Double =
        Arena.ofConfined().use { a ->
            check(drawLineH.invokeExact(c, p.medium.code, p.softness.toFloat(), p.worn.toFloat(), a.allocateFrom(F, *xy), xy.size / 2,
                a.floats(profile), profile.size, tremor, if (smooth) 1 else 0, if (ruler) 1 else 0, seed) as Float, "drawing a line")
        }

    fun sketchLine(c: MemorySegment, p: Pencil, xy: FloatArray, pressure: Float, passes: Int, wander: Float, tremor: Float, seed: Long): Double =
        Arena.ofConfined().use { a ->
            check(sketchLineH.invokeExact(c, p.medium.code, p.softness.toFloat(), p.worn.toFloat(), a.allocateFrom(F, *xy), xy.size / 2,
                pressure, passes, wander, tremor, seed) as Float, "sketching a line")
        }

    fun hatch(c: MemorySegment, p: Pencil, rings: List<FloatArray>, angle: Float, spacing: Float, length: Float, pressure: Float, seed: Long): Double =
        Arena.ofConfined().use { a ->
            val (xy, sizes, n) = a.rings(rings)
            check(hatchH.invokeExact(c, p.medium.code, p.softness.toFloat(), p.worn.toFloat(), xy, sizes, n,
                angle, spacing, length, pressure, seed) as Float, "hatching")
        }

    fun erase(c: MemorySegment, rings: List<FloatArray>, feather: Float, strength: Float) = Arena.ofConfined().use { a ->
        val (xy, sizes, n) = a.rings(rings)
        check(eraseH.invokeExact(c, xy, sizes, n, feather, strength) as Int, "erasing")
    }

    fun fixDrawing(c: MemorySegment) = check(fixH.invokeExact(c) as Int, "fixative")

    fun stipple(c: MemorySegment, rings: List<FloatArray>, feather: Float, size: Float, coverage: Float, r: Float, g: Float, b: Float,
                hiding: Float, stiff: Float, p0: Float, p1: Float, cluster: Float, drag: Float, dipEvery: Int, seed: Long) =
        Arena.ofConfined().use { a ->
            val (xy, sizes, n) = a.rings(rings)
            check(stippleH.invokeExact(c, xy, sizes, n, feather, size, coverage, r, g, b, hiding, stiff, p0, p1,
                cluster, drag, dipEvery, seed) as Int, "stippling")
        }

    fun tubes(): String {
        val p = nonNull(tubesH.invokeExact() as MemorySegment, "listing tubes")
        try {
            return p.reinterpret(Long.MAX_VALUE).getString(0)
        } finally {
            freeStringH.invokeExact(p)
        }
    }

    /** `[r, g, b (linear), scatter, stiff, drying]` of the pile. */
    fun mixTubes(tubes: Map<String, Double>, medium: Float): FloatArray = Arena.ofConfined().use { a ->
        require(tubes.isNotEmpty()) { "mix at least one tube" }
        require(tubes.keys.none { '\n' in it }) { "tube names cannot contain newlines" }
        val out = a.allocate(F, 6)
        check(mixTubesH.invokeExact(a.allocateFrom(tubes.keys.joinToString("\n")), a.floats(tubes.values.toList()),
            tubes.size, medium, out) as Int, "mixing tubes")
        out.toArray(F)
    }

    fun mixSpectral(rgb: FloatArray, weights: FloatArray): FloatArray = Arena.ofConfined().use { a ->
        val out = a.allocate(F, 3)
        check(mixSpectralH.invokeExact(a.allocateFrom(F, *rgb), a.allocateFrom(F, *weights), weights.size, out) as Int, "spectral mix")
        out.toArray(F)
    }

    fun renderArgb(c: MemorySegment, pixels: Int, relief: Float, gloss: Float): IntArray = Arena.ofConfined().use { a ->
        val out = a.allocate(I, pixels.toLong())
        check(renderH.invokeExact(c, relief, gloss, out, pixels) as Int, "render")
        out.toArray(I)
    }

    fun renderWetArgb(c: MemorySegment, pixels: Int): IntArray = Arena.ofConfined().use { a ->
        val out = a.allocate(I, pixels.toLong())
        check(renderWetH.invokeExact(c, out, pixels) as Int, "render")
        out.toArray(I)
    }

    fun stats(c: MemorySegment): PaintStats = Arena.ofConfined().use { a ->
        val out = a.allocate(JAVA_DOUBLE, 6)
        check(statsH.invokeExact(c, out) as Int, "stats")
        val v = out.toArray(JAVA_DOUBLE)
        PaintStats(v[0], v[1], v[2], v[3], v[4], v[5])
    }

    fun savePng(c: MemorySegment, path: String) = Arena.ofConfined().use { a ->
        check(saveH.invokeExact(c, a.allocateFrom(path)) as Int, "saving $path")
    }

    fun saveState(c: MemorySegment, path: String) = Arena.ofConfined().use { a ->
        check(saveStateH.invokeExact(c, a.allocateFrom(path)) as Int, "saving $path")
    }

    fun loadState(path: String): MemorySegment = Arena.ofConfined().use { a ->
        nonNull(loadStateH.invokeExact(a.allocateFrom(path)) as MemorySegment, "loading $path")
    }

    // ---- upstream's later tools ----

    fun canvasNewV2(w: Int, aspect: Float, style: Int, seed: Long, r: Float, g: Float, b: Float, sizeMm: Float, engine: Int): MemorySegment =
        nonNull(canvasNewV2H.invokeExact(w, aspect, style, seed, r, g, b, sizeMm, engine) as MemorySegment, "creating a canvas")

    fun strokeNoise(c: MemorySegment, noise: Int) = check(strokeNoiseH.invokeExact(c, noise) as Int, "keying a stroke's noise")
    fun canvasEngine(c: MemorySegment): Int = (canvasEngineH.invokeExact(c) as Int).also { if (it < 0) error("reading the engine") }

    fun groundFinish(c: MemorySegment, absorbent: Float) = check(groundFinishH.invokeExact(c, absorbent) as Int, "finishing the ground")

    /** [cfg] is the 11 construction values of `field_paint_brush_new_v2`, NaN for the preset's. */
    fun brushNewV2(kind: Int, width: Float, seed: Long, engine: Int, cfg: FloatArray): MemorySegment = Arena.ofConfined().use { a ->
        require(cfg.size == 11)
        nonNull(brushNewV2H.invokeExact(kind, width, seed, engine, a.allocateFrom(F, *cfg)) as MemorySegment, "creating a brush")
    }

    /** kind, width, bristles, length, stiffness, hair, run, lay, pickup, push, splay, ragged, point. */
    fun brushConfig(b: MemorySegment): FloatArray = Arena.ofConfined().use { a ->
        val out = a.allocate(F, 13)
        check(brushConfigH.invokeExact(b, out) as Int, "reading the brush")
        out.toArray(F)
    }

    fun brushLoadV2(b: MemorySegment, r: Float, g: Float, bl: Float, hiding: Float, scatter: Float, stiff: Float, drying: Float,
                    amount: Float, wipe: Float, thinner: Float, turps: Float, oil: Float, side: Float, share: Float, streak: Float, partSeed: Long) =
        check(brushLoadV2H.invokeExact(b, r, g, bl, hiding, scatter, stiff, drying, amount, wipe, thinner, turps, oil,
            side, share, streak, partSeed) as Int, "loading the brush")

    fun brushWipe(b: MemorySegment, fraction: Float) = check(brushWipeH.invokeExact(b, fraction) as Int, "wiping the brush")

    fun brushCarried(c: MemorySegment, b: MemorySegment): DoubleArray = Arena.ofConfined().use { a ->
        val out = a.allocate(D, 2)
        check(brushCarriedH.invokeExact(c, b, out) as Int, "measuring the brush")
        out.toArray(D)
    }

    /** `[r, g, b (linear), mm³]` of the paint in the brush, or null when it is clean. */
    fun brushPaint(c: MemorySegment, b: MemorySegment): FloatArray? = Arena.ofConfined().use { a ->
        val out = a.allocate(F, 4)
        val st = brushPaintH.invokeExact(c, b, out) as Int
        check(st, "reading the brush's paint")
        if (st == 0) null else out.toArray(F)
    }

    fun spatter(c: MemorySegment, b: MemorySegment, x: Float, y: Float, tx: Float, ty: Float, spread: Float, force: Float, seed: Long): Int =
        (spatterH.invokeExact(c, b, x, y, tx, ty, spread, force, seed) as Int).also { check(it, "spatter") }

    fun knifeNew(width: Float): MemorySegment = nonNull(knifeNewH.invokeExact(width) as MemorySegment, "making a knife")
    fun knifeFree(k: MemorySegment) { knifeFreeH.invokeExact(k) }
    fun knifeLoad(k: MemorySegment, r: Float, g: Float, b: Float, hiding: Float, scatter: Float, stiff: Float, drying: Float,
                  amount: Float, wipe: Boolean, thinner: Float, turps: Float, oil: Float) =
        check(knifeLoadH.invokeExact(k, r, g, b, hiding, scatter, stiff, drying, amount, if (wipe) 1 else 0, thinner, turps, oil) as Int, "loading the knife")
    fun knifeWipe(k: MemorySegment) = check(knifeWipeH.invokeExact(k) as Int, "wiping the knife")
    fun knifeFullness(k: MemorySegment): Float = knifeFullnessH.invokeExact(k) as Float
    fun knifePaint(c: MemorySegment, k: MemorySegment): FloatArray? = Arena.ofConfined().use { a ->
        val out = a.allocate(F, 4)
        val st = knifePaintH.invokeExact(c, k, out) as Int
        check(st, "reading the knife's paint")
        if (st == 0) null else out.toArray(F)
    }
    fun knifeDrag(c: MemorySegment, k: MemorySegment, xy: FloatArray, p0: Float, p1: Float, angle: Float, lay: Boolean, lift: Float) =
        Arena.ofConfined().use { a ->
            check(knifeDragH.invokeExact(c, k, a.allocateFrom(F, *xy), xy.size / 2, p0, p1, angle, if (lay) 1 else 0, lift) as Int, "a knife stroke")
        }

    fun ragNew(width: Float, seed: Long): MemorySegment = nonNull(ragNewH.invokeExact(width, seed) as MemorySegment, "making a rag")
    fun ragFree(g: MemorySegment) { ragFreeH.invokeExact(g) }
    fun ragDip(c: MemorySegment, g: MemorySegment, amount: Float) = check(ragDipH.invokeExact(c, g, amount) as Int, "dipping the rag")
    fun ragRefold(c: MemorySegment, g: MemorySegment) = check(ragRefoldH.invokeExact(c, g) as Int, "refolding the rag")
    fun ragWipe(c: MemorySegment, g: MemorySegment, xy: FloatArray, pressure: List<Double>, seed: Long): Double = Arena.ofConfined().use { a ->
        val pr = if (pressure.isEmpty()) MemorySegment.NULL else a.floats(pressure)
        val got = ragWipeH.invokeExact(c, g, a.allocateFrom(F, *xy), xy.size / 2, pr, pressure.size, seed) as Double
        if (got.isNaN()) error("a wipe") else got
    }
    /** load, soaked, damp. */
    fun ragState(c: MemorySegment, g: MemorySegment): FloatArray = Arena.ofConfined().use { a ->
        val out = a.allocate(F, 3)
        check(ragStateH.invokeExact(c, g, out) as Int, "reading the rag")
        out.toArray(F)
    }

    fun renderLitArgb(c: MemorySegment, pixels: Int, azimuth: Float, elevation: Float, strength: Float, dried: Boolean): IntArray =
        Arena.ofConfined().use { a ->
            val out = a.allocate(I, pixels.toLong())
            check(renderLitH.invokeExact(c, azimuth, elevation, strength, if (dried) 1 else 0, out, pixels) as Int, "render")
            out.toArray(I)
        }

    fun statsV2(c: MemorySegment): PaintStats = Arena.ofConfined().use { a ->
        val out = a.allocate(D, 9)
        check(statsV2H.invokeExact(c, out) as Int, "stats")
        val v = out.toArray(D)
        PaintStats(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8].toInt())
    }

    private fun bytes(what: String, call: (MemorySegment, MemorySegment) -> Int): ByteArray = Arena.ofConfined().use { a ->
        val out = a.allocate(A)
        val len = a.allocate(L)
        check(call(out, len), what)
        val n = len.get(L, 0)
        val buf = out.get(A, 0).reinterpret(n)
        try { buf.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE) } finally { freeBytesH.invokeExact(buf, n) }
    }

    private fun fromBytes(bytes: ByteArray, what: String, call: (MemorySegment, Long) -> MemorySegment): MemorySegment = Arena.ofConfined().use { a ->
        nonNull(call(a.allocateFrom(java.lang.foreign.ValueLayout.JAVA_BYTE, *bytes), bytes.size.toLong()), what)
    }

    fun brushState(b: MemorySegment): ByteArray = bytes("saving the brush") { o, l -> brushStateH.invokeExact(b, o, l) as Int }
    fun brushFromState(bytes: ByteArray): MemorySegment = fromBytes(bytes, "restoring a brush") { p, n -> brushFromStateH.invokeExact(p, n) as MemorySegment }
    fun knifeState(k: MemorySegment): ByteArray = bytes("saving the knife") { o, l -> knifeStateH.invokeExact(k, o, l) as Int }
    fun knifeFromState(bytes: ByteArray): MemorySegment = fromBytes(bytes, "restoring a knife") { p, n -> knifeFromStateH.invokeExact(p, n) as MemorySegment }
    fun ragStateBytes(g: MemorySegment): ByteArray = bytes("saving the rag") { o, l -> ragStateBytesH.invokeExact(g, o, l) as Int }
    fun ragFromState(bytes: ByteArray): MemorySegment = fromBytes(bytes, "restoring a rag") { p, n -> ragFromStateH.invokeExact(p, n) as MemorySegment }

    // ---- water ----

    fun canvasNewPaper(w: Int, aspect: Float, paper: String, seed: Long, sizeMm: Float, engine: Int): MemorySegment = Arena.ofConfined().use { a ->
        nonNull(canvasNewPaperH.invokeExact(w, aspect, a.allocateFrom(paper), seed, sizeMm, engine) as MemorySegment, "making a sheet of $paper")
    }

    fun mmPerUnit(c: MemorySegment): Float = mmPerUnitH.invokeExact(c) as Float

    /** The paper's 12 values, or null when the canvas has no water. */
    fun paper(c: MemorySegment): FloatArray? = Arena.ofConfined().use { a ->
        val out = a.allocate(F, 12)
        val st = paperH.invokeExact(c, out) as Int
        check(st, "reading the paper")
        if (st == 0) null else out.toArray(F)
    }

    fun tilt(c: MemorySegment, degrees: Float, direction: Float, seed: Long) = check(tiltH.invokeExact(c, degrees, direction, seed) as Int, "tilting")
    fun evaporation(c: MemorySegment, umS: Float, seed: Long) = check(evaporationH.invokeExact(c, umS, seed) as Int, "setting evaporation")
    fun dispersion(c: MemorySegment, v: Float, seed: Long) = check(dispersionH.invokeExact(c, v, seed) as Int, "setting dispersion")
    fun marangoni(c: MemorySegment, v: Float, seed: Long) = check(marangoniH.invokeExact(c, v, seed) as Int, "setting the surface tension drop")
    fun waitSeconds(c: MemorySegment, s: Float) = check(waitSecondsH.invokeExact(c, s) as Int, "waiting")

    fun wbrushNew(c: MemorySegment, width: Float, capacity: Float): MemorySegment = nonNull(wbrushNewH.invokeExact(c, width, capacity) as MemorySegment, "making a watercolour brush")
    fun wbrushFree(b: MemorySegment) { wbrushFreeH.invokeExact(b) }

    private class Pig(val names: MemorySegment, val params: MemorySegment, val conc: MemorySegment, val n: Int)
    private fun Arena.pig(paint: WaterPaint): Pig {
        val ps = paint.pigments.entries.toList()
        if (ps.isEmpty()) return Pig(allocateFrom(""), MemorySegment.NULL, MemorySegment.NULL, 0)
        require(ps.none { '\n' in it.key.name }) { "pigment names cannot contain newlines" }
        val params = FloatArray(8 * ps.size)
        ps.forEachIndexed { i, e -> e.key.params().copyInto(params, 8 * i) }
        return Pig(allocateFrom(ps.joinToString("\n") { it.key.name }), allocateFrom(F, *params),
            allocateFrom(F, *FloatArray(ps.size) { ps[it].value.toFloat() }), ps.size)
    }

    fun wbrushLoad(b: MemorySegment, paint: WaterPaint, fill: Float, wipe: Float) = Arena.ofConfined().use { a ->
        val p = a.pig(paint)
        check(wbrushLoadH.invokeExact(b, p.names, p.params, p.conc, p.n, fill, wipe) as Int, "loading the brush")
    }
    fun wbrushBlot(b: MemorySegment, share: Float) = check(wbrushBlotH.invokeExact(b, share) as Int, "blotting the brush")
    fun wbrushState(b: MemorySegment): FloatArray = Arena.ofConfined().use { a ->
        val out = a.allocate(F, 4)
        check(wbrushStateH.invokeExact(b, out) as Int, "reading the brush")
        out.toArray(F)
    }
    /** [xytp] is x, y, t, pressure per sample; returns laid mm³, taken mm³, laid µg, taken µg, seconds. */
    fun wbrushStroke(c: MemorySegment, b: MemorySegment, xytp: FloatArray, seed: Long): DoubleArray = Arena.ofConfined().use { a ->
        val out = a.allocate(D, 5)
        check(wbrushStrokeH.invokeExact(c, b, a.allocateFrom(F, *xytp), xytp.size / 4, seed, out) as Int, "a watercolour stroke")
        out.toArray(D)
    }
    fun wbrushStateBytes(b: MemorySegment): ByteArray = bytes("saving the brush") { o, l -> wbrushStateBytesH.invokeExact(b, o, l) as Int }
    fun wbrushFromState(bytes: ByteArray): MemorySegment = fromBytes(bytes, "restoring a watercolour brush") { p, n -> wbrushFromStateH.invokeExact(p, n) as MemorySegment }

    fun waterDrop(c: MemorySegment, x: Float, y: Float, volume: Float, paint: WaterPaint, seed: Long) = Arena.ofConfined().use { a ->
        val p = a.pig(paint)
        check(waterDropH.invokeExact(c, x, y, volume, p.names, p.params, p.conc, p.n, seed) as Int, "a drop")
    }
    fun waterWash(c: MemorySegment, rings: List<FloatArray>, feather: Float, um: Float, paint: WaterPaint, seed: Long) = Arena.ofConfined().use { a ->
        val (xy, sizes, n) = a.rings(rings)
        val p = a.pig(paint)
        check(waterWashH.invokeExact(c, xy, sizes, n, feather, um, p.names, p.params, p.conc, p.n, seed) as Int, "a wash")
    }
    fun waterStats(c: MemorySegment): WaterLedger? = Arena.ofConfined().use { a ->
        val out = a.allocate(D, 9 + 9 * 8)
        val n = waterStatsH.invokeExact(c, out, 9 + 9 * 8) as Int
        check(n, "reading the water")
        if (n == 0) return@use null
        val v = out.toArray(D)
        val names = (waterSpeciesH.invokeExact(c) as MemorySegment).let { p ->
            nonNull(p, "naming the pigments")
            try { p.reinterpret(Long.MAX_VALUE).getString(0) } finally { freeStringH.invokeExact(p) }
        }.split('\n')
        val pigments = (0 until v[8].toInt()).map { k ->
            val o = 9 + 9 * k
            PigmentLedger(names.getOrElse(k) { "?" }, v[o], v[o + 1], v[o + 2], v[o + 3], v[o + 4], v[o + 5], v[o + 6], v[o + 7], v[o + 8])
        }
        WaterLedger(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], pigments)
    }
    fun waterMap(c: MemorySegment, which: Int, pixels: Int): FloatArray? = Arena.ofConfined().use { a ->
        val out = a.allocate(F, pixels.toLong())
        val n = waterMapH.invokeExact(c, which, out, pixels) as Int
        check(n, "a water map")
        if (n == 0) null else out.toArray(F)
    }
    fun aqPigment(name: String): FloatArray? = Arena.ofConfined().use { a ->
        val out = a.allocate(F, 8)
        val st = aqPigmentH.invokeExact(a.allocateFrom(name), out) as Int
        check(st, "a pigment")
        if (st == 0) null else out.toArray(F)
    }
    fun aqPigmentNames(): List<String> {
        val p = nonNull(aqPigmentNamesH.invokeExact() as MemorySegment, "listing pigments")
        try { return p.reinterpret(Long.MAX_VALUE).getString(0).split('\n') } finally { freeStringH.invokeExact(p) }
    }
    fun aqPigmentMake(r: Float, g: Float, b: Float, strength: Float, opacity: Float, density: Float, diameter: Float, stain: Float, lift: Float): FloatArray =
        Arena.ofConfined().use { a ->
            val out = a.allocate(F, 8)
            check(aqPigmentMakeH.invokeExact(r, g, b, strength, opacity, density, diameter, stain, lift, out) as Int, "making a pigment")
            out.toArray(F)
        }

    // ---- the segmented brush ----

    fun dbrushNew(c: MemorySegment, kind: Int, width: Float, cfg: FloatArray, hairs: Int, segs: Int, seed: Long): MemorySegment = Arena.ofConfined().use { a ->
        require(cfg.size == 11)
        nonNull(dbrushNewH.invokeExact(c, kind, width, a.allocateFrom(F, *cfg), hairs, segs, seed) as MemorySegment, "making a segmented brush")
    }
    fun dbrushFree(b: MemorySegment) { dbrushFreeH.invokeExact(b) }
    fun dbrushSet(b: MemorySegment, muS: Float, muK: Float, yieldRad: Float, plastic: Float, recover: Float, cohesion: Float) =
        check(dbrushSetH.invokeExact(b, muS, muK, yieldRad, plastic, recover, cohesion) as Int, "setting the brush")
    fun dbrushInfo(b: MemorySegment): FloatArray = Arena.ofConfined().use { a ->
        val out = a.allocate(F, 14)
        check(dbrushInfoH.invokeExact(b, out) as Int, "reading the brush")
        out.toArray(F)
    }
    fun dbrushLoad(b: MemorySegment, r: Float, g: Float, bl: Float, hiding: Float, scatter: Float, stiff: Float, drying: Float,
                   amount: Float, wipe: Float, thinner: Float, turps: Float, oil: Float, side: Float, share: Float, streak: Float, partSeed: Long) =
        check(dbrushLoadH.invokeExact(b, r, g, bl, hiding, scatter, stiff, drying, amount, wipe, thinner, turps, oil, side, share, streak, partSeed) as Int, "loading the brush")
    fun dbrushLoadWater(c: MemorySegment, b: MemorySegment, paint: WaterPaint, fill: Float, wipe: Float) = Arena.ofConfined().use { a ->
        val p = a.pig(paint)
        check(dbrushLoadWaterH.invokeExact(c, b, p.names, p.params, p.conc, p.n, fill, wipe) as Int, "loading the brush with watercolour")
    }
    /** [samples] is x, y, t, pressure, tilt, azimuth, roll per sample (radians). */
    fun dbrushStroke(c: MemorySegment, b: MemorySegment, samples: FloatArray, seed: Long): DoubleArray = Arena.ofConfined().use { a ->
        val out = a.allocate(D, 8)
        check(dbrushStrokeH.invokeExact(c, b, a.allocateFrom(F, *samples), samples.size / 7, seed, out) as Int, "a segmented stroke")
        out.toArray(D)
    }
    fun dbrushHairs(c: MemorySegment, b: MemorySegment): FloatArray = Arena.ofConfined().use { a ->
        val out = a.allocate(A)
        val len = a.allocate(L)
        check(dbrushHairsH.invokeExact(c, b, out, len) as Int, "reading the hairs")
        val n = len.get(L, 0)
        val buf = out.get(A, 0).reinterpret(n * 4)
        try { buf.toArray(F) } finally { freeFloatsH.invokeExact(buf, n) }
    }
    fun contactMap(c: MemorySegment, pixels: Int): FloatArray? = Arena.ofConfined().use { a ->
        val out = a.allocate(F, pixels.toLong())
        val n = contactMapH.invokeExact(c, out, pixels) as Int
        check(n, "the contact map")
        if (n == 0) null else out.toArray(F)
    }
    fun dbrushState(b: MemorySegment): ByteArray = bytes("saving the brush") { o, l -> dbrushStateH.invokeExact(b, o, l) as Int }
    fun dbrushFromState(bytes: ByteArray): MemorySegment = fromBytes(bytes, "restoring a segmented brush") { p, n -> dbrushFromStateH.invokeExact(p, n) as MemorySegment }
}
