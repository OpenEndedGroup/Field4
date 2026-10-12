# Field Document: ai4_paint_studio

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. Scribbler

<!-- field-box id="ai4_paint_scribbler" index="0" -->

```edn [field-box id="ai4_paint_scribbler" role="properties"]
{"id" "ai4_paint_scribbler" "index" 0 "name" "Scribbler" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Scribbler" "_tombstone" false "frame" #afield/rect [820.0 80.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ai4_paint_scribbler_code_.kt" "lines" 58 "characters" 2837 "sha256" "f48066be42672914f9d4626a393d4438721dd8e8f5a93d119d10768c38f6c482"}]}
```

```kotlin [field-property box="ai4_paint_scribbler" name="code" path="ai4_paint_scribbler_code_.kt"]
import afield.paint.*
import kotlin.random.Random

// A brush the painter does not have yet: a scribble, painted on the Studio's easel.
val studio = thisBox.inside.boxes.values.single { it.name == "Studio" }

fun inside(ring: List<List<Double>>, x: Double, y: Double) = ring.indices.count { i ->
    val a = ring[i]; val b = ring[(i + 1) % ring.size]
    (a[1] > y) != (b[1] > y) && x < a[0] + (y - a[1]) / (b[1] - a[1]) * (b[0] - a[0])
} % 2 == 1

fun ink(spec: String): Vec4 = if (spec.startsWith("#")) {
    val v = spec.drop(1).chunked(2).map { it.toInt(16) / 255.0 }
    rgb(v[0], v[1], v[2])
} else Tubes[spec].color

aiTool("scribble") {
    describe("Scribble inside a region: rows of looping, wandering line that fill the outline the way a quick hand " +
        "does. Dense scribbles read as tone, loose ones as texture; layer colours for depth.")
    effects(ToolEffect.CONTROL_RUNTIME)
    val outline by param<List<Double>>("the region, [x0, y0, x1, y1, ...] in canvas units")
    val color by param<String>("#rrggbb, or a tube name")
    val density by param<Double>("rows of loops per 100 units: 1 loose .. 8 dense", default = 3.0)
    val width by param<Double>("brush width", default = 5.0)
    action {
        val canvas = studio.properties["studio.canvas"] as PaintCanvas
        val ring = outline().chunked(2)
        val rnd = Random(color().hashCode() + outline().hashCode())
        val loop = 50.0 / density()
        val left = ring.minOf { it[0] }; val right = ring.maxOf { it[0] }
        val brush = canvas.brush(BrushKind.Round, width())
        var row = ring.minOf { it[1] }
        var dir = 1.0
        while (row <= ring.maxOf { it[1] }) {
            val line = FLine()
            var x = if (dir > 0) left else right
            var a = rnd.nextDouble(6.28)
            var r = loop          // the loop size wanders, and the hand drifts off its row
            var drift = 0.0
            var pen = false
            while (x in left..right) {
                r = (r + rnd.nextDouble(-0.08, 0.08) * loop).coerceIn(loop * 0.4, loop * 1.7)
                drift = (drift + rnd.nextDouble(-0.05, 0.05) * loop).coerceIn(-loop, loop)
                val px = x + cos(a) * r
                val py = row + drift + sin(a) * r * 0.8
                pen = if (inside(ring, px, py)) { if (pen) line.lineTo(px, py) else line.moveTo(px, py); true } else false
                x += dir * loop * rnd.nextDouble(0.06, 0.2)
                a += rnd.nextDouble(0.2, 0.55)
            }
            brush.load(ink(color()), hiding = 0.8, wipe = 0.6)
            canvas.stroke(line, brush, pressure = 0.4..0.8)
            row += loop * 1.1
            dir = -dir
        }
        (studio.properties["studio.refresh"] as () -> Unit)()
        "scribbled ${color()} over the region at density ${density()}"
    }
}
```

## 2. Studio

<!-- field-box id="ai4_paint_studio" index="1" -->

### Narrative

From `code`

A studio for AI4: open this box's AI panel and ask for a painting ("a quiet harbour at dusk").
The agent paints with the paint_* tools below, looks at the canvas as it goes, and hangs what it
finishes in the gallery. The canvas and the painter's tools belong to the conversation: rewind the
chat to a request and the canvas, and every kept brush as dirty as it was, go back to how they were
before it; /branches and /branch switch between versions. /canvas, /palette, /tools and /gallery show
the easel, the tube paints, the kept tools and the hung paintings.
Needs the paint library: ./gradlew buildPaintNative.

```edn [field-box id="ai4_paint_studio" role="properties"]
{"id" "ai4_paint_studio" "index" 1 "name" "Studio" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Studio" "_tombstone" false "frame" #afield/rect [80.0 80.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ai4_paint_studio_code_.kt" "lines" 641 "characters" 44982 "sha256" "d7179a44651515b9f3e15d9ea143dae19c87fc1a2f15570f19e5837d331f315e"}]}
```

```kotlin [field-property box="ai4_paint_studio" name="code" path="ai4_paint_studio_code_.kt"]
// A studio for AI4: open this box's AI panel and ask for a painting ("a quiet harbour at dusk").
// The agent paints with the paint_* tools below, looks at the canvas as it goes, and hangs what it
// finishes in the gallery. The canvas and the painter's tools belong to the conversation: rewind the
// chat to a request and the canvas, and every kept brush as dirty as it was, go back to how they were
// before it; /branches and /branch switch between versions. /canvas, /palette, /tools and /gallery show
// the easel, the tube paints, the kept tools and the hung paintings.
// Needs the paint library: ./gradlew buildPaintNative.
import afield.paint.*
import java.io.File
import java.util.Base64

// The canvas is kept on the box, not in a script variable, so re-running this box keeps the painting.
fun fresh(ground: PaintGround = PaintGround.Oil, aspect: Double = 1.25, seed: Long = 7) =
    PaintCanvas(900, aspect = aspect, ground = ground, seed = seed)
fun freshPaper(paper: Paper, aspect: Double, seed: Long) = PaintCanvas.paper(900, aspect = aspect, paper = paper, seed = seed)
fun canvas(): PaintCanvas = thisBox.properties.getOrPut("studio.canvas") { fresh() } as PaintCanvas
fun replace(c: PaintCanvas) { thisBox.properties["studio.canvas"] = c }
// The tools the painter keeps by name, on the box for the same reason: a brush stays dirty between requests.
fun tools(): PaintToolbox = thisBox.properties.getOrPut("studio.tools") { PaintToolbox() } as PaintToolbox

fun pngUrl(bytes: ByteArray) = "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes)
fun show() = display("main", canvas().png())
// Another box's tool can paint on this easel: it finds the canvas here and calls this to show the result.
thisBox.properties["studio.refresh"] = { show() }

// "#rrggbb", one tube ("ultramarine blue"), or a pile of tubes by parts ("lead white:3, yellow ochre:1").
class Paint(val color: Vec4, val tubes: Map<String, Double>?)
fun paintOf(spec: String): Paint {
    val s = spec.trim()
    if (s.startsWith("#") && (s.length == 7 || s.length == 4)) {
        val hex = if (s.length == 4) s.drop(1).map { "$it$it" }.joinToString("") else s.drop(1)
        val v = hex.chunked(2).map { it.toInt(16) / 255.0 }
        return Paint(rgb(v[0], v[1], v[2]), null)
    }
    val tubes: Map<String, Double> = s.split(',').filter { it.isNotBlank() }.associate { part ->
        val (name, parts) = part.split(':').let { it[0].trim() to (it.getOrNull(1)?.trim()?.toDoubleOrNull() ?: 1.0) }
        val tube = Tubes.all.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: throw IllegalArgumentException("no tube \"$name\": colours are #rrggbb or tube names from the palette (${Tubes.names.joinToString()})")
        tube.name to parts
    }
    require(tubes.isNotEmpty()) { "no colour given" }
    return Paint(Tubes.mix(tubes), tubes)
}

// A smooth curve through flat [x0, y0, x1, y1, ...] (Catmull-Rom as cubics), closed when asked.
fun through(xy: List<Double>, closed: Boolean = false): FLine {
    val p = xy.chunked(2).filter { it.size == 2 }
    require(p.size >= 2) { "a path needs at least two points, as [x0, y0, x1, y1, ...]" }
    val pts = if (closed) p + listOf(p[0]) else p
    val line = FLine().moveTo(pts[0][0], pts[0][1])
    if (pts.size == 2) return line.lineTo(pts[1][0], pts[1][1])
    for (i in 0 until pts.size - 1) {
        val a = if (i > 0) pts[i - 1] else if (closed) p[p.size - 1] else pts[i]
        val b = pts[i]; val c = pts[i + 1]
        val d = if (i + 2 < pts.size) pts[i + 2] else if (closed) p[1] else c
        line.cubicTo(b[0] + (c[0] - a[0]) / 6, b[1] + (c[1] - a[1]) / 6,
            c[0] - (d[0] - b[0]) / 6, c[1] - (d[1] - b[1]) / 6, c[0], c[1])
    }
    return line
}
fun region(xy: List<Double>) = through(xy, closed = true)
fun rad(degrees: Double) = degrees * PI / 180

fun brushOf(name: String) = BrushKind.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    ?: throw IllegalArgumentException("no brush \"$name\": ${BrushKind.entries.joinToString { it.name }}")
// A kept brush by name, or a fresh one of a kind (loaded below); a fresh one needs a colour.
fun brushFor(tool: String?, kind: String, width: Double, color: String?): PaintBrush =
    if (tool != null) tools().brush(tool)
    else canvas().brush(brushOf(kind), width = width).also { require(color != null) { "a fresh brush needs a color (or name a kept tool)" } }
fun loadInto(b: PaintBrush, color: String?, wipe: Double = 0.85, hiding: Double = 0.92, thinness: Double = 0.0, turps: Double = 0.0, part: LoadPart? = null) {
    val p = paintOf(color ?: return)
    if (p.tubes != null) b.load(p.tubes, medium = thinness, wipe = wipe, turps = turps, part = part)
    else b.load(p.color, hiding = hiding, wipe = wipe, turps = turps, part = part)
}
// Watercolour: "ultramarine:2, burnt sienna:1" by µg of pigment per mm³ of water, or "water".
fun waterPaintOf(spec: String): WaterPaint {
    if (spec.trim().equals("water", ignoreCase = true)) return WaterPaint.Clear
    return spec.split(',').filter { it.isNotBlank() }.map { part ->
        val (name, conc) = part.split(':').let { it[0].trim() to (it.getOrNull(1)?.trim()?.toDoubleOrNull() ?: 2.0) }
        val n = Watercolors.names.firstOrNull { it.equals(name, ignoreCase = true) }
            ?: throw IllegalArgumentException("no watercolour \"$name\": ${Watercolors.names.joinToString()}, or \"water\"")
        Watercolors[n] * conc
    }.reduce(WaterPaint::plus)
}
// A moving brush takes watercolour when the colour names watercolour pigments (or "water"), oil otherwise.
fun isWatercolour(spec: String) = spec.split(',').all { part ->
    val name = part.split(':')[0].trim()
    name.equals("water", true) || Watercolors.names.any { it.equals(name, true) }
}
fun loadMoving(d: DynamicBrush, color: String, wipe: Double = 0.85, hiding: Double = 0.92, fill: Double = 1.0) {
    if (isWatercolour(color)) { d.loadWater(canvas(), waterPaintOf(color), fill = fill); return }
    val p = paintOf(color)
    if (p.tubes != null) d.load(p.tubes, wipe = wipe) else d.load(p.color, hiding = hiding, wipe = wipe)
}
fun movingReport(name: String, d: DynamicBrush, r: DynamicReport) =
    "%.1f s of gesture with %s; at most %s of a hair lay on the paper; %s".format(r.seconds, name, pct(r.maxContact),
        if (d.watercolour) "laid %.1f mm³ of water and %.1f µg of pigment, the brush %s full".format(r.waterLaid, r.pigmentLaid, pct(d.fill))
        else "the brush ${pct(d.fill)} loaded") +
        (if (d.retainedBend > 0.05) "; its hairs have taken a set of %.0f°".format(Math.toDegrees(d.retainedBend)) else "")

fun lightOf(name: String) = when (name.lowercase()) {
    "flat" -> null
    "raking" -> PaintLight.Raking
    "gallery" -> PaintLight.Gallery
    else -> throw IllegalArgumentException("no light \"$name\": flat, raking or gallery")
}
fun handlingOf(name: String) = Handling.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    ?: throw IllegalArgumentException("no handling \"$name\": ${Handling.entries.joinToString { it.name }}")

fun pct(x: Double) = "${(x * 100).roundToInt()}%"
fun state(): String = canvas().stats.let {
    "about ${it.handMinutes.roundToInt()} minutes of a painter's work so far; " +
        "paint open ${pct(it.open)}, setting ${pct(it.setting)}, tacky ${pct(it.tacky)}, dry or bare ${pct(it.dry)}"
} + (canvas().water?.let { w ->
    if (w.dry) "; the water has all dried" else "; water %.0f mm³ standing on the paper and %.0f mm³ in it".format(w.surfaceMm3, w.absorbedMm3)
} ?: "")
// The hung paintings, newest first, as a strip of thumbnails in the "gallery" channel.
fun galleryDir() = File(thisBox.inside.filename, "gallery")
fun showGallery() {
    val files = galleryDir().listFiles { f -> f.name.endsWith(".png") }.orEmpty().sortedByDescending { it.lastModified() }
    if (files.isEmpty()) return
    display("gallery", files.joinToString("", "<div style='display:flex;flex-wrap:wrap;gap:10px'>", "</div>") { f ->
        val img = javax.imageio.ImageIO.read(f)
        val w = 220; val h = img.height * w / img.width
        val small = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
        small.createGraphics().apply {
            setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(img, 0, 0, w, h, null); dispose()
        }
        val out = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(small, "jpg", it) }
        "<figure style='margin:0'><img src='data:image/jpeg;base64,${Base64.getEncoder().encodeToString(out.toByteArray())}' width='$w'>" +
            "<figcaption style='font-style:italic;text-align:center'>${f.nameWithoutExtension}</figcaption></figure>"
    })
}

fun done(what: String): Map<String, Any> { show(); return mapOf("ok" to true, "did" to what, "canvas" to state()) }

if (!PaintCanvas.isAvailable) markdown(PaintLibrary.describe()) else thisBox.agentExtension("studio") {

    tool("paint.look") {
        describe("Look at the painting as it stands: an image of the canvas (dried, lit from the upper left), and how wet it is.")
        effects(ToolEffect.CAPTURE_IMAGE)
        val wet by param<Boolean>("show the paint as it is now, wet, without relief", default = false)
        val light by param<String>("flat; raking, a low lamp that shows the relief, knife ridges and wet sheen; or gallery", default = "flat")
        val map by param<String?>("instead of the picture, a map of the water: surface (standing water), absorbed, pigment; " +
            "or contact, where the hairs of the last moving-brush stroke touched", default = null)
        action {
            val c = canvas()
            val png = map()?.let { m ->
                val which = when (m.lowercase()) {
                    "surface" -> WaterMap.SurfaceWater
                    "absorbed" -> WaterMap.AbsorbedWater
                    "pigment" -> WaterMap.Pigment
                    "contact" -> null
                    else -> throw IllegalArgumentException("no map \"$m\": surface, absorbed, pigment or contact")
                }
                which?.let { c.waterMapPng(it) } ?: c.contactMapPng()
            } ?: c.png(relief = if (wet()) 0.0 else 0.6, wet = wet(), light = lightOf(light()))
            // Every look is also kept, numbered, in the document's progress folder: the painting's stages.
            val dir = File(thisBox.inside.filename, "progress").apply { mkdirs() }
            File(dir, "%03d.png".format((dir.list()?.size ?: 0) + 1)).writeBytes(png)
            image(pngUrl(png), state())
        }
    }

    tool("paint.stroke") {
        describe("Drag a loaded brush along one or more paths. Each path is one movement of the hand, a smooth curve through " +
            "[x0, y0, x1, y1, ...]; the brush is loaded once, before the first, and runs out along the rest the way a real one does.")
        effects(ToolEffect.CONTROL_RUNTIME)
        val paths by param<List<List<Double>>>("paths, each [x0, y0, x1, y1, ...] in canvas units")
        val color by param<String?>("#rrggbb, a tube name, or tubes by parts: \"lead white:3, yellow ochre:1\"; null paints a kept tool with what it carries", default = null)
        val tool by param<String?>("a kept brush's name (paint_tool) to paint with, dirty as it is; null for a fresh brush of `brush`", default = null)
        val brush by param<String>("Round, HogFlat, Filbert, Fan, Rigger or Badger (a fresh brush)", default = "Filbert")
        val width by param<Double>("brush width in canvas units (a fresh brush)", default = 20.0)
        val pressure by param<List<Double>>("pressure at the start and end of each movement, 0..1", default = listOf(0.7, 0.7))
        val thinness by param<Double>("0 paint from the tube, 1 thinned with oil (tube mixes only)", default = 0.0)
        val wipe by param<Double>("how much of the brush's last paint to wipe off first; 0 mixes into it", default = 0.85)
        val hiding by param<Double>("0.05 a glaze, 0.5 a scumble, 0.92 body colour (hex colours only)", default = 0.92)
        action {
            val c = canvas()
            (tool()?.let { tools()[it] } as? DynamicBrush)?.let { d ->
                color()?.let { loadMoving(d, it, wipe = wipe(), hiding = hiding()) }
                val line = FLine(); paths().forEach { line += through(it) }
                val r = c.stroke(c.gesture(line, pressure = pressure(), tilt = 30.0), d)
                return@action done(movingReport(tool()!!, d, r))
            }
            val b = brushFor(tool(), brush(), width(), color())
            loadInto(b, color(), wipe = wipe(), hiding = hiding(), thinness = thinness())
            val line = FLine()
            paths().forEach { line += through(it) }
            val pr = pressure()
            c.stroke(line, b, pressure = pr.first()..(pr.getOrNull(1) ?: pr.first()))
            done("${paths().size} stroke(s), " + (tool()?.let { "with $it, now ${pct(b.fullness)} loaded" } ?: "${brush()} ${width()}"))
        }
    }

    tool("paint.dab") {
        describe("Touch the brush down at points and lift it: dabs, petals, leaves, highlights. One load for all of them.")
        effects(ToolEffect.CONTROL_RUNTIME)
        val points by param<List<Double>>("[x0, y0, x1, y1, ...]")
        val color by param<String?>("#rrggbb, a tube name, or tubes by parts; null dabs a kept tool with what it carries", default = null)
        val tool by param<String?>("a kept brush's name to dab with; null for a fresh brush", default = null)
        val brush by param<String>("Round, HogFlat, Filbert, Fan, Rigger, Badger or Stippler (a fresh brush)", default = "Round")
        val width by param<Double>("brush width in canvas units", default = 12.0)
        val pressure by param<Double>("harder makes a bigger, fuller mark", default = 0.6)
        val angle by param<Double>("a flat's wide axis, degrees", default = 0.0)
        action {
            val c = canvas()
            val b = brushFor(tool(), brush(), width(), color())
            loadInto(b, color())
            points().chunked(2).filter { it.size == 2 }.forEach { c.touch(b, it[0], it[1], pressure = pressure(), angle = rad(angle())) }
            done("${points().size / 2} dab(s)")
        }
    }

    tool("paint.wash") {
        describe("Watercolour or ink with a round watercolour brush: the water on the paper flows, sinks in, dries with dark edges, " +
            "settles and blooms on its own. A full brush lays a wet wash; a nearly dry one (fill 0.1-0.3), light and fast, " +
            "skips over the paper's tooth (drybrush); a damp clean brush on a wet wash drinks colour back up. Paint wet into wet " +
            "by washing over water already there (paint_wet).")
        effects(ToolEffect.CONTROL_RUNTIME)
        val paths by param<List<List<Double>>>("strokes, each [x0, y0, x1, y1, ...]")
        val color by param<String?>("pigments by concentration in µg per mm³ of water, \"ultramarine:2, burnt sienna:1\" (1 pale .. 6 strong), " +
            "\"water\" for clean water; null paints a kept brush with what it holds", default = null)
        val tool by param<String?>("a kept watercolour brush (paint_tool type Watercolour); null for a fresh one", default = null)
        val width by param<Double>("a fresh brush's width, canvas units", default = 40.0)
        val fill by param<Double>("how full of water the brush is loaded, 0..1 (above 1 overloads it)", default = 1.0)
        val pressure by param<Double>("0.2 light .. 1 pressed flat", default = 0.6)
        val speed by param<Double>("mm/s: 30 slow and wet .. 300 a quick flick", default = 80.0)
        action {
            val c = canvas()
            val b = tool()?.let { tools().waterBrush(it) } ?: c.waterBrush(width()).also { require(color() != null) { "a fresh brush needs a color (or name a kept one)" } }
            color()?.let { b.load(waterPaintOf(it), fill = fill()) }
            val line = FLine(); paths().forEach { line += through(it) }
            val r = c.wash(line, b, pressure = listOf(pressure()), speed = speed())
            done("laid %.1f mm³ of water and %.1f µg of pigment, took up %.1f mm³; the brush is %s full".format(r.waterLaid, r.pigmentLaid, r.waterTaken, pct(b.fill)))
        }
    }

    tool("paint.gesture") {
        describe("Paint with a kept brush of moving hairs (paint_tool moving=true) through timed movements of the hand: how fast, how hard, " +
            "how the handle is tilted and turned. The hairs bend, drag, splay and flick as a real brush's do, pick up and lay paint where " +
            "they touch, and keep a set if they are bent hard. Slower to paint with than paint_stroke; use it for the marks that depend on " +
            "the hand: a flicked grass blade, a rolled ribbon, a broken drybrush scrub, a pressed and lifted calligraphic stroke.")
        effects(ToolEffect.CONTROL_RUNTIME)
        val paths by param<List<List<Double>>>("movements, each [x0, y0, x1, y1, ...]; one after another")
        val tool by param<String>("the kept moving brush")
        val color by param<String?>("oil (#rrggbb or tubes by parts) or, for watercolour, pigments by concentration, µg/mm³ " +
            "(\"ultramarine:3\"; 1 pale .. 6 strong .. 20-80 pan strength for drybrush); null paints with what it holds", default = null)
        val fill by param<Double>("watercolour: how full of water to load it, 0..1 (0.1-0.3 for drybrush)", default = 1.0)
        val speed by param<Double>("mm/s: 20 slow and deliberate .. 400 a flick", default = 80.0)
        val pressure by param<List<Double>>("pressure along each movement, evenly spaced, 0 the tips just touch .. 1 pressed .. 1.5 crushed, below 0 lifted off: " +
            "[0.9, -0.3] presses then lifts away", default = listOf(0.6))
        val tilt by param<Double>("the handle's lean from upright, degrees: 0 straight down .. 60 laid over", default = 30.0)
        val roll by param<List<Double>?>("the handle's turn, degrees, at the start and end of each movement (a flat's wide axis); null keeps a flat across its path", default = null)
        val pause by param<Double>("seconds between movements (the brush stays down and still at the end of each for this long first)", default = 0.3)
        action {
            val c = canvas()
            val d = tools().dynamicBrush(tool())
            color()?.let { loadMoving(d, it, fill = fill()) }
            var g: TimedGesture? = null
            for (xy in paths()) {
                val one = c.gesture(through(xy), speed = speed(), pressure = pressure(), tilt = tilt())
                val r = roll()
                val rolled = if (r == null) one else TimedGesture(one.samples.mapIndexed { i, s ->
                    val u = if (one.samples.size > 1) i.toDouble() / (one.samples.size - 1) else 0.0
                    s.copy(roll = r.first() + ((r.getOrNull(1) ?: r.first()) - r.first()) * u)
                })
                g = g?.pause(pause())?.then(rolled) ?: rolled
            }
            val r = c.stroke(g ?: throw IllegalArgumentException("no movements given"), d)
            done(movingReport(tool(), d, r))
        }
    }

    tool("paint.drop") {
        describe("Let drops of watercolour or ink fall on the paper (or clean water into a damp wash, for a bloom). Each spreads and the paper takes it.")
        effects(ToolEffect.CONTROL_RUNTIME)
        val points by param<List<Double>>("[x0, y0, x1, y1, ...]")
        val color by param<String>("pigments by concentration, \"sumi:3\", or \"water\"")
        val volume by param<Double>("each drop's volume, µl (mm³): 5 a small drop .. 50 a big one", default = 20.0)
        action {
            val c = canvas(); val p = waterPaintOf(color())
            points().chunked(2).filter { it.size == 2 }.forEach { c.drop(it[0], it[1], p, volumeMm3 = volume()) }
            done("${points().size / 2} drop(s)")
        }
    }

    tool("paint.wet") {
        describe("Lay water over a region at once with a wide mop: clean water to paint wet-in-wet into, or a flat wash of colour.")
        effects(ToolEffect.CONTROL_RUNTIME)
        val outline by param<List<Double>>("the region, [x0, y0, ...]")
        val microns by param<Double>("how deep, µm: 40 damp .. 150 a wet wash .. 300 flooded", default = 100.0)
        val color by param<String>("\"water\", or pigments by concentration", default = "water")
        action { canvas().wet(region(outline()), microns(), waterPaintOf(color())); done("wet a region with ${microns()} µm") }
    }

    tool("paint.tilt") {
        describe("Tilt the board: wet washes run downhill, gather and drip off the edge. 0 lays it flat again.")
        effects(ToolEffect.CONTROL_RUNTIME)
        val degrees by param<Double>("from flat; 90 upright", default = 20.0)
        val toward by param<Double>("the direction it falls, degrees: 90 down the canvas, 0 to the right", default = 90.0)
        action { canvas().tilt(degrees(), toward()); done("tilted ${degrees()}° toward ${toward()}°") }
    }

    tool("paint.tool") {
        describe("Pick up a tool and keep it by name for later requests: a brush stays dirty with what it last picked up until you " +
            "load or wipe it, a knife keeps its bead, a rag its load. Replaces a kept tool of the same name.")
        effects(ToolEffect.CONTROL_RUNTIME)
        val name by param<String>("what to call it, e.g. \"big filbert\"")
        val type by param<String>("a brush (Round, HogFlat, Filbert, Fan, Rigger, Badger, Stippler), Watercolour, Knife, Rag, or a pencil grade (HB, 4B) or Chalk")
        val width by param<Double>("brush width, knife blade length or rag pad size, canvas units", default = 20.0)
        val bristles by param<Int?>("how many bristles (a brush); null for the kind's own", default = null)
        val stiffness by param<Double?>("0 soft hair .. 1 stiff hog (a brush); null for the kind's own", default = null)
        val ragged by param<Double?>("0 even .. 1 ragged hair lengths, for broken marks (a brush)", default = null)
        val point by param<Double?>("0 blunt .. 1 a fine point (a round brush)", default = null)
        val moving by param<Boolean>("a brush whose hairs move (paint with paint_gesture): they bend, splay, flick and keep a set; " +
            "load it with oil or watercolour", default = false)
        action {
            val c = canvas(); val t = type().trim()
            val made: PaintTool = when {
                moving() -> c.dynamicBrush(brushOf(t), width = width(), hairs = 32,
                    config = BrushConfig(bristles = bristles(), stiffness = stiffness(), ragged = ragged(), point = point()))
                t.equals("knife", true) -> c.knife(width())
                t.equals("rag", true) -> c.rag(width())
                t.equals("watercolour", true) || t.equals("watercolor", true) -> c.waterBrush(width())
                t.equals("chalk", true) -> c.chalk()
                BrushKind.entries.any { it.name.equals(t, true) } ->
                    c.brush(brushOf(t), width = width(), config = BrushConfig(bristles = bristles(), stiffness = stiffness(), ragged = ragged(), point = point()))
                else -> c.pencil(t)
            }
            tools()[name()] = made
            mapOf("ok" to true, "kept" to name(), "tools" to tools().describe(c))
        }
    }

    tool("paint.load") {
        describe("Load a kept brush or knife with paint, or dampen a kept rag with spirits. A brush mixes the new paint into what is " +
            "left in it unless you wipe it; dip one edge (side, share) to double-load it.")
        effects(ToolEffect.CONTROL_RUNTIME)
        val tool by param<String>("the kept tool's name")
        val color by param<String?>("#rrggbb, a tube name, or tubes by parts; null only wipes (a rag: dampens; a pencil: sharpens)", default = null)
        val amount by param<Double>("a knife: 0..1 of a full bead; a watercolour brush: how full of water, 0..1", default = 0.6)
        val wipe by param<Double>("how much of what is in the brush to wipe off first, 0..1; 0 mixes into it (a knife: above 0 wipes it clean)", default = 0.85)
        val thinness by param<Double>("tube mixes: 0 from the tube, 1 thinned with oil medium", default = 0.0)
        val turps by param<Double>("0..0.9 of the paint's volume turpentine: it flows, spatters, and evaporates as it is laid", default = 0.0)
        val side by param<Double>("dip one edge only: -1 or 1; 0 the whole brush", default = 0.0)
        val share by param<Double>("with side: the share of the width that goes in", default = 1.0)
        val damp by param<Double>("a rag: how damp with spirits, 0..1", default = 0.6)
        action {
            val c = canvas()
            when (val t = tools()[tool()] ?: throw IllegalArgumentException("no kept tool \"${tool()}\": ${tools().names}")) {
                is PaintBrush ->
                    if (color() == null) t.wipe(wipe())
                    else loadInto(t, color(), wipe = wipe(), thinness = thinness(), turps = turps(),
                        part = if (side() != 0.0 || share() < 1.0) LoadPart(side(), share()) else null)
                is PaintKnife -> {
                    val p = color()?.let(::paintOf)
                    if (p == null) t.wipe()
                    else if (p.tubes != null) t.load(p.tubes, medium = thinness(), amount = amount(), wipe = wipe() > 0)
                    else t.load(p.color, amount = amount(), wipe = wipe() > 0, turps = turps())
                }
                is PaintRag -> c.dampen(t, damp())
                is Pencil -> t.sharpen()
                is WaterBrush -> color()?.let { t.load(waterPaintOf(it), fill = amount(), wipe = wipe()) } ?: t.blot(wipe())
                is DynamicBrush -> loadMoving(t, color() ?: throw IllegalArgumentException("a moving brush is loaded with a color"), wipe = wipe(), fill = amount())
            }
            mapOf("ok" to true, "tools" to tools().describe(c))
        }
    }

    tool("paint.knife") {
        describe("Pull a painting knife along paths: it lays flat slabs of paint with ridges at the blade's ends, or, with lay=false and " +
            "pressed hard, scrapes wet paint off back to the dry surface. The paint it scrapes stays on the blade.")
        effects(ToolEffect.CONTROL_RUNTIME)
        val paths by param<List<List<Double>>>("paths, each [x0, y0, x1, y1, ...]")
        val tool by param<String?>("a kept knife's name; null for a fresh 60-unit knife", default = null)
        val color by param<String?>("paint to pick up first; null uses what is on the blade", default = null)
        val pressure by param<Double>("0.3 a light touch riding on the surface .. 1 down to the dry paint", default = 0.6)
        val lay by param<Boolean>("lay paint from the blade (false: scrape only)", default = true)
        val angle by param<Double?>("the blade's angle in degrees; null holds it across the path", default = null)
        action {
            val c = canvas()
            val k = tool()?.let { tools().knife(it) } ?: c.knife(60.0)
            color()?.let(::paintOf)?.let { if (it.tubes != null) k.load(it.tubes) else k.load(it.color) }
            val line = FLine(); paths().forEach { line += through(it) }
            c.knife(line, k, pressure = pressure()..pressure(), angle = angle()?.let(::rad), lay = lay())
            done("${paths().size} knife pull(s), the blade ${pct(k.fullness)} loaded")
        }
    }

    tool("paint.wipe") {
        describe("Lift wet paint with a rag: wipe along paths (streaks, wiped-out lights) or blot at points (a crumpled patch). Only paint " +
            "that is still open comes away. A dampened rag (paint_load) lifts nearly to the ground.")
        effects(ToolEffect.CONTROL_RUNTIME)
        val paths by param<List<List<Double>>>("wipes, each [x0, y0, x1, y1, ...]", default = emptyList<List<Double>>())
        val points by param<List<Double>>("blots, [x0, y0, x1, y1, ...]", default = emptyList<Double>())
        val tool by param<String?>("a kept rag's name; null for a fresh clean rag", default = null)
        val width by param<Double>("a fresh rag's pad size", default = 50.0)
        val pressure by param<Double>("0..1", default = 0.6)
        val refold by param<Boolean>("turn a clean face out first", default = false)
        action {
            val c = canvas()
            val r = tool()?.let { tools().rag(it) } ?: c.rag(width())
            if (refold()) c.refold(r)
            var lifted = paths().sumOf { c.wipe(through(it), r, listOf(pressure())) }
            lifted += points().chunked(2).filter { it.size == 2 }.sumOf { c.blot(r, it[0], it[1], pressure()) }
            done("lifted ${"%.1f".format(lifted)} mm³ of paint; the rag's face is ${pct(c.ragState(r).load)} loaded")
        }
    }

    tool("paint.spatter") {
        describe("Flick a loaded brush: the loose paint flies off in droplets that land in a cone toward the flick. Fluid or turps-thinned " +
            "paint spatters readily, stiff paint barely; harder flicks throw more, smaller drops.")
        effects(ToolEffect.CONTROL_RUNTIME)
        val at by param<List<Double>>("where the brush is, [x, y]")
        val toward by param<List<Double>>("the flick's direction and reach, [dx, dy] in canvas units")
        val tool by param<String?>("a kept brush's name; null for a fresh round brush", default = null)
        val color by param<String?>("paint to load first; null flicks what the kept brush carries", default = null)
        val turps by param<Double>("with color: 0..0.9 turpentine in the paint", default = 0.4)
        val spread by param<Double>("half the cone's angle, degrees", default = 20.0)
        val force by param<Double>("0..1", default = 0.6)
        action {
            val c = canvas()
            val b = brushFor(tool(), "Round", 30.0, color())
            loadInto(b, color(), turps = turps())
            val n = c.spatter(b, at()[0], at()[1], toward = toward()[0] to toward()[1], spread = rad(spread()), force = force())
            done("$n droplets landed")
        }
    }

    tool("paint.work") {
        describe("Cover a region with many strokes planned like a painter's: Broad to lay in, Body for the main painting, Detail " +
            "(clipped), Hatch, Glaze (thin, soft), Scumble (broken), or Blend (a clean brush that softens wet paint, lays nothing).")
        effects(ToolEffect.CONTROL_RUNTIME)
        val outline by param<List<Double>>("the region's outline, [x0, y0, x1, y1, ...], closed automatically")
        val color by param<String>("#rrggbb, a tube name, or tubes by parts", default = "#ffffff")
        val handling by param<String>("Broad, Body, Detail, Hatch, Glaze, Scumble or Blend", default = "Body")
        val angle by param<Double?>("stroke direction in degrees (0 left to right, 90 top to bottom); null for the handling's own", default = null)
        val width by param<Double?>("brush width in canvas units; null for the handling's own", default = null)
        val coverage by param<Double?>("layers of strokes over each point; null for the handling's own", default = null)
        val hiding by param<Double>("how much one coat hides: 0.05 glaze .. 0.92 body colour", default = 0.85)
        val feather by param<Double>("soften the region's edge by this many units", default = 0.0)
        action {
            canvas().work(region(outline()), paintOf(color()).color, handling = handlingOf(handling()), hiding = hiding(),
                angle = angle()?.let(::rad), width = width(), coverage = coverage(), feather = feather())
            done("worked a region, ${handling()}")
        }
    }

    tool("paint.glaze") {
        describe("A thin transparent glaze over a region or the whole canvas, to warm, cool or deepen. Dries the canvas first.")
        effects(ToolEffect.CONTROL_RUNTIME)
        val color by param<String>("#rrggbb, a tube name, or tubes by parts")
        val outline by param<List<Double>?>("the region, [x0, y0, ...]; null for the whole canvas", default = null)
        val coats by param<Double>("how thick", default = 1.0)
        val feather by param<Double>("soften the edge by this many units", default = 20.0)
        action {
            canvas().dry()
            canvas().glaze(paintOf(color()).color, outline()?.let(::region), coats = coats(), feather = feather())
            done("glazed")
        }
    }

    tool("paint.draw") {
        describe("Draw lines in graphite or chalk: underdrawing, contours, a ruled horizon. 'sketch' restates each line a few times, the way a hand searches for it.")
        effects(ToolEffect.CONTROL_RUNTIME)
        val paths by param<List<List<Double>>>("paths, each [x0, y0, x1, y1, ...]")
        val pencil by param<String>("a grade, 6H..8B (HB is middling), or chalk", default = "HB")
        val pressure by param<Double>("0..1", default = 0.6)
        val sketch by param<Boolean>("restate each line a few times", default = false)
        val ruler by param<Boolean>("straight edges", default = false)
        action {
            val c = canvas()
            val p = if (pencil().equals("chalk", ignoreCase = true)) c.chalk() else c.pencil(pencil())
            paths().forEach { if (sketch()) c.sketch(through(it), p, pressure = pressure()) else c.draw(through(it), p, pressure = pressure(), ruler = ruler()) }
            done("${paths().size} line(s) in ${pencil()}")
        }
    }

    tool("paint.hatch") {
        describe("Hatch a region with parallel pencil or chalk strokes, for shading in a drawing.")
        effects(ToolEffect.CONTROL_RUNTIME)
        val outline by param<List<Double>>("the region, [x0, y0, ...]")
        val pencil by param<String>("a grade, 6H..8B, or chalk", default = "2B")
        val angle by param<Double>("degrees", default = 45.0)
        val spacing by param<Double>("between strokes, canvas units", default = 6.0)
        action {
            val c = canvas()
            c.hatch(region(outline()), if (pencil().equals("chalk", true)) c.chalk() else c.pencil(pencil()), angle = rad(angle()), spacing = spacing())
            done("hatched")
        }
    }

    tool("paint.stipple") {
        describe("Stipple a region with many small touches: foliage, texture, sparkle on water, distant crowds.")
        effects(ToolEffect.CONTROL_RUNTIME)
        val outline by param<List<Double>>("the region, [x0, y0, ...]")
        val color by param<String>("#rrggbb, a tube name, or tubes by parts")
        val size by param<Double>("touch size, canvas units", default = 4.0)
        val coverage by param<Double>("touches per point on average", default = 0.6)
        val cluster by param<Double>("0 even, 1 clumped", default = 0.3)
        val hiding by param<Double>("how much each touch hides what is under it", default = 0.7)
        action {
            canvas().stipple(region(outline()), paintOf(color()).color, size = size(), coverage = coverage(), cluster = cluster(),
                hiding = hiding(), feather = size() * 2)
            done("stippled")
        }
    }

    tool("paint.wait") {
        describe("Let simulated time pass: wet oil sets (minutes); water flows, sinks in and evaporates (seconds: a wash goes from wet " +
            "to damp in a minute or two). Or dry the canvas completely: paint laid afterwards no longer mixes into what is there. " +
            "Drying a sheet that is wet all over is simulated minute by minute and takes a minute or two to compute: call it on its own, " +
            "not inside a tool program with a short timeout.")
        effects(ToolEffect.CONTROL_RUNTIME)
        val minutes by param<Double?>("how long; null with no seconds dries the canvas completely", default = null)
        val seconds by param<Double?>("how long, in seconds, for watercolour timing", default = null)
        action {
            val c = canvas()
            when {
                seconds() != null -> c.waitSeconds(seconds()!!)
                minutes() != null -> c.wait(minutes()!!)
                else -> c.dry()
            }
            done(seconds()?.let { "waited $it s" } ?: minutes()?.let { "waited $it minutes" } ?: "dried")
        }
    }

    tool("paint.fresh") {
        describe("Put a new canvas on the easel. The old one is not lost: it stays with the conversation, and rewinding brings it back.")
        effects(ToolEffect.CONTROL_RUNTIME)
        val ground by param<String>("Oil (warm primed linen), OilRedGround, Linen (raw), Bare (flat white); or paper for watercolour and ink: " +
            "ColdPress, HotPress, Rough, Xuan (rice paper), Blotting", default = "Oil")
        val aspect by param<Double>("width / height: 1.25 landscape, 0.8 portrait, 1 square", default = 1.25)
        action {
            val seed = System.nanoTime() % 100_000
            val paper = Paper.entries.firstOrNull { it.name.equals(ground(), true) }
            if (paper != null) replace(freshPaper(paper, aspect(), seed)) else {
                val g = PaintGround.entries.firstOrNull { it.name.equals(ground(), true) }
                    ?: throw IllegalArgumentException("no ground \"${ground()}\": ${PaintGround.entries.joinToString()}, ${Paper.entries.joinToString()}")
                replace(fresh(g, aspect(), seed))
            }
            done("a fresh ${ground()} ${if (paper != null) "sheet" else "canvas"}, ${aspect()}")
        }
    }

    tool("paint.crack") {
        describe("Age the finished painting: craquelure through paint and ground, with dirt in the cracks. Irreversible on this branch.")
        exposure(ToolExposure.DEFERRED)
        effects(ToolEffect.CONTROL_RUNTIME)
        val even by param<Boolean>("one even network instead of an aged hierarchy", default = false)
        action { canvas().crack(if (even()) CrackKind.Even else CrackKind.Aged); done("cracked") }
    }

    tool("paint.hang") {
        describe("Hang the painting in the gallery under a title: a dried, lit PNG in the document's gallery folder, shown in this box's gallery panel.")
        effects(ToolEffect.FILESYSTEM_WRITE)
        val title by param<String>("the painting's title")
        action {
            val dir = galleryDir().apply { mkdirs() }
            val file = File(dir, title().replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifBlank { "untitled" } + ".png")
            file.writeBytes(canvas().png(relief = 0.7, gloss = 0.15))
            showGallery()
            mapOf("ok" to true, "hung" to file.name)
        }
    }

    context("canvas") {
        val c = canvas()
        "The canvas on the Studio easel is 1000 units wide and ${c.heightUnits.roundToInt()} tall (aspect ${c.aspect}), " +
            "x to the right, y DOWN from the top edge. It is ${(c.mmPerUnit * 1000).roundToInt()} mm wide, so one unit is " +
            "${"%.2f".format(c.mmPerUnit)} mm: a 10 mm brush is ${(10 / c.mmPerUnit).roundToInt()} units wide. ${state()}. " +
            "Kept tools: " + tools().describe(c).lines().joinToString("; ")
    }

    context("craft", changesOften = false) {
        """
        |You are painting in oils on a simulated canvas with the paint_* tools; your painting is the Studio box's output.
        |Paint the way a painter does: block in big shapes with paint_work (Broad, then Body) in thin, muted paint first; build
        |light and form with thicker paint; finish with paint_stroke and paint_dab details and highlights. paint_look shows you
        |the canvas — look after each stage, and judge it as a painting. Wet paint mixes into what you lay over it: wait or dry
        |(paint_wait) when you want a clean layer on top. Every request you are given starts from the canvas as the conversation
        |left it, and rewinding the chat brings back the canvas as it was then, so be bold.
        |Keep the tools you will come back to (paint_tool, then paint_load and the tool= of paint_stroke and paint_dab): a kept
        |brush carries what it picked up from the canvas into its next stroke, as a real one does, so a brush kept for the sky
        |stays a sky brush. A knife (paint_knife) lays flat slabs and ridges or scrapes wet paint away; a rag (paint_wipe) lifts
        |open paint for lights; paint_spatter flicks fluid paint. paint_look with light="raking" shows the relief and wet sheen.
        |On paper (paint_fresh with ColdPress, HotPress, Rough, Xuan or Blotting) you can paint in watercolour and ink: paint_wash,
        |paint_drop, paint_wet and paint_tilt. The water then moves by itself, flowing, sinking in, settling, drying to darker edges
        |and blooming where wet meets damp; time matters (paint_wait seconds), and paint_look map="surface" shows where it is still
        |wet. Watercolours: ${Watercolors.names.joinToString()}. Oil, pencil and the knife still work on paper, over the watercolour.
        |Watercolour strength is µg of pigment per mm³ of water: 0.5 a faint tint, 1-2 pale, 3-5 a middle value, 6-10 strong and
        |dark, 20-80 the paint straight off the pan. Size brushes by the paper's millimetres (the canvas context gives the scale): a
        |real painter's rounds are 3-25 mm, a wash brush 25-50 mm. Paint a subject made of separate things (stones, leaves, boats)
        |as separate strokes, each the size of the thing.
        |Drybrush: a moving round or flat loaded with pan-strength colour (20-80) and little water (fill 0.08-0.2), dragged fast
        |(150-300 mm/s), light (pressure 0.2-0.35) and laid over (tilt 40-60) across the form, so the paint catches only the tops of
        |the paper's tooth and leaves the hollows white: weathered stone and wood, bark, dry grass, light glittering on water. It
        |works on Rough paper, less on ColdPress, hardly on HotPress, and over a wash only once the wash is dry.
        |Light comes from the white paper, so plan it: leave the lights unpainted and paint the colour around and across them.
        |Glitter on water is drybrush of the water's own dark colour dragged across white or pale paper in short horizontal strokes,
        |so the hollows stay white and sparkle; don't paint the glitter, paint the dark between it.
        |Soft, blended passages (a sky, a soft distance) are wet-in-wet: paint_wet the region with clean water first (40-100 µm),
        |then wash or drop the colours into it while it is still wet, before anything dries; let it dry before hard-edged forms go
        |over it. Paint light to dark and big to small: washes first, then the middle values, then darks, drybrush and details.
        |A brush kept with paint_tool moving=true has hairs that move: paint with it through paint_gesture, giving the speed, pressure
        |profile, tilt and roll of the hand, for marks that depend on the hand (flicks, rolled ribbons, a broken drybrush scrub, a
        |pressed-and-lifted line). It takes oil or watercolour; paint_look map="contact" shows where its hairs touched.
        |Colours: "#rrggbb", or real tube paints mixed by parts, "lead white:4, cadmium yellow:1" (they mix like paint, so blue and
        |yellow make green). Tubes: ${Tubes.names.joinToString()}.
        """.trimMargin()
    }

    command("canvas", "show the canvas here") { "![the canvas](${pngUrl(canvas().png())})\n\n${state()}" }
    command("tools", "list the kept tools and what they carry") { _ -> tools().describe(canvas()).lines().joinToString("\n") { "- $it" } }
    command("gallery", "list the hung paintings") { _ ->
        galleryDir().listFiles { f -> f.name.endsWith(".png") }.orEmpty().sortedBy { it.lastModified() }
            .joinToString("\n") { "- *${it.nameWithoutExtension}*" }.ifBlank { "Nothing hung yet." }
    }
    command("palette", "list the tube paints") { _ ->
        Tubes.all.joinToString("\n") { t ->
            val hex = "#%02x%02x%02x".format((t.color.x * 255).roundToInt(), (t.color.y * 255).roundToInt(), (t.color.z * 255).roundToInt())
            "- <span style=\"display:inline-block;width:1em;height:1em;background:$hex;vertical-align:middle\"></span> **${t.name}** — ${t.pigment}, hiding ${t.hiding}"
        }
    }

    // The canvas belongs to the conversation: a rewind restores it even over changes made outside the chat
    // (the replaced canvas is saved first), and reopening the document puts it back where the chat left it.
    branchState("canvas",
        save = { canvas().saveState(it.path) },
        restore = { f -> replace(f?.let { PaintCanvas.load(it.path) } ?: fresh()); show() },
        onConflict = BranchConflict.RESTORE, resumeOnOpen = true)
    // and so do the kept tools, each bristle's paint and all
    branchState("tools",
        save = { tools().save(it) },
        restore = { f -> thisBox.properties["studio.tools"] = f?.let { PaintToolbox.load(it) } ?: PaintToolbox() },
        onConflict = BranchConflict.RESTORE, resumeOnOpen = true)
}

if (PaintCanvas.isAvailable) { show(); showGallery() }
```

